/*
 * ZRDBack — Zstd Reverse Delta Backup
 * Copyright (C) 2026 CobaltDev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * The ZVCR-3D format and the reference implementation (zvcr, zvcr_utils)
 * by crane are licensed under the GNU Lesser General Public License v3.
 */
package space.cobaltworks.zrdback.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.TreeSet;

import space.cobaltworks.zvcr.Zvcr;
import space.cobaltworks.zvcr.format.PackedDeltaData;
import space.cobaltworks.zvcr.format.PackedSnapshot;
import space.cobaltworks.zvcr.io.ZvcrFiles;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.SegmentState;
import space.cobaltworks.zvcr.region.TileEntityListDelta;
import space.cobaltworks.zvcr.region.ZvcrFile;

/**
 * Advisory per-file timestamp cache: a small {@code r.X.Y.zvcr3d.ts} sidecar
 * next to each backup file holding the file's sorted distinct chain
 * timestamps. Lets {@code /zrdback list} and the restore pre-check answer
 * without parsing (decompressing) the {@code .zvcr3d} itself.
 *
 * <p>The cache is strictly advisory: a missing, stale or corrupt sidecar
 * falls back to a full parse (which repopulates the sidecar), and every
 * sidecar failure is swallowed — it must never break correctness. Freshness
 * is checked by comparing mtimes (the writer always rewrites the sidecar
 * after the {@code .zvcr3d}) plus the recorded file size.
 */
final class TimestampSidecar {

    private static final String SUFFIX = ".ts";
    private static final byte[] MAGIC = "ZRDTS1\n".getBytes(StandardCharsets.US_ASCII);

    private TimestampSidecar() {}

    /** Sorted distinct chain timestamps of one parsed {@code .zvcr3d} file. */
    static long[] collect(ZvcrFile zvcr) {
        TreeSet<Long> stamps = new TreeSet<>();
        for (int i = 0; i < Zvcr.SEGMENTS_PER_REGION; i++) {
            Segment segment = zvcr.region.get(i / Zvcr.REGION_SIDELENGTH_SEGMENTS,
                    i % Zvcr.REGION_SIDELENGTH_SEGMENTS).orElse(null);
            if (segment == null) {
                continue;
            }
            for (SegmentState state : segment.info.segmentStates()) {
                stamps.add(state.timestamp());
            }
            for (TileEntityListDelta delta : segment.tileEntities.reverseDeltas()) {
                stamps.add(delta.timestamp());
            }
            for (int s = 0; s < segment.sectionCount; s++) {
                collectChain(stamps, segment.blockSections.section(s));
                collectChain(stamps, segment.biomeSections.section(s));
            }
        }
        long[] out = new long[stamps.size()];
        int index = 0;
        for (long stamp : stamps) {
            out[index++] = stamp;
        }
        return out;
    }

    private static void collectChain(TreeSet<Long> stamps, PackedDeltaData chain) {
        for (PackedSnapshot snapshot : chain.reverseDeltas()) {
            stamps.add(snapshot.timestamp());
        }
    }

    static Path sidecarPath(Path zvcrPath) {
        return zvcrPath.resolveSibling(zvcrPath.getFileName().toString() + SUFFIX);
    }

    /**
     * Timestamps of one backup file: the sidecar when fresh, otherwise a full
     * parse that also (best-effort) populates the sidecar for next time.
     */
    static TreeSet<Long> timestampsFor(Path zvcrPath) throws IOException {
        long[] cached = readIfFresh(zvcrPath);
        if (cached != null) {
            TreeSet<Long> stamps = new TreeSet<>();
            for (long stamp : cached) {
                stamps.add(stamp);
            }
            return stamps;
        }
        long[] stamps = collect(ZvcrFiles.readFile(zvcrPath));
        write(zvcrPath, stamps);
        TreeSet<Long> set = new TreeSet<>();
        for (long stamp : stamps) {
            set.add(stamp);
        }
        return set;
    }

    /** Oldest timestamp of one file; {@link Long#MAX_VALUE} when it has none. */
    static long oldestFor(Path zvcrPath) throws IOException {
        TreeSet<Long> stamps = timestampsFor(zvcrPath);
        return stamps.isEmpty() ? Long.MAX_VALUE : stamps.first();
    }

    /**
     * Returns the cached timestamps when the sidecar exists, parses, and is
     * not older than the backup file (mtime + size check); {@code null}
     * otherwise. Never throws — the cache is advisory.
     */
    private static long[] readIfFresh(Path zvcrPath) {
        Path sidecar = sidecarPath(zvcrPath);
        if (!Files.isRegularFile(sidecar) || !Files.isRegularFile(zvcrPath)) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(sidecar);
            if (data.length < MAGIC.length + 16 || !startsWith(data, MAGIC)) {
                return null;
            }
            long size = readLong(data, MAGIC.length);
            long count = readLong(data, MAGIC.length + 8);
            if (count < 0 || count > Integer.MAX_VALUE
                    || data.length != MAGIC.length + 16 + 8 * count
                    || size != Files.size(zvcrPath)) {
                return null;
            }
            // The writer rewrites the sidecar after the .zvcr3d, so sidecar
            // mtime >= file mtime is the normal state; the reverse means the
            // backup file changed after the sidecar was written.
            if (Files.getLastModifiedTime(sidecar)
                    .compareTo(Files.getLastModifiedTime(zvcrPath)) < 0) {
                return null;
            }
            long[] stamps = new long[(int) count];
            for (int i = 0; i < stamps.length; i++) {
                stamps[i] = readLong(data, MAGIC.length + 16 + 8 * i);
            }
            return stamps;
        } catch (IOException e) {
            return null;
        }
    }

    /** Best-effort sidecar write; failures are swallowed (cache is advisory). */
    static void write(Path zvcrPath, long[] sortedTimestamps) {
        try {
            byte[] data = new byte[MAGIC.length + 16 + 8 * sortedTimestamps.length];
            System.arraycopy(MAGIC, 0, data, 0, MAGIC.length);
            writeLong(data, MAGIC.length, Files.size(zvcrPath));
            writeLong(data, MAGIC.length + 8, sortedTimestamps.length);
            for (int i = 0; i < sortedTimestamps.length; i++) {
                writeLong(data, MAGIC.length + 16 + 8 * i, sortedTimestamps[i]);
            }
            Files.write(sidecarPath(zvcrPath), data,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            // advisory cache — ignore
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static long readLong(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    private static void writeLong(byte[] data, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            data[offset + i] = (byte) value;
            value >>>= 8;
        }
    }
}

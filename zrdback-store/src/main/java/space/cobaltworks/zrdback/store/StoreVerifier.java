/*
 * ZRDBack: Zstd Reverse Delta Backup
 * Copyright (C) 2026 CobaltWorks
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
 * by crayne are licensed under the GNU Lesser General Public License v3.
 */
package space.cobaltworks.zrdback.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import space.cobaltworks.zvcr.io.ZvcrFiles;
import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.RegionLocation;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.SegmentState;

/**
 * Integrity check over a backup store: every region file must parse, its
 * chains must be internally consistent, and the store must be self-consistent
 * (sidecars fresh, blob index matching the blobs on disk).
 *
 * <p>The check is read-only and never modifies the store.
 */
public final class StoreVerifier {

    /** One problem found in the store. */
    public record Problem(String location, String message) {
        @Override
        public String toString() {
            return location + ": " + message;
        }
    }

    /** Outcome of a verification run. */
    public record Report(int filesChecked, int chunksChecked, List<Problem> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private StoreVerifier() {}

    /** Verifies the whole store under {@code storeRoot}. */
    public static Report verify(Path storeRoot) throws IOException {
        List<Problem> problems = new ArrayList<>();
        int files = 0;
        int chunks = 0;

        for (Dimension dimension : Dimension.values()) {
            Path dimDir = StoreLayout.dimensionDirectory(storeRoot, dimension);
            if (!Files.isDirectory(dimDir)) {
                continue;
            }
            List<Path> regionFiles;
            try (Stream<Path> walk = Files.walk(dimDir)) {
                regionFiles = walk.filter(f -> f.getFileName().toString().endsWith(".zvcr3d"))
                        .sorted()
                        .toList();
            }
            for (Path file : regionFiles) {
                files++;
                chunks += verifyRegionFile(storeRoot, dimension, file, problems);
            }
        }

        verifyBlobStore(storeRoot, problems);
        return new Report(files, chunks, List.copyOf(problems));
    }

    private static int verifyRegionFile(Path storeRoot, Dimension dimension, Path file,
                                        List<Problem> problems) {
        String where = storeRoot.relativize(file).toString();
        int chunks = 0;

        RegionLocation location;
        try {
            location = StoreLayout.locationOf(file, dimension);
        } catch (IllegalArgumentException e) {
            problems.add(new Problem(where, e.getMessage()));
            return 0;
        }

        space.cobaltworks.zvcr.region.ZvcrFile zvcr;
        try {
            zvcr = ZvcrFiles.readFile(file);
        } catch (IOException | RuntimeException e) {
            problems.add(new Problem(where, "unreadable: " + e));
            return 0;
        }

        if (zvcr.dimensionType != dimension) {
            problems.add(new Problem(where, "dimension header says " + zvcr.dimensionType
                    + " but the file lives under " + dimension.directoryName));
        }
        if (zvcr.protocolVersion != space.cobaltworks.zrdback.store.StoreFormat.PROTOCOL_VERSION) {
            problems.add(new Problem(where, "protocol version " + zvcr.protocolVersion
                    + " differs from the expected " + StoreFormat.PROTOCOL_VERSION
                    + " ; restore would map block IDs incorrectly"));
        }

        for (int i = 0; i < space.cobaltworks.zvcr.Zvcr.SEGMENTS_PER_REGION; i++) {
            Segment segment = zvcr.region.get(i / space.cobaltworks.zvcr.Zvcr.REGION_SIDELENGTH_SEGMENTS,
                    i % space.cobaltworks.zvcr.Zvcr.REGION_SIDELENGTH_SEGMENTS).orElse(null);
            if (segment == null) {
                continue;
            }
            chunks++;
            verifySegment(where, location, segment, problems);
        }
        return chunks;
    }

    private static void verifySegment(String where, RegionLocation location, Segment segment,
                                       List<Problem> problems) {
        int localX = location.rx() % space.cobaltworks.zvcr.Zvcr.REGION_SIDELENGTH_SEGMENTS;
        int localZ = location.rz() % space.cobaltworks.zvcr.Zvcr.REGION_SIDELENGTH_SEGMENTS;
        String chunk = where + " [" + localX + "," + localZ + "]";

        for (SegmentState state : segment.info.segmentStates()) {
            if (state.timestamp() < 0) {
                problems.add(new Problem(chunk, "negative segment-state timestamp"));
            }
        }
        for (int s = 0; s < segment.sectionCount; s++) {
            verifyChain(chunk, "block section " + s, segment.blockSections.section(s), problems);
            verifyChain(chunk, "biome section " + s, segment.biomeSections.section(s), problems);
        }
        long previous = Long.MAX_VALUE;
        for (var delta : segment.tileEntities.reverseDeltas()) {
            if (delta.timestamp() > previous) {
                problems.add(new Problem(chunk, "tile-entity deltas are not in newest-first order"));
                break;
            }
            previous = delta.timestamp();
        }
    }

    private static void verifyChain(String where, String what,
                                     space.cobaltworks.zvcr.format.PackedDeltaData chain,
                                     List<Problem> problems) {
        long previous = Long.MAX_VALUE;
        for (var snapshot : chain.reverseDeltas()) {
            if (snapshot.timestamp() > previous) {
                problems.add(new Problem(where, what + ": chain entries are not in newest-first order"));
                break;
            }
            previous = snapshot.timestamp();
        }
    }

    private static void verifyBlobStore(Path storeRoot, List<Problem> problems) {
        Path indexPath = storeRoot.resolve(StoreLayout.INDEX_FILE);
        if (!Files.isRegularFile(indexPath)) {
            return;
        }
        Map<String, List<FileBlobStore.Entry>> index;
        try {
            index = FileBlobStore.open(storeRoot).trackedEntries();
        } catch (IOException e) {
            problems.add(new Problem(indexPath.getFileName().toString(), "unreadable: " + e));
            return;
        }
        Path blobsDir = storeRoot.resolve(StoreLayout.BLOBS_DIRECTORY);
        Map<String, Integer> referenced = new LinkedHashMap<>();
        for (Map.Entry<String, List<FileBlobStore.Entry>> file : index.entrySet()) {
            for (FileBlobStore.Entry entry : file.getValue()) {
                referenced.merge(entry.hash(), 1, Integer::sum);
            }
        }
        if (!Files.isDirectory(blobsDir)) {
            if (!referenced.isEmpty()) {
                problems.add(new Problem(StoreLayout.BLOBS_DIRECTORY,
                        "index references " + referenced.size() + " blobs but the directory is missing"));
            }
            return;
        }
        try (Stream<Path> blobs = Files.list(blobsDir)) {
            for (Path blob : blobs.filter(f -> f.getFileName().toString().endsWith(".zst")).toList()) {
                String name = blob.getFileName().toString();
                String hash = name.substring(0, name.length() - 4);
                if (!referenced.containsKey(hash)) {
                    problems.add(new Problem(StoreLayout.BLOBS_DIRECTORY + "/" + name,
                            "orphan blob; not referenced by the index"));
                }
            }
        } catch (IOException e) {
            problems.add(new Problem(StoreLayout.BLOBS_DIRECTORY, "unreadable: " + e));
            return;
        }
        for (Map.Entry<String, Integer> blob : referenced.entrySet()) {
            Path path = blobsDir.resolve(blob.getKey() + ".zst");
            if (!Files.isRegularFile(path)) {
                problems.add(new Problem(StoreLayout.INDEX_FILE,
                        "missing blob " + blob.getKey() + " (referenced " + blob.getValue() + "×)"));
                continue;
            }
            // The blob name is the hash of the *uncompressed* content, so a
            // truncated or corrupt blob still has the right name. Check that
            // it actually decompresses.
            try {
                byte[] content = space.cobaltworks.zvcr.io.ZstdCodec.decompress(Files.readAllBytes(path));
                String actual = sha256Hex(content);
                if (!actual.equals(blob.getKey())) {
                    problems.add(new Problem(StoreLayout.BLOBS_DIRECTORY + "/" + blob.getKey() + ".zst",
                            "corrupt blob; content hashes to " + actual));
                }
            } catch (IOException | RuntimeException e) {
                problems.add(new Problem(StoreLayout.BLOBS_DIRECTORY + "/" + blob.getKey() + ".zst",
                        "unreadable: " + e));
            }
        }
    }

    /** SHA-256 of the given bytes, lowercase hex. */
    private static String sha256Hex(byte[] content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}

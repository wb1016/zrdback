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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import space.cobaltworks.zvcr.Zvcr;
import space.cobaltworks.zvcr.format.PackedData;
import space.cobaltworks.zvcr.format.PackedDeltaData;
import space.cobaltworks.zvcr.format.PackedSnapshot;
import space.cobaltworks.zvcr.io.ZvcrFiles;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.TileEntityListDelta;
import space.cobaltworks.zvcr.region.ZvcrFile;

/**
 * Read-only inspection of a backup store: aggregate statistics and
 * point-in-time comparisons.
 *
 * <p>Everything here works on the stored format alone - no Minecraft classes
 * are involved - so the same answers are available to the in-game commands and
 * to the standalone CLI.
 */
public final class StoreInspector {

    private StoreInspector() {}

    /**
     * Aggregate statistics over all backup files: chain lengths, palette
     * dedup effectiveness, blob store size. Used to tune the checkpoint
     * interval.
     */
    public record Stats(int files, long fileBytes, int segments, int chains, long chainEntries,
                        int minChain, int maxChain, long teDeltas,
                        long distinctPalettes, long paletteRefs,
                        int trackedFiles, int blobCount, long blobBytes) {
        public double avgChain() {
            return chains == 0 ? 0 : (double) chainEntries / chains;
        }

        /** Fraction of paletted snapshots sharing a deduplicated palette. */
        public double paletteDedupRatio() {
            return paletteRefs == 0 ? 1.0 : (double) distinctPalettes / paletteRefs;
        }
    }

    /**
     * What changed between two restore points.
     *
     * @param chunksChanged chunks whose content differs between the two points
     * @param chunksOnlyInNew chunks that exist only in the newer point
     * @param chunksOnlyInOld chunks that exist only in the older point
     * @param filesChanged auxiliary files whose content differs
     * @param durationMs how long the comparison took
     */
    public record Diff(int chunksChanged, int chunksOnlyInNew, int chunksOnlyInOld,
                       int filesChanged, long durationMs) {}

    /**
     * Aggregate statistics over every backup file in the store.
     *
     * @param storeRoot the store directory
     */
    public static Stats stats(Path storeRoot) throws IOException {
        int files = 0;
        long fileBytes = 0;
        int segments = 0;
        int chains = 0;
        long chainEntries = 0;
        int minChain = Integer.MAX_VALUE;
        int maxChain = 0;
        long teDeltas = 0;
        Set<space.cobaltworks.zvcr.format.Palette> distinctPalettes = new HashSet<>();
        long paletteRefs = 0;

        for (Path file : StoreLayout.regionFiles(storeRoot)) {
            files++;
            fileBytes += Files.size(file);
            ZvcrFile zvcr = ZvcrFiles.readFile(file);
            for (int i = 0; i < Zvcr.SEGMENTS_PER_REGION; i++) {
                Segment segment = zvcr.region.get(i / Zvcr.REGION_SIDELENGTH_SEGMENTS,
                        i % Zvcr.REGION_SIDELENGTH_SEGMENTS).orElse(null);
                if (segment == null) {
                    continue;
                }
                segments++;
                teDeltas += segment.tileEntities.size();
                for (int s = 0; s < segment.sectionCount; s++) {
                    for (PackedDeltaData chain : new PackedDeltaData[] {
                            segment.blockSections.section(s),
                            segment.biomeSections.section(s)}) {
                        int size = chain.reverseDeltas().size();
                        if (size == 0) {
                            continue;
                        }
                        chains++;
                        chainEntries += size;
                        minChain = Math.min(minChain, size);
                        maxChain = Math.max(maxChain, size);
                        for (PackedSnapshot snapshot : chain.reverseDeltas()) {
                            if (snapshot.data() instanceof PackedData.Paletted paletted) {
                                paletteRefs++;
                                distinctPalettes.add(paletted.palette());
                            }
                        }
                    }
                }
            }
        }
        if (minChain == Integer.MAX_VALUE) {
            minChain = 0;
        }
        FileBlobStore blobs = FileBlobStore.open(storeRoot);
        return new Stats(files, fileBytes, segments, chains, chainEntries, minChain, maxChain,
                teDeltas, distinctPalettes.size(), paletteRefs,
                blobs.trackedFileCount(), blobs.blobCount(), blobs.blobBytes());
    }

    /**
     * Compares two restore points.
     *
     * <p>Chunks are compared through their stored chains: a chunk counts as
     * changed when its reconstructed state differs between the two timestamps.
     * The comparison is per chunk and per section, so it is exact but costs
     * one reconstruction per chunk. fine for an operator-initiated command,
     * too slow for a periodic job.
     *
     * @param storeRoot the store directory
     * @param older the earlier timestamp
     * @param newer the later timestamp
     */
    public static Diff diff(Path storeRoot, long older, long newer) throws IOException {
        long start = System.currentTimeMillis();
        if (older > newer) {
            long swap = older;
            older = newer;
            newer = swap;
        }
        int changed = 0;
        int onlyNew = 0;
        int onlyOld = 0;

        for (Path file : StoreLayout.regionFiles(storeRoot)) {
            ZvcrFile zvcr = ZvcrFiles.readFile(file);
            for (int i = 0; i < Zvcr.SEGMENTS_PER_REGION; i++) {
                Segment segment = zvcr.region.get(i / Zvcr.REGION_SIDELENGTH_SEGMENTS,
                        i % Zvcr.REGION_SIDELENGTH_SEGMENTS).orElse(null);
                if (segment == null) {
                    continue;
                }
                ChunkState oldState = chunkState(segment, older);
                ChunkState newState = chunkState(segment, newer);
                if (oldState == null && newState == null) {
                    continue;
                }
                if (oldState == null) {
                    onlyNew++;
                } else if (newState == null) {
                    onlyOld++;
                } else if (!oldState.equals(newState)) {
                    changed++;
                }
            }
        }

        int filesChanged = diffFiles(storeRoot, older, newer);
        return new Diff(changed, onlyNew, onlyOld, filesChanged,
                System.currentTimeMillis() - start);
    }

    /**
     * The reconstructed state of one chunk at a timestamp: the block and biome
     * section contents plus the tile-entity list.
     */
    record ChunkState(int[] blocks, int[] biomes, int tileEntities) {

        @Override
        public boolean equals(Object o) {
            return o instanceof ChunkState other
                    && java.util.Arrays.equals(blocks, other.blocks)
                    && java.util.Arrays.equals(biomes, other.biomes)
                    && tileEntities == other.tileEntities;
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(blocks) * 31
                    + java.util.Arrays.hashCode(biomes) * 31 + tileEntities;
        }
    }

    /**
     * The state of a chunk at {@code timestamp}, or null when it had none.
     *
     * <p>{@code snapshotBefore} clamps to the oldest known state, so it cannot
     * answer "the chunk did not exist yet". Each chain is therefore checked
     * against its own oldest entry: a chain whose oldest entry is newer than
     * the queried timestamp contributes nothing.
     */
    static ChunkState chunkState(Segment segment, long timestamp) {
        List<int[]> blocks = new ArrayList<>();
        List<int[]> biomes = new ArrayList<>();
        boolean any = false;
        for (int s = 0; s < segment.sectionCount; s++) {
            PackedDeltaData blockChain = segment.blockSections.section(s);
            if (hadStateBefore(blockChain, timestamp)) {
                blocks.add(blockChain.snapshotBefore(timestamp).orElseThrow());
                any = true;
            }
            PackedDeltaData biomeChain = segment.biomeSections.section(s);
            if (hadStateBefore(biomeChain, timestamp)) {
                biomes.add(biomeChain.snapshotBefore(timestamp).orElseThrow());
                any = true;
            }
        }
        int tileEntities = -1;
        if (hadTileStateBefore(segment.tileEntities, timestamp)) {
            tileEntities = segment.tileEntities.snapshotBefore(timestamp)
                    .map(Map::size).orElse(0);
            any = true;
        }
        if (!any) {
            return null;
        }
        return new ChunkState(concat(blocks), concat(biomes), tileEntities);
    }

    /** True when the chain holds a state at or before {@code timestamp}. */
    private static boolean hadStateBefore(PackedDeltaData chain, long timestamp) {
        List<PackedSnapshot> entries = chain.reverseDeltas();
        if (entries.isEmpty()) {
            return false;
        }
        // The chain is newest-first, so the last entry is the oldest state.
        return entries.get(entries.size() - 1).timestamp() <= timestamp;
    }

    /** True when the tile-entity history holds a state at or before {@code timestamp}. */
    private static boolean hadTileStateBefore(
            space.cobaltworks.zvcr.region.TileEntityHistory history, long timestamp) {
        List<TileEntityListDelta> entries = history.reverseDeltas();
        if (entries.isEmpty()) {
            return false;
        }
        return entries.get(entries.size() - 1).timestamp() <= timestamp;
    }

    private static int[] concat(List<int[]> parts) {
        int total = 0;
        for (int[] part : parts) {
            total += part.length;
        }
        int[] out = new int[total];
        int offset = 0;
        for (int[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    /** Counts the auxiliary files whose content differs between two points. */
    private static int diffFiles(Path storeRoot, long older, long newer) throws IOException {
        FileBlobStore blobs = FileBlobStore.open(storeRoot);
        Map<String, List<FileBlobStore.Entry>> tracked = blobs.trackedEntries();
        int changed = 0;
        for (Map.Entry<String, List<FileBlobStore.Entry>> file : tracked.entrySet()) {
            String oldHash = hashAt(file.getValue(), older);
            String newHash = hashAt(file.getValue(), newer);
            if (!java.util.Objects.equals(oldHash, newHash)) {
                changed++;
            }
        }
        return changed;
    }

    /** The content hash of a file's newest entry at or before {@code timestamp}. */
    private static String hashAt(List<FileBlobStore.Entry> history, long timestamp) {
        FileBlobStore.Entry best = null;
        for (FileBlobStore.Entry entry : history) {
            if (entry.timestamp() <= timestamp && (best == null || entry.timestamp() > best.timestamp())) {
                best = entry;
            }
        }
        return best == null ? null : best.hash();
    }
}

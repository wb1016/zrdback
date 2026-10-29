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
import java.nio.file.Path;
import java.util.List;

import space.cobaltworks.zvcr.io.ZvcrFiles;
import space.cobaltworks.zvcr.io.ZvcrWriter;
import space.cobaltworks.zvcr.region.Segment;

/**
 * Retention: drops chain history older than a cutoff.
 *
 * <p>Reverse-delta chains are truncated from the tail, which is safe by
 * construction (each delta only depends on newer snapshots), so pruning never
 * invalidates the states that remain.
 */
public final class StorePruner {

    private StorePruner() {}

    /**
     * Prunes every region file and the blob history of a store.
     *
     * @param storeRoot the store directory
     * @param cutoff unix seconds; entries older than this are dropped
     * @param dryRun when true nothing is written
     * @return the number of removed chain/blob entries
     */
    public static int prune(Path storeRoot, long cutoff, boolean dryRun) throws IOException {
        int removed = 0;
        for (Path file : StoreLayout.regionFiles(storeRoot)) {
            removed += pruneFile(file, cutoff, dryRun);
        }
        if (!dryRun) {
            // prune() drops the history entries and garbage-collects the
            // blobs they referenced, then flushes the index itself. Flushing
            // from a second instance here would write back the *unpruned*
            // index read from disk and leave it referencing deleted blobs.
            removed += FileBlobStore.open(storeRoot).prune(cutoff);
        }
        return removed;
    }

    /**
     * Prunes one region file: drops chain, tile-entity and segment-state
     * entries older than {@code cutoff}.
     *
     * @return the number of removed entries
     */
    public static int pruneFile(Path file, long cutoff, boolean dryRun) throws IOException {
        var zvcr = ZvcrFiles.readFile(file);
        int removed = 0;
        for (int i = 0; i < space.cobaltworks.zvcr.Zvcr.SEGMENTS_PER_REGION; i++) {
            Segment segment = zvcr.region.get(
                    i / space.cobaltworks.zvcr.Zvcr.REGION_SIDELENGTH_SEGMENTS,
                    i % space.cobaltworks.zvcr.Zvcr.REGION_SIDELENGTH_SEGMENTS).orElse(null);
            if (segment == null) {
                continue;
            }
            for (int s = 0; s < segment.sectionCount; s++) {
                removed += segment.blockSections.section(s).truncateOlderThan(cutoff);
                removed += segment.biomeSections.section(s).truncateOlderThan(cutoff);
            }
            removed += segment.tileEntities.truncateOlderThan(cutoff);
            removed += segment.info.truncateOlderThan(cutoff);
        }
        if (removed > 0 && !dryRun) {
            ZvcrFiles.atomicWrite(file, ZvcrWriter.serialize(zvcr));
            TimestampSidecar.write(file, TimestampSidecar.collect(zvcr));
        }
        return removed;
    }

    /** The restore points (timestamps) present in a store. */
    public static java.util.Set<Long> timestamps(Path storeRoot) throws IOException {
        return FileBlobStore.open(storeRoot).timestamps();
    }
}

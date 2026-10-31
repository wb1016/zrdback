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
import java.util.ArrayList;
import java.util.Comparator;
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
     * Prunes a store according to a retention policy.
     *
     * <p>An unbounded policy is a no-op: nothing is written and nothing is
     * removed.
     *
     * @return the number of removed chain/blob entries
     */
    public static int prune(Path storeRoot, RetentionPolicy policy, boolean dryRun)
            throws IOException {
        if (policy.isUnbounded()) {
            return 0;
        }
        return prune(storeRoot, cutoff(storeRoot, policy, System.currentTimeMillis() / 1000), dryRun);
    }

    /**
     * The oldest timestamp a prune under this policy keeps.
     *
     * <p>The age limit gives a cutoff of {@code now - days * 86400}. The
     * snapshot limit gives the timestamp of the N-th newest restore point:
     * every entry at or after it survives, so at least N restore points stay
     * restorable. The stricter of the two cutoffs wins.
     *
     * @param now the current unix time in seconds
     * @return the cutoff, {@link Long#MIN_VALUE} when nothing may be pruned
     */
    public static long cutoff(Path storeRoot, RetentionPolicy policy, long now)
            throws IOException {
        long ageCutoff = policy.hasAgeLimit() ? now - policy.days() * 86400L : Long.MIN_VALUE;
        long snapshotCutoff = policy.hasSnapshotLimit()
                ? nthNewestTimestamp(storeRoot, policy.snapshots())
                : Long.MIN_VALUE;
        return Math.max(ageCutoff, snapshotCutoff);
    }

    /**
     * The timestamp of the N-th newest restore point, or {@link Long#MIN_VALUE}
     * when the store holds fewer than N of them (nothing may be dropped).
     */
    private static long nthNewestTimestamp(Path storeRoot, int n) throws IOException {
        List<Long> newestFirst = new ArrayList<>(timestamps(storeRoot));
        newestFirst.sort(Comparator.reverseOrder());
        if (newestFirst.size() < n) {
            return Long.MIN_VALUE;
        }
        return newestFirst.get(n - 1);
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

    /**
     * The restore points (timestamps) present in a store: every chain,
     * tile-entity and segment-state timestamp of every {@code .zvcr3d} file,
     * plus the blob-store history timestamps.
     *
     * <p>This is the same set {@code /zrdback list} shows, and it is what the
     * snapshot-count retention limit counts.
     */
    public static java.util.TreeSet<Long> timestamps(Path storeRoot) throws IOException {
        java.util.TreeSet<Long> stamps = new java.util.TreeSet<>();
        for (Path file : StoreLayout.regionFiles(storeRoot)) {
            stamps.addAll(TimestampSidecar.timestampsFor(file));
        }
        stamps.addAll(FileBlobStore.open(storeRoot).timestamps());
        return stamps;
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.cobaltworks.zvcr.format.PackedData;
import space.cobaltworks.zvcr.format.PackedSnapshot;
import space.cobaltworks.zvcr.io.ZvcrFiles;
import space.cobaltworks.zvcr.io.ZvcrWriter;
import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.RegionLocation;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.ZvcrFile;

/**
 * Retention pruning: dropping chain history and garbage-collecting the blobs
 * it referenced.
 *
 * <p>Pruning is destructive and irreversible, so the interesting cases are
 * the ones that must <em>not</em> delete anything: the newest state of every
 * chain, the newest entry of every file, and the whole store when no limit
 * applies.
 */
class StorePrunerTest {

    private static final int PROTOCOL = 777;

    /**
     * Writes a region file whose section-0 chain holds one entry per given
     * timestamp (newest first).
     *
     * @return the path of the written file
     */
    private static Path writeRegion(Path store, long... timestamps) throws IOException {
        ZvcrFile zvcr = new ZvcrFile(PROTOCOL, Dimension.OVERWORLD);
        Segment segment = new Segment(Dimension.OVERWORLD);
        int[] atoms = new int[space.cobaltworks.zvcr.Zvcr.SECTION_SIZE_BLOCKS];
        for (long timestamp : timestamps) {
            // A different value per entry, so every insert is a real change.
            java.util.Arrays.fill(atoms, (int) timestamp);
            segment.blockSections.section(0).insertSnapshot(
                    new PackedSnapshot(PackedData.pack(atoms, true), timestamp));
        }
        zvcr.region.set(0, 0, segment);
        Path file = new RegionLocation(0, 0, Dimension.OVERWORLD).filePath(store);
        ZvcrFiles.atomicWrite(file, ZvcrWriter.serialize(zvcr));
        return file;
    }

    /** Stores one auxiliary file version and returns the store. */
    private static FileBlobStore writeBlob(Path store, String name, long timestamp)
            throws IOException {
        FileBlobStore blobs = FileBlobStore.open(store);
        blobs.storeContent(store, name, ("content-" + timestamp).getBytes(), timestamp);
        blobs.flush();
        return blobs;
    }

    /** The timestamps still present in a region file's section-0 chain. */
    private static List<Long> chainTimestamps(Path file) throws IOException {
        return ZvcrFiles.readFile(file).region.get(0, 0).orElseThrow()
                .blockSections.section(0).reverseDeltas().stream()
                .map(PackedSnapshot::timestamp)
                .toList();
    }

    @Test
    void pruningDropsEntriesOlderThanTheCutoff(@TempDir Path tmp) throws Exception {
        Path file = writeRegion(tmp, 1000, 2000, 3000);

        int removed = StorePruner.pruneFile(file, 2000, false);

        assertEquals(1, removed);
        assertEquals(List.of(3000L, 2000L), chainTimestamps(file));
    }

    @Test
    void pruningKeepsTheNewestState(@TempDir Path tmp) throws Exception {
        Path file = writeRegion(tmp, 1000, 2000, 3000);

        // A cutoff in the future would drop everything if the head were not
        // protected.
        StorePruner.pruneFile(file, 9999, false);

        assertEquals(List.of(3000L), chainTimestamps(file),
                "only the head may remain, and it must survive");
    }

    @Test
    void aDryRunChangesNothing(@TempDir Path tmp) throws Exception {
        Path file = writeRegion(tmp, 1000, 2000, 3000);
        writeBlob(tmp, "level.dat", 1000);
        writeBlob(tmp, "level.dat", 2000);

        int removed = StorePruner.prune(tmp, 2000, true);

        assertEquals(1, removed, "the dry run reports what would be dropped");
        assertEquals(List.of(3000L, 2000L, 1000L), chainTimestamps(file),
                "the store must be untouched");
        assertEquals(2, FileBlobStore.open(tmp).trackedEntries().get("level.dat").size());
    }

    @Test
    void pruningAlsoDropsBlobHistoryAndCollectsTheBlobs(@TempDir Path tmp) throws Exception {
        writeRegion(tmp, 1000, 2000, 3000);
        writeBlob(tmp, "level.dat", 1000);
        writeBlob(tmp, "level.dat", 2000);
        writeBlob(tmp, "other.dat", 1500);
        int blobsBefore = FileBlobStore.open(tmp).blobCount();
        assertEquals(3, blobsBefore);

        int removed = StorePruner.prune(tmp, 2000, false);

        assertEquals(2, removed, "one chain entry and one blob history entry");
        FileBlobStore blobs = FileBlobStore.open(tmp);
        assertEquals(1, blobs.trackedEntries().get("level.dat").size(),
                "only the newest entry of a file survives");
        assertEquals(1, blobs.trackedEntries().get("other.dat").size(),
                "the newest entry of a file always survives");
        assertEquals(2, blobs.blobCount(),
                "the blob of the dropped history entry is garbage-collected");
    }

    @Test
    void theAgeLimitGivesAnAgeCutoff(@TempDir Path tmp) throws Exception {
        writeRegion(tmp, 1000, 2000, 3000);

        long cutoff = StorePruner.cutoff(tmp, RetentionPolicy.ofDays(7), 1000 * 86400L);

        assertEquals(1000 * 86400L - 7 * 86400L, cutoff);
    }

    @Test
    void anUnboundedPolicyHasNoCutoff(@TempDir Path tmp) throws Exception {
        writeRegion(tmp, 1000, 2000, 3000);

        long cutoff = StorePruner.cutoff(tmp, RetentionPolicy.UNBOUNDED, 1000 * 86400L);

        assertEquals(Long.MIN_VALUE, cutoff);
    }

    @Test
    void anUnboundedPolicyPrunesNothing(@TempDir Path tmp) throws Exception {
        Path file = writeRegion(tmp, 1000, 2000, 3000);
        writeBlob(tmp, "level.dat", 1000);

        int removed = StorePruner.prune(tmp, RetentionPolicy.UNBOUNDED, false);

        assertEquals(0, removed);
        assertEquals(List.of(3000L, 2000L, 1000L), chainTimestamps(file));
        assertEquals(1, FileBlobStore.open(tmp).trackedEntries().get("level.dat").size());
    }

    @Test
    void theSnapshotLimitKeepsTheLastRestorePoints(@TempDir Path tmp) throws Exception {
        // Restore points 1000, 2000, 3000 (chain) and 4000 (blob).
        Path file = writeRegion(tmp, 1000, 2000, 3000);
        writeBlob(tmp, "level.dat", 4000);

        long cutoff = StorePruner.cutoff(tmp, RetentionPolicy.ofSnapshots(2), 5000);

        // The two newest restore points are 4000 and 3000; everything older
        // than 3000 may go.
        assertEquals(3000, cutoff);
    }

    @Test
    void keepingFewerSnapshotsThanExistDropsTheOlderOnes(@TempDir Path tmp) throws Exception {
        Path file = writeRegion(tmp, 1000, 2000, 3000);

        int removed = StorePruner.prune(tmp, RetentionPolicy.ofSnapshots(2), false);

        assertEquals(1, removed);
        assertEquals(List.of(3000L, 2000L), chainTimestamps(file));
    }

    @Test
    void keepingMoreSnapshotsThanExistKeepsEverything(@TempDir Path tmp) throws Exception {
        Path file = writeRegion(tmp, 1000, 2000, 3000);

        long cutoff = StorePruner.cutoff(tmp, RetentionPolicy.ofSnapshots(10), 5000);

        assertEquals(Long.MIN_VALUE, cutoff, "nothing may be dropped");
        assertEquals(0, StorePruner.prune(tmp, RetentionPolicy.ofSnapshots(10), false));
        assertEquals(List.of(3000L, 2000L, 1000L), chainTimestamps(file));
    }

    @Test
    void theStricterOfTheTwoLimitsWins(@TempDir Path tmp) throws Exception {
        writeRegion(tmp, 1000, 2000, 3000);
        long now = 100000;

        // The age limit (10 days) reaches far past the oldest entry, so it
        // would keep everything; the snapshot limit (keep 2) is stricter and
        // cuts at the second newest restore point.
        long cutoff = StorePruner.cutoff(tmp, new RetentionPolicy(10, 2), now);
        assertEquals(2000, cutoff, "the snapshot limit is the stricter one");

        // The other way round: keeping 10 snapshots does not constrain
        // anything, so the one-day age limit decides.
        cutoff = StorePruner.cutoff(tmp, new RetentionPolicy(1, 10), now);
        assertEquals(now - 86400L, cutoff, "the age limit is the stricter one");
    }

    @Test
    void theStoreTimestampsCoverChainsAndBlobs(@TempDir Path tmp) throws Exception {
        writeRegion(tmp, 1000, 2000, 3000);
        writeBlob(tmp, "level.dat", 4000);

        var timestamps = StorePruner.timestamps(tmp);

        assertTrue(timestamps.contains(1000L));
        assertTrue(timestamps.contains(3000L));
        assertTrue(timestamps.contains(4000L), "blob history timestamps are restore points too");
        assertFalse(timestamps.contains(9999L));
    }

    @Test
    void theTimestampSidecarIsRewrittenAfterAPrune(@TempDir Path tmp) throws Exception {
        Path file = writeRegion(tmp, 1000, 2000, 3000);
        // The sidecar is written by the pruner; without it a later read would
        // fall back to parsing the file.
        StorePruner.pruneFile(file, 2000, false);

        Path sidecar = TimestampSidecar.sidecarPath(file);
        assertTrue(Files.isRegularFile(sidecar), "the sidecar must exist");
        assertEquals(List.of(2000L, 3000L),
                List.of(TimestampSidecar.timestampsFor(file).toArray(new Long[0])));
    }
}

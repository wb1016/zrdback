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
 * by crayne are licensed under the GNU Lesser General Public License v3.
 */
package space.cobaltworks.zrdback.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import space.cobaltworks.zvcr.format.PackedData;
import space.cobaltworks.zvcr.format.PackedSnapshot;
import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.Segment;

/**
 * The chunk-state comparison behind {@code /zrdback diff}.
 *
 * <p>The subtle part is "the chunk did not exist yet": a chain's
 * {@code snapshotBefore} clamps to the oldest known state, so it cannot answer
 * that question on its own.
 */
class ChunkStateTest {

    private static Segment segmentWith(int[] atoms, long timestamp) {
        Segment segment = new Segment(Dimension.OVERWORLD);
        segment.blockSections.section(0).insertSnapshot(
                new PackedSnapshot(PackedData.pack(atoms, true), timestamp));
        return segment;
    }

    @Test
    void aChunkCreatedLaterHasNoStateEarlier() {
        Segment segment = segmentWith(new int[] {1, 2}, 2000);

        assertNull(BackupService.chunkState(segment, 1000),
                "a chunk first recorded at 2000 had no state at 1000");
        assertNotNull(BackupService.chunkState(segment, 2000));
        assertNotNull(BackupService.chunkState(segment, 3000),
                "a chunk keeps its last known state after its last backup");
    }

    @Test
    void anUnchangedChunkComparesEqual() {
        Segment segment = segmentWith(new int[] {1, 2}, 1000);
        assertEquals(BackupService.chunkState(segment, 1000),
                BackupService.chunkState(segment, 5000));
    }

    @Test
    void aChangedChunkComparesDifferent() {
        Segment segment = new Segment(Dimension.OVERWORLD);
        segment.blockSections.section(0).insertSnapshot(
                new PackedSnapshot(PackedData.pack(new int[] {1, 2}, true), 1000));
        segment.blockSections.section(0).insertSnapshot(
                new PackedSnapshot(PackedData.pack(new int[] {1, 3}, true), 2000));

        assertEquals(false, BackupService.chunkState(segment, 1000)
                .equals(BackupService.chunkState(segment, 2000)),
                "a chunk whose content changed must not compare equal");
    }

    @Test
    void anEmptySegmentHasNoState() {
        assertNull(BackupService.chunkState(new Segment(Dimension.OVERWORLD), 1000));
    }

    @Test
    void theStateCoversEverySection() {
        Segment segment = new Segment(Dimension.OVERWORLD);
        segment.blockSections.section(0).insertSnapshot(
                new PackedSnapshot(PackedData.pack(new int[] {1}, true), 1000));
        segment.blockSections.section(1).insertSnapshot(
                new PackedSnapshot(PackedData.pack(new int[] {2}, true), 1000));

        var state = BackupService.chunkState(segment, 1000);
        assertNotNull(state);
        assertEquals(2, state.blocks().length / 4096, "both sections must be present");
        assertEquals(1, state.blocks()[0]);
        assertEquals(2, state.blocks()[4096]);
    }

    @Test
    void theStateIncludesTileEntities() {
        Segment segment = segmentWith(new int[] {1}, 1000);
        segment.tileEntities.insertSnapshot(1000, java.util.List.of(
                new space.cobaltworks.zvcr.region.TileEntity(1,
                        new space.cobaltworks.zvcr.region.TileEntityPosition(0, 0, 0),
                        new byte[] {1})));

        var state = BackupService.chunkState(segment, 1000);
        assertNotNull(state);
        assertEquals(1, state.tileEntities());
    }

    @Test
    void arraysCompareByContent() {
        assertEquals(Arrays.hashCode(new int[] {1, 2}), Arrays.hashCode(new int[] {1, 2}));
    }
}

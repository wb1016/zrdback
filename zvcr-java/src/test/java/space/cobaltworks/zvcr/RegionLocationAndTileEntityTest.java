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
package space.cobaltworks.zvcr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.cobaltworks.zvcr.format.DeltaInsertionResult;
import space.cobaltworks.zvcr.io.ZvcrFiles;
import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.RegionLocation;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.SegmentState;
import space.cobaltworks.zvcr.region.SegmentStateType;
import space.cobaltworks.zvcr.region.TileEntity;
import space.cobaltworks.zvcr.region.TileEntityDelta;
import space.cobaltworks.zvcr.region.TileEntityHistory;
import space.cobaltworks.zvcr.region.TileEntityPosition;
import space.cobaltworks.zvcr.region.ZvcrFile;

class RegionLocationAndTileEntityTest {

    @Test
    void floorDiv32NegativeRegions() {
        assertEquals(0, RegionLocation.floorDiv32(0));
        assertEquals(0, RegionLocation.floorDiv32(31));
        assertEquals(1, RegionLocation.floorDiv32(32));
        assertEquals(-1, RegionLocation.floorDiv32(-1));
        assertEquals(-1, RegionLocation.floorDiv32(-32));
        assertEquals(-2, RegionLocation.floorDiv32(-33));
        assertEquals(-1, RegionLocation.floorDiv32(-31));
    }

    @Test
    void directoryLayout() {
        RegionLocation loc = new RegionLocation(0, -1, Dimension.OVERWORLD);
        Path path = loc.filePath(Path.of("backups"));
        assertEquals("backups/overworld/0/-1/r.0.-1.zvcr3d", path.toString().replace('\\', '/'));
    }

    @Test
    void fileNameParsing() {
        Optional<RegionLocation> parsed = RegionLocation.fromFileName(
                Dimension.NETHER, "r.-12.34.zvcr3d");
        assertTrue(parsed.isPresent());
        assertEquals(-12, parsed.get().rx());
        assertEquals(34, parsed.get().rz());
        assertEquals(Dimension.NETHER, parsed.get().dimension());

        assertFalse(RegionLocation.fromFileName(Dimension.OVERWORLD, "r.1.2.mca").isPresent());
        assertFalse(RegionLocation.fromFileName(Dimension.OVERWORLD, "other.zvcr3d").isPresent());
    }

    @Test
    void regionIdRoundtrip() {
        for (int[] coords : new int[][] {{0, 0}, {-1, -1}, {12345, -6789}, {-100000, 100000}}) {
            RegionLocation loc = new RegionLocation(coords[0], coords[1], Dimension.THE_END);
            assertEquals(loc, RegionLocation.fromRegionId(loc.toRegionId()));
        }
    }

    // ------------------------------------------------------------------
    // Tile entity history
    // ------------------------------------------------------------------

    private static TileEntity te(int x, int y, int z, int type, byte[] nbt) {
        return new TileEntity(type, new TileEntityPosition(x, z, y), nbt);
    }

    @Test
    void tileEntityInsertAndReconstruct() {
        TileEntityHistory history = new TileEntityHistory();
        byte[] nbt1 = {1, 2, 3};
        byte[] nbt2 = {4, 5};

        TileEntity a = te(1, 64, 2, 10, nbt1);
        TileEntity b = te(3, 64, 4, 20, nbt2);

        // initial snapshot
        var r1 = history.insertSnapshot(1000, java.util.List.of(a, b));
        assertEquals(2, assertInstanceOf(DeltaInsertionResult.Success.class, r1).changes());

        // change b's NBT, remove a, add c
        TileEntity b2 = te(3, 64, 4, 20, new byte[] {9});
        TileEntity c = te(5, 70, 6, 30, new byte[] {7});
        var r2 = history.insertSnapshot(2000, java.util.List.of(b2, c));
        assertEquals(3, assertInstanceOf(DeltaInsertionResult.Success.class, r2).changes());

        // reconstruct at t=1000: original a and b, no c
        Map<TileEntityPosition, TileEntity> old = history.snapshotBefore(1000).orElseThrow();
        assertEquals(2, old.size());
        assertEquals(a, old.get(a.pos()));
        assertEquals(b, old.get(b.pos()));

        // reconstruct at t=2000: b2 and c, no a
        Map<TileEntityPosition, TileEntity> now = history.snapshotBefore(2000).orElseThrow();
        assertEquals(2, now.size());
        assertEquals(b2, now.get(b.pos()));
        assertEquals(c, now.get(c.pos()));
    }

    @Test
    void tileEntityNoChangesRejected() {
        TileEntityHistory history = new TileEntityHistory();
        TileEntity a = te(1, 64, 2, 10, new byte[] {1});
        history.insertSnapshot(1000, java.util.List.of(a));
        var result = history.insertSnapshot(2000, java.util.List.of(a));
        assertEquals(DeltaInsertionResult.Status.NO_CHANGES_MADE,
                assertInstanceOf(DeltaInsertionResult.Rejected.class, result).status());
    }

    @Test
    void tileEntityOlderThanLatestRejected() {
        TileEntityHistory history = new TileEntityHistory();
        history.insertSnapshot(2000, java.util.List.of());
        var result = history.insertSnapshot(1000, java.util.List.of(
                te(1, 64, 2, 10, new byte[] {1})));
        assertEquals(DeltaInsertionResult.Status.SNAPSHOT_OLDER_THAN_LATEST,
                assertInstanceOf(DeltaInsertionResult.Rejected.class, result).status());
    }

    @Test
    void tileEntityMidChainCheckpointInvisible() {
        TileEntityHistory history = new TileEntityHistory();
        TileEntity a = te(1, 64, 2, 10, new byte[] {1});
        history.insertSnapshot(1000, java.util.List.of(a));
        TileEntity b = te(3, 64, 4, 20, new byte[] {2});
        history.insertSnapshot(2000, java.util.List.of(a, b));
        TileEntity a2 = te(1, 64, 2, 10, new byte[] {3});
        history.insertSnapshot(3000, java.util.List.of(a2, b));

        var before1000 = history.snapshotBefore(1000).orElseThrow();
        var before2000 = history.snapshotBefore(2000).orElseThrow();

        // materialize a checkpoint at index 1 (timestamp 2000)
        history.insertCheckpoint(1);
        assertEquals(4, history.size());

        assertEquals(before1000, history.snapshotBefore(1000).orElseThrow());
        assertEquals(before2000, history.snapshotBefore(2000).orElseThrow());
    }

    // ------------------------------------------------------------------
    // Segment info
    // ------------------------------------------------------------------

    @Test
    void segmentInfoInsertRules() {
        space.cobaltworks.zvcr.region.SegmentInfo info = new space.cobaltworks.zvcr.region.SegmentInfo();
        assertTrue(info.insertSnapshot(new SegmentState(SegmentStateType.NEW, 1000)));
        // same type -> rejected
        assertFalse(info.insertSnapshot(new SegmentState(SegmentStateType.NEW, 2000)));
        // older -> rejected
        assertFalse(info.insertSnapshot(new SegmentState(SegmentStateType.OLD, 500)));
        // different type, newer -> accepted
        assertTrue(info.insertSnapshot(new SegmentState(SegmentStateType.OLD, 3000)));
        assertEquals(2, info.size());
        assertEquals(SegmentStateType.OLD, info.latestSnapshot().orElseThrow().type());
        assertEquals(SegmentStateType.NEW, info.snapshotBefore(1000).orElseThrow().type());
    }

    // ------------------------------------------------------------------
    // File-level helpers
    // ------------------------------------------------------------------

    @Test
    void atomicWriteAndReadBack(@TempDir Path tempDir) throws Exception {
        ZvcrFile file = new ZvcrFile(777, Dimension.OVERWORLD);
        Segment segment = new Segment(Dimension.OVERWORLD);
        segment.blockSections.section(0).insertSnapshot(
                new space.cobaltworks.zvcr.format.PackedSnapshot(
                        new space.cobaltworks.zvcr.format.PackedData.SingleValue(42), 1000));
        file.region.set(3, 4, segment);

        RegionLocation loc = new RegionLocation(3, 4, Dimension.OVERWORLD);
        ZvcrFiles.writeFileAt(file, tempDir, loc);

        Path expected = tempDir.resolve("overworld").resolve("0").resolve("0").resolve("r.3.4.zvcr3d");
        assertTrue(java.nio.file.Files.exists(expected));
        assertFalse(java.nio.file.Files.exists(expected.resolveSibling("r.3.4.zvcr3d.tmp")));

        ZvcrFile parsed = ZvcrFiles.readFile(expected);
        assertEquals(42, parsed.region.get(3, 4).orElseThrow()
                .blockSections.section(0).latestSnapshot().orElseThrow()
                .data().unpack(4096)[0]);
    }
}

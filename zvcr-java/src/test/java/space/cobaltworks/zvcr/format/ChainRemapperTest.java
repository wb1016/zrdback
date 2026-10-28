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
package space.cobaltworks.zvcr.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.cobaltworks.zvcr.Zvcr;
import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.Segment;

/**
 * The registry remapper: a chain's atoms are registry IDs, so translating a
 * backup from one registry epoch to another must move <em>every</em> entry of
 * the chain — the head snapshot and the deltas below it.
 */
class ChainRemapperTest {

    /**
     * A two-entry chain: the head holds the newer state {@code {1,2,9}}, the
     * delta below it holds the older state {@code {1,2,3}} — stored as
     * {@code {UNCHANGED, UNCHANGED, 3}} because only the last position
     * differs.
     */
    private static PackedDeltaData buildChain() {
        PackedDeltaData chain = new PackedDeltaData(3);
        chain.insertSnapshot(new PackedSnapshot(PackedData.pack(new int[] {1, 2, 3}, true), 1000));
        chain.insertSnapshot(new PackedSnapshot(PackedData.pack(new int[] {1, 2, 9}, true), 2000));
        return chain;
    }

    @Test
    void remapsEveryEntryOfTheChain() {
        PackedDeltaData chain = buildChain();
        ChainRemapper.remapChain(chain, id -> id * 10);

        assertArrayEquals(new int[] {10, 20, 90}, chain.snapshotBefore(9999).orElseThrow());
        // The delta stored the older value 3 at the last position; it must
        // have been translated too, otherwise the older state would mix ID
        // spaces and read back a wrong block.
        assertArrayEquals(new int[] {10, 20, 30}, chain.snapshotBefore(1000).orElseThrow());
    }

    @Test
    void leavesTheUnchangedSentinelAlone() {
        PackedDeltaData chain = buildChain();
        ChainRemapper.remapChain(chain, id -> id * 10);
        // The sentinel marks "unchanged", it is not a registry ID.
        int[] delta = chain.reverseDeltas().get(1).data().unpack(3);
        assertEquals(Zvcr.STATE_UNCHANGED, delta[0]);
        assertEquals(Zvcr.STATE_UNCHANGED, delta[1]);
    }

    @Test
    void remapsAWholeFile() {
        var file = new space.cobaltworks.zvcr.region.ZvcrFile(777, Dimension.OVERWORLD);
        Segment segment = new Segment(Dimension.OVERWORLD);
        segment.blockSections.section(0).insertSnapshot(
                new PackedSnapshot(PackedData.pack(new int[] {5, 6}, true), 1000));
        segment.biomeSections.section(0).insertSnapshot(
                new PackedSnapshot(PackedData.pack(new int[] {2}, true), 1000));
        file.region.set(0, 0, segment);

        ChainRemapper.remap(file, id -> id + 100, id -> id + 1000);

        // A segment's chains are section-sized (4096 atoms), so only the
        // leading entries carry the data written above.
        int[] blocks = file.region.get(0, 0).orElseThrow().blockSections.section(0)
                .snapshotBefore(9999).orElseThrow();
        assertArrayEquals(new int[] {105, 106}, new int[] {blocks[0], blocks[1]});
        int[] biomes = file.region.get(0, 0).orElseThrow().biomeSections.section(0)
                .snapshotBefore(9999).orElseThrow();
        assertArrayEquals(new int[] {1002}, new int[] {biomes[0]});
    }

    @Test
    void remapsDirectModePalettes() {
        // More than 257 unique atoms forces direct mode, where the atoms are
        // stored inline in the packed longs.
        int[] atoms = new int[300];
        for (int i = 0; i < atoms.length; i++) {
            atoms[i] = i;
        }
        PackedData data = PackedData.pack(atoms, true);
        assertTrue(((PackedData.Paletted) data).palette().direct(),
                "300 unique atoms must use direct mode");

        PackedData remapped = ChainRemapper.remapData(data, atoms.length, id -> id + 1);
        int[] out = remapped.unpack(atoms.length);
        for (int i = 0; i < atoms.length; i++) {
            assertEquals(i + 1, out[i]);
        }
    }

    @Test
    void keepsChainStructureAfterRemapping() {
        PackedDeltaData chain = buildChain();
        int sizeBefore = chain.size();
        List<PackedSnapshot> before = chain.reverseDeltas();

        ChainRemapper.remapChain(chain, id -> id + 1);

        assertEquals(sizeBefore, chain.size());
        assertEquals(before.get(0).timestamp(), chain.reverseDeltas().get(0).timestamp());
        assertEquals(before.get(1).timestamp(), chain.reverseDeltas().get(1).timestamp());
    }

    @Test
    void translatesTheSentinelThroughTheAtomRemapper() {
        assertEquals(Zvcr.STATE_UNCHANGED,
                AtomRemapper.translate(Zvcr.STATE_UNCHANGED, id -> id + 1));
        assertEquals(6, AtomRemapper.translate(5, id -> id + 1));
    }
}

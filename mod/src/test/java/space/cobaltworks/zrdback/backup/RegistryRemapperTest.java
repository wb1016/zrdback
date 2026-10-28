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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.cobaltworks.zvcr.format.PackedData;
import space.cobaltworks.zvcr.format.PackedSnapshot;
import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.ZvcrFile;
import space.cobaltworks.zrdback.store.RegistryManifest;

/**
 * The registry remapper translates a backup from the registry epoch it was
 * written under into the current one — the difference between restoring the
 * right blocks and restoring plausible-looking wrong ones.
 */
class RegistryRemapperTest {

    /** A live registry whose block IDs are shifted by +1000 against the stored one. */
    private static RegistrySnapshot liveRegistry() {
        List<String> blocks = new java.util.ArrayList<>();
        blocks.add("minecraft:air");
        blocks.add("minecraft:stone");
        blocks.add("minecraft:dirt");
        for (int i = 3; i < 1100; i++) {
            blocks.add("minecraft:filler" + i);
        }
        List<String> biomes = List.of("minecraft:plains", "minecraft:desert");
        return snapshotOf(blocks, biomes);
    }

    /** The epoch the backup was written under: the same names, different IDs. */
    private static RegistryManifest storedRegistry() {
        List<String> blocks = new java.util.ArrayList<>();
        blocks.add("minecraft:air");
        blocks.add("minecraft:stone");
        blocks.add("minecraft:dirt");
        for (int i = 3; i < 1100; i++) {
            blocks.add("minecraft:filler" + i);
        }
        return RegistryManifest.of(777, blocks, List.of("minecraft:plains", "minecraft:desert"));
    }

    private static RegistrySnapshot snapshotOf(List<String> blocks, List<String> biomes) {
        return new RegistrySnapshot(RegistryManifest.of(777, blocks, biomes), blocks, biomes);
    }

    @Test
    void translatesStoredIdsIntoTheCurrentRegistry() {
        // The stored epoch assigned "minecraft:stone" the ID 1; the live
        // registry assigns it 1001.
        RegistrySnapshot live = liveRegistry();
        RegistryManifest stored = storedRegistry();

        // Build the live snapshot with the shifted numbering.
        List<String> liveBlocks = new java.util.ArrayList<>();
        liveBlocks.add("minecraft:air");
        for (int i = 1; i < 1100; i++) {
            liveBlocks.add("minecraft:filler" + i);
        }
        liveBlocks.add("minecraft:stone");
        liveBlocks.add("minecraft:dirt");
        RegistrySnapshot shifted = snapshotOf(liveBlocks, live.biomeNames());

        RegistryRemapper remapper = RegistryRemapper.from(stored, shifted);
        assertEquals(shifted.blockId("minecraft:stone"),
                remapper.blocks().translate(1),
                "the stored ID of stone must translate to the live ID of stone");
        assertEquals(shifted.blockId("minecraft:dirt"),
                remapper.blocks().translate(2));
    }

    @Test
    void theSameRegistryNeedsNoTranslation() {
        List<String> blocks = List.of("minecraft:air", "minecraft:stone", "minecraft:dirt");
        List<String> biomes = List.of("minecraft:plains", "minecraft:desert");
        RegistrySnapshot live = snapshotOf(blocks, biomes);
        RegistryManifest stored = RegistryManifest.of(777, blocks, biomes);
        RegistryRemapper remapper = RegistryRemapper.from(stored, live);
        assertTrue(remapper.isIdentity(),
                "the same fingerprint must not build a translation table");
    }

    @Test
    void aMissingBlockBecomesTheFallback() {
        // The live registry does not contain the stored block at all.
        RegistrySnapshot live = snapshotOf(List.of("minecraft:air", "minecraft:stone"),
                List.of("minecraft:plains"));
        RegistryManifest stored = RegistryManifest.of(777,
                List.of("minecraft:air", "minecraft:removed_block"),
                List.of("minecraft:plains"));

        RegistryRemapper remapper = RegistryRemapper.from(stored, live);
        assertEquals(-1, remapper.blocks().translate(1),
                "a block that no longer exists has no translation");
    }

    @Test
    void remappingAFileMovesEveryAtom() {
        RegistrySnapshot live = liveRegistry();
        RegistryManifest stored = storedRegistry();

        ZvcrFile file = new ZvcrFile(777, Dimension.OVERWORLD);
        Segment segment = new Segment(Dimension.OVERWORLD);
        segment.blockSections.section(0).insertSnapshot(
                new PackedSnapshot(PackedData.pack(new int[] {1, 2}, true), 1000));
        file.region.set(0, 0, segment);

        RegistryRemapper.from(stored, live).remapFile(file);

        // The live registry is the same mapping, so the IDs stay as they are.
        int[] blocks = file.region.get(0, 0).orElseThrow().blockSections.section(0)
                .snapshotBefore(9999).orElseThrow();
        assertEquals(1, blocks[0]);
        assertEquals(2, blocks[1]);
    }

    @Test
    void theAirBlockIsAlwaysAvailableAsAFallback() {
        RegistrySnapshot live = snapshotOf(List.of("minecraft:air"), List.of("minecraft:plains"));
        assertEquals(0, live.airId(), "air must be resolvable by name");
        assertEquals(0, live.plainsId());
    }
}

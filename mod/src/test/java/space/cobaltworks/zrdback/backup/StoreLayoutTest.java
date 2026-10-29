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
package space.cobaltworks.zrdback.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.cobaltworks.zvcr.io.ZvcrFiles;
import space.cobaltworks.zvcr.io.ZvcrWriter;
import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.RegionLocation;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.ZvcrFile;

/**
 * The store layout and the epoch bookkeeping that makes a restore safe across
 * Minecraft updates.
 */
class StoreLayoutTest {

    private static ZvcrFile buildFile(int blockId, int biomeId) {
        ZvcrFile file = new ZvcrFile(777, Dimension.OVERWORLD);
        Segment segment = new Segment(Dimension.OVERWORLD);
        segment.blockSections.section(0).insertSnapshot(
                new space.cobaltworks.zvcr.format.PackedSnapshot(
                        space.cobaltworks.zvcr.format.PackedData.pack(
                                new int[] {blockId}, true), 1000));
        segment.biomeSections.section(0).insertSnapshot(
                new space.cobaltworks.zvcr.format.PackedSnapshot(
                        space.cobaltworks.zvcr.format.PackedData.pack(
                                new int[] {biomeId}, true), 1000));
        file.region.set(0, 0, segment);
        return file;
    }

    @Test
    void regionFilesAreFoundAcrossDimensions(@TempDir Path tmp) throws Exception {
        Path overworld = tmp.resolve("overworld").resolve("0").resolve("0");
        Files.createDirectories(overworld);
        Files.write(overworld.resolve("r.1.2.zvcr3d"),
                ZvcrWriter.serialize(buildFile(1, 1)));

        Path nether = tmp.resolve("nether").resolve("0").resolve("0");
        Files.createDirectories(nether);
        Files.write(nether.resolve("r.3.4.zvcr3d"),
                ZvcrWriter.serialize(buildFile(1, 1)));

        List<Path> files = space.cobaltworks.zrdback.store.StoreLayout.regionFiles(tmp);
        assertEquals(2, files.size(), "both dimensions must be found");
    }

    @Test
    void regionLocationRoundTrips(@TempDir Path tmp) {
        Path dir = tmp.resolve("overworld").resolve("0").resolve("0");
        RegionLocation location = new RegionLocation(1, 2, Dimension.OVERWORLD);
        assertEquals(dir.resolve("r.1.2.zvcr3d"), location.filePath(tmp));
        assertEquals(location,
                space.cobaltworks.zrdback.store.StoreLayout.locationOf(
                        dir.resolve("r.1.2.zvcr3d"), Dimension.OVERWORLD));
    }

    @Test
    void theEpochSidecarTracksTheRegistryOfAFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("r.1.2.zvcr3d");
        Files.write(file, ZvcrWriter.serialize(buildFile(1, 1)));

        assertEquals(null, space.cobaltworks.zrdback.store.EpochSidecar.read(file),
                "a file without a sidecar has an unknown epoch");

        space.cobaltworks.zrdback.store.EpochSidecar.write(file, "abc123");
        assertEquals("abc123", space.cobaltworks.zrdback.store.EpochSidecar.read(file));

        // The sidecar lives next to the file it describes.
        assertTrue(Files.exists(tmp.resolve("r.1.2.zvcr3d.ep")));
    }

    @Test
    void aPrunedFileKeepsItsEpoch(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("r.1.2.zvcr3d");
        ZvcrFile zvcr = buildFile(1, 1);
        Files.write(file, ZvcrWriter.serialize(zvcr));
        space.cobaltworks.zrdback.store.EpochSidecar.write(file, "epoch-a");

        // A prune rewrites the file but does not change the atoms, so the
        // epoch must stay valid.
        ZvcrFiles.atomicWrite(file, ZvcrWriter.serialize(zvcr));
        assertEquals("epoch-a", space.cobaltworks.zrdback.store.EpochSidecar.read(file));
    }

    @Test
    void differentRegistriesProduceDifferentFingerprints() {
        var air = List.of("minecraft:air", "minecraft:stone");
        var dirt = List.of("minecraft:air", "minecraft:dirt");
        assertNotEquals(
                space.cobaltworks.zrdback.store.RegistryManifest.fingerprintOf(777, air, List.of()),
                space.cobaltworks.zrdback.store.RegistryManifest.fingerprintOf(777, dirt, List.of()));
    }
}

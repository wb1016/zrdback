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

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.cobaltworks.zvcr.format.PackedData;
import space.cobaltworks.zvcr.format.PackedSnapshot;
import space.cobaltworks.zvcr.io.ZvcrWriter;
import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.ZvcrFile;

class StoreVerifierTest {

    private static ZvcrFile buildFile(int protocolVersion) {
        ZvcrFile file = new ZvcrFile(protocolVersion, Dimension.OVERWORLD);
        Segment segment = new Segment(Dimension.OVERWORLD);
        segment.blockSections.section(0).insertSnapshot(
                new PackedSnapshot(PackedData.pack(new int[] {1, 2}, true), 1000));
        file.region.set(0, 0, segment);
        return file;
    }

    private static void writeRegion(Path store, String name, ZvcrFile file) throws Exception {
        Path dir = store.resolve("overworld").resolve("0").resolve("0");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), ZvcrWriter.serialize(file));
    }

    @Test
    void acceptsAConsistentStore(@TempDir Path tmp) throws Exception {
        writeRegion(tmp, "r.0.0.zvcr3d", buildFile(777));
        StoreVerifier.Report report = StoreVerifier.verify(tmp);
        assertTrue(report.ok(), () -> "unexpected problems: " + report.problems());
        assertEquals(1, report.filesChecked());
        assertEquals(1, report.chunksChecked());
    }

    @Test
    void flagsAProtocolVersionMismatch(@TempDir Path tmp) throws Exception {
        writeRegion(tmp, "r.0.0.zvcr3d", buildFile(700));
        StoreVerifier.Report report = StoreVerifier.verify(tmp);
        assertFalse(report.ok());
        assertTrue(report.problems().stream().anyMatch(p -> p.message().contains("protocol version")),
                () -> "expected a protocol version problem: " + report.problems());
    }

    @Test
    void flagsAnUnreadableRegionFile(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("overworld").resolve("0").resolve("0");
        Files.createDirectories(dir);
        Files.write(dir.resolve("r.0.0.zvcr3d"), new byte[] {1, 2, 3, 4});
        StoreVerifier.Report report = StoreVerifier.verify(tmp);
        assertFalse(report.ok());
        assertTrue(report.problems().stream().anyMatch(p -> p.message().contains("unreadable")),
                () -> "expected an unreadable file problem: " + report.problems());
    }

    @Test
    void flagsAMissingBlob(@TempDir Path tmp) throws Exception {
        FileBlobStore store = FileBlobStore.open(tmp);
        store.storeContent(tmp, "level.dat", new byte[] {1, 2, 3}, 1000);
        store.flush();

        // Remove the blob but keep the index, a broken store. The blob is
        // named after the content hash, so find it rather than guessing.
        Path blob;
        try (var blobs = Files.list(tmp.resolve(StoreLayout.BLOBS_DIRECTORY))) {
            blob = blobs.findFirst().orElseThrow();
        }
        Files.delete(blob);

        StoreVerifier.Report report = StoreVerifier.verify(tmp);
        assertFalse(report.ok());
        assertTrue(report.problems().stream().anyMatch(p -> p.message().contains("missing blob")),
                () -> "expected a missing blob problem: " + report.problems());
    }

    @Test
    void flagsAnOrphanBlob(@TempDir Path tmp) throws Exception {
        FileBlobStore store = FileBlobStore.open(tmp);
        store.storeContent(tmp, "level.dat", new byte[] {1, 2, 3}, 1000);
        store.flush();

        // A blob nobody references.
        Files.write(tmp.resolve(StoreLayout.BLOBS_DIRECTORY).resolve("deadbeef.zst"),
                space.cobaltworks.zvcr.io.ZstdCodec.compress(new byte[] {9}));

        StoreVerifier.Report report = StoreVerifier.verify(tmp);
        assertFalse(report.ok());
        assertTrue(report.problems().stream().anyMatch(p -> p.message().contains("orphan blob")),
                () -> "expected an orphan blob problem: " + report.problems());
    }

    @Test
    void flagsACorruptBlob(@TempDir Path tmp) throws Exception {
        FileBlobStore store = FileBlobStore.open(tmp);
        store.storeContent(tmp, "level.dat", new byte[] {1, 2, 3}, 1000);
        store.flush();

        // A truncated blob still has the right file name (the name is the
        // hash of the uncompressed content), so only decompressing it
        // reveals the corruption.
        Path blob;
        try (var blobs = Files.list(tmp.resolve(StoreLayout.BLOBS_DIRECTORY))) {
            blob = blobs.findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(blob);
        Files.write(blob, java.util.Arrays.copyOf(bytes, bytes.length / 2));

        StoreVerifier.Report report = StoreVerifier.verify(tmp);
        assertFalse(report.ok());
        assertTrue(report.problems().stream().anyMatch(p -> p.message().contains("unreadable")
                        || p.message().contains("corrupt blob")),
                () -> "expected a corrupt blob problem: " + report.problems());
    }

    @Test
    void anEmptyStoreIsConsistent(@TempDir Path tmp) throws Exception {
        StoreVerifier.Report report = StoreVerifier.verify(tmp);
        assertTrue(report.ok());
        assertEquals(0, report.filesChecked());
    }
}

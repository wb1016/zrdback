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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistryManifestTest {

    private static List<String> names(String... values) {
        return List.of(values);
    }

    @Test
    void fingerprintChangesWithTheMapping() {
        String a = RegistryManifest.fingerprintOf(777, names("minecraft:air", "minecraft:stone"),
                names("minecraft:plains"));
        String b = RegistryManifest.fingerprintOf(777, names("minecraft:air", "minecraft:dirt"),
                names("minecraft:plains"));
        String c = RegistryManifest.fingerprintOf(778, names("minecraft:air", "minecraft:stone"),
                names("minecraft:plains"));
        assertNotEquals(a, b, "a changed block must change the fingerprint");
        assertNotEquals(a, c, "a changed protocol version must change the fingerprint");
        assertEquals(a, RegistryManifest.fingerprintOf(777, names("minecraft:air", "minecraft:stone"),
                names("minecraft:plains")), "the fingerprint must be stable");
    }

    @Test
    void roundTripsThroughDisk(@TempDir Path tmp) throws Exception {
        RegistryManifest manifest = RegistryManifest.of(777,
                names("minecraft:air", "minecraft:oak_log[axis=x]"),
                names("minecraft:plains", "minecraft:desert"));

        manifest.writeIfAbsent(tmp);
        assertTrue(RegistryManifest.exists(tmp, manifest.fingerprint()));

        RegistryManifest read = RegistryManifest.read(tmp, manifest.fingerprint());
        assertEquals(manifest.fingerprint(), read.fingerprint());
        assertEquals(manifest.protocolVersion(), read.protocolVersion());
        assertEquals(manifest.blockNames(), read.blockNames());
        assertEquals(manifest.biomeNames(), read.biomeNames());
    }

    @Test
    void manifestsAreImmutable(@TempDir Path tmp) throws Exception {
        RegistryManifest manifest = RegistryManifest.of(777, names("minecraft:air"), names("minecraft:plains"));
        manifest.writeIfAbsent(tmp);
        long firstWrite = Files.getLastModifiedTime(RegistryManifest.pathFor(tmp, manifest.fingerprint()))
                .toMillis();

        // A second write of the same fingerprint must not touch the file.
        manifest.writeIfAbsent(tmp);
        assertEquals(firstWrite, Files.getLastModifiedTime(
                RegistryManifest.pathFor(tmp, manifest.fingerprint())).toMillis());
    }

    @Test
    void rejectsAMismatchedFingerprint(@TempDir Path tmp) throws Exception {
        RegistryManifest manifest = RegistryManifest.of(777, names("minecraft:air"), names("minecraft:plains"));
        manifest.writeIfAbsent(tmp);
        assertThrows(java.io.IOException.class,
                () -> RegistryManifest.read(tmp, "0000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test
    void listsTheFingerprintsItKnows(@TempDir Path tmp) throws Exception {
        RegistryManifest first = RegistryManifest.of(777, names("minecraft:air"), names("minecraft:plains"));
        RegistryManifest second = RegistryManifest.of(777, names("minecraft:stone"), names("minecraft:plains"));
        first.writeIfAbsent(tmp);
        second.writeIfAbsent(tmp);
        List<String> fingerprints = RegistryManifest.listFingerprints(tmp);
        assertEquals(2, fingerprints.size());
        assertTrue(fingerprints.contains(first.fingerprint()));
        assertTrue(fingerprints.contains(second.fingerprint()));
    }

    @Test
    void rejectsATruncatedManifest(@TempDir Path tmp) throws Exception {
        RegistryManifest manifest = RegistryManifest.of(777, names("minecraft:air"), names("minecraft:plains"));
        manifest.writeIfAbsent(tmp);
        Path path = RegistryManifest.pathFor(tmp, manifest.fingerprint());
        byte[] raw = space.cobaltworks.zvcr.io.ZstdCodec.decompress(Files.readAllBytes(path));
        String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        Files.write(path, space.cobaltworks.zvcr.io.ZstdCodec.compress(
                text.substring(0, text.length() / 2).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(java.io.IOException.class, () -> RegistryManifest.read(tmp, manifest.fingerprint()));
    }

    @Test
    void emptyRegistryIsDistinctFromAnUnsetOne() {
        assertNotEquals(RegistryManifest.fingerprintOf(777, names(), names()),
                RegistryManifest.fingerprintOf(777, names("minecraft:air"), names()));
        assertFalse(RegistryManifest.exists(Path.of("/nonexistent-store"), "deadbeef"));
    }
}

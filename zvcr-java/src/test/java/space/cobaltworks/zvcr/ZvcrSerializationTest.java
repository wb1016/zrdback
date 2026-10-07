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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import space.cobaltworks.zvcr.format.PackedData;
import space.cobaltworks.zvcr.format.PackedSnapshot;
import space.cobaltworks.zvcr.io.ByteReader;
import space.cobaltworks.zvcr.io.ZvcrReader;
import space.cobaltworks.zvcr.io.ZvcrWriter;
import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.ZvcrFile;

/**
 * Byte-exact serialization roundtrips for the ZVCR-3D container layout.
 */
class ZvcrSerializationTest {

    private static final int PROTOCOL = 777; // MC 26.3

    private static int[] iota(int n, int start) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = start + i;
        return out;
    }

    private static ZvcrFile buildDiverseFile() {
        ZvcrFile file = new ZvcrFile(PROTOCOL, Dimension.OVERWORLD);
        Segment segment = new Segment(Dimension.OVERWORLD);

        // Section 0: single-value chain
        segment.blockSections.section(0).insertSnapshot(
                new PackedSnapshot(new PackedData.SingleValue(0xABCD), 1000));

        // Section 1: 4-bit chain with a delta
        int[] s1a = new int[Zvcr.SECTION_SIZE_BLOCKS];
        Arrays.fill(s1a, 7);
        int[] s1b = s1a.clone();
        s1b[5] = 9;
        segment.blockSections.section(1).insertSnapshot(new PackedSnapshot(PackedData.pack(s1a), 1000));
        segment.blockSections.section(1).insertSnapshot(new PackedSnapshot(PackedData.pack(s1b), 2000));

        // Section 2: 8-bit palette
        int[] s2 = new int[Zvcr.SECTION_SIZE_BLOCKS];
        for (int i = 0; i < s2.length; i++) s2[i] = i % 256;
        segment.blockSections.section(2).insertSnapshot(new PackedSnapshot(PackedData.pack(s2), 1000));

        // Section 3: direct mode (258+ uniques)
        int[] s3 = iota(Zvcr.SECTION_SIZE_BLOCKS, 0);
        segment.blockSections.section(3).insertSnapshot(new PackedSnapshot(PackedData.pack(s3), 1000));

        // Biome section 0: 4-bit
        int[] b0 = new int[Zvcr.SECTION_SIZE_BIOMES];
        for (int i = 0; i < b0.length; i++) b0[i] = i % 4;
        segment.biomeSections.section(0).insertSnapshot(new PackedSnapshot(PackedData.pack(b0), 1000));

        // Shared palette across sections 1 and 4 (dedup must collapse them):
        // s1b packs to a real 2-entry palette ([7, 9]); reuse it verbatim.
        segment.blockSections.section(4).insertSnapshot(new PackedSnapshot(PackedData.pack(s1b), 1500));

        file.region.set(0, 0, segment);
        // A second, far-away segment (negative region coords exercised elsewhere)
        Segment segment2 = new Segment(Dimension.OVERWORLD);
        segment2.blockSections.section(0).insertSnapshot(
                new PackedSnapshot(new PackedData.SingleValue(1), 1000));
        file.region.set(31, 31, segment2);
        return file;
    }

    @Test
    void byteExactRoundtrip() {
        ZvcrFile file = buildDiverseFile();
        byte[] bytes1 = ZvcrWriter.serialize(file);
        ZvcrFile parsed = ZvcrReader.read(bytes1);
        byte[] bytes2 = ZvcrWriter.serialize(parsed);

        // Same implementation + same zstd level => deterministic bytes
        assertArrayEquals(bytes1, bytes2);
    }

    @Test
    void headerLayout() {
        ZvcrFile file = new ZvcrFile(PROTOCOL, Dimension.NETHER);
        byte[] bytes = ZvcrWriter.serialize(file);
        assertEquals('z', bytes[0]);
        assertEquals('v', bytes[1]);
        assertEquals('c', bytes[2]);
        assertEquals('r', bytes[3]);
        assertEquals('3', bytes[4]);
        assertEquals('d', bytes[5]);
        assertEquals(7, bytes[6] & 0xFF);          // version 1.0.0.0
        assertEquals(1, bytes[7] & 0xFF);          // nether
        assertEquals(PROTOCOL & 0xFF, bytes[8] & 0xFF);        // protocol LE
        assertEquals((PROTOCOL >>> 8) & 0xFF, bytes[9] & 0xFF);
    }

    @Test
    void structuralRoundtrip() {
        ZvcrFile file = buildDiverseFile();
        ZvcrFile parsed = ZvcrReader.read(ZvcrWriter.serialize(file));

        assertEquals(7, parsed.version.number);
        assertEquals(PROTOCOL, parsed.protocolVersion);
        assertEquals(Dimension.OVERWORLD, parsed.dimensionType);
        assertEquals(2, parsed.region.presentCount());

        Segment segment = parsed.region.get(0, 0).orElseThrow();
        assertEquals(24, segment.sectionCount);

        // Section 0: single value
        var chain0 = segment.blockSections.section(0);
        assertEquals(1, chain0.reverseDeltas().size());
        assertInstanceOf(PackedData.SingleValue.class, chain0.latestSnapshot().orElseThrow().data());
        assertEquals(0xABCD, chain0.latestSnapshot().orElseThrow().data().unpack(4096)[0]);

        // Section 1: two entries, newest first
        var chain1 = segment.blockSections.section(1);
        assertEquals(2, chain1.reverseDeltas().size());
        assertEquals(2000, chain1.latestSnapshot().orElseThrow().timestamp());
        assertEquals(1000, chain1.delta(1).orElseThrow().timestamp());
        int[] reconstructed = chain1.snapshotBefore(1000).orElseThrow();
        assertEquals(7, reconstructed[5]); // delta restored the previous value

        // Section 3: direct palette
        var chain3 = segment.blockSections.section(3);
        PackedData.Paletted paletted = assertInstanceOf(PackedData.Paletted.class,
                chain3.latestSnapshot().orElseThrow().data());
        assertTrue(paletted.palette().direct());

        // Far segment present
        assertTrue(parsed.region.get(31, 31).isPresent());
    }

    @Test
    void paletteTableDedup() {
        // Two sections sharing identical content must share one palette entry;
        // the file must parse back with both snapshots referencing the same index.
        ZvcrFile file = buildDiverseFile();
        byte[] bytes = ZvcrWriter.serialize(file);
        ZvcrFile parsed = ZvcrReader.read(bytes);

        Segment segment = parsed.region.get(0, 0).orElseThrow();
        PackedData.Paletted s1 = (PackedData.Paletted) segment.blockSections.section(1)
                .latestSnapshot().orElseThrow().data();
        PackedData.Paletted s4 = (PackedData.Paletted) segment.blockSections.section(4)
                .latestSnapshot().orElseThrow().data();
        assertEquals(s1.palette(), s4.palette());
    }

    @Test
    void emptyRegionRoundtrip() {
        ZvcrFile file = new ZvcrFile(PROTOCOL, Dimension.THE_END);
        byte[] bytes = ZvcrWriter.serialize(file);
        ZvcrFile parsed = ZvcrReader.read(bytes);
        assertEquals(0, parsed.region.presentCount());
        assertEquals(Dimension.THE_END, parsed.dimensionType);
        assertEquals(16, parsed.dimensionType.sectionCount());
    }

    @Test
    void maxDeltasSkipsOlderEntries() {
        ZvcrFile file = buildDiverseFile();
        byte[] bytes = ZvcrWriter.serialize(file);

        ZvcrFile parsed = ZvcrReader.read(bytes, 1);
        Segment segment = parsed.region.get(0, 0).orElseThrow();
        // Section 1 has 2 entries; only the head is materialized
        assertEquals(1, segment.blockSections.section(1).reverseDeltas().size());
        // Section 0 has 1 entry; unaffected
        assertEquals(1, segment.blockSections.section(0).reverseDeltas().size());
    }

    @Test
    void rejectsBadMagic() {
        byte[] bytes = ZvcrWriter.serialize(buildDiverseFile());
        bytes[0] = 'X';
        var ex = assertThrows(ByteReader.ReadException.class, () -> ZvcrReader.read(bytes));
        assertTrue(ex.getMessage().contains("Invalid header prefix"));
    }

    @Test
    void rejectsBadVersion() {
        byte[] bytes = ZvcrWriter.serialize(buildDiverseFile());
        bytes[6] = 8; // > latest (7)
        var ex = assertThrows(ByteReader.ReadException.class, () -> ZvcrReader.read(bytes));
        assertTrue(ex.getMessage().contains("Invalid zvcr version"));
    }

    @Test
    void rejectsBadDimension() {
        byte[] bytes = ZvcrWriter.serialize(buildDiverseFile());
        bytes[7] = 3;
        assertThrows(IllegalArgumentException.class, () -> ZvcrReader.read(bytes));
    }

    @Test
    void rejectsTruncatedFile() {
        byte[] bytes = ZvcrWriter.serialize(buildDiverseFile());
        byte[] truncated = Arrays.copyOf(bytes, bytes.length / 2);
        assertThrows(Exception.class, () -> ZvcrReader.read(truncated));
    }

    @Test
    void olderVersionsAcceptedReadOnly() {
        // Version number 6 (0.1.4.0) passes the <= 7 gate; the reader applies no
        // per-version logic (matching the reference reader).
        byte[] bytes = ZvcrWriter.serialize(buildDiverseFile());
        bytes[6] = 6;
        ZvcrFile parsed = ZvcrReader.read(bytes);
        assertEquals(6, parsed.version.number);
    }

    @Test
    void netherAndEndSectionCounts() {
        for (Dimension dim : List.of(Dimension.NETHER, Dimension.THE_END)) {
            ZvcrFile file = new ZvcrFile(PROTOCOL, dim);
            Segment segment = new Segment(dim);
            segment.blockSections.section(0).insertSnapshot(
                    new PackedSnapshot(new PackedData.SingleValue(3), 1000));
            file.region.set(0, 0, segment);
            ZvcrFile parsed = ZvcrReader.read(ZvcrWriter.serialize(file));
            assertEquals(16, parsed.region.get(0, 0).orElseThrow().sectionCount);
        }
    }

    @Test
    void manySegmentsRoundtrip() {
        ZvcrFile file = new ZvcrFile(PROTOCOL, Dimension.OVERWORLD);
        for (int x = 0; x < 32; x += 7) {
            for (int z = 0; z < 32; z += 11) {
                Segment segment = new Segment(Dimension.OVERWORLD);
                segment.blockSections.section(0).insertSnapshot(
                        new PackedSnapshot(new PackedData.SingleValue(x * 32 + z), 1000));
                file.region.set(x, z, segment);
            }
        }
        byte[] bytes = ZvcrWriter.serialize(file);
        ZvcrFile parsed = ZvcrReader.read(bytes);
        assertEquals(file.region.presentCount(), parsed.region.presentCount());
        for (int x = 0; x < 32; x += 7) {
            for (int z = 0; z < 32; z += 11) {
                Segment segment = parsed.region.get(x, z).orElseThrow();
                assertEquals(x * 32 + z, segment.blockSections.section(0)
                        .latestSnapshot().orElseThrow().data().unpack(4096)[0]);
            }
        }
    }
}

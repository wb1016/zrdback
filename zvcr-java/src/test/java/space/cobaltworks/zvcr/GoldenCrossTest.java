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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import space.cobaltworks.zvcr.format.PackedData;
import space.cobaltworks.zvcr.format.PackedSnapshot;
import space.cobaltworks.zvcr.io.ZstdCodec;
import space.cobaltworks.zvcr.io.ZvcrReader;
import space.cobaltworks.zvcr.io.ZvcrWriter;
import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.SegmentState;
import space.cobaltworks.zvcr.region.SegmentStateType;
import space.cobaltworks.zvcr.region.TileEntity;
import space.cobaltworks.zvcr.region.TileEntityPosition;
import space.cobaltworks.zvcr.region.ZvcrFile;

/**
 * Golden cross-validation against the C++ reference implementation.
 *
 * <p>{@code golden-tool/zvcr_golden gen} writes a deterministic synthetic file
 * (mirrored by {@link #buildGoldenFile()} below). The test compares the
 * <em>uncompressed region containers</em> byte-for-byte: Zstd output may differ
 * across implementations, but the container layout may not.
 *
 * <p>Regenerate the golden files with:
 * <pre>
 *   cd golden-tool && cmake -B build && cmake --build build
 *   ./build/zvcr_golden gen zvcr-java/src/test/resources/golden/golden.zvcr3d
 *   ./build/zvcr_golden hexdump zvcr-java/src/test/resources/golden/golden.zvcr3d \
 *       &gt; zvcr-java/src/test/resources/golden/golden.hex
 * </pre>
 */
class GoldenCrossTest {

    private static final int PROTOCOL = 777;

    /** Mirrors golden-tool/main.cpp buildMainSegment()/buildFarSegment(). */
    private static ZvcrFile buildGoldenFile() {
        ZvcrFile file = new ZvcrFile(PROTOCOL, Dimension.OVERWORLD);

        Segment main = new Segment(Dimension.OVERWORLD);
        // Section 0: single-value chain
        main.blockSections.section(0).insertSnapshot(
                new PackedSnapshot(new PackedData.SingleValue(0xABCD), 1000));
        // Section 1: uniform 7, then position 5 -> 9
        int[] s1a = new int[Zvcr.SECTION_SIZE_BLOCKS];
        Arrays.fill(s1a, 7);
        int[] s1b = s1a.clone();
        s1b[5] = 9;
        main.blockSections.section(1).insertSnapshot(new PackedSnapshot(PackedData.pack(s1a), 1000));
        main.blockSections.section(1).insertSnapshot(new PackedSnapshot(PackedData.pack(s1b), 2000));
        // Section 2: 8-bit palette (256 uniques)
        int[] s2 = new int[Zvcr.SECTION_SIZE_BLOCKS];
        for (int i = 0; i < s2.length; i++) s2[i] = i % 256;
        main.blockSections.section(2).insertSnapshot(new PackedSnapshot(PackedData.pack(s2), 1000));
        // Section 3: direct mode (4096 uniques)
        int[] s3 = new int[Zvcr.SECTION_SIZE_BLOCKS];
        for (int i = 0; i < s3.length; i++) s3[i] = i;
        main.blockSections.section(3).insertSnapshot(new PackedSnapshot(PackedData.pack(s3), 1000));
        // Section 4: shares section 1's latest palette
        main.blockSections.section(4).insertSnapshot(new PackedSnapshot(PackedData.pack(s1b), 1500));
        // Biome section 0: 4 uniques
        int[] b0 = new int[Zvcr.SECTION_SIZE_BIOMES];
        for (int i = 0; i < b0.length; i++) b0[i] = i % 4;
        main.biomeSections.section(0).insertSnapshot(new PackedSnapshot(PackedData.pack(b0), 1000));
        // Segment info
        main.info.insertSnapshot(new SegmentState(SegmentStateType.NEW, 1000));
        main.info.insertSnapshot(new SegmentState(SegmentStateType.OLD, 3000));
        // Tile entities
        TileEntity a = new TileEntity(10, new TileEntityPosition(1, 2, 64), new byte[] {1, 2, 3});
        TileEntity b = new TileEntity(20, new TileEntityPosition(3, 4, 64), new byte[] {4, 5});
        main.tileEntities.insertSnapshot(1000, List.of(a, b));
        TileEntity b2 = new TileEntity(20, new TileEntityPosition(3, 4, 64), new byte[] {9});
        TileEntity c = new TileEntity(30, new TileEntityPosition(5, 6, 70), new byte[] {7});
        main.tileEntities.insertSnapshot(2000, List.of(b2, c));
        file.region.set(0, 0, main);

        Segment far = new Segment(Dimension.OVERWORLD);
        far.blockSections.section(0).insertSnapshot(
                new PackedSnapshot(new PackedData.SingleValue(1), 1000));
        file.region.set(31, 31, far);

        return file;
    }

    private static String containerHex(byte[] zvcrBytes) {
        byte[] container = ZstdCodec.decompress(Arrays.copyOfRange(zvcrBytes, 10, zvcrBytes.length));
        StringBuilder sb = new StringBuilder(container.length * 2);
        for (byte b : container) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String resourceHex(String name) throws Exception {
        try (var in = GoldenCrossTest.class.getResourceAsStream("/golden/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (var in = GoldenCrossTest.class.getResourceAsStream("/golden/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void javaWriterMatchesCppContainer() throws Exception {
        String expected = resourceHex("golden.hex");
        String actual = containerHex(ZvcrWriter.serialize(buildGoldenFile()));
        assertEquals(expected, actual,
                "Java-written uncompressed container differs from the C++ reference");
    }

    @Test
    void javaReaderParsesCppGoldenFile() throws Exception {
        byte[] golden = resourceBytes("golden.zvcr3d");
        ZvcrFile parsed = ZvcrReader.read(golden);

        assertEquals(7, parsed.version.number);
        assertEquals(PROTOCOL, parsed.protocolVersion);
        assertEquals(Dimension.OVERWORLD, parsed.dimensionType);
        assertEquals(2, parsed.region.presentCount());

        Segment main = parsed.region.get(0, 0).orElseThrow();
        assertEquals(0xABCD, main.blockSections.section(0)
                .latestSnapshot().orElseThrow().data().unpack(4096)[0]);
        assertEquals(2, main.blockSections.section(1).reverseDeltas().size());
        assertEquals(7, main.blockSections.section(1).snapshotBefore(1000).orElseThrow()[5]);
        assertEquals(9, main.blockSections.section(1).snapshotBefore(9999).orElseThrow()[5]);

        // Tile entities: latest = b2 + c; before 1000 = a + b
        var latest = main.tileEntities.snapshotBefore(9999).orElseThrow();
        assertEquals(2, latest.size());
        assertEquals(9, latest.get(new TileEntityPosition(3, 4, 64)).nbt()[0]);
        var oldest = main.tileEntities.snapshotBefore(1000).orElseThrow();
        assertEquals(2, oldest.size());
        assertArrayEquals(new byte[] {1, 2, 3},
                oldest.get(new TileEntityPosition(1, 2, 64)).nbt());

        // Segment info
        assertEquals(SegmentStateType.OLD, main.info.latestSnapshot().orElseThrow().type());
        assertEquals(SegmentStateType.NEW, main.info.snapshotBefore(1000).orElseThrow().type());

        // Re-serializing the parsed file must reproduce the same container.
        assertEquals(resourceHex("golden.hex"), containerHex(ZvcrWriter.serialize(parsed)));
    }

    @Test
    void javaSingleValueAndDeltaSemanticsMatchCpp() throws Exception {
        byte[] golden = resourceBytes("golden.zvcr3d");
        ZvcrFile parsed = ZvcrReader.read(golden);
        Segment far = parsed.region.get(31, 31).orElseThrow();
        assertEquals(1, far.blockSections.section(0).reverseDeltas().size());
        assertInstanceOf(PackedData.SingleValue.class,
                far.blockSections.section(0).latestSnapshot().orElseThrow().data());
    }
}

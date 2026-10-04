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
package space.cobaltworks.zrdback.zvcr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

import space.cobaltworks.zrdback.zvcr.format.PackedData;

class PackedDataTest {

    private static int[] iota(int n, int start) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = start + i;
        return out;
    }

    // ------------------------------------------------------------------
    // bitsPerEntry / packed lengths
    // ------------------------------------------------------------------

    @Test
    void bitsPerEntryThresholds() {
        assertEquals(4, space.cobaltworks.zrdback.zvcr.format.Palette.bitsPerEntry(1));
        assertEquals(4, space.cobaltworks.zrdback.zvcr.format.Palette.bitsPerEntry(16));
        assertEquals(8, space.cobaltworks.zrdback.zvcr.format.Palette.bitsPerEntry(17));
        assertEquals(8, space.cobaltworks.zrdback.zvcr.format.Palette.bitsPerEntry(256));
        assertEquals(16, space.cobaltworks.zrdback.zvcr.format.Palette.bitsPerEntry(257));
    }

    @Test
    void packedLengthsForBlockSections() {
        // 4096 atoms: 16/long at 4 bits -> 256; 8/long at 8 bits -> 512; 4/long at 16 bits -> 1024
        assertEquals(256, space.cobaltworks.zrdback.zvcr.format.Palette.packedArrayLength(4096, 4));
        assertEquals(512, space.cobaltworks.zrdback.zvcr.format.Palette.packedArrayLength(4096, 8));
        assertEquals(1024, space.cobaltworks.zrdback.zvcr.format.Palette.packedArrayLength(4096, 16));
        // 64 atoms: 4 / 8 / 16 longs
        assertEquals(4, space.cobaltworks.zrdback.zvcr.format.Palette.packedArrayLength(64, 4));
        assertEquals(8, space.cobaltworks.zrdback.zvcr.format.Palette.packedArrayLength(64, 8));
        assertEquals(16, space.cobaltworks.zrdback.zvcr.format.Palette.packedArrayLength(64, 16));
    }

    // ------------------------------------------------------------------
    // Roundtrips per bit width
    // ------------------------------------------------------------------

    @Test
    void roundtripSingleValue() {
        int[] data = new int[Zvcr.SECTION_SIZE_BLOCKS];
        Arrays.fill(data, 0xABCD);
        PackedData packed = PackedData.pack(data);
        assertInstanceOf(PackedData.SingleValue.class, packed);
        assertArrayEquals(data, packed.unpack(Zvcr.SECTION_SIZE_BLOCKS));
    }

    @Test
    void roundtrip4Bit() {
        // exactly 16 unique values over 4096 atoms -> 4 bits
        int[] data = new int[Zvcr.SECTION_SIZE_BLOCKS];
        for (int i = 0; i < data.length; i++) data[i] = i % 16;
        PackedData packed = PackedData.pack(data);
        PackedData.Paletted paletted = assertInstanceOf(PackedData.Paletted.class, packed);
        assertEquals(4, paletted.palette().bitsPerEntry());
        assertEquals(16, paletted.palette().length());
        assertEquals(256, paletted.packedLongs().length);
        assertArrayEquals(data, packed.unpack(Zvcr.SECTION_SIZE_BLOCKS));
    }

    @Test
    void roundtrip8Bit() {
        // exactly 256 unique values over 4096 atoms -> 8 bits
        int[] data = new int[Zvcr.SECTION_SIZE_BLOCKS];
        for (int i = 0; i < data.length; i++) data[i] = i % 256;
        PackedData packed = PackedData.pack(data);
        PackedData.Paletted paletted = assertInstanceOf(PackedData.Paletted.class, packed);
        assertEquals(8, paletted.palette().bitsPerEntry());
        assertEquals(256, paletted.palette().length());
        assertEquals(512, paletted.packedLongs().length);
        assertArrayEquals(data, packed.unpack(Zvcr.SECTION_SIZE_BLOCKS));
    }

    @Test
    void roundtripDirectModeWith258Entries() {
        // 258+ unique values switch to direct palette mode (empty palette, raw atoms).
        int[] data = iota(Zvcr.SECTION_SIZE_BLOCKS, 0); // 4096 unique values
        PackedData packed = PackedData.pack(data);
        PackedData.Paletted paletted = assertInstanceOf(PackedData.Paletted.class, packed);
        assertTrue(paletted.palette().direct());
        assertEquals(16, paletted.palette().bitsPerEntry());
        assertEquals(1024, paletted.packedLongs().length);
        assertArrayEquals(data, packed.unpack(Zvcr.SECTION_SIZE_BLOCKS));
    }

    @Test
    void roundtripBiomeSection() {
        // exactly 4 unique values over 64 atoms -> 4 bits, 4 longs
        int[] data = new int[Zvcr.SECTION_SIZE_BIOMES];
        for (int i = 0; i < data.length; i++) data[i] = 5 + (i % 4);
        PackedData packed = PackedData.pack(data);
        PackedData.Paletted paletted = assertInstanceOf(PackedData.Paletted.class, packed);
        assertEquals(4, paletted.palette().bitsPerEntry());
        assertEquals(4, paletted.packedLongs().length);
        assertArrayEquals(data, packed.unpack(Zvcr.SECTION_SIZE_BIOMES));
    }

    // ------------------------------------------------------------------
    // Exact bit layout (LSB-first within uint64 cells)
    // ------------------------------------------------------------------

    @Test
    void lsbFirstLayout4Bit() {
        // exactly 16 unique values (1..16); palette index of value v is v-1.
        int[] data = new int[64];
        for (int i = 0; i < 64; i++) data[i] = (i % 16) + 1;
        PackedData.Paletted paletted = (PackedData.Paletted) PackedData.pack(data);

        long expectedCell0 = 0;
        for (int i = 0; i < 16; i++) {
            expectedCell0 |= (long) i << (4 * i); // indices 0..15, LSB-first
        }
        assertEquals(0xFEDCBA9876543210L, expectedCell0);
        assertEquals(expectedCell0, paletted.packedLongs()[0]);
    }

    @Test
    void lsbFirstLayout16BitDirect() {
        // 258+ unique values -> direct mode: raw atoms at 16 bits, 4 per long.
        int[] data = new int[1024];
        for (int i = 0; i < data.length; i++) data[i] = i;
        PackedData.Paletted paletted = (PackedData.Paletted) PackedData.pack(data);
        assertTrue(paletted.palette().direct());
        long expected = 0x0001L << 16 | 0x0002L << 32 | 0x0003L << 48; // data[0]=0 occupies bits 0..15
        assertEquals(expected, paletted.packedLongs()[0]);
        assertArrayEquals(data, paletted.unpack(1024));
    }

    @Test
    void highBitPatternsSurviveUnsigned() {
        // Atoms with the high bit set must not be sign-extended anywhere.
        int[] data = {0x8001, 0xFFFF, 0x7FFF, 0x0001};
        PackedData packed = PackedData.pack(data);
        assertArrayEquals(data, packed.unpack(4));
    }

    // ------------------------------------------------------------------
    // Reference-implementation quirk (replicated for byte compatibility)
    // ------------------------------------------------------------------

    @Test
    void quirk257EntryPaletteIsLossyAtIndex256() {
        // The C++ buildPalette stores palette indices in a uint8 array, so index
        // 256 truncates to 0. With exactly 257 unique values the palette stays
        // indirect (16 bits), and atoms of the 257th unique value read back as
        // the 1st palette entry. Replicated exactly; documented, not "fixed".
        int[] data = new int[Zvcr.SECTION_SIZE_BLOCKS];
        for (int i = 0; i < data.length; i++) data[i] = 1000 + (i % 257); // 257 uniques: 1000..1256
        PackedData.Paletted paletted = (PackedData.Paletted) PackedData.pack(data);

        assertEquals(257, paletted.palette().length());
        assertEquals(16, paletted.palette().bitsPerEntry());
        assertEquals(1024, paletted.packedLongs().length);

        int[] unpacked = paletted.unpack(Zvcr.SECTION_SIZE_BLOCKS);
        for (int i = 0; i < data.length; i++) {
            int expected = (data[i] == 1256) ? 1000 : data[i];
            assertEquals(expected, unpacked[i], "mismatch at " + i);
        }
    }

    // ------------------------------------------------------------------
    // Canonical palette mode (mod write path)
    // ------------------------------------------------------------------

    @Test
    void canonicalRoundtripIsLosslessIncluding257Entries() {
        // Canonical mode assigns full-range indices, so the 257-entry case that
        // is lossy in reference-compatible mode roundtrips exactly.
        int[] data = new int[Zvcr.SECTION_SIZE_BLOCKS];
        for (int i = 0; i < data.length; i++) data[i] = 1000 + (i % 257);
        PackedData packed = PackedData.pack(data, true);
        assertArrayEquals(data, packed.unpack(Zvcr.SECTION_SIZE_BLOCKS));
    }

    @Test
    void canonicalPaletteIsSortedAscending() {
        int[] data = {500, 3, 500, 42, 3, 42};
        PackedData.Paletted paletted = (PackedData.Paletted) PackedData.pack(data, true);
        assertArrayEquals(new int[] {3, 42, 500}, paletted.palette().entries());
        assertArrayEquals(data, paletted.unpack(6));
    }

    @Test
    void canonicalPalettesDependOnlyOnAtomSet() {
        // Same atom set, different arrangements -> identical palette (dedup).
        int[] a = new int[64];
        int[] b = new int[64];
        for (int i = 0; i < 64; i++) {
            a[i] = (i % 7) * 100;          // first-encounter order: 0, 100, ..., 600
            b[i] = ((63 - i) % 7) * 100;   // different first-encounter order
        }
        PackedData.Paletted pa = (PackedData.Paletted) PackedData.pack(a, true);
        PackedData.Paletted pb = (PackedData.Paletted) PackedData.pack(b, true);
        assertEquals(pa.palette(), pb.palette());
        assertArrayEquals(a, pa.unpack(64));
        assertArrayEquals(b, pb.unpack(64));
    }

    @Test
    void canonicalSingleValueAndDirect() {
        int[] uniform = new int[64];
        java.util.Arrays.fill(uniform, 0xBEEF);
        assertInstanceOf(PackedData.SingleValue.class, PackedData.pack(uniform, true));

        int[] many = new int[4096];
        for (int i = 0; i < many.length; i++) many[i] = i; // 4096 uniques -> direct
        PackedData.Paletted direct = (PackedData.Paletted) PackedData.pack(many, true);
        assertTrue(direct.palette().direct());
        assertArrayEquals(many, direct.unpack(4096));
    }

    @Test
    void canonicalAndDefaultAgreeOnReaderSemantics() {
        // Both modes must produce data the same reader reconstructs identically.
        java.util.random.RandomGenerator rng = java.util.random.RandomGenerator.of("L64X128MixRandom");
        for (int trial = 0; trial < 50; trial++) {
            int[] data = new int[64 + rng.nextInt(1000)];
            for (int i = 0; i < data.length; i++) data[i] = rng.nextInt(300);
            long distinct = java.util.Arrays.stream(data).distinct().count();
            if (distinct == 257) continue; // default mode is lossy there (quirk)
            assertArrayEquals(data, PackedData.pack(data, false).unpack(data.length));
            assertArrayEquals(data, PackedData.pack(data, true).unpack(data.length));
        }
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    void rejectsOutOfRangeAtoms() {
        assertThrows(IllegalArgumentException.class,
                () -> PackedData.pack(new int[] {0x10000}));
        assertThrows(IllegalArgumentException.class,
                () -> PackedData.pack(new int[] {-1}));
    }

    @Test
    void randomRoundtrips() {
        RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
        for (int trial = 0; trial < 200; trial++) {
            int uniqueCount = 1 + rng.nextInt(300);
            int[] unique = new int[uniqueCount];
            for (int i = 0; i < uniqueCount; i++) {
                unique[i] = rng.nextInt(0x10000);
            }
            int[] data = new int[64 + rng.nextInt(4096 - 64 + 1)];
            for (int i = 0; i < data.length; i++) {
                data[i] = unique[rng.nextInt(uniqueCount)];
            }
            // The uint8-index quirk makes exactly-257-distinct-value sections
            // lossy (covered explicitly above); skip them here.
            long distinct = Arrays.stream(data).distinct().count();
            if (distinct == 257) continue;
            PackedData packed = PackedData.pack(data);
            assertArrayEquals(data, packed.unpack(data.length),
                    "roundtrip failed for " + distinct + " distinct values, size " + data.length);
        }
    }
}

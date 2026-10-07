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
package space.cobaltworks.zvcr.format;

import java.util.Arrays;

import space.cobaltworks.zvcr.Zvcr;

/**
 * Section data in packed form: either a single uniform value or a palette-packed
 * {@code long} array. Mirrors the C++ {@code PackedData<unpackedSize>} variant.
 *
 * <p>Packing layout (must stay byte-compatible with the C++ reference):
 * <ul>
 *   <li>{@code valuesPerLong = 64 / bitsPerEntry}, entries packed LSB-first</li>
 *   <li>{@code bitsPerEntry}: 4 (palette ≤ 16), 8 (≤ 256), else 16 (direct mode)</li>
 *   <li>Indirect palettes may hold up to 257 entries; the reference implementation
 *       truncates palette indices to 8 bits when building them, so the 257th entry
 *       (index 256) aliases index 0. Replicated here for byte compatibility.</li>
 * </ul>
 */
public sealed interface PackedData permits PackedData.SingleValue, PackedData.Paletted {

    int SINGLE_VALUE_TYPE = 0;
    int SECTION_PALETTE_TYPE = 1;

    /** Packs {@code sectionData} (atoms, each 0..0xFFFF) of the given unpacked size. */
    static PackedData pack(int[] sectionData) {
        return pack(sectionData, false);
    }

    /**
     * Packs {@code sectionData} (atoms, each 0..0xFFFF).
     *
     * @param canonicalPalette when true, palette entries are assigned in
     *     ascending atom order instead of first-encounter order. This makes the
     *     palette depend only on the SET of atoms in the section, so sections
     *     with equal atom sets share one palette-table entry (better dedup).
     *     Readers are order-agnostic, so this is format-compatible; it also
     *     avoids the reference implementation's uint8 index-truncation quirk
     *     (index 256 is representable at 16 bits per entry). The default
     *     (false) replicates the C++ writer byte-for-byte.
     */
    static PackedData pack(int[] sectionData, boolean canonicalPalette) {
        final int unpackedSize = sectionData.length;

        if (canonicalPalette) {
            return packCanonical(sectionData);
        }

        // buildPalette — replicates the C++ exactly, including the uint8 index
        // truncation quirk (palette index 256 aliases 0).
        final boolean[] unique = new boolean[0x10000];
        final int[] indices = new int[0x10000];
        final int[] entries = new int[Zvcr.MAX_INDIRECT_PALETTE_SIZE + 1];
        int paletteLength = 0;
        boolean direct = false;

        for (int atom : sectionData) {
            if (atom < 0 || atom > 0xFFFF) {
                throw new IllegalArgumentException("Atom out of uint16 range: " + atom);
            }
            if (unique[atom]) continue;
            unique[atom] = true;

            if (paletteLength > Zvcr.MAX_INDIRECT_PALETTE_SIZE) {
                direct = true;
                break;
            }
            indices[atom] = paletteLength;
            entries[paletteLength++] = atom;
        }

        if (direct) {
            return packDirect(sectionData);
        }
        if (paletteLength == 1) {
            return new SingleValue(entries[0]);
        }

        int bits = Palette.bitsPerEntry(paletteLength);
        long[] packed = new long[Palette.packedArrayLength(unpackedSize, bits)];
        long mask = (1L << bits) - 1;

        int unpackedIndex = 0;
        for (int cellIndex = 0; cellIndex < packed.length; cellIndex++) {
            long cell = 0;
            for (int bitIndex = 0; bitIndex < 64 && unpackedIndex < unpackedSize; bitIndex += bits) {
                int slice = indices[sectionData[unpackedIndex++]] & 0xFF;
                cell = (cell & ~(mask << bitIndex)) | ((slice & mask) << bitIndex);
            }
            packed[cellIndex] = cell;
        }
        return new Paletted(packed, new Palette(Arrays.copyOf(entries, paletteLength), bits));
    }

    /**
     * Canonical-order packing: unique atoms sorted ascending, full-range
     * indices (no uint8 truncation). Same bit-width and cell layout rules as
     * the default mode.
     */
    private static PackedData packCanonical(int[] sectionData) {
        final int unpackedSize = sectionData.length;
        final boolean[] unique = new boolean[0x10000];
        int uniqueCount = 0;
        for (int atom : sectionData) {
            if (atom < 0 || atom > 0xFFFF) {
                throw new IllegalArgumentException("Atom out of uint16 range: " + atom);
            }
            if (!unique[atom]) {
                unique[atom] = true;
                uniqueCount++;
            }
        }
        if (uniqueCount == 1) {
            return new SingleValue(sectionData[0]);
        }
        if (uniqueCount > Zvcr.MAX_INDIRECT_PALETTE_SIZE + 1) {
            return packDirect(sectionData);
        }

        int[] entries = new int[uniqueCount];
        int[] indices = new int[0x10000];
        int next = 0;
        for (int atom = 0; atom < 0x10000; atom++) {
            if (unique[atom]) {
                entries[next] = atom;
                indices[atom] = next;
                next++;
            }
        }

        int bits = Palette.bitsPerEntry(uniqueCount);
        long[] packed = new long[Palette.packedArrayLength(unpackedSize, bits)];
        long mask = (1L << bits) - 1;

        int unpackedIndex = 0;
        for (int cellIndex = 0; cellIndex < packed.length; cellIndex++) {
            long cell = 0;
            for (int bitIndex = 0; bitIndex < 64 && unpackedIndex < unpackedSize; bitIndex += bits) {
                int slice = indices[sectionData[unpackedIndex++]];
                cell = (cell & ~(mask << bitIndex)) | ((slice & mask) << bitIndex);
            }
            packed[cellIndex] = cell;
        }
        return new Paletted(packed, new Palette(entries, bits));
    }

    /** Direct-mode packing: raw 16-bit atoms, 4 per long. */
    static PackedData packDirect(int[] sectionData) {
        final int bits = 16;
        final long mask = 0xFFFFL;
        long[] packed = new long[Palette.packedArrayLength(sectionData.length, bits)];

        int unpackedIndex = 0;
        for (int cellIndex = 0; cellIndex < packed.length; cellIndex++) {
            long cell = 0;
            for (int bitIndex = 0; bitIndex < 64 && unpackedIndex < sectionData.length; bitIndex += bits) {
                long slice = sectionData[unpackedIndex++] & mask;
                cell = (cell & ~(mask << bitIndex)) | (slice << bitIndex);
            }
            packed[cellIndex] = cell;
        }
        return new Paletted(packed, Palette.DIRECT);
    }

    /** Unpacks into an atom array of exactly {@code unpackedSize} entries. */
    int[] unpack(int unpackedSize);

    /** Uniform section: every atom equals {@code value}. */
    record SingleValue(int value) implements PackedData {
        public SingleValue {
            if (value < 0 || value > 0xFFFF) {
                throw new IllegalArgumentException("Atom out of uint16 range: " + value);
            }
        }

        @Override
        public int[] unpack(int unpackedSize) {
            int[] out = new int[unpackedSize];
            Arrays.fill(out, value);
            return out;
        }
    }

    /** Palette-packed section: {@code packedLongs} + indirect or direct {@link Palette}. */
    record Paletted(long[] packedLongs, Palette palette) implements PackedData {
        public Paletted {
            packedLongs = packedLongs.clone();
        }

        @Override
        public int[] unpack(int unpackedSize) {
            int[] out = new int[unpackedSize];
            if (palette.direct()) {
                unpackInto(out, packedLongs, 16, null);
            } else {
                unpackInto(out, packedLongs, palette.bitsPerEntry(), palette.entries());
            }
            return out;
        }
    }

    private static void unpackInto(int[] out, long[] packed, int bits, int[] paletteEntries) {
        final long mask = (1L << bits) - 1;
        int unpackedIndex = 0;
        for (long cell : packed) {
            for (int bitIndex = 0; bitIndex < 64 && unpackedIndex < out.length; bitIndex += bits) {
                int slice = (int) ((cell >>> bitIndex) & mask);
                if (paletteEntries != null) {
                    if (slice >= paletteEntries.length) {
                        throw new IllegalArgumentException(
                                "Palette index out of bounds: " + slice + " >= " + paletteEntries.length);
                    }
                    slice = paletteEntries[slice];
                }
                out[unpackedIndex++] = slice;
            }
        }
    }
}

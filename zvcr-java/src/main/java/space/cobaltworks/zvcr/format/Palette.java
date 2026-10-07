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
 * A palette for packed section data. Mirrors the C++ {@code Palette} struct.
 *
 * <p>An <em>indirect</em> palette maps packed indices to atom values via
 * {@link #entries()}. A <em>direct</em> palette (empty entries) means packed
 * entries are raw 16-bit atom values; its {@code bitsPerEntry} is always 16.
 */
public record Palette(int[] entries, int bitsPerEntry) {

    public static final Palette DIRECT = new Palette(new int[0], 16);

    public Palette {
        entries = entries.clone();
        if (bitsPerEntry < 0 || bitsPerEntry > 16 || bitsPerEntry % 4 != 0) {
            throw new IllegalArgumentException("bitsPerEntry must be 4/8/16, got " + bitsPerEntry);
        }
        // The reference implementation allows indirect palettes of up to 257 entries
        // (its "switch to direct" check runs before pushing the next unique atom),
        // encoded at 16 bits per entry.
        if (entries.length > Zvcr.MAX_INDIRECT_PALETTE_SIZE + 1) {
            throw new IllegalArgumentException(
                    "Indirect palette too long: " + entries.length);
        }
    }

    public static Palette of(int[] entries) {
        return new Palette(entries, bitsPerEntry(entries.length));
    }

    public boolean direct() {
        return entries.length == 0;
    }

    public int length() {
        return entries.length;
    }

    /**
     * Bits per entry for a given palette length: 4 bits for 1..=16 unique values,
     * 8 bits for 17..=256, else 16 (direct mode). Rounding to multiples of 4 is
     * intentional — byte-aligned data compresses better with Zstd.
     */
    public static int bitsPerEntry(int paletteLength) {
        if (paletteLength <= 16) return 4;
        if (paletteLength <= Zvcr.MAX_INDIRECT_PALETTE_SIZE) return 8;
        return 16;
    }

    /** Number of entries stored per packed {@code long}. */
    public static int valuesPerLong(int bitsPerEntry) {
        return 64 / bitsPerEntry;
    }

    /** Length of the packed {@code long} array for {@code unpackedSize} atoms. */
    public static int packedArrayLength(int unpackedSize, int bitsPerEntry) {
        int valuesPerLong = valuesPerLong(bitsPerEntry);
        return (unpackedSize + valuesPerLong - 1) / valuesPerLong;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Palette other)) return false;
        return bitsPerEntry == other.bitsPerEntry && Arrays.equals(entries, other.entries);
    }

    @Override
    public int hashCode() {
        int result = bitsPerEntry;
        result = 31 * result + Arrays.hashCode(entries);
        return result;
    }

    @Override
    public String toString() {
        return "Palette[len=" + entries.length + ", bits=" + bitsPerEntry + "]";
    }
}

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

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

import space.cobaltworks.zvcr.Zvcr;

/**
 * Atom-value remapping for a chain: rewrites every stored atom through a
 * translation table.
 *
 * <p>Chains store <em>global</em> registry IDs (block states, biomes). Those
 * IDs are only meaningful for the registry that produced them — a Minecraft
 * update or a mod change shifts the ID space. Remapping translates a chain
 * written against an older registry into the current one so a restore places
 * the right blocks instead of the right <em>IDs</em>.
 *
 * <p>The translation must be applied to every entry of a chain, not just its
 * head: a delta stores the <em>previous</em> value of each changed position,
 * so old and new values have to move through the same table to stay
 * consistent.
 *
 * <p>{@link Zvcr#STATE_UNCHANGED} ({@code 0xFFFF}) is a sentinel, not a
 * registry ID, and is never translated.
 */
public final class AtomRemapper {

    private AtomRemapper() {}

    /**
     * Applies {@code op} to every atom of {@code data}.
     *
     * <p>Palette order is preserved (the canonical ascending order is a
     * property of the <em>values</em>, and the mapping is a function of the
     * value, so a translated palette stays sorted iff the original was). The
     * packed indices are untouched — they index the palette, not the atoms.
     */
    public static PackedData remap(PackedData data, IntUnaryOperator op) {
        if (data instanceof PackedData.SingleValue single) {
            return new PackedData.SingleValue(translate(single.value(), op));
        }
        PackedData.Paletted paletted = (PackedData.Paletted) data;
        Palette palette = paletted.palette();
        if (palette.direct()) {
            // Direct mode stores the atoms inline in the packed longs; they
            // cannot be translated without unpacking.
            throw new IllegalArgumentException(
                    "Direct-mode palettes must be remapped after unpacking");
        }
        int[] entries = palette.entries();
        int[] translated = new int[entries.length];
        for (int i = 0; i < entries.length; i++) {
            translated[i] = translate(entries[i], op);
        }
        return new PackedData.Paletted(paletted.packedLongs(), new Palette(translated, palette.bitsPerEntry()));
    }

    /**
     * Applies {@code op} to every atom of a direct-mode palette by unpacking,
     * translating and repacking it.
     *
     * @param unpackedSize number of atoms in the section
     */
    public static PackedData remapDirect(PackedData.Paletted paletted, int unpackedSize,
                                         IntUnaryOperator op) {
        int[] atoms = paletted.unpack(unpackedSize);
        for (int i = 0; i < atoms.length; i++) {
            atoms[i] = translate(atoms[i], op);
        }
        return PackedData.pack(atoms, true);
    }

    /** Translates one atom, leaving the "unchanged" sentinel alone. */
    public static int translate(int atom, IntUnaryOperator op) {
        return atom == Zvcr.STATE_UNCHANGED ? atom : op.applyAsInt(atom);
    }

    /** Translates every atom of an array, leaving the sentinel alone. */
    public static int[] translate(int[] atoms, IntUnaryOperator op) {
        int[] out = Arrays.copyOf(atoms, atoms.length);
        for (int i = 0; i < out.length; i++) {
            out[i] = translate(out[i], op);
        }
        return out;
    }
}

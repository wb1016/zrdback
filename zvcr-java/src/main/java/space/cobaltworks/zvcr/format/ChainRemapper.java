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

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

import space.cobaltworks.zvcr.Zvcr;
import space.cobaltworks.zvcr.region.DeltaSections;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.ZvcrFile;

/**
 * Translates the registry IDs of a whole file between two registry epochs.
 *
 * <p>Chunks store block states and biomes as numeric registry IDs. Those IDs
 * are only meaningful for the exact registry that produced them, so a file
 * written before a Minecraft update or a mod change must be translated before
 * it can be restored against the current registry.
 *
 * <p>The translation applies to <em>every</em> entry of every chain: a delta
 * stores the previous value of each changed position, so the head snapshot and
 * the deltas below it must move through the same table to stay consistent.
 */
public final class ChainRemapper {

    private ChainRemapper() {}

    /**
     * Remaps every block and biome atom of {@code file}.
     *
     * @param file the file to translate in place
     * @param blocks translation for block-state IDs
     * @param biomes translation for biome IDs
     */
    public static void remap(ZvcrFile file, IntUnaryOperator blocks, IntUnaryOperator biomes) {
        for (int i = 0; i < Zvcr.SEGMENTS_PER_REGION; i++) {
            Segment segment = file.region.get(i / Zvcr.REGION_SIDELENGTH_SEGMENTS,
                    i % Zvcr.REGION_SIDELENGTH_SEGMENTS).orElse(null);
            if (segment == null) {
                continue;
            }
            for (int s = 0; s < segment.sectionCount; s++) {
                remapChain(segment.blockSections.section(s), blocks);
                remapChain(segment.biomeSections.section(s), biomes);
            }
        }
    }

    /** Remaps every atom of one chain in place. */
    public static void remapChain(PackedDeltaData chain, IntUnaryOperator op) {
        List<PackedSnapshot> remapped = new ArrayList<>(chain.size());
        for (PackedSnapshot snapshot : chain.reverseDeltas()) {
            remapped.add(new PackedSnapshot(remapData(snapshot.data(), chain.unpackedSize(), op),
                    snapshot.timestamp()));
        }
        chain.replaceAll(remapped);
    }

    /**
     * Remaps the atoms of one packed section.
     *
     * <p>Indirect palettes hold their atoms in the palette table, so the
     * packed longs (palette indices) stay untouched and only the palette
     * entries are translated. Direct palettes store the atoms inline and have
     * to be unpacked, translated and repacked.
     */
    public static PackedData remapData(PackedData data, int unpackedSize, IntUnaryOperator op) {
        if (data instanceof PackedData.SingleValue single) {
            return new PackedData.SingleValue(AtomRemapper.translate(single.value(), op));
        }
        PackedData.Paletted paletted = (PackedData.Paletted) data;
        if (paletted.palette().direct()) {
            return AtomRemapper.remapDirect(paletted, unpackedSize, op);
        }
        int[] entries = paletted.palette().entries();
        int[] translated = new int[entries.length];
        for (int i = 0; i < entries.length; i++) {
            translated[i] = AtomRemapper.translate(entries[i], op);
        }
        return new PackedData.Paletted(paletted.packedLongs(),
                new Palette(translated, paletted.palette().bitsPerEntry()));
    }

    /** Remaps the atoms of every section of a segment group. */
    public static void remapSections(DeltaSections sections, IntUnaryOperator op) {
        for (int s = 0; s < sections.sectionCount(); s++) {
            remapChain(sections.section(s), op);
        }
    }
}

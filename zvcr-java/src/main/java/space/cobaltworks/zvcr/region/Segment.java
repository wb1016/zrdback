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
package space.cobaltworks.zvcr.region;

/**
 * One segment (= a Minecraft chunk): {@code sectionCount} block and biome
 * sections ordered by ascending section Y, plus segment info and tile entity
 * history. Mirrors the C++ {@code Segment}.
 */
public final class Segment {

    public final int sectionCount;
    public final DeltaSections blockSections;
    public final DeltaSections biomeSections;
    public final SegmentInfo info;
    public final TileEntityHistory tileEntities;

    public Segment(Dimension dimension) {
        this(dimension.sectionCount());
    }

    public Segment(int sectionCount) {
        this.sectionCount = sectionCount;
        this.blockSections = new DeltaSections(sectionCount, space.cobaltworks.zvcr.Zvcr.SECTION_SIZE_BLOCKS);
        this.biomeSections = new DeltaSections(sectionCount, space.cobaltworks.zvcr.Zvcr.SECTION_SIZE_BIOMES);
        this.info = new SegmentInfo();
        this.tileEntities = new TileEntityHistory();
    }
}

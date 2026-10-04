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

/**
 * Core ZVCR-3D format constants. Mirrors zvcr_lib/src/zvcr/definitions.hpp and
 * segment.hpp from the C++ reference implementation.
 */
public final class Zvcr {

    /** Atom value marking "unchanged" positions inside reverse deltas. */
    public static final int STATE_UNCHANGED = 0xFFFF;

    public static final int REGION_SIDELENGTH_SEGMENTS = 32;
    public static final int SEGMENTS_PER_REGION =
            REGION_SIDELENGTH_SEGMENTS * REGION_SIDELENGTH_SEGMENTS;

    public static final int SEGMENT_SIDELENGTH_BLOCKS = 16;
    public static final int SECTION_SIZE_BLOCKS =
            SEGMENT_SIDELENGTH_BLOCKS * SEGMENT_SIDELENGTH_BLOCKS * SEGMENT_SIDELENGTH_BLOCKS;

    public static final int SEGMENT_SIDELENGTH_BIOMES = 4;
    public static final int SECTION_SIZE_BIOMES =
            SEGMENT_SIDELENGTH_BIOMES * SEGMENT_SIDELENGTH_BIOMES * SEGMENT_SIDELENGTH_BIOMES;

    /** Overworld has 24 sections; nether/end have 16. */
    public static final int MAX_SECTION_COUNT = 24;

    /**
     * An indirect palette may hold up to 257 entries (the reference implementation's
     * "switch to direct" check runs before pushing the next unique atom), encoded at
     * 16 bits per entry. 258+ unique values switch to direct palette mode.
     */
    public static final int MAX_INDIRECT_PALETTE_SIZE = 256;

    private Zvcr() {}
}

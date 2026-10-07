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

import space.cobaltworks.zvcr.Zvcr;

/**
 * Vanilla dimensions supported by the ZVCR-3D format. Mirrors the C++
 * {@code DimensionType}. ZVCR Y levels are unsigned: {@code zvcr_y + min_y = mc_y}.
 */
public enum Dimension {
    OVERWORLD(0, "overworld", true, -64, 384),
    NETHER(1, "nether", false, 0, 256),
    THE_END(2, "end", false, 0, 256);

    /** Encoded dimension type stored in the file header. */
    public final int id;
    public final String directoryName;
    public final boolean hasSkyLight;
    /** Minimum block Y in Minecraft coordinates. */
    public final int minY;
    /** World block height (section count * 16). */
    public final int height;

    Dimension(int id, String directoryName, boolean hasSkyLight, int minY, int height) {
        this.id = id;
        this.directoryName = directoryName;
        this.hasSkyLight = hasSkyLight;
        this.minY = minY;
        this.height = height;
    }

    public int sectionCount() {
        return height / Zvcr.SEGMENT_SIDELENGTH_BLOCKS;
    }

    public int minSectionY() {
        return minY / Zvcr.SEGMENT_SIDELENGTH_BLOCKS;
    }

    public static Dimension byId(int id) {
        if (id < 0 || id >= values().length) {
            throw new IllegalArgumentException("Invalid dimension type id: " + id);
        }
        return values()[id];
    }
}

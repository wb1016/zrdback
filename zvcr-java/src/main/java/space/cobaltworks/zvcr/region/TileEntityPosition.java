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
 * Position of a tile entity within a chunk: local x/z (uint8) and unsigned y
 * (uint16, 0 = bottom of the world). Mirrors the C++ {@code TileEntityPosition}.
 */
public record TileEntityPosition(int x, int z, int y) {

    public TileEntityPosition {
        if (x < 0 || x > 0xFF) throw new IllegalArgumentException("x out of uint8 range: " + x);
        if (z < 0 || z > 0xFF) throw new IllegalArgumentException("z out of uint8 range: " + z);
        if (y < 0 || y > 0xFFFF) throw new IllegalArgumentException("y out of uint16 range: " + y);
    }

    /** Packed as {@code y << 16 | z << 8 | x}. */
    public int packedPosition() {
        return (y << 16) | (z << 8) | x;
    }

    public static TileEntityPosition unpack(int packedPosition) {
        return new TileEntityPosition(
                packedPosition & 0xFF,
                (packedPosition >>> 8) & 0xFF,
                (packedPosition >>> 16) & 0xFFFF);
    }
}

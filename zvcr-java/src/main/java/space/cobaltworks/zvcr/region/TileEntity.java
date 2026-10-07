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

import java.util.Arrays;

/**
 * A tile entity: registry type ID plus its raw NBT buffer (big-endian, modified
 * UTF-8, compound keys sorted alphabetically, {@code id}/{@code keepPacked}/
 * {@code x}/{@code y}/{@code z} tags stripped). Mirrors the C++ {@code TileEntity}.
 *
 * <p>NBT buffers are compared byte-for-byte for equality (the reason compound
 * keys must be canonically sorted before insertion).
 */
public record TileEntity(int type, TileEntityPosition pos, byte[] nbt) {

    public TileEntity {
        if (type < 0 || type > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("type out of uint32 range: " + type);
        }
        nbt = nbt.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TileEntity other)) return false;
        return type == other.type && pos.equals(other.pos) && Arrays.equals(nbt, other.nbt);
    }

    @Override
    public int hashCode() {
        int result = type;
        result = 31 * result + pos.hashCode();
        result = 31 * result + Arrays.hashCode(nbt);
        return result;
    }
}

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
package space.cobaltworks.zrdback.zvcr.region;

/**
 * One entry in a tile-entity list delta. {@link Put} stores the tile entity as
 * it existed at that snapshot's time; {@link Erase} marks that the tile entity
 * did not exist. (Reverse-time naming: in the latest snapshot every present TE
 * is a put; older deltas record erase for TEs that did not exist yet.)
 */
public sealed interface TileEntityDelta {

    record Put(TileEntity tileEntity) implements TileEntityDelta {}

    Erase ERASE = new Erase();

    final class Erase implements TileEntityDelta {
        private Erase() {}

        @Override
        public String toString() {
            return "Erase";
        }
    }
}

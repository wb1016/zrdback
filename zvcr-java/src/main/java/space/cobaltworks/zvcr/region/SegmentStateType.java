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

/** Segment state type encoding: Unknown=0, New=1, Old=2. */
public enum SegmentStateType {
    UNKNOWN(0),
    NEW(1),
    OLD(2);

    public final int id;

    SegmentStateType(int id) {
        this.id = id;
    }

    public static SegmentStateType byId(int id) {
        if (id < 0 || id >= values().length) {
            throw new IllegalArgumentException("Invalid segment state id: " + id);
        }
        return values()[id];
    }
}

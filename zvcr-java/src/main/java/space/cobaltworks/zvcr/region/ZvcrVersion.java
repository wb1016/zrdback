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
 * ZVCR-3D format version numbers. Mirrors the C++ {@code Version} enum in
 * region/version.hpp. Version 7 = 1.0.0.0 (Release 1) is the only version the
 * reference implementation writes; older versions are read-only.
 */
public enum ZvcrVersion {
    V0_0_0_1(1, "0.0.0.1"),
    V0_1_0_0(2, "0.1.0.0"),
    V0_1_1_0(3, "0.1.1.0"),
    V0_1_2_0(4, "0.1.2.0"),
    V0_1_3_0(5, "0.1.3.0"),
    V0_1_4_0(6, "0.1.4.0"),
    V1_0_0_0(7, "1.0.0.0");

    /** Encoded version number stored in the file header. */
    public final int number;
    public final String name;

    ZvcrVersion(int number, String name) {
        this.number = number;
        this.name = name;
    }

    public static final ZvcrVersion LATEST = V1_0_0_0;

    public static ZvcrVersion byNumber(int number) {
        for (ZvcrVersion v : values()) {
            if (v.number == number) return v;
        }
        throw new IllegalArgumentException("Unknown ZVCR version number: " + number);
    }
}

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

import java.util.Optional;

import space.cobaltworks.zrdback.zvcr.Zvcr;

/**
 * A ZVCR region: 32×32 optional segments indexed {@code x * 32 + z}, plus the
 * protocol version that determines the registry ID space. Mirrors the C++
 * {@code Region}.
 */
public final class Region {

    private final Segment[] segments = new Segment[Zvcr.SEGMENTS_PER_REGION];
    public final int protocolVersion;

    public Region(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public Optional<Segment> get(int x, int z) {
        return Optional.ofNullable(segments[segmentIndex(x, z)]);
    }

    public void set(int x, int z, Segment segment) {
        segments[segmentIndex(x, z)] = segment;
    }

    /** Number of present (non-null) segments. */
    public int presentCount() {
        int count = 0;
        for (Segment s : segments) {
            if (s != null) count++;
        }
        return count;
    }

    public static int segmentIndex(int x, int z) {
        if (x < 0 || x >= Zvcr.REGION_SIDELENGTH_SEGMENTS || z < 0 || z >= Zvcr.REGION_SIDELENGTH_SEGMENTS) {
            throw new IndexOutOfBoundsException("Segment coords out of bounds: " + x + ", " + z);
        }
        return x * Zvcr.REGION_SIDELENGTH_SEGMENTS + z;
    }
}

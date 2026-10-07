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

import java.nio.file.Path;
import java.util.Optional;

/**
 * Standard ZVCR directory layout and file naming:
 * {@code parent/{dimension}/{sectorX}/{sectorZ}/r.{regionX}.{regionZ}.zvcr3d}
 * where {@code sector = floorDiv(region, 32)}. Mirrors the C++
 * {@code RegionLocation}.
 */
public record RegionLocation(int rx, int rz, Dimension dimension) {

    public static final String EXTENSION = "zvcr3d";
    public static final String PREFIX = "r.";

    /** {@code floor(region / 32)} — plain {@code /} truncates toward zero for negatives. */
    public static int floorDiv32(int regionCoordinate) {
        if (regionCoordinate >= 0) {
            return regionCoordinate / 32;
        }
        return (regionCoordinate - 31) / 32;
    }

    public Path directory(Path parentDirectory) {
        return parentDirectory.resolve(dimension.directoryName)
                .resolve(Integer.toString(floorDiv32(rx)))
                .resolve(Integer.toString(floorDiv32(rz)));
    }

    public String fileNameExtensionless() {
        return PREFIX + rx + "." + rz;
    }

    public String fileName() {
        return fileNameExtensionless() + "." + EXTENSION;
    }

    public Path filePath(Path parentDirectory) {
        return directory(parentDirectory).resolve(fileName());
    }

    /**
     * Parses {@code r.{rx}.{rz}.zvcr3d} (extension optional for the
     * extensionless form used by callers that already validated it).
     */
    public static Optional<RegionLocation> fromFileName(Dimension dimension, String filename) {
        if (!filename.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String body = filename.substring(PREFIX.length());
        if (body.endsWith("." + EXTENSION)) {
            body = body.substring(0, body.length() - EXTENSION.length() - 1);
        }
        int dot = body.indexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        try {
            int rx = Integer.parseInt(body.substring(0, dot));
            int rz = Integer.parseInt(body.substring(dot + 1));
            return Optional.of(new RegionLocation(rx, rz, dimension));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Packs region coords (21-bit two's complement each) + dimension into an ID. */
    public long toRegionId() {
        long ux = rx & 0x1FFFFFL;
        long uz = rz & 0x1FFFFFL;
        long ud = dimension.id & 0xFFFFFL;
        return (ud << 42) | (ux << 21) | uz;
    }

    public static RegionLocation fromRegionId(long regionId) {
        long ux = (regionId >>> 21) & 0x1FFFFFL;
        long uz = regionId & 0x1FFFFFL;
        long ud = (regionId >>> 42) & 0xFFFFFL;
        int rx = ux >= 0x100000 ? (int) (ux - 0x200000) : (int) ux;
        int rz = uz >= 0x100000 ? (int) (uz - 0x200000) : (int) uz;
        return new RegionLocation(rx, rz, Dimension.byId((int) ud));
    }
}

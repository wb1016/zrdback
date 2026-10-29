/*
 * ZRDBack: Zstd Reverse Delta Backup
 * Copyright (C) 2026 CobaltWorks
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
 * by crayne are licensed under the GNU Lesser General Public License v3.
 */
package space.cobaltworks.zrdback.backup;

import java.util.Locale;
import java.util.Set;

import space.cobaltworks.zvcr.region.Dimension;

/**
 * What a restore should produce.
 *
 * <p>A full restore reproduces the world as it was: chunks, tile entities and
 * the auxiliary files (level.dat, player data, ...). Partial restores exist for
 * the common recovery cases. a single dimension, or just the player data of
 * one player. where the goal is to extract something from the backup rather
 * than to rebuild a bootable world.
 */
public record RestoreOptions(Set<Dimension> dimensions, boolean chunks, boolean files,
                              String playerDataFilter) {

    /** A full restore of every dimension, chunks and auxiliary files. */
    public static RestoreOptions all() {
        return new RestoreOptions(Set.of(Dimension.values()), true, true, null);
    }

    /** Only the given dimensions' chunks. */
    public static RestoreOptions chunksOnly(Set<Dimension> dimensions) {
        return new RestoreOptions(dimensions, true, false, null);
    }

    /** Only the auxiliary files (level.dat, player data, ...). */
    public static RestoreOptions filesOnly() {
        return new RestoreOptions(Set.of(), false, true, null);
    }

    /** Only the auxiliary files whose path contains {@code filter}. */
    public static RestoreOptions playerDataOnly(String player) {
        return new RestoreOptions(Set.of(), false, true, player);
    }

    /**
     * Parses a dimension name ({@code overworld}, {@code nether}, {@code end}).
     *
     * @throws IllegalArgumentException when the name is not a known dimension
     */
    public static Dimension parseDimension(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (Dimension dimension : Dimension.values()) {
            if (dimension.directoryName.equals(normalized)) {
                return dimension;
            }
        }
        throw new IllegalArgumentException("Unknown dimension '" + name
                + "' (expected overworld, nether or end)");
    }
}

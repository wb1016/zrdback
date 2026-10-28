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
 * by crayne are licensed under the GNU Lesser General Public License v3.
 */
package space.cobaltworks.zrdback.store;

/** Constants shared by the mod and the CLI. */
public final class StoreFormat {

    /**
     * ZVCR protocol version written into every backup file.
     *
     * <p>Matches the MC 26.3 protocol version, the same value the C++
     * reference implementation writes. It identifies the <em>format</em>, not
     * the registry ID space — see {@link RegistryManifest} for that.
     */
    public static final int PROTOCOL_VERSION = 777;

    private StoreFormat() {}
}

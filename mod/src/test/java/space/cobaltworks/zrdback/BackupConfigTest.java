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
package space.cobaltworks.zrdback;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The store root must be scoped per save on integrated servers: the world
 * save's parent is the shared {@code saves/} directory, so an unscoped store
 * would mix every singleplayer world's backups (and prune/retention/restore
 * would hit other saves' data).
 */
class BackupConfigTest {

    @Test
    void dedicatedServerUsesStoreRootAsIs() {
        Path base = Path.of("/server/zrdback-backups");
        Path worldRoot = Path.of("/server/world");
        assertEquals(base, BackupConfig.resolveStoreRoot(base, worldRoot, true));
    }

    @Test
    void singleplayerDefaultStoreIsScopedPerWorld() {
        Path base = Path.of("/instance/saves/zrdback-backups");
        Path worldRoot = Path.of("/instance/saves/My World");
        assertEquals(base.resolve("My World"),
                BackupConfig.resolveStoreRoot(base, worldRoot, false));
    }

    @Test
    void singleplayerExplicitOutputIsScopedToo() {
        Path base = Path.of("/mnt/backups");
        Path worldRoot = Path.of("/instance/saves/My World");
        assertEquals(Path.of("/mnt/backups/My World"),
                BackupConfig.resolveStoreRoot(base, worldRoot, false));
    }
}

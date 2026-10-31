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
package space.cobaltworks.zrdback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * The settings can be changed from inside the game; both the in-memory
     * values and the config file must follow.
     */
    @Test
    void aMissingFileYieldsTheDefaults(@TempDir Path tmp) throws Exception {
        BackupConfig config = load(tmp, tmp.resolve("zrdback.properties"));

        assertEquals(60, config.intervalMinutes());
        assertEquals(32, config.checkpointInterval());
        assertEquals(0, config.retentionDays());
        assertEquals(0, config.retentionSnapshots());
        assertEquals(0, config.threads());
        assertTrue(config.backupOnStop());
        assertEquals(4, config.compressionLevel());
        assertEquals(0, config.dimensions().size());
    }

    @Test
    void settingAValueChangesItAndPersistsIt(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("zrdback.properties");
        BackupConfig config = load(tmp, file);

        config.set(BackupConfig.KEY_INTERVAL_MINUTES, "15");

        assertEquals(15, config.intervalMinutes());
        assertTrue(Files.readString(file).contains("interval-minutes=15"),
                "the file must hold the new value");
    }

    @Test
    void anInvalidValueIsRejectedAndChangesNothing(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("zrdback.properties");
        BackupConfig config = load(tmp, file);

        assertThrows(IllegalArgumentException.class,
                () -> config.set(BackupConfig.KEY_INTERVAL_MINUTES, "0"));
        assertThrows(IllegalArgumentException.class,
                () -> config.set(BackupConfig.KEY_INTERVAL_MINUTES, "soon"));
        assertThrows(IllegalArgumentException.class,
                () -> config.set(BackupConfig.KEY_COMPRESSION_LEVEL, "20"));
        assertThrows(IllegalArgumentException.class,
                () -> config.set("nonsense", "1"));

        assertEquals(60, config.intervalMinutes(), "the value must not change");
        assertTrue(!Files.exists(file) || !Files.readString(file).contains("interval-minutes=0"));
    }

    @Test
    void resettingRestoresTheDefault(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("zrdback.properties");
        BackupConfig config = load(tmp, file);

        config.set(BackupConfig.KEY_INTERVAL_MINUTES, "15");
        config.reset(BackupConfig.KEY_INTERVAL_MINUTES);

        assertEquals(60, config.intervalMinutes());
        assertTrue(Files.readString(file).contains("interval-minutes=60"));
    }

    @Test
    void reloadingPicksUpAnExternalEdit(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("zrdback.properties");
        BackupConfig config = load(tmp, file);

        Files.writeString(file, "interval-minutes=42\ncompression-level=9\n");
        config.reload();

        assertEquals(42, config.intervalMinutes());
        assertEquals(9, config.compressionLevel());
    }

    @Test
    void aNewOutputDirectoryAppliesAtTheNextStart(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("zrdback.properties");
        Path world = tmp.resolve("world");
        Files.createDirectories(world);
        BackupConfig config = load(tmp, file);
        Path before = config.outputDirectory();

        config.set(BackupConfig.KEY_OUTPUT_DIRECTORY, tmp.resolve("elsewhere").toString());

        assertEquals(before, config.outputDirectory(),
                "the running store must not move while the server is up");
        assertTrue(Files.readString(file).contains("output-directory="),
                "the new value is written for the next start");
        assertEquals(tmp.resolve("elsewhere").toString(),
                config.value(BackupConfig.KEY_OUTPUT_DIRECTORY));

        // A fresh load (the next server start) picks it up.
        BackupConfig restarted = load(tmp, file);
        assertEquals(tmp.resolve("elsewhere").toAbsolutePath().normalize(),
                restarted.outputDirectory());
    }

    @Test
    void theDimensionsKeepTheirOrderInTheFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("zrdback.properties");
        BackupConfig config = load(tmp, file);

        config.set(BackupConfig.KEY_DIMENSIONS, "nether,overworld");

        assertEquals("nether,overworld", config.value(BackupConfig.KEY_DIMENSIONS));
        assertTrue(Files.readString(file).contains("dimensions=nether,overworld"));
    }

    @Test
    void anUnknownDimensionIsRejected(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("zrdback.properties");
        BackupConfig config = load(tmp, file);

        assertThrows(IllegalArgumentException.class,
                () -> config.set(BackupConfig.KEY_DIMENSIONS, "overworld,moon"));
    }

    private static BackupConfig load(Path tmp, Path file) {
        return BackupConfig.load(file, tmp.resolve("world"), true);
    }
}

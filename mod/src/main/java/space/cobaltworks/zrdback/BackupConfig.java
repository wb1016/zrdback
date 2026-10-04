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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Minimal config surface (v1): {@code config/zrdback.properties}.
 *
 * <ul>
 *   <li>{@code output-directory} — backup storage root; empty = sibling
 *       {@code zrdback-backups} directory next to the world save (the world save
 *       folder itself is never written to).</li>
 *   <li>{@code interval-minutes} — hourly cadence by default.</li>
 *   <li>{@code checkpoint-interval} — full snapshot every N deltas per chain.</li>
 * </ul>
 */
public final class BackupConfig {

    public static final int PROTOCOL_VERSION = 777; // MC 26.3

    private final Path outputDirectory;
    private final int intervalMinutes;
    private final int checkpointInterval;
    private final int retentionDays;

    private BackupConfig(Path outputDirectory, int intervalMinutes, int checkpointInterval,
                         int retentionDays) {
        this.outputDirectory = outputDirectory;
        this.intervalMinutes = intervalMinutes;
        this.checkpointInterval = checkpointInterval;
        this.retentionDays = retentionDays;
    }

    public static BackupConfig load(Path worldRoot) {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path configFile = configDir.resolve("zrdback.properties");
        Properties props = new Properties();
        if (Files.isRegularFile(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                props.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + configFile, e);
            }
        }
        String output = props.getProperty("output-directory", "").trim();
        // getWorldPath(LevelResource.ROOT) ends in "/." (ROOT = ".") and may be
        // relative; normalize so the backups land NEXT TO the world save, never
        // inside it.
        Path normalizedWorldRoot = worldRoot.toAbsolutePath().normalize();
        Path outputDirectory = output.isEmpty()
                ? normalizedWorldRoot.getParent().resolve("zrdback-backups")
                : Path.of(output).toAbsolutePath().normalize();
        int interval = parsePositive(props, "interval-minutes", 60);
        int checkpoint = parsePositive(props, "checkpoint-interval", 32);
        // 0 disables automatic pruning (manual /backup prune <days> still works).
        int retention = Math.max(0,
                Integer.parseInt(props.getProperty("retention-days", "0").trim()));
        return new BackupConfig(outputDirectory, interval, checkpoint, retention);
    }

    private static int parsePositive(Properties props, String key, int fallback) {
        String raw = props.getProperty(key, "").trim();
        if (raw.isEmpty()) return fallback;
        int value = Integer.parseInt(raw);
        if (value <= 0) throw new IllegalStateException(key + " must be positive: " + value);
        return value;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public int intervalMinutes() {
        return intervalMinutes;
    }

    public int checkpointInterval() {
        return checkpointInterval;
    }

    /** 0 = keep history forever (manual prune only). */
    public int retentionDays() {
        return retentionDays;
    }
}

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
package space.cobaltworks.zrdback;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

import space.cobaltworks.zrdback.store.StoreFormat;

/**
 * Minimal config surface (v1): {@code config/zrdback.properties}.
 *
 * <ul>
 *   <li>{@code output-directory} — backup storage root; empty = sibling
 *       {@code zrdback-backups} directory next to the world save (the world save
 *       folder itself is never written to). On an integrated server
 *       (singleplayer/LAN) the store is scoped per save:
 *       {@code <root>/<world-folder-name>/}, because the world save's parent is
 *       the shared {@code saves/} directory of the whole instance.</li>
 *   <li>{@code interval-minutes} — hourly cadence by default.</li>
 *   <li>{@code checkpoint-interval} — full snapshot every N deltas per chain.</li>
 *   <li>{@code retention-days} — drop history older than N days; 0 = keep
 *       forever. The chain is continuous, so retention only ever removes
 *       the oldest snapshots — it never creates a second chain.</li>
 *   <li>{@code threads} — worker threads for backup/prune; 0 = auto (half the
 *       cores, min 1). Workers run at minimum priority so ticks are served
 *       first. Region files are independent, so they process in parallel.</li>
 *   <li>{@code backup-on-stop} — run a final backup while the server shuts
 *       down, capturing the state that the exit save would otherwise leave
 *       behind.</li>
 *   <li>{@code compression-level} — Zstd level for incremental backups
 *       (1..19, default 4). Prune rewrites always use level 8.</li>
 *   <li>{@code dimensions} — comma-separated dimensions to back up
 *       ({@code overworld,nether,end}); empty = all.</li>
 * </ul>
 */
public final class BackupConfig {

    /** ZVCR protocol version written into every backup file. */
    public static final int PROTOCOL_VERSION = StoreFormat.PROTOCOL_VERSION;

    /** Zstd level used for the frequent incremental rewrite. */
    public static final int COMPRESSION_LEVEL_INCREMENTAL = 4;

    /** Zstd level used for the rarer prune rewrites (smaller files). */
    public static final int COMPRESSION_LEVEL_PRUNE = 8;

    private final Path outputDirectory;
    private final int intervalMinutes;
    private final int checkpointInterval;
    private final int retentionDays;
    private final int threads;
    private final boolean backupOnStop;
    private final int compressionLevel;
    private final java.util.Set<String> dimensions;

    private BackupConfig(Path outputDirectory, int intervalMinutes, int checkpointInterval,
                         int retentionDays, int threads, boolean backupOnStop,
                         int compressionLevel, java.util.Set<String> dimensions) {
        this.outputDirectory = outputDirectory;
        this.intervalMinutes = intervalMinutes;
        this.checkpointInterval = checkpointInterval;
        this.retentionDays = retentionDays;
        this.threads = threads;
        this.backupOnStop = backupOnStop;
        this.compressionLevel = compressionLevel;
        this.dimensions = dimensions;
    }

    public static BackupConfig load(Path worldRoot, boolean dedicatedServer) {
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
        Path base = output.isEmpty()
                ? normalizedWorldRoot.getParent().resolve("zrdback-backups")
                : Path.of(output).toAbsolutePath().normalize();
        Path outputDirectory = resolveStoreRoot(base, normalizedWorldRoot, dedicatedServer);
        if (!outputDirectory.equals(base) && Files.exists(base.resolve("files-index.json"))) {
            ZrdBack.LOGGER.warn(
                    "Found a legacy shared backup store at {} that mixed every singleplayer "
                    + "save's backups into one directory; it is no longer used. Each save now "
                    + "backs up into its own subdirectory ({}). Move or delete the old store "
                    + "manually.", base, outputDirectory);
        }
        int interval = parsePositive(props, "interval-minutes", 60);
        int checkpoint = parsePositive(props, "checkpoint-interval", 32);
        // 0 disables automatic pruning (manual /backup prune <days> still works).
        int retention = Math.max(0,
                Integer.parseInt(props.getProperty("retention-days", "0").trim()));
        int threads = Integer.parseInt(props.getProperty("threads", "0").trim());
        if (threads < 0) {
            throw new IllegalStateException("threads must be >= 0: " + threads);
        }
        boolean backupOnStop = parseBoolean(props, "backup-on-stop", true);
        int compression = parseCompressionLevel(props, "compression-level",
                COMPRESSION_LEVEL_INCREMENTAL);
        java.util.Set<String> dimensions = parseDimensions(props, "dimensions");
        return new BackupConfig(outputDirectory, interval, checkpoint, retention, threads,
                backupOnStop, compression, dimensions);
    }

    /**
     * Resolves the per-save store root from the configured base directory.
     *
     * <p>A dedicated server hosts exactly one world, so the base is used as-is
     * (flat layout, compatible with existing server stores). An integrated
     * server (singleplayer/LAN) shares one {@code saves/} directory across all
     * worlds of the instance — without scoping, every save's backups would mix
     * into one store and prune/retention/restore would hit other saves' data.
     */
    static Path resolveStoreRoot(Path base, Path normalizedWorldRoot, boolean dedicatedServer) {
        if (dedicatedServer) {
            return base;
        }
        return base.resolve(normalizedWorldRoot.getFileName().toString());
    }

    private static int parsePositive(Properties props, String key, int fallback) {
        String raw = props.getProperty(key, "").trim();
        if (raw.isEmpty()) return fallback;
        int value = Integer.parseInt(raw);
        if (value <= 0) throw new IllegalStateException(key + " must be positive: " + value);
        return value;
    }

    private static boolean parseBoolean(Properties props, String key, boolean fallback) {
        String raw = props.getProperty(key, "").trim();
        if (raw.isEmpty()) return fallback;
        if (raw.equalsIgnoreCase("true") || raw.equals("1") || raw.equalsIgnoreCase("yes")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false") || raw.equals("0") || raw.equalsIgnoreCase("no")) {
            return false;
        }
        throw new IllegalStateException(key + " must be true or false: " + raw);
    }

    private static int parseCompressionLevel(Properties props, String key, int fallback) {
        String raw = props.getProperty(key, "").trim();
        if (raw.isEmpty()) return fallback;
        int value = Integer.parseInt(raw);
        if (value < 1 || value > 19) {
            throw new IllegalStateException(key + " must be between 1 and 19: " + value);
        }
        return value;
    }

    private static java.util.Set<String> parseDimensions(Properties props, String key) {
        String raw = props.getProperty(key, "").trim();
        if (raw.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<String> dimensions = new java.util.LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            switch (name) {
                case "overworld", "nether", "end" -> dimensions.add(name);
                default -> throw new IllegalStateException(
                        key + " contains an unknown dimension '" + name
                        + "' (expected overworld, nether or end)");
            }
        }
        return java.util.Set.copyOf(dimensions);
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

    /** Worker threads for backup/prune; 0 = auto (all cores). */
    public int threads() {
        return threads;
    }

    /** Whether to run a final backup during server shutdown. */
    public boolean backupOnStop() {
        return backupOnStop;
    }

    /** Zstd level for incremental backups. */
    public int compressionLevel() {
        return compressionLevel;
    }

    /**
     * The dimensions to back up; empty means all of them.
     *
     * <p>Names match the store directory names ({@code overworld},
     * {@code nether}, {@code end}).
     */
    public java.util.Set<String> dimensions() {
        return dimensions;
    }

    /** True when {@code dimension} (a store directory name) is enabled. */
    public boolean includesDimension(String dimension) {
        return dimensions.isEmpty() || dimensions.contains(dimension);
    }
}

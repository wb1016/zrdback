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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;

import space.cobaltworks.zvcr.io.ZvcrFiles;
import space.cobaltworks.zrdback.store.RetentionPolicy;
import space.cobaltworks.zrdback.store.StoreFormat;
import space.cobaltworks.zrdback.store.StoreLayout;

/**
 * The mod's configuration ({@code config/zrdback.properties}).
 *
 * <p>The file can be edited by hand, but every setting can also be changed
 * from inside the game with {@code /zrdback config set <key> <value>}; both
 * write the same file, and the in-game form validates the value before it is
 * applied.
 *
 * <ul>
 *   <li>{@code output-directory} - backup storage root; empty = sibling
 *       {@code zrdback-backups} directory next to the world save (the world save
 *       folder itself is never written to). On an integrated server
 *       (singleplayer/LAN) the store is scoped per save:
 *       {@code <root>/<world-folder-name>/}, because the world save's parent is
 *       the shared {@code saves/} directory of the whole instance. Changing this
 *       needs a restart: the running service would otherwise keep writing into
 *       the old store while reading the new one.</li>
 *   <li>{@code interval-minutes} - hourly cadence by default.</li>
 *   <li>{@code checkpoint-interval} - full snapshot every N deltas per chain.</li>
 *   <li>{@code retention-days} - drop history older than N days; 0 = keep
 *       forever. The chain is continuous, so retention only ever removes
 *       the oldest snapshots - it never creates a second chain.</li>
 *   <li>{@code retention-snapshots} - keep at least N restore points, no matter
 *       how old they are; 0 = the count does not constrain retention.</li>
 *   <li>{@code threads} - worker threads for backup/prune; 0 = auto (half the
 *       cores, min 1). Workers run at minimum priority so ticks are served
 *       first. Region files are independent, so they process in parallel.
 *       Changing this needs a restart: the worker pool is sized at startup.</li>
 *   <li>{@code backup-on-stop} - run a final backup while the server shuts
 *       down, capturing the state that the exit save would otherwise leave
 *       behind.</li>
 *   <li>{@code compression-level} - Zstd level for incremental backups
 *       (1..19, default 4). Prune rewrites always use level 8.</li>
 *   <li>{@code dimensions} - comma-separated dimensions to back up
 *       ({@code overworld,nether,end}); empty = all.</li>
 * </ul>
 *
 * <p>The settings are read by the backup worker threads, so every field is
 * volatile and a change becomes visible to the next backup without a restart.
 */
public final class BackupConfig {

    /** ZVCR protocol version written into every backup file. */
    public static final int PROTOCOL_VERSION = StoreFormat.PROTOCOL_VERSION;

    /** Zstd level used for the frequent incremental rewrite. */
    public static final int COMPRESSION_LEVEL_INCREMENTAL = 4;

    /** Zstd level used for the rarer prune rewrites (smaller files). */
    public static final int COMPRESSION_LEVEL_PRUNE = 8;

    public static final String KEY_OUTPUT_DIRECTORY = "output-directory";
    public static final String KEY_INTERVAL_MINUTES = "interval-minutes";
    public static final String KEY_CHECKPOINT_INTERVAL = "checkpoint-interval";
    public static final String KEY_RETENTION_DAYS = "retention-days";
    public static final String KEY_RETENTION_SNAPSHOTS = "retention-snapshots";
    public static final String KEY_THREADS = "threads";
    public static final String KEY_BACKUP_ON_STOP = "backup-on-stop";
    public static final String KEY_COMPRESSION_LEVEL = "compression-level";
    public static final String KEY_DIMENSIONS = "dimensions";

    /** Every settable key, in the order they are displayed. */
    public static final List<String> KEYS = List.of(
            KEY_OUTPUT_DIRECTORY,
            KEY_INTERVAL_MINUTES,
            KEY_CHECKPOINT_INTERVAL,
            KEY_RETENTION_DAYS,
            KEY_RETENTION_SNAPSHOTS,
            KEY_THREADS,
            KEY_BACKUP_ON_STOP,
            KEY_COMPRESSION_LEVEL,
            KEY_DIMENSIONS);

    /**
     * Settings whose change only takes effect after a server restart: the
     * store root is resolved once at startup, and the worker pool is sized
     * once at startup.
     */
    public static final Set<String> RESTART_REQUIRED =
            Set.of(KEY_OUTPUT_DIRECTORY, KEY_THREADS);

    private final Path configFile;
    private final Path worldRoot;
    private final boolean dedicatedServer;
    /** Guards the mutation of the settings as a whole. */
    private final Object lock = new Object();

    private volatile String outputDirectorySetting;
    /**
     * A new output directory that was set at runtime. It is written to the
     * config file but not applied to the running store: the store root is
     * resolved once at startup, and switching it mid-flight would leave the
     * old and the new store half-used.
     */
    private volatile String pendingOutputDirectory;
    private volatile Path storeRoot;
    private volatile int intervalMinutes;
    private volatile int checkpointInterval;
    private volatile int retentionDays;
    private volatile int retentionSnapshots;
    private volatile int threads;
    private volatile boolean backupOnStop;
    private volatile int compressionLevel;
    private volatile Set<String> dimensions;

    private BackupConfig(Path configFile, Path worldRoot, boolean dedicatedServer) {
        this.configFile = configFile;
        this.worldRoot = worldRoot;
        this.dedicatedServer = dedicatedServer;
    }

    public static BackupConfig load(Path worldRoot, boolean dedicatedServer) {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("zrdback.properties");
        return load(configFile, worldRoot, dedicatedServer);
    }

    /**
     * Loads the config from an explicit file. The file need not exist: every
     * setting then takes its default value.
     */
    public static BackupConfig load(Path configFile, Path worldRoot, boolean dedicatedServer) {
        BackupConfig config = new BackupConfig(configFile, worldRoot, dedicatedServer);
        config.readFromDisk();
        return config;
    }

    /** The file this config is (or would be) stored in. */
    public Path configFile() {
        return configFile;
    }

    private void readFromDisk() {
        Properties props = new Properties();
        if (Files.isRegularFile(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                props.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + configFile, e);
            }
        }
        synchronized (lock) {
            outputDirectorySetting = props.getProperty(KEY_OUTPUT_DIRECTORY, "").trim();
            pendingOutputDirectory = null;
            storeRoot = resolveStoreRoot(baseDirectory(outputDirectorySetting),
                    normalizedWorldRoot(), dedicatedServer);
            warnAboutLegacyStore();
            intervalMinutes = parsePositive(KEY_INTERVAL_MINUTES,
                    props.getProperty(KEY_INTERVAL_MINUTES), 60);
            checkpointInterval = parsePositive(KEY_CHECKPOINT_INTERVAL,
                    props.getProperty(KEY_CHECKPOINT_INTERVAL), 32);
            // 0 disables automatic pruning (manual /zrdback prune still works).
            retentionDays = parseNonNegative(KEY_RETENTION_DAYS,
                    props.getProperty(KEY_RETENTION_DAYS), 0);
            retentionSnapshots = parseNonNegative(KEY_RETENTION_SNAPSHOTS,
                    props.getProperty(KEY_RETENTION_SNAPSHOTS), 0);
            threads = parseNonNegative(KEY_THREADS, props.getProperty(KEY_THREADS), 0);
            backupOnStop = parseBoolean(KEY_BACKUP_ON_STOP,
                    props.getProperty(KEY_BACKUP_ON_STOP), true);
            compressionLevel = parseCompressionLevel(KEY_COMPRESSION_LEVEL,
                    props.getProperty(KEY_COMPRESSION_LEVEL), COMPRESSION_LEVEL_INCREMENTAL);
            dimensions = parseDimensions(props.getProperty(KEY_DIMENSIONS));
        }
    }

    /**
     * Changes one setting and writes the config file.
     *
     * @param key one of {@link #KEYS}
     * @param value the new value
     * @return the canonical form of the value that was applied
     * @throws IllegalArgumentException when the key is unknown or the value is
     *     invalid; nothing is changed in that case
     * @throws IOException when the config file cannot be written
     */
    public String set(String key, String value) throws IOException {
        synchronized (lock) {
            String canonical = canonicalize(key, value);
            String previous = value(key);
            apply(key, canonical);
            try {
                writeToDisk();
            } catch (IOException e) {
                apply(key, previous); // the file still holds the old value
                throw e;
            }
            return canonical;
        }
    }

    /**
     * Restores one setting to its default value and writes the config file.
     *
     * @return the canonical form of the value that was applied
     */
    public String reset(String key) throws IOException {
        synchronized (lock) {
            String canonical = canonicalize(key, defaultValue(key));
            String previous = value(key);
            apply(key, canonical);
            try {
                writeToDisk();
            } catch (IOException e) {
                apply(key, previous); // the file still holds the old value
                throw e;
            }
            return canonical;
        }
    }

    /**
     * Re-reads the config file, picking up edits made outside the game.
     *
     * @throws IOException when the file cannot be read
     */
    public void reload() throws IOException {
        readFromDisk();
    }

    /** The current value of one setting, as it is written to the file. */
    public String value(String key) {
        synchronized (lock) {
            return switch (key) {
                case KEY_OUTPUT_DIRECTORY -> pendingOutputDirectory != null
                        ? pendingOutputDirectory : outputDirectorySetting;
                case KEY_INTERVAL_MINUTES -> Integer.toString(intervalMinutes);
                case KEY_CHECKPOINT_INTERVAL -> Integer.toString(checkpointInterval);
                case KEY_RETENTION_DAYS -> Integer.toString(retentionDays);
                case KEY_RETENTION_SNAPSHOTS -> Integer.toString(retentionSnapshots);
                case KEY_THREADS -> Integer.toString(threads);
                case KEY_BACKUP_ON_STOP -> Boolean.toString(backupOnStop);
                case KEY_COMPRESSION_LEVEL -> Integer.toString(compressionLevel);
                case KEY_DIMENSIONS -> String.join(",", dimensions);
                default -> throw new IllegalArgumentException("unknown setting: " + key);
            };
        }
    }

    /**
     * A human-readable description of one setting's current value, with the
     * "unset" values spelled out.
     */
    public String describe(String key) {
        synchronized (lock) {
            return switch (key) {
                case KEY_OUTPUT_DIRECTORY -> {
                    String configured = value(KEY_OUTPUT_DIRECTORY);
                    if (configured.isEmpty()) {
                        yield "(default) next to the world save";
                    }
                    yield configured;
                }
                case KEY_THREADS -> threads == 0
                        ? "0 (auto)" : Integer.toString(threads);
                case KEY_DIMENSIONS -> dimensions.isEmpty()
                        ? "(all)" : String.join(",", dimensions);
                default -> value(key);
            };
        }
    }

    /** The default value of one setting, as a file value. */
    public static String defaultValue(String key) {        return switch (key) {
            case KEY_OUTPUT_DIRECTORY -> "";
            case KEY_INTERVAL_MINUTES -> "60";
            case KEY_CHECKPOINT_INTERVAL -> "32";
            case KEY_RETENTION_DAYS -> "0";
            case KEY_RETENTION_SNAPSHOTS -> "0";
            case KEY_THREADS -> "0";
            case KEY_BACKUP_ON_STOP -> "true";
            case KEY_COMPRESSION_LEVEL -> Integer.toString(COMPRESSION_LEVEL_INCREMENTAL);
            case KEY_DIMENSIONS -> "";
            default -> throw new IllegalArgumentException("unknown setting: " + key);
        };
    }

    /**
     * Validates a value and returns its canonical form, without applying it.
     *
     * @throws IllegalArgumentException when the key or the value is invalid
     */
    private String canonicalize(String key, String value) {
        String raw = value == null ? "" : value.trim();
        return switch (key) {
            case KEY_OUTPUT_DIRECTORY -> raw;
            case KEY_INTERVAL_MINUTES -> Integer.toString(parsePositive(key, raw, 60));
            case KEY_CHECKPOINT_INTERVAL -> Integer.toString(parsePositive(key, raw, 32));
            case KEY_RETENTION_DAYS -> Integer.toString(parseNonNegative(key, raw, 0));
            case KEY_RETENTION_SNAPSHOTS -> Integer.toString(parseNonNegative(key, raw, 0));
            case KEY_THREADS -> Integer.toString(parseNonNegative(key, raw, 0));
            case KEY_BACKUP_ON_STOP -> Boolean.toString(parseBoolean(key, raw, true));
            case KEY_COMPRESSION_LEVEL -> Integer.toString(parseCompressionLevel(key, raw,
                    COMPRESSION_LEVEL_INCREMENTAL));
            case KEY_DIMENSIONS -> String.join(",", parseDimensions(raw));
            default -> throw new IllegalArgumentException("unknown setting: " + key
                    + " (known: " + String.join(", ", KEYS) + ")");
        };
    }

    /** Applies an already validated, canonical value. */
    private void apply(String key, String canonical) {
        switch (key) {
            case KEY_OUTPUT_DIRECTORY ->
                    // Not applied to the live store: the store root stays where
                    // it is until the next restart.
                    pendingOutputDirectory = canonical;
            case KEY_INTERVAL_MINUTES -> intervalMinutes = Integer.parseInt(canonical);
            case KEY_CHECKPOINT_INTERVAL -> checkpointInterval = Integer.parseInt(canonical);
            case KEY_RETENTION_DAYS -> retentionDays = Integer.parseInt(canonical);
            case KEY_RETENTION_SNAPSHOTS -> retentionSnapshots = Integer.parseInt(canonical);
            case KEY_THREADS -> threads = Integer.parseInt(canonical);
            case KEY_BACKUP_ON_STOP -> backupOnStop = Boolean.parseBoolean(canonical);
            case KEY_COMPRESSION_LEVEL -> compressionLevel = Integer.parseInt(canonical);
            case KEY_DIMENSIONS -> dimensions = parseDimensions(canonical);
            default -> throw new IllegalArgumentException("unknown setting: " + key);
        }
    }

    /** Writes the current settings to the config file. */
    private void writeToDisk() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ZRDBack configuration.\n");
        sb.append("# Edit by hand or with /zrdback config set; both write this file.\n");
        sb.append("# A value of 0 disables the corresponding limit.\n");
        for (String key : KEYS) {
            sb.append(key).append('=').append(value(key)).append('\n');
        }
        ZvcrFiles.atomicWrite(configFile, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Path normalizedWorldRoot() {
        // getWorldPath(LevelResource.ROOT) ends in "/." (ROOT = ".") and may be
        // relative; normalize so the backups land NEXT TO the world save, never
        // inside it.
        return worldRoot.toAbsolutePath().normalize();
    }

    private Path baseDirectory(String output) {
        return output.isEmpty()
                ? normalizedWorldRoot().getParent().resolve("zrdback-backups")
                : Path.of(output).toAbsolutePath().normalize();
    }

    private void warnAboutLegacyStore() {
        Path base = baseDirectory(outputDirectorySetting);
        if (!storeRoot.equals(base) && Files.exists(base.resolve(StoreLayout.INDEX_FILE))) {
            ZrdBack.LOGGER.warn(
                    "Found a legacy shared backup store at {} that mixed every singleplayer "
                    + "save's backups into one directory; it is no longer used. Each save now "
                    + "backs up into its own subdirectory ({}). Move or delete the old store "
                    + "manually.", base, storeRoot);
        }
    }

    /**
     * Resolves the per-save store root from the configured base directory.
     *
     * <p>A dedicated server hosts exactly one world, so the base is used as-is
     * (flat layout, compatible with existing server stores). An integrated
     * server (singleplayer/LAN) shares one {@code saves/} directory across all
     * worlds of the instance - without scoping, every save's backups would mix
     * into one store and prune/retention/restore would hit other saves' data.
     */
    static Path resolveStoreRoot(Path base, Path normalizedWorldRoot, boolean dedicatedServer) {
        if (dedicatedServer) {
            return base;
        }
        return base.resolve(normalizedWorldRoot.getFileName().toString());
    }

    private static int parsePositive(String key, String raw, int fallback) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return fallback;
        int parsed = parseInt(key, value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(key + " must be a positive number: " + parsed);
        }
        return parsed;
    }

    private static int parseNonNegative(String key, String raw, int fallback) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return fallback;
        int parsed = parseInt(key, value);
        if (parsed < 0) {
            throw new IllegalArgumentException(key + " must not be negative: " + parsed);
        }
        return parsed;
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, got '" + value + "'");
        }
    }

    private static boolean parseBoolean(String key, String raw, boolean fallback) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return fallback;
        if (value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes")) {
            return true;
        }
        if (value.equalsIgnoreCase("false") || value.equals("0") || value.equalsIgnoreCase("no")) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false, got '" + value + "'");
    }

    private static int parseCompressionLevel(String key, String raw, int fallback) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return fallback;
        int parsed = parseInt(key, value);
        if (parsed < 1 || parsed > 19) {
            throw new IllegalArgumentException(key + " must be between 1 and 19: " + parsed);
        }
        return parsed;
    }

    private static Set<String> parseDimensions(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            switch (name) {
                case "overworld", "nether", "end" -> parsed.add(name);
                default -> throw new IllegalArgumentException(
                        KEY_DIMENSIONS + " contains an unknown dimension '" + name
                        + "' (expected overworld, nether or end)");
            }
        }
        // Keep the configured order: the file is rewritten from this value, and
        // Set.copyOf would shuffle it on every write.
        return java.util.Collections.unmodifiableSet(parsed);
    }

    public Path outputDirectory() {
        return storeRoot;
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

    /** 0 = the number of restore points does not constrain retention. */
    public int retentionSnapshots() {
        return retentionSnapshots;
    }

    /** The configured retention as a policy. */
    public RetentionPolicy retentionPolicy() {
        return new RetentionPolicy(retentionDays, retentionSnapshots);
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

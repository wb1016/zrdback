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
package space.cobaltworks.zrdback.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import space.cobaltworks.zrdback.zvcr.Zvcr;
import space.cobaltworks.zrdback.zvcr.format.DeltaInsertionResult;
import space.cobaltworks.zrdback.zvcr.format.PackedData;
import space.cobaltworks.zrdback.zvcr.format.PackedDeltaData;
import space.cobaltworks.zrdback.zvcr.format.PackedSnapshot;
import space.cobaltworks.zrdback.zvcr.io.ZvcrFiles;
import space.cobaltworks.zrdback.zvcr.io.ZvcrReader;
import space.cobaltworks.zrdback.zvcr.io.ZvcrWriter;
import space.cobaltworks.zrdback.zvcr.region.Dimension;
import space.cobaltworks.zrdback.zvcr.region.RegionLocation;
import space.cobaltworks.zrdback.zvcr.region.Segment;
import space.cobaltworks.zrdback.zvcr.region.SegmentState;
import space.cobaltworks.zrdback.zvcr.region.TileEntityListDelta;
import space.cobaltworks.zrdback.zvcr.region.ZvcrFile;
import space.cobaltworks.zrdback.BackupConfig;

/**
 * The backup engine: observes the live world save and writes incremental
 * ZVCR-3D backups. The mod never writes to the world folder itself, but each
 * backup first asks vanilla to flush its unsaved state (chunks, level.dat,
 * player data) so the snapshot is current, not the last autosave.
 *
 * <p>Flow per region file: header-only scan (8 KiB) → change gate
 * ({@code offset != 0 && headerTimestamp > chain-head timestamp}) → read only
 * the changed chunks via vanilla {@code RegionFile} → semantic diff against
 * the existing ZVCR chain head → insert. Unchanged chunks cost an in-memory
 * timestamp compare.
 */
public final class BackupService {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(BackupService.class);

    private final BackupConfig config;
    private final MinecraftServer server;

    public BackupService(BackupConfig config, MinecraftServer server) {
        this.config = config;
        this.server = server;
    }

    public Path outputDirectory() {
        return config.outputDirectory();
    }

    public int intervalMinutes() {
        return config.intervalMinutes();
    }

    public int checkpointInterval() {
        return config.checkpointInterval();
    }

    /**
     * Restores the world as of {@code timestamp} into
     * {@code <output>/restore/<timestamp>/} — never into the live world save.
     */
    public WorldRestorer.Result restore(long timestamp) throws IOException {
        return new WorldRestorer(config, server).restore(timestamp);
    }

    /**
     * Aggregate statistics over all backup files — chain lengths, palette
     * dedup effectiveness, blob store size. Used to tune the checkpoint
     * interval (HANDOFF §11.2).
     */
    public Stats stats() throws IOException {
        int files = 0;
        long fileBytes = 0;
        int segments = 0;
        int chains = 0;
        long chainEntries = 0;
        int minChain = Integer.MAX_VALUE;
        int maxChain = 0;
        long teDeltas = 0;
        java.util.Set<space.cobaltworks.zrdback.zvcr.format.Palette> distinctPalettes = new java.util.HashSet<>();
        long paletteRefs = 0;

        for (Dimension dimension : Dimension.values()) {
            Path dimDir = config.outputDirectory().resolve(dimension.directoryName);
            if (!Files.isDirectory(dimDir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dimDir)) {
                for (Path file : walk.filter(f -> f.getFileName().toString().endsWith(".zvcr3d")).toList()) {
                    files++;
                    fileBytes += Files.size(file);
                    ZvcrFile zvcr = ZvcrFiles.readFile(file);
                    for (int i = 0; i < Zvcr.SEGMENTS_PER_REGION; i++) {
                        Segment segment = zvcr.region.get(i / Zvcr.REGION_SIDELENGTH_SEGMENTS,
                                i % Zvcr.REGION_SIDELENGTH_SEGMENTS).orElse(null);
                        if (segment == null) {
                            continue;
                        }
                        segments++;
                        teDeltas += segment.tileEntities.size();
                        for (int s = 0; s < segment.sectionCount; s++) {
                            for (PackedDeltaData chain : new PackedDeltaData[] {
                                    segment.blockSections.section(s),
                                    segment.biomeSections.section(s)}) {
                                int size = chain.reverseDeltas().size();
                                if (size == 0) {
                                    continue;
                                }
                                chains++;
                                chainEntries += size;
                                minChain = Math.min(minChain, size);
                                maxChain = Math.max(maxChain, size);
                                for (var snapshot : chain.reverseDeltas()) {
                                    if (snapshot.data() instanceof PackedData.Paletted paletted) {
                                        paletteRefs++;
                                        distinctPalettes.add(paletted.palette());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (minChain == Integer.MAX_VALUE) {
            minChain = 0;
        }
        FileBlobStore blobs = FileBlobStore.open(config.outputDirectory());
        return new Stats(files, fileBytes, segments, chains, chainEntries, minChain, maxChain,
                teDeltas, distinctPalettes.size(), paletteRefs,
                blobs.trackedFileCount(), blobs.blobCount(), blobs.blobBytes());
    }

    public record Stats(int files, long fileBytes, int segments, int chains, long chainEntries,
                        int minChain, int maxChain, long teDeltas,
                        long distinctPalettes, long paletteRefs,
                        int trackedFiles, int blobCount, long blobBytes) {
        public double avgChain() {
            return chains == 0 ? 0 : (double) chainEntries / chains;
        }

        /** Fraction of paletted snapshots sharing a deduplicated palette. */
        public double paletteDedupRatio() {
            return paletteRefs == 0 ? 1.0 : (double) distinctPalettes / paletteRefs;
        }
    }

    /** One page of {@code /zrdback list} output. */
    public record BackupList(long queriedAt, int totalStore, int totalInRange, int skipped,
                             List<Long> timestamps, long durationMs) {}

    /**
     * Lists restore points: newest first, restricted to backups whose age at
     * query time is within {@code [minDays, maxDays]} days (both inclusive;
     * {@code maxDays} 0 = unbounded), skipping the {@code skipNums} newest and
     * returning at most {@code keepNums}.
     */
    public BackupList listBackups(int minDays, int maxDays, int skipNums, int keepNums)
            throws IOException {
        long start = System.currentTimeMillis();
        BackupList windowed = window(backupTimestamps(), Instant.now().getEpochSecond(),
                minDays, maxDays, skipNums, keepNums);
        return new BackupList(windowed.queriedAt(), windowed.totalStore(), windowed.totalInRange(),
                windowed.skipped(), windowed.timestamps(), System.currentTimeMillis() - start);
    }

    /**
     * Pure windowing behind {@link #listBackups} (unit-tested without a store).
     * A timestamp in the future relative to {@code now} (clock skew) has a
     * negative age and is never listed.
     */
    static BackupList window(Collection<Long> timestamps, long now,
                             int minDays, int maxDays, int skipNums, int keepNums) {
        long minAge = minDays * 86400L;
        long maxAge = maxDays <= 0 ? Long.MAX_VALUE : maxDays * 86400L;
        List<Long> sorted = timestamps.stream().distinct()
                .sorted(Comparator.reverseOrder()).toList();
        List<Long> inRange = new ArrayList<>();
        for (long timestamp : sorted) {
            long age = now - timestamp;
            if (age < minAge) {
                continue; // too young; sorted newest-first, later ones are older
            }
            if (age > maxAge) {
                break; // too old; everything after is older still
            }
            inRange.add(timestamp);
        }
        int skipped = Math.min(Math.max(skipNums, 0), inRange.size());
        int keep = Math.max(keepNums, 0);
        List<Long> page = List.copyOf(
                inRange.subList(skipped, Math.min(skipped + keep, inRange.size())));
        return new BackupList(now, sorted.size(), inRange.size(), skipped, page, 0);
    }

    /**
     * Every distinct restore-point timestamp in the store: chain-entry,
     * segment-state and tile-entity-delta timestamps from all {@code .zvcr3d}
     * files, plus blob-store history timestamps. A run that changed nothing
     * leaves no timestamp anywhere — and nothing to restore — so it is
     * correctly absent. Region files are independent, so they are harvested in
     * parallel on the worker pool (same memory profile as {@link #prune}).
     */
    public TreeSet<Long> backupTimestamps() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Dimension dimension : Dimension.values()) {
            Path dimDir = config.outputDirectory().resolve(dimension.directoryName);
            if (!Files.isDirectory(dimDir)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(dimDir)) {
                files.addAll(stream
                        .filter(f -> f.getFileName().toString().endsWith(".zvcr3d"))
                        .toList());
            }
        }

        TreeSet<Long> stamps = new TreeSet<>();
        try (ExecutorService pool = newWorkerPool()) {
            List<Future<TreeSet<Long>>> futures = new ArrayList<>(files.size());
            for (Path file : files) {
                futures.add(pool.submit(() -> fileTimestamps(file)));
            }
            for (Future<TreeSet<Long>> future : futures) {
                stamps.addAll(await(future, "List"));
            }
        }
        stamps.addAll(FileBlobStore.open(config.outputDirectory()).timestamps());
        return stamps;
    }

    /** Distinct timestamps of one {@code .zvcr3d} file's chains. */
    private static TreeSet<Long> fileTimestamps(Path file) throws IOException {
        ZvcrFile zvcr = ZvcrFiles.readFile(file);
        TreeSet<Long> stamps = new TreeSet<>();
        for (int i = 0; i < Zvcr.SEGMENTS_PER_REGION; i++) {
            Segment segment = zvcr.region.get(i / Zvcr.REGION_SIDELENGTH_SEGMENTS,
                    i % Zvcr.REGION_SIDELENGTH_SEGMENTS).orElse(null);
            if (segment == null) {
                continue;
            }
            for (SegmentState state : segment.info.segmentStates()) {
                stamps.add(state.timestamp());
            }
            for (TileEntityListDelta delta : segment.tileEntities.reverseDeltas()) {
                stamps.add(delta.timestamp());
            }
            for (int s = 0; s < segment.sectionCount; s++) {
                for (PackedDeltaData chain : new PackedDeltaData[] {
                        segment.blockSections.section(s), segment.biomeSections.section(s)}) {
                    for (PackedSnapshot snapshot : chain.reverseDeltas()) {
                        stamps.add(snapshot.timestamp());
                    }
                }
            }
        }
        return stamps;
    }

    public String statusLine() {
        StringBuilder sb = new StringBuilder("ZVCR Backup: output=").append(config.outputDirectory())
                .append(", interval=").append(config.intervalMinutes()).append(" min")
                .append(", checkpoint-interval=").append(config.checkpointInterval());
        try {
            FileBlobStore blobs = FileBlobStore.open(config.outputDirectory());
            sb.append(", tracked-files=").append(blobs.trackedFileCount())
                    .append(", blobs=").append(blobs.blobCount())
                    .append(" (").append(blobs.blobBytes() / 1024).append(" KiB)");
        } catch (IOException e) {
            sb.append(", blob-store unavailable");
        }
        return sb.toString();
    }

    public record Result(int regionsScanned, int chunksChanged, int chunksInserted,
                         int blobsStored, int entriesPruned, int regionsSkipped,
                         long durationMs) {}

    /** Per-region backup outcome: skipped = the region kept changing while
     *  being read (world was being modified) and was left for a later pass. */
    private record RegionOutcome(int changed, int inserted, boolean skipped) {}

    /** Re-submission rounds for regions that were unstable while being read. */
    private static final int MAX_BACKUP_WAVES = 4;

    /** Runs a full incremental backup across all supported dimensions. */
    public Result runBackup() throws IOException {
        long start = System.currentTimeMillis();
        flushWorldSave();
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Files.createDirectories(config.outputDirectory());
        // One timestamp for the whole run: the change gate (chunk header newer
        // than chain head) must be consistent across all region files.
        long backupTimestamp = Instant.now().getEpochSecond();

        record RegionTask(DimensionMapping mapping, Path mca, int rx, int rz) {}
        List<RegionTask> tasks = new ArrayList<>();
        for (DimensionMapping mapping : DimensionMapping.values()) {
            // 26.x layout: world/dimensions/<ns>/<path>/region — resolved via the
            // vanilla API rather than hardcoded directory names.
            Path regionDir = net.minecraft.world.level.dimension.DimensionType
                    .getStorageFolder(mapping.levelKey(), worldRoot).resolve("region");
            if (!Files.isDirectory(regionDir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(regionDir)) {
                for (Path mca : files.filter(f -> f.getFileName().toString().endsWith(".mca")).toList()) {
                    String fileName = mca.getFileName().toString();
                    long[] coords = RegionScanner.parseRegionCoords(fileName);
                    if (coords == null) {
                        continue;
                    }
                    tasks.add(new RegionTask(mapping, mca, (int) coords[0], (int) coords[1]));
                }
            }
        }

        // Region files are independent (own .mca, own .zvcr3d): scan, chunk
        // inflate, extraction, packing, serialization and fsync all parallelize.
        // Regions that kept changing while being read (world being modified —
        // e.g. mass chunk generation) are re-submitted in later waves: the
        // write storm is transient, so a later pass reads them consistently.
        long changed = 0;
        long inserted = 0;
        int regionsSkipped = 0;
        try (ExecutorService pool = newWorkerPool()) {
            List<RegionTask> wave = new ArrayList<>(tasks);
            for (int waveIndex = 0; waveIndex < MAX_BACKUP_WAVES && !wave.isEmpty(); waveIndex++) {
                if (waveIndex > 0) {
                    LOGGER.warn("backup wave {}: re-reading {} region(s) that were being "
                            + "modified during the previous pass", waveIndex + 1, wave.size());
                    try {
                        Thread.sleep(2000L * waveIndex);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("backup interrupted", e);
                    }
                }
                List<Future<RegionOutcome>> futures = new ArrayList<>(wave.size());
                for (RegionTask task : wave) {
                    futures.add(pool.submit(() -> backupRegionFile(task.mapping(), task.mca(),
                            worldRoot, task.rx(), task.rz(), backupTimestamp)));
                }
                List<RegionTask> stillUnstable = new ArrayList<>();
                for (int i = 0; i < futures.size(); i++) {
                    RegionOutcome outcome = await(futures.get(i), "Backup");
                    changed += outcome.changed();
                    inserted += outcome.inserted();
                    if (outcome.skipped()) {
                        stillUnstable.add(wave.get(i));
                    }
                }
                regionsSkipped = stillUnstable.size();
                wave = stillUnstable;
            }
        }

        int blobsStored = backupAuxiliaryFiles(worldRoot, backupTimestamp);

        int pruned = 0;
        if (config.retentionDays() > 0) {
            pruned = prune(config.retentionDays()).chunksChanged();
        }
        return new Result(tasks.size(), (int) changed, (int) inserted, blobsStored, pruned,
                regionsSkipped, System.currentTimeMillis() - start);
    }

    /**
     * Asks vanilla to flush its unsaved state (chunks, level.dat, player data)
     * before the backup reads the world from disk. Without this the backup
     * captures the last autosave — player inventory, position and recent edits
     * live only in memory until vanilla's pause/autosave/exit save, so a
     * restore could roll them back arbitrarily far.
     *
     * <p>The save must run on the server thread; this hops there and waits
     * (bounded, so a stopping server can't hang the backup thread). The mod
     * itself still never writes to the world folder — vanilla does.
     */
    private void flushWorldSave() throws IOException {
        CompletableFuture<Void> flushed = new CompletableFuture<>();
        server.execute(() -> {
            try {
                server.saveEverything(true, true, false);
            } catch (RuntimeException e) {
                LOGGER.warn("Pre-backup save failed; backing up the last saved state", e);
            } finally {
                flushed.complete(null);
            }
        });
        try {
            flushed.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.warn("Pre-backup save did not finish within 60s; backing up the last saved state");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Pre-backup save interrupted", e);
        } catch (ExecutionException e) {
            // the future is only completed normally; unreachable, but get() declares it
            throw new IOException("Pre-backup save failed", e);
        }
    }

    private ExecutorService newWorkerPool() {
        // Auto = half the cores: on SMT CPUs that matches the physical core
        // count (the sweet spot for zlib/zstd work) and leaves logical siblings
        // for the server/render/GC threads.
        int threads = config.threads() > 0
                ? config.threads()
                : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread thread = new Thread(r, "zrdback-worker-" + seq.incrementAndGet());
            thread.setDaemon(true);
            // MIN_PRIORITY maps to nice 19 on Linux: workers only soak up idle
            // CPU instead of competing tick-for-tick with the server thread.
            // No throughput loss while cores are idle; ticks win when busy.
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    /** Waits for a task result, unwrapping failures to the thrown cause. */
    private static <T> T await(Future<T> future, String what) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(what + " interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException rt) throw rt;
            if (cause instanceof Error err) throw err;
            throw new IOException(cause);
        }
    }

    /**
     * Backs up the files ZVCR does not model semantically into the
     * content-addressed blob store: level.dat, playerdata/ (modern) and
     * players/ (legacy), data/** (world gen settings, scoreboard, game rules,
     * ...), and per-dimension entities/, poi/ and data/** (raids, world
     * border, dragon fight, ...).
     *
     * @return number of files with new content
     */
    private int backupAuxiliaryFiles(Path worldRoot, long timestamp) throws IOException {
        FileBlobStore store = FileBlobStore.open(config.outputDirectory());
        int stored = 0;
        stored += storeOrCount(store, worldRoot, "level.dat", timestamp);
        stored += storeOrCount(store, worldRoot, "level.dat_old", timestamp);
        stored += walkAndStore(store, worldRoot, worldRoot.resolve("playerdata"), timestamp);
        stored += walkAndStore(store, worldRoot, worldRoot.resolve("players"), timestamp);
        stored += walkAndStore(store, worldRoot, worldRoot.resolve("data"), timestamp);
        stored += walkAndStore(store, worldRoot, worldRoot.resolve("datapacks"), timestamp);
        for (DimensionMapping mapping : DimensionMapping.values()) {
            Path dimDir = net.minecraft.world.level.dimension.DimensionType
                    .getStorageFolder(mapping.levelKey(), worldRoot);
            stored += walkAndStore(store, worldRoot, dimDir.resolve("entities"), timestamp);
            stored += walkAndStore(store, worldRoot, dimDir.resolve("poi"), timestamp);
            stored += walkAndStore(store, worldRoot, dimDir.resolve("data"), timestamp);
        }
        store.flush();
        return stored;
    }

    /**
     * Reads a file the server may be writing concurrently. Two identical
     * consecutive reads are required before the content is trusted; a file
     * that keeps changing returns null and is skipped for this backup round
     * (the next one picks it up).
     */
    private static byte[] readStableBytes(Path file) {
        try {
            byte[] previous = Files.readAllBytes(file);
            for (int attempt = 0; attempt < 3; attempt++) {
                byte[] current = Files.readAllBytes(file);
                if (java.util.Arrays.equals(previous, current)) {
                    return previous;
                }
                previous = current;
                Thread.sleep(50L * (attempt + 1));
            }
        } catch (NoSuchFileException e) {
            // file vanished mid-walk — skip
            return null;
        } catch (IOException e) {
            LOGGER.warn("could not read {} stably: {} — skipped", file, e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        LOGGER.warn("{} kept changing while being read — skipped this backup round", file);
        return null;
    }

    private static int storeOrCount(FileBlobStore store, Path worldRoot,
                                    String relativePath, long timestamp) throws IOException {
        Path file = worldRoot.resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        byte[] content = readStableBytes(file);
        if (content == null) {
            return 0;
        }
        return store.storeContent(worldRoot, relativePath, content, timestamp) ? 1 : 0;
    }

    private static int walkAndStore(FileBlobStore store, Path worldRoot,
                                    Path directory, long timestamp) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int stored = 0;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = worldRoot.relativize(file).toString().replace('\\', '/');
                byte[] content = readStableBytes(file);
                if (content == null) {
                    continue;
                }
                if (store.storeContent(worldRoot, relative, content, timestamp)) {
                    stored++;
                }
            }
        }
        return stored;
    }

    /** Prunes chain entries and blob history older than {@code retentionDays}. */
    public Result prune(int retentionDays) throws IOException {
        long start = System.currentTimeMillis();
        long cutoff = Instant.now().getEpochSecond() - retentionDays * 86400L;

        List<Path> files = new ArrayList<>();
        for (Dimension dimension : Dimension.values()) {
            Path dimDir = config.outputDirectory().resolve(dimension.directoryName);
            if (!Files.isDirectory(dimDir)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(dimDir)) {
                files.addAll(stream
                        .filter(f -> f.getFileName().toString().endsWith(".zvcr3d"))
                        .toList());
            }
        }

        long pruned = 0;
        try (ExecutorService pool = newWorkerPool()) {
            List<Future<Integer>> futures = new ArrayList<>(files.size());
            for (Path file : files) {
                futures.add(pool.submit(() -> pruneFile(file, cutoff)));
            }
            for (Future<Integer> future : futures) {
                pruned += await(future, "Prune");
            }
        }
        pruned += FileBlobStore.open(config.outputDirectory()).prune(cutoff);
        return new Result(files.size(), (int) pruned, 0, 0, 0, 0,
                System.currentTimeMillis() - start);
    }

    private int pruneFile(Path file, long cutoff) throws IOException {
        ZvcrFile zvcr = ZvcrFiles.readFile(file);
        int removed = 0;
        for (int i = 0; i < Zvcr.SEGMENTS_PER_REGION; i++) {
            Segment segment = zvcr.region.get(i / Zvcr.REGION_SIDELENGTH_SEGMENTS,
                    i % Zvcr.REGION_SIDELENGTH_SEGMENTS).orElse(null);
            if (segment == null) {
                continue;
            }
            for (int s = 0; s < segment.sectionCount; s++) {
                removed += segment.blockSections.section(s).truncateOlderThan(cutoff);
                removed += segment.biomeSections.section(s).truncateOlderThan(cutoff);
            }
            removed += segment.tileEntities.truncateOlderThan(cutoff);
            removed += segment.info.truncateOlderThan(cutoff);
        }
        if (removed > 0) {
            ZvcrFiles.atomicWrite(file, ZvcrWriter.serialize(zvcr));
        }
        return removed;
    }

    /**
     * Backs up one region file. The server writes region files concurrently
     * with this scan, and a rewrite moves the chunk to new sectors while the
     * freed sectors get recycled by other chunks — reading through a header
     * cached before such a rewrite yields ANOTHER chunk's payload (valid NBT,
     * wrong position), which would corrupt the store with swapped chunks.
     *
     * <p>Every chunk save touches the region header (timestamp and/or
     * offset), so the read is wrapped in optimistic concurrency: snapshot the
     * header, buffer the extracted chunks, and only insert them if the header
     * and file length are unchanged after the read window. Otherwise discard
     * and retry; a region that never stabilizes is skipped for this round and
     * picked up by the next backup.
     */
    private RegionOutcome backupRegionFile(DimensionMapping mapping, Path mca, Path worldRoot,
                                           int rx, int rz, long backupTimestamp) throws IOException {
        RegionLocation location = new RegionLocation(rx, rz, mapping.dimension);
        Path backupPath = location.filePath(config.outputDirectory());
        ZvcrFile zvcr;
        if (Files.exists(backupPath)) {
            zvcr = ZvcrFiles.readFile(backupPath);
        } else {
            zvcr = new ZvcrFile(BackupConfig.PROTOCOL_VERSION, mapping.dimension);
        }

        ChunkExtractor extractor = new ChunkExtractor(
                server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME));

        for (int attempt = 1; attempt <= MAX_REGION_READ_ATTEMPTS; attempt++) {
            Map<Long, RegionScanner.ScannedChunk> snapshot = RegionScanner.scan(mca);
            if (snapshot.isEmpty()) {
                return new RegionOutcome(0, 0, false);
            }
            long lengthAtScan = Files.size(mca);

            // Gate: chunk changed iff its header timestamp is newer than the
            // newest chain-head timestamp across its section chains (0 if no
            // segment yet).
            List<RegionScanner.ScannedChunk> changedChunks = new ArrayList<>();
            for (RegionScanner.ScannedChunk chunk : snapshot.values()) {
                long headTs = headTimestamp(zvcr, chunk.localX(), chunk.localZ());
                if (headTs == 0 || chunk.headerTimestamp() > headTs) {
                    changedChunks.add(chunk);
                }
            }
            if (changedChunks.isEmpty()) {
                return new RegionOutcome(0, 0, false);
            }

            // Read phase — results are buffered; nothing reaches the store
            // until the region proves stable below.
            record PendingChunk(int localX, int localZ, ChunkExtractor.ExtractedChunk extracted) {}
            List<PendingChunk> pending = new ArrayList<>();
            try (ChunkReader reader = ChunkReader.open(mca, worldRoot.getFileName().toString(), mapping.levelKey)) {
                for (RegionScanner.ScannedChunk chunk : changedChunks) {
                    CompoundTag chunkNbt = reader.readChunk(chunk.localX(), chunk.localZ());
                    if (chunkNbt == null) {
                        continue; // read anomaly (mid-write) — vanilla logged it
                    }
                    // Recycled-sector guard: the payload must belong to the
                    // position it was read from.
                    int expectedX = rx * 32 + chunk.localX();
                    int expectedZ = rz * 32 + chunk.localZ();
                    if (chunkNbt.getIntOr("xPos", expectedX) != expectedX
                            || chunkNbt.getIntOr("zPos", expectedZ) != expectedZ) {
                        LOGGER.warn("r.{}:{} chunk [{},{}] read while the server was rewriting it "
                                + "(xPos/zPos mismatch) — skipped", rx, rz, chunk.localX(), chunk.localZ());
                        continue;
                    }
                    String status = chunkNbt.contains("Status")
                            ? chunkNbt.getStringOr("Status", "") : "";
                    int namespaceSep = status.indexOf(':');
                    if (namespaceSep >= 0) {
                        status = status.substring(namespaceSep + 1);
                    }
                    if (!status.equals("full")) {
                        if (DEBUG) LOGGER.info("chunk {}.{} skipped: status={}", chunk.localX(), chunk.localZ(), status);
                        continue; // not a fully generated chunk (matches the C++ import)
                    }
                    pending.add(new PendingChunk(chunk.localX(), chunk.localZ(),
                            extractor.extract(chunkNbt, mapping.dimension)));
                }
            }

            // Stability check: any chunk save in the read window touches the
            // header (timestamp and/or offset), so equality here proves the
            // payloads above were read consistently.
            Map<Long, RegionScanner.ScannedChunk> after = RegionScanner.scan(mca);
            long lengthAfter = Files.size(mca);
            if (after.equals(snapshot) && lengthAfter == lengthAtScan) {
                int inserted = 0;
                for (PendingChunk p : pending) {
                    if (insertChunk(zvcr, p.localX(), p.localZ(), p.extracted(), backupTimestamp)) {
                        inserted++;
                    }
                }
                if (inserted > 0 || !Files.exists(backupPath)) {
                    ZvcrFiles.atomicWrite(backupPath, ZvcrWriter.serialize(zvcr));
                }
                return new RegionOutcome(changedChunks.size(), inserted, false);
            }
            if (attempt < MAX_REGION_READ_ATTEMPTS) {
                LOGGER.warn("r.{}:{} changed while being read (attempt {}/{}) — retrying",
                        rx, rz, attempt, MAX_REGION_READ_ATTEMPTS);
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("region read interrupted", e);
                }
            }
        }
        LOGGER.warn("r.{}:{} kept changing while being read — skipped this backup round; "
                + "the next backup will pick it up", rx, rz);
        return new RegionOutcome(0, 0, true);
    }

    private boolean insertChunk(ZvcrFile zvcr, int localX, int localZ,
                                ChunkExtractor.ExtractedChunk extracted, long timestamp) {
        if (DEBUG) {
            LOGGER.info("chunk {}.{}: sections={} biomes={} tes={}", localX, localZ,
                    countNonNull(extracted.blockSections), countNonNull(extracted.biomeSections),
                    extracted.tileEntities.size());
        }
        Segment segment = zvcr.region.get(localX, localZ).orElse(null);
        if (segment == null) {
            segment = new Segment(zvcr.dimensionType);
            zvcr.region.set(localX, localZ, segment);
        }

        boolean anyChange = false;
        for (int s = 0; s < extracted.sectionCount; s++) {
            int[] blocks = extracted.blockSections[s];
            if (blocks != null) {
                anyChange |= insertAndCheckpoint(segment.blockSections.section(s),
                        new PackedSnapshot(PackedData.pack(blocks, true), timestamp));
            }
            int[] biomes = extracted.biomeSections[s];
            if (biomes != null) {
                anyChange |= insertAndCheckpoint(segment.biomeSections.section(s),
                        new PackedSnapshot(PackedData.pack(biomes, true), timestamp));
            }
        }

        DeltaInsertionResult teResult = segment.tileEntities.insertSnapshot(timestamp, extracted.tileEntities);
        anyChange |= teResult instanceof DeltaInsertionResult.Success;

        segment.info.insertSnapshot(new space.cobaltworks.zrdback.zvcr.region.SegmentState(
                space.cobaltworks.zrdback.zvcr.region.SegmentStateType.NEW, timestamp));
        return anyChange;
    }

    private boolean insertAndCheckpoint(PackedDeltaData chain, PackedSnapshot snapshot) {
        DeltaInsertionResult result = chain.insertSnapshot(snapshot);
        if (!(result instanceof DeltaInsertionResult.Success)) {
            return false; // NO_CHANGES_MADE / SNAPSHOT_OLDER_THAN_LATEST: skip silently
        }
        maybeCheckpoint(chain);
        return true;
    }

    /**
     * Checkpoint policy: materialize the oldest state once
     * {@code checkpointInterval} deltas have accumulated since the newest
     * full snapshot. "Full" detection unpacks entries and checks for
     * {@code 0xFFFF} atoms — sound in practice (0xFFFF is not a valid
     * blockstate/biome ID), self-healing, and needs no sidecar state.
     */
    private void maybeCheckpoint(PackedDeltaData chain) {
        List<PackedSnapshot> deltas = chain.reverseDeltas();
        int deltasSinceFull = 0;
        for (int i = 1; i < deltas.size(); i++) { // index 0 (head) is always full
            if (looksFull(chain, deltas.get(i))) {
                break;
            }
            deltasSinceFull++;
        }
        if (deltasSinceFull >= config.checkpointInterval()) {
            chain.insertCheckpoint(deltas.size() - 1);
        }
    }

    private static boolean looksFull(PackedDeltaData chain, PackedSnapshot snapshot) {
        if (snapshot.data() instanceof PackedData.SingleValue) {
            return true; // a single-value entry is always a full snapshot
        }
        int[] atoms = snapshot.data().unpack(chain.unpackedSize());
        for (int atom : atoms) {
            if (atom == Zvcr.STATE_UNCHANGED) {
                return false;
            }
        }
        return true;
    }

    private static int countNonNull(Object[] array) {
        int count = 0;
        for (Object o : array) {
            if (o != null) count++;
        }
        return count;
    }

    private static final boolean DEBUG = false;
    /** Retries for the concurrent-write stability check in backupRegionFile. */
    private static final int MAX_REGION_READ_ATTEMPTS = 6;

    private static long headTimestamp(ZvcrFile zvcr, int localX, int localZ) {
        Segment segment = zvcr.region.get(localX, localZ).orElse(null);
        if (segment == null) {
            return 0;
        }
        long max = 0;
        for (int s = 0; s < segment.sectionCount; s++) {
            var blockHead = segment.blockSections.section(s).latestSnapshot();
            if (blockHead.isPresent() && blockHead.get().timestamp() > max) {
                max = blockHead.get().timestamp();
            }
            var biomeHead = segment.biomeSections.section(s).latestSnapshot();
            if (biomeHead.isPresent() && biomeHead.get().timestamp() > max) {
                max = biomeHead.get().timestamp();
            }
        }
        return max;
    }

    /** Maps a ZVCR dimension to its level key (paths come from DimensionType). */
    private enum DimensionMapping {
        OVERWORLD(Dimension.OVERWORLD, Level.OVERWORLD),
        NETHER(Dimension.NETHER, Level.NETHER),
        THE_END(Dimension.THE_END, Level.END);

        private final Dimension dimension;
        private final ResourceKey<Level> levelKey;

        DimensionMapping(Dimension dimension, ResourceKey<Level> levelKey) {
            this.dimension = dimension;
            this.levelKey = levelKey;
        }

        Dimension dimension() {
            return dimension;
        }

        ResourceKey<Level> levelKey() {
            return levelKey;
        }
    }
}

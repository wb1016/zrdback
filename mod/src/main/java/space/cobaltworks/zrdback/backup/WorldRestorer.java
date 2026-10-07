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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.Level;

import space.cobaltworks.zvcr.Zvcr;
import space.cobaltworks.zvcr.format.PackedData;
import space.cobaltworks.zvcr.format.PackedDeltaData;
import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.TileEntity;
import space.cobaltworks.zvcr.region.TileEntityPosition;
import space.cobaltworks.zvcr.region.ZvcrFile;
import space.cobaltworks.zrdback.BackupConfig;

/**
 * Reconstructs a world snapshot from the backup store: region files from the
 * ZVCR-3D chains (blocks, biomes, tile entities) plus the auxiliary files from
 * the blob store (level.dat, players, entities, poi).
 *
 * <p>Restore always writes to a separate target directory — never into the
 * live world save. Restored chunks carry {@code isLightOn=false} and no
 * heightmaps; vanilla recomputes both on load.
 */
public final class WorldRestorer {

    private final BackupConfig config;
    private final MinecraftServer server;
    private final PalettedContainerFactory containerFactory;
    private final Registry<Biome> biomes;

    public WorldRestorer(BackupConfig config, MinecraftServer server) {
        this.config = config;
        this.server = server;
        this.containerFactory = PalettedContainerFactory.create(server.registryAccess());
        this.biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
    }

    public record Result(int chunksRestored, int chunksSkipped, int filesRestored,
                         Path targetDir, long durationMs) {}

    /** Per-file restore outcome (region files are restored in parallel). */
    private record RegionRestore(int restored, int skipped) {}

    /**
     * Restores the world as of {@code timestamp} into
     * {@code <output>/restore/<timestamp>/}.
     *
     * <p>Fails with the recorded history range when {@code timestamp} predates
     * every stored state: chains clamp to their oldest entry while the blob
     * store returns nothing, so an out-of-range timestamp would otherwise
     * silently produce old terrain without level.dat/playerdata — a world that
     * vanilla re-seeds on first open.
     */
    public Result restore(long timestamp) throws IOException {
        long start = System.currentTimeMillis();
        FileBlobStore blobs = FileBlobStore.open(config.outputDirectory());
        long blobOldest = blobs.oldestTimestamp();
        if (blobOldest != Long.MAX_VALUE && timestamp < blobOldest) {
            throw new IOException("No backup data exists at or before " + timestamp
                    + " (oldest recorded state: " + blobOldest
                    + ", newest: " + blobs.newestTimestamp()
                    + "). Use /zrdback list to see available backups, or /zrdback restore latest.");
        }

        // Fail fast: verify chunk-data coverage BEFORE touching the target
        // directory. The per-file timestamp sidecars make this O(files); a
        // missing sidecar costs one parse (and populates the sidecar).
        long dataOldest = prescanOldestTimestamp();
        if (dataOldest == Long.MAX_VALUE) {
            throw new IOException("The backup store contains no chunk data to restore.");
        }
        if (timestamp < dataOldest) {
            throw new IOException("No backup data exists at or before " + timestamp
                    + " (oldest recorded chunk state: " + dataOldest
                    + "). Use /zrdback list to see available backups, or /zrdback restore latest.");
        }

        Path targetRoot = config.outputDirectory().resolve("restore").resolve(Long.toString(timestamp));
        if (Files.isDirectory(targetRoot)) {
            // A previous restore to the same timestamp may have left files the
            // current store no longer produces (deleted regions, pruned
            // history); remove them so the output is exactly this restore.
            deleteRecursively(targetRoot);
        }
        Files.createDirectories(targetRoot);

        try {
            RegionRestore regions = restoreRegions(timestamp, targetRoot);
            int files = blobs.restore(timestamp, targetRoot);

            if (regions.restored() == 0 && files == 0) {
                throw new IOException("The last backup captured no chunk data — the world was "
                        + "being heavily modified while it ran (e.g. mass chunk generation). "
                        + "Run /zrdback now again once chunk generation has settled, then restore.");
            }
            return new Result(regions.restored(), regions.skipped(), files, targetRoot,
                    System.currentTimeMillis() - start);
        } catch (IOException e) {
            deleteRecursively(targetRoot);
            throw e;
        }
    }

    /**
     * Oldest recorded chunk timestamp across all {@code .zvcr3d} files — the
     * same value the restore walk used to compute post-hoc, now checked before
     * any target-directory work happens.
     */
    private long prescanOldestTimestamp() throws IOException {
        long oldest = Long.MAX_VALUE;
        for (Dimension dimension : Dimension.values()) {
            Path dimDir = config.outputDirectory().resolve(dimension.directoryName);
            if (!Files.isDirectory(dimDir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dimDir)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".zvcr3d")).toList()) {
                    oldest = Math.min(oldest, TimestampSidecar.oldestFor(file));
                }
            }
        }
        return oldest;
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /**
     * Restores all region files in parallel on the worker pool (same shape as
     * backup/prune). All tasks are awaited even when one fails, so cleanup
     * below never races a still-writing task; the first failure is rethrown.
     */
    private RegionRestore restoreRegions(long timestamp, Path targetRoot) throws IOException {
        record Task(Path file, int rx, int rz, Dimension dimension) {}
        List<Task> tasks = new ArrayList<>();
        for (Dimension dimension : Dimension.values()) {
            Path dimDir = config.outputDirectory().resolve(dimension.directoryName);
            if (!Files.isDirectory(dimDir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dimDir)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".zvcr3d")).toList()) {
                    String name = file.getFileName().toString(); // r.{rx}.{rz}.zvcr3d
                    String body = name.substring(2, name.length() - ".zvcr3d".length());
                    int dot = body.indexOf('.');
                    tasks.add(new Task(file, Integer.parseInt(body.substring(0, dot)),
                            Integer.parseInt(body.substring(dot + 1)), dimension));
                }
            }
        }

        int restored = 0;
        int skipped = 0;
        Throwable failure = null;
        try (ExecutorService pool = BackupService.newWorkerPool(config)) {
            List<Future<RegionRestore>> futures = new ArrayList<>(tasks.size());
            for (Task task : tasks) {
                futures.add(pool.submit(() -> restoreRegionFile(task.file(), task.rx(), task.rz(),
                        task.dimension(), timestamp, targetRoot)));
            }
            for (Future<RegionRestore> future : futures) {
                try {
                    RegionRestore result = future.get();
                    restored += result.restored();
                    skipped += result.skipped();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (failure == null) failure = e;
                } catch (ExecutionException e) {
                    if (failure == null) failure = e.getCause();
                }
            }
        }
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException rt) throw rt;
        if (failure instanceof Error err) throw err;
        if (failure != null) throw new IOException(failure);
        return new RegionRestore(restored, skipped);
    }

    private RegionRestore restoreRegionFile(Path zvcrPath, int rx, int rz, Dimension dimension,
                                            long timestamp, Path targetRoot) throws IOException {
        ZvcrFile zvcr = space.cobaltworks.zvcr.io.ZvcrFiles.readFile(zvcrPath);
        if (zvcr.region.presentCount() == 0) {
            return new RegionRestore(0, 0);
        }

        // Mirror the live layout in the target directory.
        ResourceKey<Level> levelKey = levelKeyFor(dimension);
        Path dimDir = DimensionType.getStorageFolder(levelKey, targetRoot);
        Path regionDir = dimDir.resolve("region");
        Files.createDirectories(regionDir);
        Path mca = regionDir.resolve("r." + rx + "." + rz + ".mca");

        int restored = 0;
        int skipped = 0;
        try (RegionFile region = new RegionFile(
                new RegionStorageInfo("zvcr-restore", levelKey, "region"),
                mca, regionDir, RegionFileVersion.DEFAULT, false)) {
            for (int i = 0; i < Zvcr.SEGMENTS_PER_REGION; i++) {
                Segment segment = zvcr.region.get(i / Zvcr.REGION_SIDELENGTH_SEGMENTS,
                        i % Zvcr.REGION_SIDELENGTH_SEGMENTS).orElse(null);
                if (segment == null) {
                    continue;
                }
                long segmentOldest = segmentOldestTimestamp(segment);
                if (segmentOldest > timestamp) {
                    // The segment had no state at or before the requested
                    // timestamp (created later); writing it would inject a
                    // chunk that did not exist at that time.
                    skipped++;
                    continue;
                }
                int localX = i / Zvcr.REGION_SIDELENGTH_SEGMENTS;
                int localZ = i % Zvcr.REGION_SIDELENGTH_SEGMENTS;
                CompoundTag chunk = buildChunkNbt(zvcr, segment, localX, localZ, rx, rz,
                        dimension, timestamp);
                try (var out = region.getChunkDataOutputStream(
                        new ChunkPos(rx * 32 + localX, rz * 32 + localZ))) {
                    NbtIo.write(chunk, out);
                }
                restored++;
            }
        }
        return new RegionRestore(restored, skipped);
    }

    /**
     * Oldest recorded state timestamp of a segment: the tail entry of its
     * block/biome chains and TE history (chains are stored newest-first).
     * MAX_VALUE when the segment has no history.
     */
    private static long segmentOldestTimestamp(Segment segment) {
        long oldest = Long.MAX_VALUE;
        for (int s = 0; s < segment.sectionCount; s++) {
            oldest = Math.min(oldest, chainOldestTimestamp(segment.blockSections.section(s)));
            oldest = Math.min(oldest, chainOldestTimestamp(segment.biomeSections.section(s)));
        }
        var teDeltas = segment.tileEntities.reverseDeltas();
        if (!teDeltas.isEmpty()) {
            oldest = Math.min(oldest, teDeltas.get(teDeltas.size() - 1).timestamp());
        }
        return oldest;
    }

    private static long chainOldestTimestamp(PackedDeltaData chain) {
        var deltas = chain.reverseDeltas();
        return deltas.isEmpty() ? Long.MAX_VALUE : deltas.get(deltas.size() - 1).timestamp();
    }

    private CompoundTag buildChunkNbt(ZvcrFile zvcr, Segment segment, int localX, int localZ,
                                      int rx, int rz, Dimension dimension, long timestamp)
            throws IOException {
        int cx = rx * 32 + localX;
        int cz = rz * 32 + localZ;

        CompoundTag root = new CompoundTag();
        // Without DataVersion vanilla runs datafixers from version 0, which
        // expect the ancient chunk format ("No key Level" errors).
        net.minecraft.nbt.NbtUtils.addCurrentDataVersion(root);
        root.putInt("xPos", cx);
        root.putInt("zPos", cz);
        root.putInt("yPos", dimension.minSectionY());
        root.putString("Status", "minecraft:full");
        root.putLong("LastUpdate", 0);
        root.putLong("InhabitedTime", 0);
        root.putBoolean("isLightOn", false);

        ListTag sections = new ListTag();
        for (int s = 0; s < segment.sectionCount; s++) {
            CompoundTag sectionTag = new CompoundTag();
            sectionTag.putByte("Y", (byte) (s + dimension.minSectionY()));

            int[] blockAtoms = chainState(segment.blockSections.section(s), timestamp,
                    Zvcr.SECTION_SIZE_BLOCKS);
            PalettedContainer<BlockState> blocks = containerFactory.createForBlockStates();
            if (blockAtoms != null) {
                fillBlocks(blocks, blockAtoms);
            }
            sectionTag.store("block_states", containerFactory.blockStatesContainerCodec(), blocks);

            int[] biomeAtoms = chainState(segment.biomeSections.section(s), timestamp,
                    Zvcr.SECTION_SIZE_BIOMES);
            PalettedContainer<Holder<Biome>> biomeContainer = containerFactory.createForBiomes();
            if (biomeAtoms != null) {
                fillBiomes(biomeContainer, biomeAtoms);
            }
            sectionTag.store("biomes", containerFactory.biomeContainerCodec(), biomeContainer);

            sections.add(sectionTag);
        }
        root.put("sections", sections);

        ListTag blockEntities = new ListTag();
        Map<TileEntityPosition, TileEntity> tes =
                segment.tileEntities.snapshotBefore(timestamp).orElse(Map.of());
        for (Map.Entry<TileEntityPosition, TileEntity> entry : tes.entrySet()) {
            blockEntities.add(buildTileEntityTag(entry.getValue(), cx, cz, dimension));
        }
        root.put("block_entities", blockEntities);
        return root;
    }

    /** State at {@code timestamp}; null when the chain has no entries. */
    private static int[] chainState(PackedDeltaData chain, long timestamp, int size) {
        if (chain.reverseDeltas().isEmpty()) {
            return null;
        }
        return chain.snapshotBefore(timestamp).orElse(null);
    }

    private void fillBlocks(PalettedContainer<BlockState> container, int[] atoms) {
        for (int i = 0; i < atoms.length; i++) {
            int atom = atoms[i];
            if (atom == 0) {
                continue; // container starts air-filled
            }
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(atom);
            if (state == null) {
                throw new IllegalStateException("Unknown blockstate ID " + atom
                        + " — protocol version mismatch?");
            }
            container.set(i & 15, (i >> 8) & 15, (i >> 4) & 15, state);
        }
    }

    private void fillBiomes(PalettedContainer<Holder<Biome>> container, int[] atoms) {
        var holderIdMap = biomes.asHolderIdMap();
        for (int i = 0; i < atoms.length; i++) {
            Holder<Biome> holder = holderIdMap.byId(atoms[i]);
            if (holder == null) {
                throw new IllegalStateException("Unknown biome ID " + atoms[i]
                        + " — protocol version mismatch?");
            }
            container.set(i & 3, (i >> 4) & 3, (i >> 2) & 3, holder);
        }
    }

    private CompoundTag buildTileEntityTag(TileEntity te, int cx, int cz, Dimension dimension)
            throws IOException {
        Tag payload = NbtIo.readAnyTag(
                new DataInputStream(new ByteArrayInputStream(te.nbt())), NbtAccounter.unlimitedHeap());
        if (!(payload instanceof CompoundTag payloadCompound)) {
            throw new IllegalStateException("Tile entity NBT payload is not a compound");
        }

        CompoundTag tag = new CompoundTag();
        BlockEntityType type = BuiltInRegistries.BLOCK_ENTITY_TYPE.byId(te.type());
        Identifier typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        tag.putString("id", typeId == null ? "minecraft:unknown" : typeId.toString());
        tag.putBoolean("keepPacked", false);
        tag.putInt("x", cx * 16 + te.pos().x());
        tag.putInt("y", te.pos().y() + dimension.minY);
        tag.putInt("z", cz * 16 + te.pos().z());
        for (String key : payloadCompound.keySet()) {
            Tag value = payloadCompound.get(key);
            if (value != null) {
                tag.put(key, value);
            }
        }
        return tag;
    }

    private static ResourceKey<Level> levelKeyFor(Dimension dimension) {
        return switch (dimension) {
            case OVERWORLD -> Level.OVERWORLD;
            case NETHER -> Level.NETHER;
            case THE_END -> Level.END;
        };
    }
}

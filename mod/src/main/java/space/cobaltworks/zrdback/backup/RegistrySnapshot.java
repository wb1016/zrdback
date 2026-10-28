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
package space.cobaltworks.zrdback.backup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import space.cobaltworks.zrdback.store.RegistryManifest;

/**
 * A snapshot of the live registry ID space, and the machinery to translate
 * between two of them.
 *
 * <p>Chunks store block states and biomes as numeric registry IDs. Those IDs
 * are only meaningful for the exact registry that produced them: a Minecraft
 * update, a mod added or removed, or a datapack change shifts the ID space,
 * and an old backup would then restore the <em>wrong blocks</em> while still
 * looking perfectly valid.
 *
 * <p>The mod therefore records the ID → canonical name mapping of the registry
 * it backed up against ({@link RegistryManifest}) and translates stored IDs
 * through it when the live registry no longer matches.
 */
public final class RegistrySnapshot {

    /** Canonical name of a block state, e.g. {@code minecraft:oak_log[axis=x]}. */
    private final List<String> blockNames;

    /** Canonical names of the biome registry, indexed by ID. */
    private final List<String> biomeNames;

    private final RegistryManifest manifest;

    /** name → ID, for translating a stored name into the current registry. */
    private final Map<String, Integer> blockIdsByName;
    private final Map<String, Integer> biomeIdsByName;

    /**
     * Builds a snapshot from explicit name tables.
     *
     * <p>Public so the remapping logic can be exercised without a running
     * server.
     */
    public RegistrySnapshot(RegistryManifest manifest, List<String> blockNames,
                             List<String> biomeNames) {
        this.manifest = manifest;
        this.blockNames = blockNames;
        this.biomeNames = biomeNames;
        this.blockIdsByName = indexByName(blockNames);
        this.biomeIdsByName = indexByName(biomeNames);
    }

    /** The stored form of this snapshot. */
    public RegistryManifest manifest() {
        return manifest;
    }

    /** The fingerprint of the captured ID space. */
    public String fingerprint() {
        return manifest.fingerprint();
    }

    /** Canonical name of the block state with the given ID. */
    public String blockName(int id) {
        return id >= 0 && id < blockNames.size() ? blockNames.get(id) : null;
    }

    /** Canonical name of the biome with the given ID. */
    public String biomeName(int id) {
        return id >= 0 && id < biomeNames.size() ? biomeNames.get(id) : null;
    }

    /** All canonical biome names, indexed by registry ID. */
    public List<String> biomeNames() {
        return biomeNames;
    }

    /** The current ID of the named block state, or {@code -1} if it is gone. */
    public int blockId(String name) {
        Integer id = blockIdsByName.get(name);
        return id == null ? -1 : id;
    }

    /** The current ID of the named biome, or {@code -1} if it is gone. */
    public int biomeId(String name) {
        Integer id = biomeIdsByName.get(name);
        return id == null ? -1 : id;
    }

    /**
     * The registry ID of air — the fallback for blocks that no longer exist.
     *
     * <p>Air keeps the position's shape without silently substituting a
     * different block for the missing one.
     */
    public int airId() {
        return blockId("minecraft:air");
    }

    /** The registry ID of the plains biome — the fallback for missing biomes. */
    public int plainsId() {
        int plains = biomeId("minecraft:plains");
        return plains < 0 ? 0 : plains;
    }

    /**
     * Captures the live registry's ID space.
     *
     * @param server the running server (for the world's dynamic biome registry)
     */
    public static RegistrySnapshot capture(MinecraftServer server) {
        Registry<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);

        List<String> blockNames = new ArrayList<>(Block.BLOCK_STATE_REGISTRY.size());
        for (int id = 0; id < Block.BLOCK_STATE_REGISTRY.size(); id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            blockNames.add(state == null ? "" : canonicalName(state));
        }

        List<String> biomeNames = new ArrayList<>(biomes.size());
        for (int id = 0; id < biomes.size(); id++) {
            Biome biome = biomes.byId(id);
            biomeNames.add(biome == null ? "" : biomes.getKey(biome).toString());
        }

        RegistryManifest manifest = RegistryManifest.of(
                space.cobaltworks.zrdback.BackupConfig.PROTOCOL_VERSION, blockNames, biomeNames);
        return new RegistrySnapshot(manifest, blockNames, biomeNames);
    }

    /**
     * Canonical name of a block state.
     *
     * <p>Singleton states are named by their block alone; other states carry
     * their property values, sorted by property name so the encoding is stable
     * across restarts and comparable between versions.
     */
    public static String canonicalName(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        StringBuilder sb = new StringBuilder(id.toString());
        List<String> properties = new ArrayList<>();
        for (Property.Value<?> value : state.getValues().toList()) {
            properties.add(value.property().getName() + "=" + value.valueName());
        }
        if (!properties.isEmpty()) {
            properties.sort(null);
            sb.append('[');
            for (int i = 0; i < properties.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(properties.get(i));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static Map<String, Integer> indexByName(List<String> names) {
        Map<String, Integer> index = new HashMap<>();
        for (int id = 0; id < names.size(); id++) {
            index.putIfAbsent(names.get(id), id);
        }
        return index;
    }
}

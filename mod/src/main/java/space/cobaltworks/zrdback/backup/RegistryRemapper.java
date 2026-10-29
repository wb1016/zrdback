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

import space.cobaltworks.zvcr.region.ZvcrFile;
import space.cobaltworks.zrdback.store.RegistryManifest;

/**
 * Translates registry IDs between two registry epochs.
 *
 * <p>Chunks store block states and biomes as numeric registry IDs, which are
 * only meaningful for the exact registry that produced them. A backup written
 * before a Minecraft update or a mod change would otherwise restore the
 * <em>wrong blocks</em> while still looking perfectly valid.
 *
 * <p>The mod records the ID -> name mapping of the registry it backed up
 * against ({@link RegistryManifest}) and translates the stored IDs through it
 * into the current registry. Blocks that no longer exist become air.
 */
public final class RegistryRemapper {

    /** Maps a stored registry ID to the ID of the same block in the current registry. */
    public interface IdMap {
        /** The current ID of the stored ID, or {@code -1} if it no longer exists. */
        int translate(int storedId);
    }

    /** A remapper that leaves every ID untouched. */
    public static final IdMap IDENTITY = storedId -> storedId;

    private final IdMap blocks;
    private final IdMap biomes;
    private final int fallbackBlock;
    private final int fallbackBiome;

    private RegistryRemapper(IdMap blocks, IdMap biomes, int fallbackBlock, int fallbackBiome) {
        this.blocks = blocks;
        this.biomes = biomes;
        this.fallbackBlock = fallbackBlock;
        this.fallbackBiome = fallbackBiome;
    }

    /** A remapper that leaves every ID untouched. */
    public static RegistryRemapper identity() {
        return new RegistryRemapper(IDENTITY, IDENTITY, 0, 0);
    }

    public IdMap blocks() {
        return blocks;
    }

    public IdMap biomes() {
        return biomes;
    }

    /** True when this remapper leaves every ID untouched. */
    public boolean isIdentity() {
        return blocks == IDENTITY && biomes == IDENTITY;
    }

    /**
     * Builds a remapper from a stored manifest to the live registry.
     *
     * <p>When the two epochs have the same fingerprint, the mapping is the
     * identity. no per-ID translation is needed and the name lookup is
     * skipped.
     *
     * @param stored the manifest of the epoch the backup was written under, or
     *     {@code null} when unknown
     * @param live the live registry
     */
    public static RegistryRemapper from(RegistryManifest stored, RegistrySnapshot live) {
        if (stored == null || live == null) {
            return identity();
        }
        if (stored.fingerprint().equals(live.fingerprint())) {
            return identity();
        }
        return new RegistryRemapper(
                blockMap(stored, live),
                biomeMap(stored, live),
                live.airId(),
                live.plainsId());
    }

    /**
     * Translates the atoms of a whole backup file into the current registry.
     *
     * <p>Every chain entry is translated, not just the head: a delta stores the
     * previous value of each changed position, so the head and the deltas
     * below it must move through the same table to stay consistent.
     */
    public void remapFile(ZvcrFile file) {
        if (isIdentity()) {
            return;
        }
        space.cobaltworks.zvcr.format.ChainRemapper.remap(file,
                id -> blocks.translate(id) < 0 ? fallbackBlock : blocks.translate(id),
                id -> biomes.translate(id) < 0 ? fallbackBiome : biomes.translate(id));
    }

    private static IdMap blockMap(RegistryManifest stored, RegistrySnapshot live) {
        java.util.List<String> names = stored.blockNames();
        int[] map = new int[names.size()];
        for (int id = 0; id < map.length; id++) {
            map[id] = live.blockId(names.get(id));
        }
        return storedId -> {
            if (storedId < 0 || storedId >= map.length) {
                return -1;
            }
            return map[storedId];
        };
    }

    private static IdMap biomeMap(RegistryManifest stored, RegistrySnapshot live) {
        java.util.List<String> names = stored.biomeNames();
        int[] map = new int[names.size()];
        for (int id = 0; id < map.length; id++) {
            map[id] = live.biomeId(names.get(id));
        }
        return storedId -> {
            if (storedId < 0 || storedId >= map.length) {
                return -1;
            }
            return map[storedId];
        };
    }
}

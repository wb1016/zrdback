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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import space.cobaltworks.zrdback.zvcr.Zvcr;
import space.cobaltworks.zrdback.zvcr.region.Dimension;
import space.cobaltworks.zrdback.zvcr.region.TileEntity;
import space.cobaltworks.zrdback.zvcr.region.TileEntityPosition;

/**
 * Extracts ZVCR-semantic data from parsed chunk NBT: per-section blockstate ID
 * arrays (4096 atoms) and biome ID arrays (64 atoms), plus the tile entity
 * list with canonical NBT.
 *
 * <p>Disc bit widths follow vanilla's {@code Strategy} rules (blocks: 0 or
 * 4..8, else global; biomes: 0..3, else global) — MC re-normalizes on load, so
 * any valid width may appear and parsing must be generic.
 */
public final class ChunkExtractor {

    /** One extracted chunk: index-aligned section arrays plus its TE list. */
    public static final class ExtractedChunk {
        public final int sectionCount;
        /** Non-null entries are the unpacked atom arrays for that section index. */
        public final int[][] blockSections;
        public final int[][] biomeSections;
        public final List<TileEntity> tileEntities = new ArrayList<>();

        ExtractedChunk(int sectionCount) {
            this.sectionCount = sectionCount;
            this.blockSections = new int[sectionCount][];
            this.biomeSections = new int[sectionCount][];
        }
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(ChunkExtractor.class);

    private final Registry<Biome> biomes;

    public ChunkExtractor(Registry<Biome> biomes) {
        this.biomes = biomes;
    }

    public ExtractedChunk extract(CompoundTag chunk, Dimension dimension) {
        ExtractedChunk out = new ExtractedChunk(dimension.sectionCount());

        ListTag sections = chunk.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompoundOrEmpty(i);
            if (!section.contains("Y")) {
                continue;
            }
            int sectionY = section.getByteOr("Y", (byte) 0);
            int index = sectionY - dimension.minSectionY();
            if (index < 0 || index >= out.sectionCount) {
                continue; // outside the dimension's height range
            }
            section.getCompound("block_states").ifPresent(container ->
                    out.blockSections[index] = unpackContainer(container, Zvcr.SECTION_SIZE_BLOCKS, true));
            section.getCompound("biomes").ifPresent(container ->
                    out.biomeSections[index] = unpackContainer(container, Zvcr.SECTION_SIZE_BIOMES, false));
        }

        ListTag blockEntities = chunk.getListOrEmpty("block_entities");
        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag teTag = blockEntities.getCompoundOrEmpty(i);
            TileEntity te = extractTileEntity(teTag, dimension);
            if (te != null) {
                out.tileEntities.add(te);
            }
        }
        return out;
    }

    private TileEntity extractTileEntity(CompoundTag teTag, Dimension dimension) {
        if (!teTag.contains("id") || !teTag.contains("x") || !teTag.contains("y") || !teTag.contains("z")) {
            return null;
        }
        String id = teTag.getStringOr("id", "");
        int namespaceSep = id.indexOf(':');
        String shortId = namespaceSep >= 0 ? id.substring(namespaceSep + 1) : id;

        BlockEntityType type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(
                Identifier.withDefaultNamespace(shortId));
        int typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(type);
        if (type == null || typeId < 0) {
            return null; // unknown TE type; skip rather than fail the backup
        }

        int x = teTag.getIntOr("x", 0);
        int y = teTag.getIntOr("y", 0) - dimension.minY;
        int z = teTag.getIntOr("z", 0);

        CompoundTag payload = teTag.copy();
        payload.remove("id");
        payload.remove("keepPacked");
        payload.remove("x");
        payload.remove("y");
        payload.remove("z");

        byte[] nbt;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CanonicalNbt.writeTagWithType(new DataOutputStream(bytes), payload);
            nbt = bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("NBT serialization failed", e);
        }
        return new TileEntity(typeId,
                new TileEntityPosition(x & 0xFF, z & 0xFF, y & 0xFFFF), nbt);
    }

    /**
     * Unpacks a disc paletted container ({@code palette} + optional
     * {@code data}) into an atom array of registry IDs.
     */
    private int[] unpackContainer(CompoundTag container, int expectedSize, boolean blocks) {
        ListTag palette = container.getListOrEmpty("palette");
        int paletteSize = palette.size();

        if (paletteSize == 1) {
            int value = paletteValueToId(palette, 0, blocks);
            int[] out = new int[expectedSize];
            java.util.Arrays.fill(out, value);
            return out;
        }

        int bits = bitsOnDisc(paletteSize, blocks);
        if (!container.contains("data")) {
            throw new IllegalStateException("Paletted container missing data (palette size "
                    + paletteSize + ")");
        }
        long[] data = container.getLongArray("data").orElseThrow();
        boolean global = bits > (blocks ? 8 : 3);

        int[] ids = null;
        if (!global) {
            ids = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                ids[i] = paletteValueToId(palette, i, blocks);
            }
        }

        int[] out = new int[expectedSize];
        int valuesPerLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int i = 0; i < expectedSize; i++) {
            long cell = data[i / valuesPerLong];
            int raw = (int) ((cell >>> ((i % valuesPerLong) * bits)) & mask);
            out[i] = global ? raw : ids[raw];
        }
        return out;
    }

    private int paletteValueToId(ListTag palette, int index, boolean blocks) {
        Tag entry = unwrapWrapper(palette.get(index));
        if (blocks) {
            // 26.3 disc format: default states are plain block-id strings;
            // non-default states are compounds {id, properties?} (the old
            // {Name, Properties} keys are gone). Heterogeneous lists get their
            // entries wrapped as {"": entry} by vanilla's list writer.
            if (entry instanceof net.minecraft.nbt.StringTag stringTag) {
                Block block = BuiltInRegistries.BLOCK.getValue(
                        Identifier.parse(stringTag.value()));
                return block == null ? -1 : Block.BLOCK_STATE_REGISTRY.getId(block.defaultBlockState());
            }
            if (entry instanceof CompoundTag compound) {
                BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, compound);
                return Block.BLOCK_STATE_REGISTRY.getId(state);
            }
            return -1;
        }
        // Biome palettes store resource-location strings; IDs come from the
        // biome registry's holder id map (same space as Registry#getId).
        String key = entry instanceof net.minecraft.nbt.StringTag stringTag
                ? stringTag.value()
                : entry.asString().orElse("");
        Biome biome = biomes.getValue(Identifier.parse(key));
        return biome == null ? -1 : biomes.getId(biome);
    }

    /** Unwraps vanilla's heterogeneous-list wrapper compounds ({"": value}). */
    private static Tag unwrapWrapper(Tag tag) {
        if (tag instanceof CompoundTag compound && compound.size() == 1 && compound.contains("")) {
            Tag inner = compound.get("");
            if (inner != null) {
                return inner;
            }
        }
        return tag;
    }

    /** Vanilla disc bit width for a given palette size. */
    static int bitsOnDisc(int paletteSize, boolean blocks) {
        // ceil(log2(paletteSize)); 0 for a single-entry palette
        int bits = paletteSize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        if (blocks) {
            return bits <= 4 ? 4 : bits; // 4 bits up to 16 entries, 5..8 linear, else global
        }
        return bits; // biomes: 1..3 linear, else global at ceil(log2)
    }
}

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
        int chunkX = chunk.getIntOr("xPos", 0);
        int chunkZ = chunk.getIntOr("zPos", 0);

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
            TileEntity te = extractTileEntity(teTag, chunkX, chunkZ, dimension);
            if (te != null) {
                out.tileEntities.add(te);
            }
        }
        return out;
    }

    private TileEntity extractTileEntity(CompoundTag teTag, int chunkX, int chunkZ,
                                         Dimension dimension) {
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

        // ZVCR stores chunk-LOCAL x/z (uint8) and y relative to the dimension
        // floor (uint16). Masking the world coordinates instead silently
        // wrapped every 256 blocks and mirrored negative chunks to the far
        // positive side — vanilla then rejected the TEs on restore ("found in
        // a wrong chunk").
        int localX = teTag.getIntOr("x", 0) - chunkX * 16;
        int localZ = teTag.getIntOr("z", 0) - chunkZ * 16;
        int y = teTag.getIntOr("y", 0) - dimension.minY;
        if (localX < 0 || localX > 0xFF || localZ < 0 || localZ > 0xFF
                || y < 0 || y > 0xFFFF) {
            LOGGER.warn("Tile entity at world ({}, {}, {}) outside its chunk ({}, {}); skipped",
                    teTag.getIntOr("x", 0), teTag.getIntOr("y", 0), teTag.getIntOr("z", 0),
                    chunkX, chunkZ);
            return null;
        }

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
        return new TileEntity(typeId, new TileEntityPosition(localX, localZ, y), nbt);
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

        // Disc data values are ALWAYS indices into the palette list — including
        // sections vanilla stores with the Global in-memory configuration
        // (>8-bit blocks / >3-bit biomes): PalettedContainer#pack re-encodes
        // into a fresh HashMapPalette and writes every distinct entry, so the
        // palette must be resolved for every multi-value section. Treating
        // large-bit sections as "global IDs" stored raw palette indices, which
        // restored as arbitrary wrong blocks.
        int[] ids = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            ids[i] = paletteValueToId(palette, i, blocks);
        }

        int[] out = new int[expectedSize];
        int valuesPerLong = 64 / bits;
        long mask = (1L << bits) - 1;
        if (data.length < (expectedSize + valuesPerLong - 1) / valuesPerLong) {
            throw new IllegalStateException("Paletted container data too short: "
                    + data.length + " longs for " + expectedSize + " entries at " + bits + " bits");
        }
        for (int i = 0; i < expectedSize; i++) {
            long cell = data[i / valuesPerLong];
            int raw = (int) ((cell >>> ((i % valuesPerLong) * bits)) & mask);
            if (raw >= paletteSize) {
                throw new IllegalStateException("Palette index out of bounds: " + raw
                        + " >= " + paletteSize + " (bits " + bits + ")");
            }
            out[i] = ids[raw];
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
                BlockState state = readBlockStateAnyFormat(compound);
                return state == null ? -1 : Block.BLOCK_STATE_REGISTRY.getId(state);
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

    /**
     * Reads a blockstate from a palette entry in either disc format: 26.3's
     * {@code {id, properties}} (via the vanilla helper) or the legacy
     * {@code {Name, Properties}} of worlds saved by older versions. The legacy
     * form matters because vanilla only upgrades chunks it actually loads — a
     * world opened once has most chunks still on disk in the old format, and
     * feeding {@code {Name: ...}} to the 26.3 helper silently yields air.
     */
    private BlockState readBlockStateAnyFormat(CompoundTag tag) {
        if (tag.contains("id")) {
            return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag);
        }
        String name = tag.getStringOr("Name", "");
        Block block = name.isEmpty() ? null
                : BuiltInRegistries.BLOCK.getValue(Identifier.parse(name));
        if (block == null) {
            return null;
        }
        BlockState state = block.defaultBlockState();
        CompoundTag props = tag.getCompoundOrEmpty("Properties");
        for (String key : props.keySet()) {
            net.minecraft.world.level.block.state.properties.Property<?> property =
                    block.getStateDefinition().getProperty(key);
            if (property != null) {
                state = applyProperty(state, property, props.getStringOr(key, ""));
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(
            BlockState state, net.minecraft.world.level.block.state.properties.Property<T> property,
            String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
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

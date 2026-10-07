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
package space.cobaltworks.zvcr.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import space.cobaltworks.zvcr.Zvcr;
import space.cobaltworks.zvcr.format.PackedData;
import space.cobaltworks.zvcr.format.PackedSnapshot;
import space.cobaltworks.zvcr.format.Palette;
import space.cobaltworks.zvcr.region.Segment;
import space.cobaltworks.zvcr.region.SegmentState;
import space.cobaltworks.zvcr.region.TileEntity;
import space.cobaltworks.zvcr.region.TileEntityDelta;
import space.cobaltworks.zvcr.region.TileEntityListDelta;
import space.cobaltworks.zvcr.region.TileEntityPosition;
import space.cobaltworks.zvcr.region.ZvcrFile;
import space.cobaltworks.zvcr.region.ZvcrVersion;

/**
 * Serializes a {@link ZvcrFile} to ZVCR-3D bytes (version 1.0.0.0 layout),
 * byte-compatible with the C++ reference implementation.
 *
 * <p>Layout: uncompressed header (magic {@code zvcr3d}, version uint8, dimension
 * uint8, protocol uint16) followed by the Zstd-compressed region container
 * (block palette table, biome palette table, 1024 optional segments).
 *
 * <p>Palette table indices are assigned in first-encounter order while the
 * segments are serialized; palettes with equal entries but different order are
 * distinct entries. Direct and single-value palettes are never stored in the
 * table (single-value snapshots use palette type 0; direct snapshots use
 * palette index {@code 0xFFFFFFFF}).
 */
public final class ZvcrWriter {

    public static final String MAGIC = "zvcr3d";

    private final ByteWriter segments = new ByteWriter();
    /** Insertion-ordered palette dedup tables; index = first-encounter order. */
    private final Map<Palette, Integer> blockPaletteTable = new LinkedHashMap<>();
    private final Map<Palette, Integer> biomePaletteTable = new LinkedHashMap<>();

    private ZvcrWriter() {}

    public static byte[] serialize(ZvcrFile file, int compressionLevel) {
        ZvcrWriter writer = new ZvcrWriter();

        // Segments (indicators + bodies) are serialized first, collecting the
        // palette tables as a side effect; the container is then assembled as
        // [block palette table][biome palette table][1024 optional segments].
        ByteWriter segmentBytes = writer.segments;
        for (int i = 0; i < Zvcr.SEGMENTS_PER_REGION; i++) {
            Segment segment = file.region.get(i / Zvcr.REGION_SIDELENGTH_SEGMENTS,
                    i % Zvcr.REGION_SIDELENGTH_SEGMENTS).orElse(null);
            if (segment == null) {
                segmentBytes.writeByte(0);
            } else {
                segmentBytes.writeByte(1);
                writer.writeSegment(segmentBytes, segment);
            }
        }

        ByteWriter container = new ByteWriter();
        writePaletteTable(container, writer.blockPaletteTable);
        writePaletteTable(container, writer.biomePaletteTable);
        container.writeBytes(segmentBytes.toByteArray());

        ByteWriter out = new ByteWriter();
        out.writeAscii(MAGIC);
        out.writeByte(ZvcrVersion.LATEST.number);
        out.writeByte(file.dimensionType.id);
        out.writeUInt16(file.protocolVersion);
        out.writeBytes(ZstdCodec.compress(container.toByteArray(), compressionLevel));
        return out.toByteArray();
    }

    public static byte[] serialize(ZvcrFile file) {
        return serialize(file, ZstdCodec.COMPRESSION_LEVEL_DEFAULT);
    }

    private void writeSegment(ByteWriter out, Segment segment) {
        for (int i = 0; i < segment.sectionCount; i++) {
            writePackedDeltaData(out, segment.blockSections.section(i), blockPaletteTable);
        }
        for (int i = 0; i < segment.sectionCount; i++) {
            writePackedDeltaData(out, segment.biomeSections.section(i), biomePaletteTable);
        }
        writeSegmentInfo(out, segment);
        writeTileEntities(out, segment.tileEntities.reverseDeltas());
    }

    private void writeSegmentInfo(ByteWriter out, Segment segment) {
        List<SegmentState> states = segment.info.segmentStates();
        out.writeUInt64(states.size());
        for (SegmentState state : states) {
            out.writeByte(state.type().id);
            out.writeUInt64(state.timestamp());
        }
    }

    private void writeTileEntities(ByteWriter out, List<TileEntityListDelta> reverseDeltas) {
        out.writeUInt64(reverseDeltas.size());
        for (TileEntityListDelta delta : reverseDeltas) {
            out.writeUInt64(delta.timestamp());
            out.writeUInt64(delta.deltas().size());

            // positions sorted by packed position, matching the C++ writer
            List<TileEntityPosition> sorted = new ArrayList<>(delta.deltas().keySet());
            sorted.sort((a, b) -> Integer.compare(a.packedPosition(), b.packedPosition()));
            for (TileEntityPosition pos : sorted) {
                out.writeUInt32(pos.packedPosition() & 0xFFFFFFFFL);
                TileEntityDelta entry = delta.deltas().get(pos);
                if (entry instanceof TileEntityDelta.Put put) {
                    out.writeByte(1);
                    TileEntity te = put.tileEntity();
                    out.writeUInt32(te.type() & 0xFFFFFFFFL);
                    out.writeUInt64(te.nbt().length);
                    out.writeBytes(te.nbt());
                } else {
                    out.writeByte(0);
                }
            }
        }
    }

    private void writePackedDeltaData(ByteWriter out, space.cobaltworks.zvcr.format.PackedDeltaData chain,
                                      Map<Palette, Integer> paletteTable) {
        List<PackedSnapshot> deltas = chain.reverseDeltas();
        out.writeUInt64(deltas.size());
        for (PackedSnapshot snapshot : deltas) {
            writePackedSnapshot(out, snapshot, paletteTable);
        }
    }

    private void writePackedSnapshot(ByteWriter out, PackedSnapshot snapshot,
                                     Map<Palette, Integer> paletteTable) {
        out.writeUInt64(snapshot.timestamp());

        if (snapshot.data() instanceof PackedData.SingleValue single) {
            out.writeByte(0); // single-value palette
            out.writeUInt16(single.value());
            return;
        }
        out.writeByte(1); // section palette

        PackedData.Paletted paletted = (PackedData.Paletted) snapshot.data();
        out.writeUInt64(paletted.packedLongs().length);
        for (long cell : paletted.packedLongs()) {
            out.writeUInt64(cell);
        }

        if (paletted.palette().direct()) {
            out.writeUInt32(0xFFFFFFFFL); // direct palette mode
            return;
        }
        Integer index = paletteTable.get(paletted.palette());
        if (index == null) {
            index = paletteTable.size();
            paletteTable.put(paletted.palette(), index);
        }
        out.writeUInt32(index);
    }

    private static void writePaletteTable(ByteWriter out, Map<Palette, Integer> table) {
        out.writeUInt32(table.size());
        for (Palette palette : table.keySet()) {
            // The C++ writer skips direct/single-value palettes here; they can
            // never be assigned a table index in practice (pack() never produces
            // them), so this loop body always writes.
            out.writeUInt16(palette.length());
            for (int atom : palette.entries()) {
                out.writeUInt16(atom);
            }
        }
    }
}

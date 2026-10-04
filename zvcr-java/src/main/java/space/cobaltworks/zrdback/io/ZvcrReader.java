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
package space.cobaltworks.zrdback.zvcr.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import space.cobaltworks.zrdback.zvcr.Zvcr;
import space.cobaltworks.zrdback.zvcr.format.PackedData;
import space.cobaltworks.zrdback.zvcr.format.PackedDeltaData;
import space.cobaltworks.zrdback.zvcr.format.PackedSnapshot;
import space.cobaltworks.zrdback.zvcr.format.Palette;
import space.cobaltworks.zrdback.zvcr.region.Dimension;
import space.cobaltworks.zrdback.zvcr.region.Segment;
import space.cobaltworks.zrdback.zvcr.region.SegmentState;
import space.cobaltworks.zrdback.zvcr.region.SegmentStateType;
import space.cobaltworks.zrdback.zvcr.region.TileEntity;
import space.cobaltworks.zrdback.zvcr.region.TileEntityDelta;
import space.cobaltworks.zrdback.zvcr.region.TileEntityListDelta;
import space.cobaltworks.zrdback.zvcr.region.TileEntityPosition;
import space.cobaltworks.zrdback.zvcr.region.ZvcrFile;
import space.cobaltworks.zrdback.zvcr.region.ZvcrVersion;

/**
 * Parses ZVCR-3D bytes into a {@link ZvcrFile}, byte-compatible with the C++
 * reference reader (including its sanity limits and backwards-compat quirks).
 */
public final class ZvcrReader {

    // Sanity limits, mirroring deserialize.hpp
    public static final int MAX_DELTA_LENGTH = 65536;
    public static final int MAX_SEGMENT_STATES_LENGTH = 65536;
    public static final int MAX_TILE_ENTITY_LIST_LENGTH = 98304;
    public static final int MAX_TILE_ENTITY_NBT_LENGTH = 65536;
    public static final int MAX_PACKED_LENGTH = 1024;
    public static final int MAX_PALETTE_TABLE_LENGTH = 262144;

    private final ByteReader in;
    private final long maxDeltas;
    private List<Palette> blockPaletteTable = List.of();
    private List<Palette> biomePaletteTable = List.of();
    private int sectionCount;

    private ZvcrReader(byte[] data, long maxDeltas) {
        this.in = new ByteReader(data);
        this.maxDeltas = maxDeltas;
    }

    public static ZvcrFile read(byte[] data) {
        return read(data, 0);
    }

    /**
     * @param maxDeltas maximum chain entries to materialize per section
     *                  (older entries are skipped); 0 = unlimited
     */
    public static ZvcrFile read(byte[] data, long maxDeltas) {
        ZvcrReader reader = new ZvcrReader(data, maxDeltas);
        return reader.readFile();
    }

    private ZvcrFile readFile() {
        validateMagic();

        int versionNumber = in.readByte("version");
        if (versionNumber > ZvcrVersion.LATEST.number) {
            throw new ByteReader.ReadException("Invalid zvcr version number: " + versionNumber
                    + "; maximum allowed: " + ZvcrVersion.LATEST.number);
        }
        ZvcrVersion version = ZvcrVersion.byNumber(versionNumber);

        int dimensionId = in.readByte("dimension type");
        Dimension dimension = Dimension.byId(dimensionId);
        this.sectionCount = dimension.sectionCount();

        int protocolVersion = in.readUInt16("protocol version");

        ZvcrFile file = new ZvcrFile(version, protocolVersion, dimension);

        byte[] compressed = in.readBytes(in.remaining(), "region container");
        byte[] container = ZstdCodec.decompress(compressed);
        in.reset(container);
        readRegion(file);
        return file;
    }

    private void validateMagic() {
        byte[] magic = in.readBytes(ZvcrWriter.MAGIC.length(), "magic prefix");
        String actual = new String(magic, java.nio.charset.StandardCharsets.US_ASCII);
        if (!actual.equals(ZvcrWriter.MAGIC)) {
            throw new ByteReader.ReadException("Invalid header prefix: " + actual);
        }
    }

    private void readRegion(ZvcrFile file) {
        blockPaletteTable = readPaletteTable();
        biomePaletteTable = readPaletteTable();

        for (int i = 0; i < Zvcr.SEGMENTS_PER_REGION; i++) {
            int present = in.readByte("segment indicator");
            if (present != 0) {
                file.region.set(i / Zvcr.REGION_SIDELENGTH_SEGMENTS,
                        i % Zvcr.REGION_SIDELENGTH_SEGMENTS, readSegment());
            }
        }
    }

    private List<Palette> readPaletteTable() {
        long tableLength = in.readUInt32("palette table length");
        if (tableLength > MAX_PALETTE_TABLE_LENGTH) {
            throw new ByteReader.ReadException("Invalid palette table length: "
                    + tableLength + " > " + MAX_PALETTE_TABLE_LENGTH);
        }
        List<Palette> table = new ArrayList<>((int) tableLength);
        for (long i = 0; i < tableLength; i++) {
            int paletteLength = in.readUInt16("palette length");
            if (paletteLength > Zvcr.MAX_INDIRECT_PALETTE_SIZE) {
                // Backwards-compat quirk: oversized palettes on read are treated
                // as direct palettes and their data is skipped.
                in.skipBytes((long) paletteLength * 2, "palette data");
                table.add(Palette.DIRECT);
                continue;
            }
            int[] entries = in.readUInt16Array(paletteLength, "palette data");
            table.add(new Palette(entries, Palette.bitsPerEntry(paletteLength)));
        }
        return table;
    }

    private Segment readSegment() {
        Segment segment = new Segment(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            readPackedDeltaData(segment.blockSections.section(i), blockPaletteTable);
        }
        for (int i = 0; i < sectionCount; i++) {
            readPackedDeltaData(segment.biomeSections.section(i), biomePaletteTable);
        }
        readSegmentInfo(segment);
        readTileEntities(segment);
        return segment;
    }

    private void readSegmentInfo(Segment segment) {
        long statesLength = in.readUInt64("segment states length");
        if (statesLength > MAX_SEGMENT_STATES_LENGTH) {
            throw new ByteReader.ReadException("Invalid segment states length: "
                    + statesLength + " > " + MAX_SEGMENT_STATES_LENGTH);
        }
        List<SegmentState> states = new ArrayList<>((int) statesLength);
        for (long i = 0; i < statesLength; i++) {
            int typeId = in.readByte("segment state type");
            SegmentStateType type = SegmentStateType.byId(typeId);
            long timestamp = in.readUInt64("segment state timestamp");
            states.add(new SegmentState(type, timestamp));
        }
        segment.info.insertAll(states);
    }

    private void readTileEntities(Segment segment) {
        long deltasLength = in.readUInt64("tile entity list deltas length");
        if (deltasLength > MAX_DELTA_LENGTH) {
            throw new ByteReader.ReadException("Invalid tile entity list deltas length: "
                    + deltasLength + " > " + MAX_DELTA_LENGTH);
        }
        List<TileEntityListDelta> deltas = new ArrayList<>((int) deltasLength);
        for (long i = 0; i < deltasLength; i++) {
            long timestamp = in.readUInt64("tile entity list timestamp");
            long listLength = in.readUInt64("tile entity list length");
            if (listLength > MAX_TILE_ENTITY_LIST_LENGTH) {
                throw new ByteReader.ReadException("Invalid tile entity list length: "
                        + listLength + " > " + MAX_TILE_ENTITY_LIST_LENGTH);
            }
            Map<TileEntityPosition, TileEntityDelta> entries = new LinkedHashMap<>();
            for (long j = 0; j < listLength; j++) {
                int packed = (int) in.readUInt32("tile entity packed position");
                TileEntityPosition pos = TileEntityPosition.unpack(packed);
                int op = in.readByte("tile entity delta operation");
                if (op == 0) {
                    entries.put(pos, TileEntityDelta.ERASE);
                    continue;
                }
                long type = in.readUInt32("tile entity type");
                long nbtLength = in.readUInt64("tile entity nbt length");
                if (nbtLength > MAX_TILE_ENTITY_NBT_LENGTH) {
                    throw new ByteReader.ReadException("Invalid tile entity nbt length: "
                            + nbtLength + " > " + MAX_TILE_ENTITY_NBT_LENGTH);
                }
                byte[] nbt = in.readBytes((int) nbtLength, "tile entity nbt");
                entries.put(pos, new TileEntityDelta.Put(new TileEntity((int) type, pos, nbt)));
            }
            deltas.add(new TileEntityListDelta(timestamp, entries));
        }
        segment.tileEntities.insertAll(deltas);
    }

    private void readPackedDeltaData(PackedDeltaData chain, List<Palette> paletteTable) {
        long deltaLength = in.readUInt64("delta length");
        if (deltaLength > MAX_DELTA_LENGTH) {
            throw new ByteReader.ReadException("Invalid delta length: "
                    + deltaLength + " > " + MAX_DELTA_LENGTH);
        }
        for (long deltaIndex = 0; deltaIndex < deltaLength; deltaIndex++) {
            if (maxDeltas != 0 && deltaIndex >= maxDeltas) {
                skipPackedSnapshot();
                continue;
            }
            chain.appendSnapshot(readPackedSnapshot(paletteTable));
        }
    }

    private PackedSnapshot readPackedSnapshot(List<Palette> paletteTable) {
        long timestamp = in.readUInt64("timestamp");
        int dataType = in.readByte("palette type");
        if (dataType == 0) {
            int singleValue = in.readUInt16("single palette value");
            return new PackedSnapshot(new PackedData.SingleValue(singleValue), timestamp);
        }
        long packedLength = in.readUInt64("packed length");
        if (packedLength > MAX_PACKED_LENGTH) {
            throw new ByteReader.ReadException("Invalid packed length: "
                    + packedLength + " > " + MAX_PACKED_LENGTH);
        }
        long[] packedLongs = in.readUInt64Array((int) packedLength, "packed data");

        long paletteIndex = in.readUInt32("palette index");
        if (paletteIndex == 0xFFFFFFFFL) {
            return new PackedSnapshot(new PackedData.Paletted(packedLongs, Palette.DIRECT), timestamp);
        }
        if (paletteIndex >= paletteTable.size()) {
            throw new ByteReader.ReadException("Invalid palette index: "
                    + paletteIndex + " >= " + paletteTable.size());
        }
        Palette palette = paletteTable.get((int) paletteIndex);
        return new PackedSnapshot(new PackedData.Paletted(packedLongs, palette), timestamp);
    }

    private void skipPackedSnapshot() {
        in.skipBytes(8, "timestamp");
        int dataType = in.readByte("palette type");
        if (dataType == 0) {
            in.skipBytes(2, "single palette value");
            return;
        }
        long packedLength = in.readUInt64("packed length");
        if (packedLength > MAX_PACKED_LENGTH) {
            throw new ByteReader.ReadException("Invalid packed length: "
                    + packedLength + " > " + MAX_PACKED_LENGTH);
        }
        in.skipBytes(packedLength * 8, "packed data");
        in.skipBytes(4, "palette index");
    }
}

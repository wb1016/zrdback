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

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Canonical NBT serialization for ZVCR tile entities, byte-compatible with the
 * C++ reference (mc_cpp {@code writeWithType}):
 *
 * <ul>
 *   <li>type byte + payload — <strong>no root name</strong></li>
 *   <li>compound keys sorted alphabetically (the reference compares raw NBT
 *       bytes for equality, so key order must be canonical)</li>
 *   <li>big-endian numbers, modified UTF-8 strings (Java's
 *       {@code DataOutput.writeUTF} is exactly modified UTF-8)</li>
 *   <li>lists: held type + int32 length + element payloads (vanilla
 *       {@code ListTag.write} semantics, including heterogeneous-list
 *       wrapping)</li>
 * </ul>
 */
public final class CanonicalNbt {

    private CanonicalNbt() {}

    public static void writeTagWithType(DataOutput out, Tag tag) throws IOException {
        out.writeByte(tag.getId());
        writePayload(out, tag);
    }

    private static void writePayload(DataOutput out, Tag tag) throws IOException {
        if (tag instanceof CompoundTag compound) {
            List<String> keys = new ArrayList<>(compound.keySet());
            keys.sort(null); // alphabetical; MC tag names are ASCII so this
                             // matches the C++ byte-order comparison
            for (String key : keys) {
                Tag value = compound.get(key);
                out.writeByte(value.getId());
                out.writeUTF(key);
                writePayload(out, value);
            }
            out.writeByte(0); // END
        } else {
            tag.write(out);
        }
    }
}

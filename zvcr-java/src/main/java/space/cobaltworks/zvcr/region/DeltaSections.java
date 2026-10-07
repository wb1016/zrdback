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
package space.cobaltworks.zvcr.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import space.cobaltworks.zvcr.Zvcr;
import space.cobaltworks.zvcr.format.DeltaInsertionResult;
import space.cobaltworks.zvcr.format.PackedDeltaData;
import space.cobaltworks.zvcr.format.PackedSnapshot;

/**
 * Per-section delta chains for one segment (blocks or biomes). Mirrors the C++
 * {@code DeltaSections<unpackedSize>}: an array of {@code sectionCount} chains
 * (allocated up to {@link Zvcr#MAX_SECTION_COUNT}).
 */
public final class DeltaSections {

    private final int sectionCount;
    private final List<PackedDeltaData> sections;

    public DeltaSections(int sectionCount, int unpackedSize) {
        if (sectionCount < 0 || sectionCount > Zvcr.MAX_SECTION_COUNT) {
            throw new IllegalArgumentException("Invalid section count: " + sectionCount);
        }
        this.sectionCount = sectionCount;
        this.sections = new ArrayList<>(Zvcr.MAX_SECTION_COUNT);
        for (int i = 0; i < Zvcr.MAX_SECTION_COUNT; i++) {
            this.sections.add(new PackedDeltaData(unpackedSize));
        }
    }

    public int sectionCount() {
        return sectionCount;
    }

    /** Direct access to a section's chain (index within {@code [0, sectionCount)}). */
    public PackedDeltaData section(int index) {
        if (index < 0 || index >= sectionCount) {
            throw new IndexOutOfBoundsException("Section index " + index + " >= " + sectionCount);
        }
        return sections.get(index);
    }

    /**
     * Inserts one snapshot per section (index-aligned). Rejected insertions
     * (older-than-latest, no changes) count as zero, matching the C++
     * {@code updateSections} ({@code value_or(0)}).
     *
     * @return total number of changed atoms across sections
     */
    public int updateSections(List<PackedSnapshot> sectionUpdates) {
        int changes = 0;
        for (int section = 0; section < sectionUpdates.size() && section < sectionCount; section++) {
            changes += applyOrZero(sections.get(section).insertSnapshot(sectionUpdates.get(section)));
        }
        return changes;
    }

    private static int applyOrZero(DeltaInsertionResult result) {
        return result instanceof DeltaInsertionResult.Success s ? s.changes() : 0;
    }
}

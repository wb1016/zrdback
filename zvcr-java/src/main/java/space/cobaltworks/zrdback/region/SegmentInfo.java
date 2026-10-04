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
package space.cobaltworks.zrdback.zvcr.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Segment info: a newest-first list of {@link SegmentState} entries describing
 * whether the chunk was newly generated or loaded from disk. Mirrors the C++
 * {@code SegmentInfo}.
 */
public final class SegmentInfo {

    private final List<SegmentState> segmentStates = new ArrayList<>();

    public SegmentInfo() {}

    public SegmentInfo(List<SegmentState> states) {
        segmentStates.addAll(states);
    }

    /** Replaces the state list (used by the deserializer). */
    public void insertAll(List<SegmentState> states) {
        segmentStates.clear();
        segmentStates.addAll(states);
    }

    public List<SegmentState> segmentStates() {
        return List.copyOf(segmentStates);
    }

    public int size() {
        return segmentStates.size();
    }

    public Optional<SegmentState> latestSnapshot() {
        return delta(0);
    }

    public Optional<SegmentState> delta(int index) {
        if (index < 0 || index >= segmentStates.size()) {
            return Optional.empty();
        }
        return Optional.of(segmentStates.get(index));
    }

    /**
     * State at (or before) {@code timestamp}: walk from the newest entry,
     * adopting each entry's type until the entry's timestamp ≤ query.
     */
    public Optional<SegmentState> snapshotBefore(long timestamp) {
        Optional<SegmentState> latestOpt = latestSnapshot();
        if (latestOpt.isEmpty()) {
            return Optional.empty();
        }
        SegmentStateType type = latestOpt.get().type();
        for (SegmentState state : segmentStates) {
            type = state.type();
            if (timestamp >= state.timestamp()) {
                break;
            }
        }
        return Optional.of(new SegmentState(type, timestamp));
    }

    public Optional<SegmentState> snapshotFrom(long timestamp) {
        return snapshotBefore(findNearestTimestamp(timestamp));
    }

    /**
     * Inserts a state at the head unless it is older than the latest or the
     * type is unchanged. Returns whether the state was inserted.
     */
    public boolean insertSnapshot(SegmentState newState) {
        Optional<SegmentState> latest = latestSnapshot();
        if (latest.isPresent()
                && (newState.timestamp() <= latest.get().timestamp()
                    || latest.get().type() == newState.type())) {
            return false;
        }
        segmentStates.add(0, newState);
        return true;
    }

    /**
     * Drops states strictly older than {@code minTimestamp}, always keeping the
     * newest (head). Returns the number removed.
     */
    public int truncateOlderThan(long minTimestamp) {
        int removed = 0;
        for (int i = segmentStates.size() - 1; i >= 1; i--) {
            if (segmentStates.get(i).timestamp() < minTimestamp) {
                segmentStates.remove(i);
                removed++;
            }
        }
        return removed;
    }

    public long findNearestTimestamp(long timestamp) {
        long closest = timestamp;
        boolean found = false;
        long minDistance = 0;
        for (SegmentState state : segmentStates) {
            long distance = Math.abs(state.timestamp() - timestamp);
            if (!found || distance < minDistance) {
                found = true;
                minDistance = distance;
                closest = state.timestamp();
            }
        }
        return closest;
    }
}

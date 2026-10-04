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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import space.cobaltworks.zrdback.zvcr.format.DeltaInsertionResult;

/**
 * Reverse-delta history of tile entity lists for one segment. Mirrors the C++
 * {@code DeltaTileEntityData}.
 *
 * <p>Entry 0 is the latest snapshot: every present TE is a {@code put}. Older
 * entries are reverse deltas: a TE absent before → {@code erase}; a TE that
 * existed with different NBT → {@code put(old TE)}; unchanged TEs are excluded.
 */
public final class TileEntityHistory {

    private final java.util.List<TileEntityListDelta> reverseDeltas = new java.util.ArrayList<>();

    public java.util.List<TileEntityListDelta> reverseDeltas() {
        return java.util.List.copyOf(reverseDeltas);
    }

    /** Replaces the delta list (used by the deserializer). */
    public void insertAll(java.util.List<TileEntityListDelta> deltas) {
        reverseDeltas.clear();
        reverseDeltas.addAll(deltas);
    }

    public int size() {
        return reverseDeltas.size();
    }

    public Optional<TileEntityListDelta> latestSnapshot() {
        return delta(0);
    }

    public Optional<TileEntityListDelta> delta(int index) {
        if (index < 0 || index >= reverseDeltas.size()) {
            return Optional.empty();
        }
        return Optional.of(reverseDeltas.get(index));
    }

    /** Materialized TE list at (or closest before) {@code timestamp}. */
    public Optional<Map<TileEntityPosition, TileEntity>> snapshotFrom(long timestamp) {
        return snapshotBefore(findNearestTimestamp(timestamp));
    }

    /**
     * Newest TE list whose timestamp is ≤ {@code timestamp}: start from the
     * latest snapshot's put entries, then apply older deltas in order (put
     * overwrites, erase removes), stopping once the entry's timestamp ≤ query.
     */
    public Optional<Map<TileEntityPosition, TileEntity>> snapshotBefore(long timestamp) {
        Optional<TileEntityListDelta> latestOpt = latestSnapshot();
        if (latestOpt.isEmpty()) {
            return Optional.empty();
        }

        Map<TileEntityPosition, TileEntity> snapshot = new LinkedHashMap<>();
        for (var entry : latestOpt.get().deltas().entrySet()) {
            if (entry.getValue() instanceof TileEntityDelta.Put put) {
                snapshot.put(entry.getKey(), put.tileEntity());
            }
        }
        if (timestamp >= latestOpt.get().timestamp()) {
            return Optional.of(snapshot);
        }

        boolean first = true;
        for (TileEntityListDelta delta : reverseDeltas) {
            if (first) {
                first = false;
                continue;
            }
            for (var entry : delta.deltas().entrySet()) {
                if (entry.getValue() instanceof TileEntityDelta.Put put) {
                    snapshot.put(entry.getKey(), put.tileEntity());
                } else {
                    snapshot.remove(entry.getKey());
                }
            }
            if (timestamp >= delta.timestamp()) {
                break;
            }
        }
        return Optional.of(snapshot);
    }

    /**
     * Inserts a new TE list snapshot at the head, converting the previous latest
     * into a reverse delta. Mirrors the C++ {@code insertSnapshot}.
     */
    public DeltaInsertionResult insertSnapshot(long timestamp,
                                               Iterable<TileEntity> tileEntityListSnapshot) {
        Optional<TileEntityListDelta> latestOpt = latestSnapshot();
        if (latestOpt.isEmpty()) {
            Map<TileEntityPosition, TileEntityDelta> deltas = new LinkedHashMap<>();
            for (TileEntity te : tileEntityListSnapshot) {
                deltas.put(te.pos(), new TileEntityDelta.Put(te));
            }
            reverseDeltas.add(new TileEntityListDelta(timestamp, deltas));
            return DeltaInsertionResult.success(deltas.size());
        }

        TileEntityListDelta latest = latestOpt.get();
        if (timestamp <= latest.timestamp()) {
            return DeltaInsertionResult.rejected(DeltaInsertionResult.Status.SNAPSHOT_OLDER_THAN_LATEST);
        }

        Map<TileEntityPosition, TileEntityDelta> newLatest = new LinkedHashMap<>();
        for (TileEntity te : tileEntityListSnapshot) {
            newLatest.put(te.pos(), new TileEntityDelta.Put(te));
        }

        Map<TileEntityPosition, TileEntityDelta> deltas = new LinkedHashMap<>();
        for (TileEntity te : tileEntityListSnapshot) {
            TileEntityDelta found = latest.deltas().get(te.pos());
            if (found == null) {
                deltas.put(te.pos(), TileEntityDelta.ERASE);
            } else if (found instanceof TileEntityDelta.Put put && !put.tileEntity().equals(te)) {
                deltas.put(te.pos(), found);
            }
        }
        for (var entry : latest.deltas().entrySet()) {
            if (!newLatest.containsKey(entry.getKey()) && entry.getValue() instanceof TileEntityDelta.Put put) {
                deltas.put(entry.getKey(), put);
            }
        }
        if (deltas.isEmpty()) {
            return DeltaInsertionResult.rejected(DeltaInsertionResult.Status.NO_CHANGES_MADE);
        }

        reverseDeltas.remove(0);
        // The delta carries the timestamp of the state it reconstructs (the
        // previous latest), not the new snapshot's timestamp.
        reverseDeltas.add(0, new TileEntityListDelta(latest.timestamp(), deltas));
        reverseDeltas.add(0, new TileEntityListDelta(timestamp, newLatest));
        return DeltaInsertionResult.success(deltas.size());
    }

    public long findNearestTimestamp(long timestamp) {
        long closest = timestamp;
        boolean found = false;
        long minDistance = 0;
        for (TileEntityListDelta delta : reverseDeltas) {
            long distance = Math.abs(delta.timestamp() - timestamp);
            if (!found || distance < minDistance) {
                found = true;
                minDistance = distance;
                closest = delta.timestamp();
            }
        }
        return closest;
    }

    /**
     * Tail truncation for pruning: drops every delta strictly older than
     * {@code minTimestamp}. The head (latest) is never removed.
     *
     * @return number of entries removed
     */
    public int truncateOlderThan(long minTimestamp) {
        int removed = 0;
        for (int i = reverseDeltas.size() - 1; i >= 1; i--) {
            if (reverseDeltas.get(i).timestamp() < minTimestamp) {
                reverseDeltas.remove(i);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Inserts a full TE-list snapshot as a mid-chain checkpoint at {@code index}
     * (0 = newest), carrying the timestamp of the entry currently at that index.
     * Readers reconstruct identically whether they encounter a delta or a full
     * snapshot. Requires a non-empty chain and {@code index < size}.
     */
    public void insertCheckpoint(int index) {
        if (reverseDeltas.isEmpty()) {
            throw new IllegalStateException("Cannot checkpoint an empty chain");
        }
        if (index < 0 || index >= reverseDeltas.size()) {
            throw new IndexOutOfBoundsException("Checkpoint index " + index
                    + " out of bounds for chain of size " + reverseDeltas.size());
        }
        if (index == 0) {
            return; // head is already a full snapshot
        }
        long timestamp = reverseDeltas.get(index).timestamp();
        Map<TileEntityPosition, TileEntity> state = snapshotBefore(timestamp)
                .orElseThrow(() -> new IllegalStateException("Chain unexpectedly empty"));
        Map<TileEntityPosition, TileEntityDelta> full = new LinkedHashMap<>();
        for (var entry : state.entrySet()) {
            full.put(entry.getKey(), new TileEntityDelta.Put(entry.getValue()));
        }
        reverseDeltas.add(index, new TileEntityListDelta(timestamp, full));
    }
}

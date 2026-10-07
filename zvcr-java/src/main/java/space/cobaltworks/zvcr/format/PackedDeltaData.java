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
package space.cobaltworks.zvcr.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import space.cobaltworks.zvcr.Zvcr;

/**
 * Reverse-delta chain for one section (block or biome). Mirrors the C++
 * {@code PackedDeltaData<unpackedSize>}.
 *
 * <p>Entry 0 is the <em>latest</em> snapshot and always holds full data. Every
 * later entry is a reverse delta: at each changed atom position it stores the
 * <em>previous</em> value; unchanged positions store {@code 0xFFFF}
 * ({@link Zvcr#STATE_UNCHANGED}). A delta entry's timestamp is the timestamp of
 * the state it reconstructs (the previous snapshot's timestamp).
 *
 * <p>Reconstruction of the state before timestamp T: unpack the latest, then
 * apply every following entry in order (overwriting all atoms ≠ 0xFFFF),
 * stopping once the entry's timestamp ≤ T.
 *
 * <p><strong>Checkpoint trick:</strong> a full snapshot inserted mid-chain is
 * indistinguishable from a delta to readers and reconstructs correctly, because
 * a non-empty delta always has ≥ 2 unique values (0xFFFF + previous values)
 * while a full snapshot may be single-value. Checkpointing = periodically
 * materialize the state at some chain index and insert a full snapshot there,
 * bounding reconstruction cost to O(checkpoint gap).
 */
public final class PackedDeltaData {

    private final int unpackedSize;
    private final List<PackedSnapshot> reverseDeltas;
    /**
     * Cached count of consecutive non-full entries after the head (index 1..).
     * {@code null} = unknown; computed lazily and maintained incrementally by
     * the mutators so the hot insert path never re-derives it by unpacking.
     */
    private Integer deltasSinceFullCache;

    public PackedDeltaData(int unpackedSize) {
        if (unpackedSize <= 0) {
            throw new IllegalArgumentException("unpackedSize must be positive");
        }
        this.unpackedSize = unpackedSize;
        this.reverseDeltas = new ArrayList<>();
    }

    public PackedDeltaData(int unpackedSize, List<PackedSnapshot> reverseDeltas) {
        if (unpackedSize <= 0) {
            throw new IllegalArgumentException("unpackedSize must be positive");
        }
        this.unpackedSize = unpackedSize;
        this.reverseDeltas = new ArrayList<>(reverseDeltas);
    }

    public int unpackedSize() {
        return unpackedSize;
    }

    /** Number of entries in the chain (0 = empty, 1 = head only). */
    public int size() {
        return reverseDeltas.size();
    }

    /** Appends a snapshot to the tail without delta conversion (deserializer use). */
    public void appendSnapshot(PackedSnapshot snapshot) {
        reverseDeltas.add(snapshot);
        deltasSinceFullCache = null;
    }

    /** Read-only view of the chain, newest first. */
    public List<PackedSnapshot> reverseDeltas() {
        return List.copyOf(reverseDeltas);
    }

    /**
     * A full snapshot: single uniform value, or no {@code 0xFFFF} marker after
     * unpacking. Deltas are never full unless every atom changed; the head is
     * always full by construction.
     */
    public boolean isFull(PackedSnapshot snapshot) {
        if (snapshot.data() instanceof PackedData.SingleValue) {
            return true;
        }
        int[] atoms = snapshot.data().unpack(unpackedSize);
        for (int atom : atoms) {
            if (atom == Zvcr.STATE_UNCHANGED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Number of consecutive non-full entries after the head — the distance to
     * the newest full snapshot behind it, i.e. the value the checkpoint policy
     * compares against {@code checkpointInterval}. O(chain) on first call per
     * chain (and after deserialization), O(1) on subsequent inserts.
     */
    public int deltasSinceFull() {
        Integer cached = deltasSinceFullCache;
        if (cached != null) {
            return cached;
        }
        int count = 0;
        for (int i = 1; i < reverseDeltas.size(); i++) {
            if (isFull(reverseDeltas.get(i))) {
                break;
            }
            count++;
        }
        deltasSinceFullCache = count;
        return count;
    }

    public Optional<PackedSnapshot> latestSnapshot() {
        return delta(0);
    }

    public Optional<PackedSnapshot> delta(int deltaIndex) {
        if (deltaIndex < 0 || deltaIndex >= reverseDeltas.size()) {
            return Optional.empty();
        }
        return Optional.of(reverseDeltas.get(deltaIndex));
    }

    /**
     * State at (or closest before) {@code timestamp}: snaps to the entry whose
     * timestamp is nearest the query, then reconstructs before it.
     */
    public Optional<int[]> snapshotFrom(long timestamp) {
        return snapshotBefore(findNearestTimestamp(timestamp));
    }

    /**
     * Reconstructs the newest state whose timestamp is ≤ {@code timestamp}.
     * Returns empty if the chain is empty.
     */
    public Optional<int[]> snapshotBefore(long timestamp) {
        Optional<PackedSnapshot> latestOpt = latestSnapshot();
        if (latestOpt.isEmpty()) {
            return Optional.empty();
        }

        PackedSnapshot latest = latestOpt.get();
        int[] current = latest.data().unpack(unpackedSize);
        if (timestamp >= latest.timestamp()) {
            return Optional.of(current);
        }

        boolean first = true;
        for (PackedSnapshot entry : reverseDeltas) {
            if (first) {
                first = false;
                continue;
            }
            int[] unpacked = entry.data().unpack(unpackedSize);
            for (int i = 0; i < unpackedSize; i++) {
                if (unpacked[i] != Zvcr.STATE_UNCHANGED) {
                    current[i] = unpacked[i];
                }
            }
            if (timestamp >= entry.timestamp()) {
                break;
            }
        }
        return Optional.of(current);
    }

    /**
     * Inserts a new snapshot at the head of the chain, converting the previous
     * latest into a reverse delta. Replicates the C++ {@code insertSnapshot}.
     *
     * <ul>
     *   <li>Empty chain: the snapshot becomes the initial full snapshot and the
     *       result is {@code unpackedSize} (matching the C++ return value).</li>
     *   <li>{@code timestamp <= latest.timestamp}: rejected
     *       ({@link Status#SNAPSHOT_OLDER_THAN_LATEST}).</li>
     *   <li>No atom differs from the latest: rejected
     *       ({@link Status#NO_CHANGES_MADE}) — a zero-change delta is never
     *       stored.</li>
     * </ul>
     */
    public DeltaInsertionResult insertSnapshot(PackedSnapshot newSnapshot) {
        if (newSnapshot.data() == null) {
            throw new IllegalArgumentException("newSnapshot.data must not be null");
        }
        Optional<PackedSnapshot> latestOpt = latestSnapshot();
        if (latestOpt.isEmpty()) {
            reverseDeltas.add(newSnapshot);
            return DeltaInsertionResult.success(unpackedSize);
        }

        PackedSnapshot latest = latestOpt.get();
        if (newSnapshot.timestamp() <= latest.timestamp()) {
            return DeltaInsertionResult.rejected(DeltaInsertionResult.Status.SNAPSHOT_OLDER_THAN_LATEST);
        }

        int[] previousUnpacked = latest.data().unpack(unpackedSize);
        int[] newUnpacked = newSnapshot.data().unpack(unpackedSize);

        int[] deltaBuilder = new int[unpackedSize];
        int changes = 0;
        boolean deltaHasMarker = false;
        for (int i = 0; i < unpackedSize; i++) {
            boolean changed = newUnpacked[i] != previousUnpacked[i];
            int value = changed ? previousUnpacked[i] : Zvcr.STATE_UNCHANGED;
            deltaBuilder[i] = value;
            if (value == Zvcr.STATE_UNCHANGED) {
                deltaHasMarker = true;
            }
            if (changed) changes++;
        }
        if (changes == 0) {
            return DeltaInsertionResult.rejected(DeltaInsertionResult.Status.NO_CHANGES_MADE);
        }

        // The delta stores the timestamp of the state it reconstructs (the
        // previous latest's timestamp), not the new snapshot's timestamp.
        PackedData deltaData = PackedData.pack(deltaBuilder);
        // Fullness of the new index-1 entry, derived from the builder without
        // re-unpacking: it has a marker iff some position was unchanged (or a
        // degenerate previous value was 0xFFFF). Matches {@link #isFull}.
        boolean newDeltaFull = deltaData instanceof PackedData.SingleValue || !deltaHasMarker;
        PackedSnapshot deltaSnapshot = new PackedSnapshot(deltaData, latest.timestamp());

        reverseDeltas.remove(0);
        reverseDeltas.add(0, deltaSnapshot);
        reverseDeltas.add(0, newSnapshot);
        if (deltasSinceFullCache != null && !newDeltaFull) {
            deltasSinceFullCache++;
        }
        return DeltaInsertionResult.success(changes);
    }

    /**
     * Inserts a full snapshot as a mid-chain checkpoint at {@code index}
     * (0 = newest). The materialized state at that index is packed as a full
     * snapshot carrying the timestamp of the entry currently at {@code index};
     * readers reconstruct identically whether they encounter a delta or a full
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
        int[] state = snapshotBefore(timestamp)
                .orElseThrow(() -> new IllegalStateException("Chain unexpectedly empty"));
        PackedData packed = PackedData.pack(state);
        reverseDeltas.add(index, new PackedSnapshot(packed, timestamp));
        if (deltasSinceFullCache != null) {
            if (packed instanceof PackedData.SingleValue || !containsMarker(state)) {
                // The inserted entry is full: the run of non-full entries after
                // the head now ends at index-1 (or earlier, if it already did).
                deltasSinceFullCache = Math.min(deltasSinceFullCache, index - 1);
            } else {
                deltasSinceFullCache = null; // degenerate (0xFFFF in data) — re-derive
            }
        }
    }

    private static boolean containsMarker(int[] atoms) {
        for (int atom : atoms) {
            if (atom == Zvcr.STATE_UNCHANGED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tail truncation for pruning: drops every entry strictly older than
     * {@code minTimestamp}. Safe by construction for reverse-delta chains (each
     * delta only depends on newer snapshots). The head (latest) is never removed.
     *
     * @return number of entries removed
     */
    public int truncateOlderThan(long minTimestamp) {
        if (reverseDeltas.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (int i = reverseDeltas.size() - 1; i >= 1; i--) {
            if (reverseDeltas.get(i).timestamp() < minTimestamp) {
                reverseDeltas.remove(i);
                removed++;
            }
        }
        if (removed > 0 && deltasSinceFullCache != null) {
            // Truncation removes a suffix (timestamps are newest-first), so the
            // leading non-full run can only shrink to the new chain length.
            deltasSinceFullCache = Math.min(deltasSinceFullCache, reverseDeltas.size() - 1);
        }
        return removed;
    }

    /**
     * Timestamp of the entry nearest (smallest absolute distance) to
     * {@code timestamp}; ties resolve to the first (newest) entry, matching the
     * C++ {@code findNearestTimestamp}.
     */
    public long findNearestTimestamp(long timestamp) {
        long closest = timestamp;
        boolean found = false;
        long minDistance = 0;
        for (PackedSnapshot entry : reverseDeltas) {
            long candidate = entry.timestamp();
            long distance = Math.abs(candidate - timestamp);
            if (!found || distance < minDistance) {
                found = true;
                minDistance = distance;
                closest = candidate;
            }
        }
        return closest;
    }
}

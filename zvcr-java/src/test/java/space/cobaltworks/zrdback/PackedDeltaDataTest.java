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
package space.cobaltworks.zrdback.zvcr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

import space.cobaltworks.zrdback.zvcr.format.DeltaInsertionResult;
import space.cobaltworks.zrdback.zvcr.format.PackedData;
import space.cobaltworks.zrdback.zvcr.format.PackedDeltaData;
import space.cobaltworks.zrdback.zvcr.format.PackedSnapshot;

class PackedDeltaDataTest {

    private static final int SIZE = Zvcr.SECTION_SIZE_BIOMES; // 64 — small and fast

    private static int[] uniform(int value) {
        int[] out = new int[SIZE];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static int[] with(int[] base, int index, int value) {
        int[] out = base.clone();
        out[index] = value;
        return out;
    }

    private static PackedSnapshot snapshot(int[] data, long timestamp) {
        return new PackedSnapshot(PackedData.pack(data), timestamp);
    }

    @Test
    void initialInsertReturnsUnpackedSize() {
        PackedDeltaData chain = new PackedDeltaData(SIZE);
        DeltaInsertionResult result = chain.insertSnapshot(snapshot(uniform(7), 1000));
        assertEquals(SIZE, assertInstanceOf(DeltaInsertionResult.Success.class, result).changes());
        assertEquals(1, chain.reverseDeltas().size());
    }

    @Test
    void insertBuildsReverseDeltaWithPreviousTimestamp() {
        PackedDeltaData chain = new PackedDeltaData(SIZE);
        chain.insertSnapshot(snapshot(uniform(7), 1000));

        int[] newer = with(uniform(7), 3, 9);
        DeltaInsertionResult result = chain.insertSnapshot(snapshot(newer, 2000));
        assertEquals(1, assertInstanceOf(DeltaInsertionResult.Success.class, result).changes());

        List<PackedSnapshot> deltas = chain.reverseDeltas();
        assertEquals(2, deltas.size());
        // head = new full snapshot at t=2000; second = delta reconstructing t=1000
        assertEquals(2000, deltas.get(0).timestamp());
        assertEquals(1000, deltas.get(1).timestamp());
        assertArrayEquals(newer, deltas.get(0).data().unpack(SIZE));

        int[] deltaUnpacked = deltas.get(1).data().unpack(SIZE);
        assertEquals(7, deltaUnpacked[3]); // previous value at the changed position
        for (int i = 0; i < SIZE; i++) {
            if (i != 3) assertEquals(Zvcr.STATE_UNCHANGED, deltaUnpacked[i]);
        }
    }

    @Test
    void reconstructBeforeEachTimestamp() {
        PackedDeltaData chain = new PackedDeltaData(SIZE);
        int[] s0 = uniform(1);
        int[] s1 = with(s0, 0, 2);
        int[] s2 = with(s1, 1, 3);
        int[] s3 = with(s2, 2, 4);

        chain.insertSnapshot(snapshot(s0, 1000));
        chain.insertSnapshot(snapshot(s1, 2000));
        chain.insertSnapshot(snapshot(s2, 3000));
        chain.insertSnapshot(snapshot(s3, 4000));
        assertEquals(4, chain.reverseDeltas().size());

        assertArrayEquals(s3, chain.snapshotBefore(4000).orElseThrow());
        assertArrayEquals(s3, chain.snapshotBefore(9999).orElseThrow());
        assertArrayEquals(s2, chain.snapshotBefore(3000).orElseThrow());
        assertArrayEquals(s2, chain.snapshotBefore(3500).orElseThrow());
        assertArrayEquals(s1, chain.snapshotBefore(2000).orElseThrow());
        assertArrayEquals(s0, chain.snapshotBefore(1000).orElseThrow());
        // before the oldest known state: clamps to the oldest entry
        assertArrayEquals(s0, chain.snapshotBefore(500).orElseThrow());
    }

    @Test
    void snapshotFromSnapsToNearestTimestamp() {
        PackedDeltaData chain = new PackedDeltaData(SIZE);
        int[] s0 = uniform(1);
        int[] s1 = with(s0, 0, 2);
        chain.insertSnapshot(snapshot(s0, 1000));
        chain.insertSnapshot(snapshot(s1, 2000));

        assertArrayEquals(s1, chain.snapshotFrom(1900).orElseThrow()); // nearest = 2000
        assertArrayEquals(s0, chain.snapshotFrom(1100).orElseThrow()); // nearest = 1000
        // exact tie between 1000 and 2000 resolves to the first (newest) entry
        assertArrayEquals(s1, chain.snapshotFrom(1500).orElseThrow());
    }

    @Test
    void noChangesMadeIsRejected() {
        PackedDeltaData chain = new PackedDeltaData(SIZE);
        chain.insertSnapshot(snapshot(uniform(7), 1000));
        DeltaInsertionResult result = chain.insertSnapshot(snapshot(uniform(7), 2000));
        assertEquals(DeltaInsertionResult.Status.NO_CHANGES_MADE,
                assertInstanceOf(DeltaInsertionResult.Rejected.class, result).status());
        assertEquals(1, chain.reverseDeltas().size());
    }

    @Test
    void olderThanLatestIsRejected() {
        PackedDeltaData chain = new PackedDeltaData(SIZE);
        chain.insertSnapshot(snapshot(uniform(7), 2000));
        DeltaInsertionResult result = chain.insertSnapshot(snapshot(with(uniform(7), 0, 1), 1000));
        assertEquals(DeltaInsertionResult.Status.SNAPSHOT_OLDER_THAN_LATEST,
                assertInstanceOf(DeltaInsertionResult.Rejected.class, result).status());
        // equal timestamp is also rejected
        result = chain.insertSnapshot(snapshot(with(uniform(7), 0, 1), 2000));
        assertEquals(DeltaInsertionResult.Status.SNAPSHOT_OLDER_THAN_LATEST,
                assertInstanceOf(DeltaInsertionResult.Rejected.class, result).status());
    }

    @Test
    void midChainCheckpointIsInvisibleToReaders() {
        PackedDeltaData chain = new PackedDeltaData(SIZE);
        int[] state = uniform(1);
        long t = 1000;
        long[] timestamps = new long[10];
        int[][] states = new int[10][];
        for (int i = 0; i < 10; i++) {
            states[i] = state = with(state, i, 10 + i);
            timestamps[i] = t;
            chain.insertSnapshot(snapshot(state, t));
            t += 1000;
        }

        // capture reconstructions before the checkpoint
        int[] beforeAt5000 = chain.snapshotBefore(5000).orElseThrow();
        int[] beforeAt2500 = chain.snapshotBefore(2500).orElseThrow();

        // materialize a full snapshot at index 5 (timestamp 6000)
        chain.insertCheckpoint(5);
        assertEquals(11, chain.reverseDeltas().size());

        // all reconstructions must be identical afterwards
        assertArrayEquals(beforeAt5000, chain.snapshotBefore(5000).orElseThrow());
        assertArrayEquals(beforeAt2500, chain.snapshotBefore(2500).orElseThrow());
        assertArrayEquals(states[9], chain.snapshotBefore(10000).orElseThrow());
        assertArrayEquals(states[8], chain.snapshotBefore(9999).orElseThrow());
        assertArrayEquals(states[0], chain.snapshotBefore(1000).orElseThrow());
        assertArrayEquals(states[5], chain.snapshotBefore(6000).orElseThrow());
    }

    @Test
    void truncateOlderThanKeepsHead() {
        PackedDeltaData chain = new PackedDeltaData(SIZE);
        int[] state = uniform(1);
        chain.insertSnapshot(snapshot(state, 1000));
        for (int i = 1; i < 6; i++) {
            state = with(state, i, 10 + i);
            chain.insertSnapshot(snapshot(state, 1000L + i * 1000));
        }
        assertEquals(6, chain.reverseDeltas().size());

        int removed = chain.truncateOlderThan(3000); // drop entries with t < 3000
        assertEquals(2, removed); // t=1000 and t=2000
        List<PackedSnapshot> deltas = chain.reverseDeltas();
        assertEquals(4, deltas.size());
        assertEquals(6000, deltas.get(0).timestamp());
        assertEquals(3000, deltas.get(3).timestamp());

        // head state still reconstructs; old timestamps clamp to oldest kept state
        assertArrayEquals(state, chain.snapshotBefore(9999).orElseThrow());
        assertArrayEquals(chain.snapshotBefore(3000).orElseThrow(),
                chain.snapshotBefore(1000).orElseThrow());
    }

    @Test
    void propertyReconstructionEqualityUnderRandomEdits() {
        RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
        for (int trial = 0; trial < 30; trial++) {
            PackedDeltaData chain = new PackedDeltaData(SIZE);
            int[] state = new int[SIZE];
            for (int i = 0; i < SIZE; i++) state[i] = rng.nextInt(20);
            long t = 1000;
            chain.insertSnapshot(snapshot(state, t));

            int steps = 5 + rng.nextInt(20);
            int[][] states = new int[steps + 1][];
            long[] stamps = new long[steps + 1];
            states[0] = state.clone();
            stamps[0] = t;

            for (int step = 1; step <= steps; step++) {
                int mutations = 1 + rng.nextInt(10);
                int[] next = state.clone();
                for (int m = 0; m < mutations; m++) {
                    next[rng.nextInt(SIZE)] = rng.nextInt(20);
                }
                t += 1 + rng.nextInt(5000);
                var result = chain.insertSnapshot(snapshot(next, t));
                if (result instanceof DeltaInsertionResult.Success) {
                    state = next;
                    states[step] = next.clone();
                    stamps[step] = t;
                } else {
                    states[step] = state.clone(); // rejected: state unchanged
                    stamps[step] = stamps[step - 1];
                }
            }

            // reconstruct at every accepted timestamp and compare
            for (int step = 0; step <= steps; step++) {
                if (states[step] == null) continue;
                int[] expected = states[step];
                // snapshotBefore(T) returns the newest state with timestamp <= T
                long query = stamps[step];
                int expectedStep = step;
                while (expectedStep > 0 && stamps[expectedStep - 1] == query) expectedStep--;
                assertArrayEquals(states[expectedStep],
                        chain.snapshotBefore(query).orElseThrow(),
                        "trial " + trial + " step " + step);
            }
        }
    }

    @Test
    void emptyChainBehaviors() {
        PackedDeltaData chain = new PackedDeltaData(SIZE);
        assertTrue(chain.snapshotBefore(1000).isEmpty());
        assertTrue(chain.latestSnapshot().isEmpty());
        assertEquals(0, chain.truncateOlderThan(0));
        assertThrows(IllegalStateException.class, () -> chain.insertCheckpoint(0));
    }
}

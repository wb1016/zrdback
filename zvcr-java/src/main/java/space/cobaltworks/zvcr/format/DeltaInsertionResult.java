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

/**
 * Result of inserting a snapshot into a delta chain. Mirrors the C++
 * {@code DeltaInsertionResult} ({@code Result<size_t, DeltaInsertionStatus>}).
 *
 * <p>{@link Status#NO_CHANGES_MADE} is a common, expected outcome (e.g. chunks
 * whose region-file timestamp advanced without content change) and must be
 * handled cheaply by callers — hence a result type instead of an exception.
 */
public sealed interface DeltaInsertionResult {

    /** Number of changed atoms (or list entries) the insertion produced. */
    record Success(int changes) implements DeltaInsertionResult {}

    enum Status {
        SNAPSHOT_OLDER_THAN_LATEST,
        NO_CHANGES_MADE
    }

    record Rejected(Status status) implements DeltaInsertionResult {}

    static DeltaInsertionResult success(int changes) {
        return new Success(changes);
    }

    static DeltaInsertionResult rejected(Status status) {
        return new Rejected(status);
    }

    /** Changes count if successful, otherwise {@code -1}. */
    default int changesOrMinusOne() {
        return this instanceof Success s ? s.changes() : -1;
    }
}

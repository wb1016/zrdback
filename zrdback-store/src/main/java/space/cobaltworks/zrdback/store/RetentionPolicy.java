/*
 * ZRDBack: Zstd Reverse Delta Backup
 * Copyright (C) 2026 CobaltWorks
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
 * by crayne are licensed under the GNU Lesser General Public License v3.
 */
package space.cobaltworks.zrdback.store;

/**
 * How much history a prune keeps.
 *
 * <p>Two independent limits:
 * <ul>
 *   <li>{@code days} - drop everything older than that many days.</li>
 *   <li>{@code snapshots} - keep at least that many restore points, no matter
 *       how old they are.</li>
 * </ul>
 *
 * <p>A limit below 1 is <em>ignored</em> rather than rejected: {@code 0} and
 * negative values disable that constraint. A policy with both limits disabled
 * keeps everything ({@link #isUnbounded()}).
 *
 * <p>Both limits apply at once, and the stricter one wins: keeping the last
 * five snapshots can delete entries that are only a day old, and a seven-day
 * age limit can delete snapshots that are among the last five.
 */
public record RetentionPolicy(int days, int snapshots) {

    /** Keeps everything. */
    public static final RetentionPolicy UNBOUNDED = new RetentionPolicy(0, 0);

    /** A policy that only limits the age of the history. */
    public static RetentionPolicy ofDays(int days) {
        return new RetentionPolicy(days, 0);
    }

    /**
     * A policy that only limits the number of restore points.
     */
    public static RetentionPolicy ofSnapshots(int snapshots) {
        return new RetentionPolicy(0, snapshots);
    }

    /** True when the age limit applies. */
    public boolean hasAgeLimit() {
        return days >= 1;
    }

    /** True when the snapshot-count limit applies. */
    public boolean hasSnapshotLimit() {
        return snapshots >= 1;
    }

    /** True when neither limit applies: nothing may be pruned. */
    public boolean isUnbounded() {
        return !hasAgeLimit() && !hasSnapshotLimit();
    }

    /** A short human-readable description of the active limits. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(hasAgeLimit() ? "older than " + days + " day(s)" : "no age limit");
        sb.append(hasSnapshotLimit()
                ? ", keep the history of the last " + snapshots + " snapshot(s)" : "");
        return sb.toString();
    }
}

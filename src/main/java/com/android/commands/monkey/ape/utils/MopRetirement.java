package com.android.commands.monkey.ape.utils;

import java.util.Arrays;

/**
 * The run's target retirement under {@code ape.mopScoring=distance} (mop-guidance "Target
 * Retirement", design D19, API Design "Part B: {@code MopRetirement}", INV-MOP-37).
 *
 * <p>One instance per run, built by {@link MopContext#fromSpec} when {@code StatefulAgent} builds
 * the run's context and held by that {@link MopContext}, sized by the artifact's
 * {@link MopData#targetCount()}. It is null when no artifact is loaded or the mode is {@code flag}:
 * under {@code flag} no counter exists and no target is retired.
 *
 * <p>One counter per target, {@code int[targets]}. Counters change only through
 * {@link #onFirstInteraction}, called from {@code StatefulAgent.moveForward} before the coverage
 * tracker records the selected action, when this is the first interaction of the action's widget in
 * its activity; they never decrease, so a retired target stays retired for the rest of the run. A
 * target whose counter is at least {@code ape.mopRetireAfter} is retired: it no longer counts toward
 * an action's distance, the prompt marker or the launcher order.
 */
public final class MopRetirement {

    private final int[] counters;
    private final int retireAfter;
    /** Targets whose counter reached {@code retireAfter} through {@link #onFirstInteraction}. */
    private int retiredCount;

    /**
     * @param targets     the artifact's target count ({@link MopData#targetCount()}); every pair's
     *                    index lies in {@code [0, targets)}
     * @param retireAfter {@code ape.mopRetireAfter}: the count at which a target retires
     * @throws IllegalArgumentException when {@code targets} is negative
     */
    public MopRetirement(int targets, int retireAfter) {
        if (targets < 0) {
            throw new IllegalArgumentException("negative target count " + targets);
        }
        this.counters = new int[targets];
        this.retireAfter = retireAfter;
    }

    /**
     * Counts one first interaction against the targets of the selected action's weight's lookup.
     *
     * <p>Adds one to the counter of each target index of {@code weightLookup.dist}, each index once
     * even if it appears twice — a target already retired included, which changes nothing it reads
     * — and returns, ascending, the indices whose counter reached {@code retireAfter} on this call
     * (empty when none did). A target is therefore returned at most once per run. The caller passes
     * a non-empty result to {@code EventSink.mopRetired} for the step's {@code dec.ret} (design
     * D23).
     *
     * @param weightLookup among the selected action's containment lookups
     *                     ({@link MopScorer#containmentLookups}), the one holding its smallest
     *                     unretired distance, ties going to the earlier node in containment order
     *                     (design D19; {@link MopScorer.Scored#lookup}). The caller does not call
     *                     when no containment lookup has an unretired pair.
     */
    public int[] onFirstInteraction(MopData.Lookup weightLookup) {
        int[][] dist = weightLookup.dist;
        int[] indices = new int[dist.length];
        for (int k = 0; k < dist.length; k++) {
            indices[k] = dist[k][0];
        }
        Arrays.sort(indices);
        int[] reached = new int[indices.length];
        int n = 0;
        for (int k = 0; k < indices.length; k++) {
            int i = indices[k];
            if (k > 0 && indices[k - 1] == i) {
                continue; // each index once
            }
            int before = counters[i]++;
            if (before < retireAfter && counters[i] >= retireAfter) {
                reached[n++] = i;
                retiredCount++;
            }
        }
        return Arrays.copyOf(reached, n);
    }

    /** True when target {@code i}'s counter is at least {@code retireAfter}. */
    public boolean retired(int i) {
        return counters[i] >= retireAfter;
    }

    /**
     * The smallest {@code d} over the pairs {@code [i, d]} of {@code dist} whose target is not
     * retired and whose {@code d <= dmax}, or {@link Integer#MAX_VALUE} when there is none.
     * Read by the distance weight (D18), the marker (D22) and the launcher order (D20, with
     * {@code dmax} = {@code ape.mopLauncherDmax}).
     */
    public int nearest(int[][] dist, int dmax) {
        return nearest(this, dist, dmax);
    }

    /**
     * {@link #nearest(int[][], int)} over a retirement that may be null: a null retirement retires
     * nothing, so every pair counts. The one walk over a lookup's pairs every distance reader shares.
     */
    public static int nearest(MopRetirement retirement, int[][] dist, int dmax) {
        int best = Integer.MAX_VALUE;
        for (int[] pair : dist) {
            int d = pair[1];
            if (d <= dmax && d < best && (retirement == null || !retirement.retired(pair[0]))) {
                best = d;
            }
        }
        return best;
    }

    /** The number of targets retired so far in the run (the {@code RUN_END} count, task 16.2). */
    public int retiredCount() {
        return retiredCount;
    }

    /** Target {@code i}'s counter; for tests. */
    int count(int i) {
        return counters[i];
    }
}

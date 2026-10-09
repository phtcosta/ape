package com.android.commands.monkey.ape.utils;

/**
 * The run's target retirement under {@code ape.mopScoring=distance} (mop-guidance "Target
 * Retirement", design D19, API Design "Part B: {@code MopRetirement}", INV-MOP-37).
 *
 * <p>One instance per run, owned by {@code StatefulAgent} and held by the run's {@link MopContext},
 * sized by the artifact's {@link MopData#targetCount()}. It is null when no artifact is loaded or
 * the mode is {@code flag}: under {@code flag} no counter exists and no target is retired.
 *
 * <p>One counter per target, {@code int[targets]}. Counters change only through
 * {@link #onFirstInteraction}, called from {@code StatefulAgent.moveForward} before the coverage
 * tracker records the selected action, when this is the first interaction of the action's widget in
 * its activity; they never decrease, so a retired target stays retired for the rest of the run. A
 * target whose counter is at least {@code ape.mopRetireAfter} is retired: it no longer counts toward
 * an action's distance, the prompt marker or the launcher order.
 *
 * <p><b>Skeleton.</b> This class states the contract the next wave codes against (task 13.6
 * implements it; tasks 13.3/13.4 read it from the distance mode). Every body throws
 * {@link UnsupportedOperationException} until then, and nothing in production constructs it:
 * {@link MopContext#fromSpec} passes null in both modes for now.
 */
public final class MopRetirement {

    /**
     * @param targets     the artifact's target count ({@link MopData#targetCount()}); every pair's
     *                    index lies in {@code [0, targets)}
     * @param retireAfter {@code ape.mopRetireAfter}: the count at which a target retires
     */
    public MopRetirement(int targets, int retireAfter) {
        throw new UnsupportedOperationException("task 13.6");
    }

    /**
     * Counts one first interaction against the targets of the selected action's weight's lookup.
     *
     * <p>Adds one to the counter of each target index of {@code weightLookup.dist}, each index once
     * even if it appears twice, and returns the indices whose counter reached
     * {@code retireAfter} on this call (empty when none did). The caller passes a non-empty result
     * to {@code EventSink.mopRetired} for the step's {@code dec.ret} (design D23).
     *
     * @param weightLookup among the selected action's containment lookups
     *                     ({@link MopScorer#containmentLookups}), the one holding its smallest
     *                     unretired distance, ties going to the earlier node in containment order
     *                     (design D19). The caller does not call when no containment lookup has an
     *                     unretired pair.
     */
    public int[] onFirstInteraction(MopData.Lookup weightLookup) {
        throw new UnsupportedOperationException("task 13.6");
    }

    /** True when target {@code i}'s counter is at least {@code retireAfter}. */
    public boolean retired(int i) {
        throw new UnsupportedOperationException("task 13.6");
    }

    /**
     * The smallest {@code d} over the pairs {@code [i, d]} of {@code dist} whose target is not
     * retired and whose {@code d <= dmax}, or {@link Integer#MAX_VALUE} when there is none.
     * Read by the distance weight (D18), the marker (D22) and the launcher order (D20, with
     * {@code dmax} = {@code ape.mopLauncherDmax}).
     */
    public int nearest(int[][] dist, int dmax) {
        throw new UnsupportedOperationException("task 13.6");
    }
}

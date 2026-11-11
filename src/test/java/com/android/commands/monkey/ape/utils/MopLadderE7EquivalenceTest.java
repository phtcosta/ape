package com.android.commands.monkey.ape.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The differential test of run-spec "Default Plan Equivalence With the E7 Jar" and mop-guidance
 * scenario "Default ladder equals the E7 jar" (design D10): at the default ladder, the ladder-read
 * {@link MopScorer#distanceWeight}, {@link MopScorer#splitTies} and
 * {@link MopScorer#nextLevelWeight} return what the E7 jar {@code bc4bc1b8} returned.
 *
 * <p>The {@code frozen*} methods are the {@code bc4bc1b8} bodies verbatim, with the context's
 * {@code weightD1..3} replaced by the literals 500, 400 and 300 and the scoring-mode test dropped
 * (the context here is always {@code distance}). They must never be edited: they are the E7
 * reference. The test prints one {@code [E7-EQUIV]} line with the input counts, which the delivery
 * note quotes.
 */
public class MopLadderE7EquivalenceTest {

    private static final int MAX_D = 10;
    private static final int MAX_TIES = 20;

    // -------------------------------------------------------------------------
    // Frozen bc4bc1b8 functions (MopScorer.java at bc4bc1b8, lines 179-248)
    // -------------------------------------------------------------------------

    private static int frozenDistanceWeight(int d) {
        if (d <= 1) {
            return 500;
        }
        if (d == 2) {
            return 400;
        }
        if (d == 3) {
            return 300;
        }
        return 0;
    }

    private static int[] frozenSplitTies(int[] weights) {
        int[] boosts = weights.clone();
        int best = 0;
        for (int w : weights) {
            best = Math.max(best, w);
        }
        if (best <= 0) {
            return boosts;
        }
        int tied = 0;
        for (int w : weights) {
            if (w == best) {
                tied++;
            }
        }
        if (tied <= 1) {
            return boosts;
        }
        int share = Math.max(best / tied, frozenNextLevelWeight(best));
        for (int k = 0; k < boosts.length; k++) {
            if (weights[k] == best) {
                boosts[k] = share;
            }
        }
        return boosts;
    }

    private static int frozenNextLevelWeight(int levelWeight) {
        for (int d = 1; d <= 3; d++) {
            if (frozenDistanceWeight(d) == levelWeight) {
                return frozenDistanceWeight(d + 1);
            }
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // The comparison
    // -------------------------------------------------------------------------

    /** The context of a {@code distance} run whose plan does not state {@code ape.mopWeights}. */
    private static MopContext defaultLadder() {
        MopContext mop = MopContext.fromSpec(MopData.forTest(null, null), StaticGuiModel.EMPTY,
                TestRunSpecs.mopSpec("ape.mopScoring", "distance").mop());
        assertEquals(MopScoring.DISTANCE, mop.scoring());
        return mop;
    }

    /** Every distance the inputs use: {@code 0..10} and {@link Integer#MAX_VALUE} (no pair). */
    private static int[] distances() {
        int[] distances = new int[MAX_D + 2];
        for (int d = 0; d <= MAX_D; d++) {
            distances[d] = d;
        }
        distances[MAX_D + 1] = Integer.MAX_VALUE;
        return distances;
    }

    /**
     * The tie-split inputs as distance arrays: for every best distance {@code d} in {@code 0..10}
     * and every tie size {@code n} in {@code 1..20}, {@code n} actions at {@code d}, alone, and
     * together with one action at each worse distance up to 10 and one with no pair.
     */
    private static List<int[]> tieInputs() {
        List<int[]> inputs = new ArrayList<>();
        for (int d = 0; d <= MAX_D; d++) {
            for (int n = 1; n <= MAX_TIES; n++) {
                int[] alone = new int[n];
                Arrays.fill(alone, d);
                inputs.add(alone);

                int[] mixed = new int[n + (MAX_D - d) + 1];
                Arrays.fill(mixed, 0, n, d);
                int k = n;
                for (int worse = d + 1; worse <= MAX_D; worse++) {
                    mixed[k++] = worse;
                }
                mixed[k] = Integer.MAX_VALUE;
                inputs.add(mixed);
            }
        }
        return inputs;
    }

    @Test
    public void theDefaultLadderEqualsTheE7Functions() {
        MopContext mop = defaultLadder();
        List<String> mismatches = new ArrayList<>();

        int[] distances = distances();
        for (int d : distances) {
            int now = MopScorer.distanceWeight(mop, d);
            int then = frozenDistanceWeight(d);
            if (now != then) {
                mismatches.add("distanceWeight(" + d + "): " + now + " != " + then);
            }
        }

        List<int[]> ties = tieInputs();
        for (int[] input : ties) {
            int[] nowWeights = new int[input.length];
            int[] thenWeights = new int[input.length];
            for (int k = 0; k < input.length; k++) {
                nowWeights[k] = MopScorer.distanceWeight(mop, input[k]);
                thenWeights[k] = frozenDistanceWeight(input[k]);
            }
            int[] now = MopScorer.splitTies(mop, nowWeights);
            int[] then = frozenSplitTies(thenWeights);
            if (!Arrays.equals(now, then)) {
                mismatches.add("splitTies(" + Arrays.toString(input) + "): " + Arrays.toString(now)
                        + " != " + Arrays.toString(then));
            }
        }

        int[] levelWeights = {0, 300, 400, 500, 1};
        for (int w : levelWeights) {
            int now = MopScorer.nextLevelWeight(mop, w);
            int then = frozenNextLevelWeight(w);
            if (now != then) {
                mismatches.add("nextLevelWeight(" + w + "): " + now + " != " + then);
            }
        }

        System.out.println("[E7-EQUIV] distanceWeight=" + distances.length
                + " splitTies=" + ties.size()
                + " nextLevelWeight=" + levelWeights.length
                + " mismatches=" + mismatches.size());
        assertEquals(String.join("\n", mismatches), 0, mismatches.size());
    }
}

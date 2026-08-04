package com.android.commands.monkey.ape.model;

import com.android.commands.monkey.ape.utils.RandomHelper;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * A3 per-step counterfactual (INV-SEL-08/09) — the recomputation itself and, above all, its
 * neutrality towards the seeded RNG stream.
 *
 * <p>The seed-identity test is this group's merge gate. Perturbing the stream would be a silent
 * bias of exactly the class the calibration autopsy catalogued: every arm would still run, every
 * line would still parse, and the comparison would quietly stop being a comparison.
 */
public class MopCounterfactualTest {

    private static ModelAction action(int priority, int mopBoost, int mopFrontierBoost, int visits) {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setPriority(priority);
        a.setMopBoost(mopBoost);
        a.setMopFrontierBoost(mopFrontierBoost);
        for (int i = 0; i < visits; i++) {
            a.visitedCount++;
        }
        return a;
    }

    // -------------------------------------------------------------------------
    // RNG isolation — the merge gate (INV-SEL-09, INV-EXPL-14)
    // -------------------------------------------------------------------------

    /** A run of roulette picks; when {@code withCounterfactual}, each pick also recomputes. */
    private static List<ModelAction> pickSequence(List<ModelAction> candidates, int steps,
                                                  boolean withCounterfactual) {
        List<ModelAction> picked = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            RandomHelper.Draw<ModelAction> draw =
                    RandomHelper.randomPickWithPriorityRecorded(candidates);
            picked.add(draw.getValue());
            if (withCounterfactual) {
                MopCounterfactual.replayRoulette(candidates, draw.getIndex(), draw.getTotal());
            }
        }
        return picked;
    }

    @Test
    public void seededSequenceIsIdenticalWithTheCounterfactualOnAndOff() {
        List<ModelAction> candidates = Arrays.asList(
                action(100, 0, 0, 0),
                action(600, 500, 0, 0),
                action(300, 0, 200, 0));

        RandomHelper.seed(20260731L);
        List<ModelAction> withoutCf = pickSequence(candidates, 200, false);
        RandomHelper.seed(20260731L);
        List<ModelAction> withCf = pickSequence(candidates, 200, true);

        assertEquals("the counterfactual must consume zero draws from the live stream",
                withoutCf, withCf);
        for (int i = 0; i < withoutCf.size(); i++) {
            assertSame(withoutCf.get(i), withCf.get(i));
        }
    }

    // -------------------------------------------------------------------------
    // Roulette replay
    // -------------------------------------------------------------------------

    @Test
    public void withoutMopWeightsTheReplayReproducesTheFactualPick() {
        // The all-MOP-weights-zero invariant, and the smoke gate for the MOP-off arm: with nothing
        // to zero, cfTotal == factualTotal and the same draw lands on the same segment.
        List<ModelAction> candidates = Arrays.asList(
                action(100, 0, 0, 0), action(200, 0, 0, 0), action(300, 0, 0, 0));

        for (int draw = 0; draw < 600; draw++) {
            ModelAction factual = walk(candidates, draw);
            assertSame("draw " + draw, factual,
                    MopCounterfactual.replayRoulette(candidates, draw, 600));
        }
    }

    /** The factual roulette walk, mirroring State.pickAction. */
    private static ModelAction walk(List<ModelAction> candidates, int index) {
        for (ModelAction candidate : candidates) {
            if (candidate.getPriority() > index) {
                return candidate;
            }
            index -= candidate.getPriority();
        }
        return null;
    }

    @Test
    public void theMopBoostedSegmentShrinksInTheCounterfactual() {
        // Factual weights 100 / 600 (500 of it MOP) / 300, total 1000. A draw at 400 lands inside
        // the boosted action. Counterfactual weights are 100 / 100 / 300, total 500, and the same
        // random point (0.4) lands at 200 — inside the third action.
        ModelAction plain = action(100, 0, 0, 0);
        ModelAction boosted = action(600, 500, 0, 0);
        ModelAction other = action(300, 0, 0, 0);
        List<ModelAction> candidates = Arrays.asList(plain, boosted, other);

        assertSame(boosted, walk(candidates, 400));
        assertSame("the MOP boost is what put the pick on the boosted action",
                other, MopCounterfactual.replayRoulette(candidates, 400, 1000));
    }

    @Test
    public void aPickTheBoostDidNotChangeReplaysToItself() {
        ModelAction plain = action(100, 0, 0, 0);
        ModelAction boosted = action(600, 500, 0, 0);
        List<ModelAction> candidates = Arrays.asList(plain, boosted);

        // A draw at 50 is inside the first action both with and without the MOP weight.
        assertSame(plain, walk(candidates, 50));
        assertSame(plain, MopCounterfactual.replayRoulette(candidates, 50, 700));
    }

    @Test
    public void theMopFrontierBoostIsZeroedToo() {
        ModelAction plain = action(100, 0, 0, 0);
        ModelAction frontier = action(300, 0, 200, 0);
        assertEquals(100, MopCounterfactual.priorityWithoutMopWeights(frontier));
        assertEquals(100, MopCounterfactual.priorityWithoutMopWeights(plain));
    }

    @Test
    public void replayIsInertWhenNothingCanBePickedWithoutMopWeight() {
        // Every candidate's priority is pure MOP boost: there is no MOP-free world in which any of
        // them is selectable, so the recomputation reports nothing rather than inventing a pick.
        List<ModelAction> candidates = Arrays.asList(action(500, 500, 0, 0), action(300, 0, 300, 0));
        assertNull(MopCounterfactual.replayRoulette(candidates, 100, 800));
    }

    // -------------------------------------------------------------------------
    // Short-circuit counterfactuals (deterministic — they draw nothing)
    // -------------------------------------------------------------------------

    @Test
    public void theUnvisitedShortCircuitFallsThroughToLeastVisited() {
        ModelAction mopTarget = action(600, 500, 0, 3);
        ModelAction neverVisited = action(100, 0, 0, 0);
        List<ModelAction> candidates = Arrays.asList(mopTarget, neverVisited);

        assertSame("without the boost the fall-through takes the least-visited action",
                neverVisited, MopCounterfactual.leastVisitedWithoutMopWeights(candidates, true));
    }

    @Test
    public void leastVisitedBreaksTiesOnTheMopFreePriority() {
        ModelAction boosted = action(600, 500, 0, 1);     // MOP-free priority 100
        ModelAction stronger = action(400, 0, 0, 1);      // MOP-free priority 400
        assertSame(stronger, MopCounterfactual.leastVisitedWithoutMopWeights(
                Arrays.asList(boosted, stronger), true));
    }

    @Test
    public void theZeroStepShortCircuitFallsThroughToTheStrongestMopFreeCandidate() {
        ModelAction mopTarget = action(700, 500, 0, 0);   // MOP-free priority 200
        ModelAction other = action(400, 0, 0, 0);
        assertSame(other, MopCounterfactual.highestPriorityWithoutMopWeights(
                Arrays.asList(mopTarget, other)));
    }

    @Test
    public void aShortCircuitOnAnUnboostedCandidateSetChangesNothing() {
        ModelAction first = action(400, 0, 0, 0);
        ModelAction second = action(100, 0, 0, 0);
        assertSame(first, MopCounterfactual.highestPriorityWithoutMopWeights(
                Arrays.asList(first, second)));
    }

    @Test
    public void emptyCandidateSetsAreInert() {
        assertNull(MopCounterfactual.leastVisitedWithoutMopWeights(new ArrayList<ModelAction>(), true));
        assertNull(MopCounterfactual.highestPriorityWithoutMopWeights(new ArrayList<ModelAction>()));
        assertNull(MopCounterfactual.replayRoulette(new ArrayList<ModelAction>(), 0, 100));
    }
}

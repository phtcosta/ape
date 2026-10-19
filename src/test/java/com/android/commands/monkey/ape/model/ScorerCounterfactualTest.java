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
public class ScorerCounterfactualTest {

    private static final ScorerCounterfactual.Scorer[] MOP = {
            ScorerCounterfactual.Scorer.MOP, ScorerCounterfactual.Scorer.MOP_FRONTIER};

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
                ScorerCounterfactual.replayRoulette(candidates, draw.getIndex(), draw.getTotal(), MOP);
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
                    ScorerCounterfactual.replayRoulette(candidates, draw, 600, MOP));
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
                other, ScorerCounterfactual.replayRoulette(candidates, 400, 1000, MOP));
    }

    @Test
    public void aPickTheBoostDidNotChangeReplaysToItself() {
        ModelAction plain = action(100, 0, 0, 0);
        ModelAction boosted = action(600, 500, 0, 0);
        List<ModelAction> candidates = Arrays.asList(plain, boosted);

        // A draw at 50 is inside the first action both with and without the MOP weight.
        assertSame(plain, walk(candidates, 50));
        assertSame(plain, ScorerCounterfactual.replayRoulette(candidates, 50, 700, MOP));
    }

    @Test
    public void theMopFrontierBoostIsZeroedToo() {
        ModelAction plain = action(100, 0, 0, 0);
        ModelAction frontier = action(300, 0, 200, 0);
        assertEquals(100, ScorerCounterfactual.priorityWithout(frontier, MOP));
        assertEquals(100, ScorerCounterfactual.priorityWithout(plain, MOP));
    }

    @Test
    public void replayIsInertWhenNothingCanBePickedWithoutMopWeight() {
        // Every candidate's priority is pure MOP boost: there is no MOP-free world in which any of
        // them is selectable, so the recomputation reports nothing rather than inventing a pick.
        List<ModelAction> candidates = Arrays.asList(action(500, 500, 0, 0), action(300, 0, 300, 0));
        assertNull(ScorerCounterfactual.replayRoulette(candidates, 100, 800, MOP));
    }

    @Test
    public void emptyCandidateSetsAreInert() {
        assertNull(ScorerCounterfactual.replayRoulette(new ArrayList<ModelAction>(), 0, 100, MOP));
    }

    /** design D-B14.18: one named scorer is zeroed and the others keep their contribution. */
    @Test
    public void zeroingOneScorerLeavesTheOthers() {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setPriority(700);
        a.setNavFrontierBoost(200);
        a.setCoverageBoost(100);
        a.setMenuBoost(250);
        assertEquals(500, ScorerCounterfactual.priorityWithout(a, ScorerCounterfactual.Scorer.NAV_FRONTIER));
        assertEquals(600, ScorerCounterfactual.priorityWithout(a, ScorerCounterfactual.Scorer.COVERAGE));
        assertEquals(450, ScorerCounterfactual.priorityWithout(a, ScorerCounterfactual.Scorer.MENU));
        assertEquals(700, ScorerCounterfactual.priorityWithout(a));
    }

    @Test
    public void eachScorerReadsItsOwnFieldUnderItsWireName() {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setMopBoost(1);
        a.setMopFrontierBoost(2);
        a.setWtgBoost(3);
        a.setNavFrontierBoost(4);
        a.setCoverageBoost(5);
        a.setMenuBoost(6);
        a.setFormBoost(7);
        String[] wires = {"mop", "mopf", "wtg", "navf", "cov", "menu", "form"};
        ScorerCounterfactual.Scorer[] scorers = ScorerCounterfactual.Scorer.values();
        assertEquals(wires.length, scorers.length);
        for (int i = 0; i < scorers.length; i++) {
            assertEquals(wires[i], scorers[i].wire());
            assertEquals(i + 1, scorers[i].of(a));
        }
    }
}

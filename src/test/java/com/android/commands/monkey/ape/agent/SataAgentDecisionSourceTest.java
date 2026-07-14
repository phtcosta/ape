package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.ModelAction.DecisionSource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * §5.1/§5.2 decision-source attribution rule ({@link SataAgent#attributeByLargestBoost}).
 *
 * <p>The rule is applied only at the priority-consuming PICK SITES — the epsilon-greedy
 * roulette ({@code randomlyPickAction}, {@code SataAgent.java:495}), the EARLY_STAGE unvisited
 * roulette ({@code randomPickWithPriority}, {@code :1106}), and the two MOP short-circuits
 * ({@code selectUnvisitedMopTarget} / {@code pickBestMopTarget}). Those sites are exercised
 * end-to-end on a device because they require a live {@link com.android.commands.monkey.ape.model.State}
 * and model graph; here we pin the pure rule they all consume: largest positive boost wins,
 * ties resolved MOP &gt; WTG &gt; Menu &gt; Form &gt; Coverage, SATA when no boost is positive.
 *
 * <p>The Back-/Menu-unvisited short-circuits ({@code :461,468}), {@code greedyPickLeastVisited}
 * ({@code :492}), and the graph-navigation / shortest-path returns ({@code :1121,1133}, backward
 * {@code :1146,1158}, ABA {@code :741}) do NOT call this rule — they set {@code SATA} literally at
 * their return, so a boost carried incidentally on those picks never changes the attribution.
 * That wiring is graph-dependent and device-gated; it is not reachable from a JVM unit test
 * without a real State/GUITree, so it is validated on the emulator (tasks.md §4.2). INV-SEL-04
 * (exactly one {@code [APE-STEP]} line per finalized action) is unaffected: this rule only
 * changes which enum value is carried, never the number of log lines and never a boost field.
 *
 * <p>The {@link ModelAction} constructor does not dereference the State, so a null-state action
 * with set boosts is sufficient (same approach as {@link SataAgentMopShortCircuitTest} and
 * {@link com.android.commands.monkey.ape.model.ModelActionTest}).
 */
public class SataAgentDecisionSourceTest {

    private static ModelAction action(int mop, int wtg, int menu, int form, int coverage) {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setMopBoost(mop);
        a.setWtgBoost(wtg);
        a.setMenuBoost(menu);
        a.setFormBoost(form);
        a.setCoverageBoost(coverage);
        return a;
    }

    @Test
    public void testNoBoostIsSata() {
        assertEquals(DecisionSource.SATA,
                SataAgent.attributeByLargestBoost(action(0, 0, 0, 0, 0)));
    }

    @Test
    public void testNegativeBoostsAreSata() {
        assertEquals(DecisionSource.SATA,
                SataAgent.attributeByLargestBoost(action(-1, -5, -3, -2, -4)));
    }

    @Test
    public void testSingleMopBoostIsMop() {
        // Spec scenario: mop=500, everything else 0 -> MOP.
        assertEquals(DecisionSource.MOP,
                SataAgent.attributeByLargestBoost(action(500, 0, 0, 0, 0)));
    }

    @Test
    public void testSingleWtgBoostIsWtg() {
        assertEquals(DecisionSource.WTG,
                SataAgent.attributeByLargestBoost(action(0, 300, 0, 0, 0)));
    }

    @Test
    public void testSingleMenuBoostIsMenu() {
        assertEquals(DecisionSource.Menu,
                SataAgent.attributeByLargestBoost(action(0, 0, 250, 0, 0)));
    }

    @Test
    public void testSingleFormBoostIsForm() {
        assertEquals(DecisionSource.Form,
                SataAgent.attributeByLargestBoost(action(0, 0, 0, 150, 0)));
    }

    @Test
    public void testSingleCoverageBoostIsCoverage() {
        assertEquals(DecisionSource.Coverage,
                SataAgent.attributeByLargestBoost(action(0, 0, 0, 0, 100)));
    }

    @Test
    public void testLargestBoostWinsOnRoulette() {
        // Spec scenario: mop=0, wtg=200, menu=0, coverage=100, form=0 -> WTG (coverage is smaller).
        assertEquals(DecisionSource.WTG,
                SataAgent.attributeByLargestBoost(action(0, 200, 0, 0, 100)));
    }

    @Test
    public void testFormWinsWhenLargest() {
        // Spec scenario: mop=0, wtg=0, menu=0, coverage=100, form=150 -> Form.
        assertEquals(DecisionSource.Form,
                SataAgent.attributeByLargestBoost(action(0, 0, 0, 150, 100)));
    }

    @Test
    public void testTieMopBeatsWtg() {
        // Spec scenario: mop=300, wtg=300 -> MOP.
        assertEquals(DecisionSource.MOP,
                SataAgent.attributeByLargestBoost(action(300, 300, 0, 0, 0)));
    }

    @Test
    public void testTieWtgBeatsMenu() {
        // Spec scenario: wtg=300, menu=300 -> WTG.
        assertEquals(DecisionSource.WTG,
                SataAgent.attributeByLargestBoost(action(0, 300, 300, 0, 0)));
    }

    @Test
    public void testTieMenuBeatsForm() {
        assertEquals(DecisionSource.Menu,
                SataAgent.attributeByLargestBoost(action(0, 0, 200, 200, 0)));
    }

    @Test
    public void testTieFormBeatsCoverage() {
        assertEquals(DecisionSource.Form,
                SataAgent.attributeByLargestBoost(action(0, 0, 0, 120, 120)));
    }

    @Test
    public void testFullPrecedenceAllEqual() {
        // All five tied on the same positive value: MOP is the most specific, wins.
        assertEquals(DecisionSource.MOP,
                SataAgent.attributeByLargestBoost(action(100, 100, 100, 100, 100)));
    }
}

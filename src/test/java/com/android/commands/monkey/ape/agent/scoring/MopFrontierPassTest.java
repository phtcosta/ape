package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.utils.StaticGuiModel;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.utils.MopData;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * mop-reach-strategies Lever B (INV-MFP-01). The MOP-frontier predicate is the pure, JVM-runnable
 * novelty of {@link MopFrontierPass}: a WTG-transition target qualifies for the boost only when it is
 * BOTH MOP-bearing ({@code MopData.activityHasMop}) AND unvisited (not in the visited set). The
 * per-action write is pure too ({@link MopFrontierPass#boostAction}) and is pinned below; what stays
 * device-gated in {@code apply()} is the resolved-node/{@code Graph} plumbing between the two — all
 * excluded from the JVM surefire classpath (dalvik/framework stubs) — the same boundary that leaves
 * {@link NavFrontierPass#apply} unit-untested. The gate ({@code isEnabled}) is covered in
 * {@link ScoringPassGateTest}; the registration position in {@link ScoringPipelineTest}.
 */
public class MopFrontierPassTest {

    private static StaticGuiModel.WtgTransition edge(String widget, String target) {
        return new StaticGuiModel.WtgTransition(widget, target);
    }

    /** INV-MFP-01: MOP+unvisited qualifies; MOP+visited does not; non-MOP+unvisited does not. */
    @Test
    public void qualifiesOnlyForMopBearingUnvisitedTargets() {
        List<StaticGuiModel.WtgTransition> transitions = Arrays.asList(
                edge("btnDetail", "com.x.Detail"),  // MOP,    unvisited -> qualifies
                edge("btnSeen",   "com.x.Seen"),     // MOP,    visited   -> excluded
                edge("btnPlain",  "com.x.Plain"));   // non-MOP,unvisited -> excluded
        Set<String> mopActivities = new HashSet<>(Arrays.asList("com.x.Detail", "com.x.Seen"));
        MopData mopData = MopData.forTest(null, mopActivities);
        Set<String> visitedTargets = new HashSet<>(Collections.singletonList("com.x.Seen"));

        Set<String> qualifying =
                MopFrontierPass.qualifyingMopTargets(transitions, mopData, visitedTargets);

        assertEquals(Collections.singleton("com.x.Detail"), qualifying);
    }

    /** No MOP-bearing target among the transitions → empty qualifying set (pass no-ops). */
    @Test
    public void emptyWhenNoMopTarget() {
        List<StaticGuiModel.WtgTransition> transitions = Collections.singletonList(
                edge("btnPlain", "com.x.Plain"));
        MopData mopData = MopData.forTest(null, new HashSet<String>());
        Set<String> qualifying = MopFrontierPass.qualifyingMopTargets(
                transitions, mopData, new HashSet<String>());
        assertTrue(qualifying.isEmpty());
    }

    // ---- A6: the boost lands in its own field (INV-MFP-02, INV-ARCH-10) ------

    @Test
    public void boostSteersByPriorityAndRecordsOnlyItsOwnField() {
        ModelAction action = new ModelAction(null, ActionType.MODEL_CLICK);
        action.setPriority(100);
        action.setWtgBoost(200);         // the WTG-MOP term, applied by its pass
        action.setNavFrontierBoost(200); // the navigation-frontier term, applied by its pass

        MopFrontierPass.boostAction(action, 200);

        assertEquals("the setPriority increment is what steers", 300, action.getPriority());
        assertEquals(200, action.getMopFrontierBoost());
        assertEquals("the other WTG-family fields are left alone (INV-MFP-02)",
                200, action.getWtgBoost());
        assertEquals(200, action.getNavFrontierBoost());
    }

    /** Rule R1: the term is written, never read back from the field it is written to. */
    @Test
    public void boostWritesItsTermWithoutReadingTheFieldBack() {
        ModelAction action = new ModelAction(null, ActionType.MODEL_CLICK);
        action.setMopFrontierBoost(999); // a stale value the pass must not build on

        MopFrontierPass.boostAction(action, 200);

        assertEquals(200, action.getMopFrontierBoost());
        assertEquals(200, action.getPriority());
    }
}

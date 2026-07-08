package com.android.commands.monkey.ape.agent.scoring;

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
 * per-action {@code setPriority}/{@code wtgBoost} write in {@code apply()} runs on a resolved
 * {@code GUITreeNode}/{@code ModelAction}/{@code Graph} — all excluded from the JVM surefire classpath
 * (dalvik/framework stubs) — so it is exercised on device (task 7.4), the same boundary that leaves
 * {@link FrontierPass#apply} unit-untested. The gate ({@code isEnabled}) is covered in
 * {@link ScoringPassGateTest}; the registration position in {@link ScoringPipelineTest}.
 */
public class MopFrontierPassTest {

    private static MopData.WtgTransition edge(String widget, String target) {
        return new MopData.WtgTransition(widget, "android.widget.Button", target);
    }

    /** INV-MFP-01: MOP+unvisited qualifies; MOP+visited does not; non-MOP+unvisited does not. */
    @Test
    public void qualifiesOnlyForMopBearingUnvisitedTargets() {
        List<MopData.WtgTransition> transitions = Arrays.asList(
                edge("btnDetail", "com.x.Detail"),  // MOP,    unvisited -> qualifies
                edge("btnSeen",   "com.x.Seen"),     // MOP,    visited   -> excluded
                edge("btnPlain",  "com.x.Plain"));   // non-MOP,unvisited -> excluded
        Set<String> mopActivities = new HashSet<>(Arrays.asList("com.x.Detail", "com.x.Seen"));
        MopData mopData = MopData.forTest(null, mopActivities, null);
        Set<String> visitedTargets = new HashSet<>(Collections.singletonList("com.x.Seen"));

        Set<String> qualifying =
                MopFrontierPass.qualifyingMopTargets(transitions, mopData, visitedTargets);

        assertEquals(Collections.singleton("com.x.Detail"), qualifying);
    }

    /** No MOP-bearing target among the transitions → empty qualifying set (pass no-ops). */
    @Test
    public void emptyWhenNoMopTarget() {
        List<MopData.WtgTransition> transitions = Collections.singletonList(
                edge("btnPlain", "com.x.Plain"));
        MopData mopData = MopData.forTest(null, new HashSet<String>(), null);
        Set<String> qualifying = MopFrontierPass.qualifyingMopTargets(
                transitions, mopData, new HashSet<String>());
        assertTrue(qualifying.isEmpty());
    }
}

package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.ComponentInfo.ActivityInfo;
import com.android.commands.monkey.ape.utils.ComponentInfo.DataSpec;
import com.android.commands.monkey.ape.utils.ComponentInfo.IntentFilter;
import com.android.commands.monkey.ape.utils.MopData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * activity-frontier §4 — JVM tests for the pure seams of both levers.
 *
 * Lever A: {@link StatefulAgent#frontierBoost} (WTG-frontier decision) and the
 * {@code ActionType} predicates for the new {@code EVENT_TRIGGER_ACTIVITY} constant.
 * Lever B: {@link SataAgent#selectTriggerCandidate} (candidate matrix + round-robin) and
 * {@link SataAgent#buildDeepLinkUri} (URI building), plus
 * {@link StatefulAgent#nonModelDecisionSource} (the INV-CT-07 attribution).
 *
 * The stagnation gate, the counter reset, the dispatch intent, and the WTG pass wiring require
 * the Android runtime / an agent instance and are covered by the deferred device smoke (task 5.3).
 */
public class ActivityFrontierTest {

    // ---- ActionType predicates (task 1.3, INV-EXPL-13) -----------------------

    @Test
    public void testTriggerActivityIsNotModelAndNotTargeted() {
        assertFalse(ActionType.EVENT_TRIGGER_ACTIVITY.requireTarget());
        assertFalse(ActionType.EVENT_TRIGGER_ACTIVITY.isModelAction());
        assertFalse("must not be classified as an app-starter",
                ActionType.EVENT_TRIGGER_ACTIVITY.canStartApp());
    }

    @Test
    public void testModelPredicatesUnchangedForExistingTypes() {
        assertTrue(ActionType.MODEL_CLICK.requireTarget());
        assertTrue(ActionType.MODEL_CLICK.isModelAction());
        assertTrue(ActionType.MODEL_BACK.isModelAction());
        assertFalse(ActionType.MODEL_BACK.requireTarget());
        assertFalse(ActionType.EVENT_ACTIVATE.isModelAction());
    }

    // ---- Lever A: frontierBoost (task 2.3, INV-WTG-06/07) --------------------

    private static MopData.WtgTransition wtg(String widget, String target) {
        return new MopData.WtgTransition(widget, "android.widget.Button", target);
    }

    @Test
    public void testFrontierBoostForUnvisitedTarget() {
        List<MopData.WtgTransition> ts = Arrays.asList(wtg("btn_detail", "com.x.DetailActivity"));
        Set<String> visited = new HashSet<>(); // DetailActivity unvisited
        assertEquals(200, StatefulAgent.frontierBoost("btn_detail", ts, visited, 200));
    }

    @Test
    public void testNoFrontierBoostForVisitedTarget() {
        List<MopData.WtgTransition> ts = Arrays.asList(wtg("btn_detail", "com.x.DetailActivity"));
        Set<String> visited = new HashSet<>(Arrays.asList("com.x.DetailActivity"));
        assertEquals(0, StatefulAgent.frontierBoost("btn_detail", ts, visited, 200));
    }

    @Test
    public void testFrontierBoostWeightZeroIsOff() {
        List<MopData.WtgTransition> ts = Arrays.asList(wtg("btn_detail", "com.x.DetailActivity"));
        assertEquals(0, StatefulAgent.frontierBoost("btn_detail", ts, new HashSet<String>(), 0));
    }

    @Test
    public void testFrontierBoostNoMatchingWidget() {
        List<MopData.WtgTransition> ts = Arrays.asList(wtg("btn_other", "com.x.DetailActivity"));
        assertEquals(0, StatefulAgent.frontierBoost("btn_detail", ts, new HashSet<String>(), 200));
    }

    @Test
    public void testFrontierBoostEmptyOrNullShortId() {
        List<MopData.WtgTransition> ts = Arrays.asList(wtg("btn_detail", "com.x.DetailActivity"));
        assertEquals(0, StatefulAgent.frontierBoost("", ts, new HashSet<String>(), 200));
        assertEquals(0, StatefulAgent.frontierBoost(null, ts, new HashSet<String>(), 200));
    }

    // ---- INV-CT-07 attribution (task 3.6) ------------------------------------

    @Test
    public void testNonModelDecisionSource() {
        assertEquals(ModelAction.DecisionSource.Component,
                StatefulAgent.nonModelDecisionSource(ActionType.EVENT_TRIGGER_ACTIVITY));
        assertEquals(ModelAction.DecisionSource.SATA,
                StatefulAgent.nonModelDecisionSource(ActionType.EVENT_NOP));
        assertEquals(ModelAction.DecisionSource.SATA,
                StatefulAgent.nonModelDecisionSource(ActionType.EVENT_ACTIVATE));
    }

    // ---- Lever B: stagnation gate predicate ----------------------------------
    // activity-trigger-dose: 6-arg form shouldTriggerAtStagnation(enabled, hasMopData,
    // graphStableCounter, stagnationStep, launchesSoFar, maxPerRun). Fires iff enabled && hasMopData
    // && counter == step && (maxPerRun == 0 || launchesSoFar < maxPerRun). INV-CT-05/08/11/12.

    @Test
    public void testGateFiresOnlyAtEqualityPoint() {
        // fires exactly at counter == step, never below or above (>= would wrongly re-fire mid-episode)
        assertTrue(SataAgent.shouldTriggerAtStagnation(true, true, 10, 10, 0, 0));
        assertFalse("below step", SataAgent.shouldTriggerAtStagnation(true, true, 9, 10, 0, 0));
        assertFalse("above step (>= would wrongly fire)",
                SataAgent.shouldTriggerAtStagnation(true, true, 11, 10, 0, 0));
        // same shape at the default step 50
        assertTrue(SataAgent.shouldTriggerAtStagnation(true, true, 50, 50, 0, 0));
        assertFalse(SataAgent.shouldTriggerAtStagnation(true, true, 49, 50, 0, 0));
    }

    @Test
    public void testGateClosedWhenDisabledOrNoMopData() {
        assertFalse("disabled flag → never",
                SataAgent.shouldTriggerAtStagnation(false, true, 50, 50, 0, 0));
        assertFalse("no MopData → never",
                SataAgent.shouldTriggerAtStagnation(true, false, 50, 50, 0, 0));
    }

    @Test
    public void testDefaultStep50ReproducesPreChangeGate() {
        // INV-CT-11: default step 50 == the pre-change gate graphStableRestartThreshold/2 (threshold
        // 100 → fired at counter == 50). Byte-identical firing point; frozen gh43 arms preserved.
        assertTrue(SataAgent.shouldTriggerAtStagnation(true, true, 50, 50, 0, 0));
        assertFalse("old gate did not fire at counter 100",
                SataAgent.shouldTriggerAtStagnation(true, true, 100, 50, 0, 0));
    }

    @Test
    public void testCapBlocksAfterBudgetExhausted() {
        // INV-CT-12: cap=2 fires while launchesSoFar < 2, blocked at >= 2
        assertTrue("0 launches", SataAgent.shouldTriggerAtStagnation(true, true, 10, 10, 0, 2));
        assertTrue("1 launch", SataAgent.shouldTriggerAtStagnation(true, true, 10, 10, 1, 2));
        assertFalse("2 launches — budget exhausted",
                SataAgent.shouldTriggerAtStagnation(true, true, 10, 10, 2, 2));
        assertFalse("3 launches — still blocked",
                SataAgent.shouldTriggerAtStagnation(true, true, 10, 10, 3, 2));
    }

    @Test
    public void testCapZeroMeansUnlimited() {
        // INV-CT-12: cap=0 (default) → never blocks, however many launches have occurred
        assertTrue(SataAgent.shouldTriggerAtStagnation(true, true, 10, 10, 10, 0));
        assertTrue(SataAgent.shouldTriggerAtStagnation(true, true, 10, 10, 999, 0));
    }

    @Test
    public void testEmptyCandidateScanDoesNotConsumeBudget() {
        // Delta scenario "empty candidate scan does not consume budget": with cap=1 and no launches
        // yet, the gate fires; but if selectTriggerCandidate finds nothing (all visited), no launch
        // is returned, so launchesSoFar stays 0 and the gate still fires at the next firing point.
        // The increment lives in the candidate != null branch of the call site (verified on device),
        // so this composition models the budget-preservation intent at the pure seams.
        assertTrue("gate fires with 0 launches, cap 1",
                SataAgent.shouldTriggerAtStagnation(true, true, 10, 10, 0, 1));
        List<ComponentInfo> allVisited = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Seen", true, false, null)));
        Set<String> visited = new HashSet<>(Arrays.asList("com.x.Seen"));
        assertNull("no candidate → no launch → no budget spent",
                SataAgent.selectTriggerCandidate(allVisited, visited, "com.x.Main", 0, null));
        assertTrue("launchesSoFar unchanged (still 0) → gate still fires",
                SataAgent.shouldTriggerAtStagnation(true, true, 10, 10, 0, 1));
    }

    // ---- Lever B: selectTriggerCandidate (task 4.1, INV-CT-06) ---------------

    private static ActivityInfo activity(String className, boolean exported, boolean isMain, String permission) {
        return new ActivityInfo(className, isMain, exported, Collections.<IntentFilter>emptyList(),
                true, Collections.<String>emptyList(), permission);
    }

    @Test
    public void testCandidateSkipsNonExported() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Hidden", false, false, null)));
        assertNull(SataAgent.selectTriggerCandidate(acts, new HashSet<String>(), "com.x.Main", 0, null));
    }

    @Test
    public void testCandidateSkipsPermissionGated() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Guarded", true, false, "android.permission.FOO")));
        assertNull(SataAgent.selectTriggerCandidate(acts, new HashSet<String>(), "com.x.Main", 0, null));
    }

    @Test
    public void testCandidateSkipsMainByFlagAndByName() {
        List<ComponentInfo> byFlag = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Launcher", true, true, null)));
        assertNull(SataAgent.selectTriggerCandidate(byFlag, new HashSet<String>(), "com.x.Other", 0, null));
        List<ComponentInfo> byName = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Main", true, false, null)));
        assertNull(SataAgent.selectTriggerCandidate(byName, new HashSet<String>(), "com.x.Main", 0, null));
    }

    @Test
    public void testCandidateSkipsVisitedAtFireTime() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Seen", true, false, null)));
        Set<String> visited = new HashSet<>(Arrays.asList("com.x.Seen"));
        assertNull(SataAgent.selectTriggerCandidate(acts, visited, "com.x.Main", 0, null));
    }

    @Test
    public void testCandidatePicksEligible() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Settings", true, false, null)));
        ComponentInfo c = SataAgent.selectTriggerCandidate(acts, new HashSet<String>(), "com.x.Main", 0, null);
        assertNotNull(c);
        assertEquals("com.x.Settings", c.className);
    }

    @Test
    public void testCandidateRoundRobinWrapsAndAdvances() {
        // Only index 2 is eligible; starting rrIndex at 1 must wrap to find it.
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Main", true, true, null),      // main → skip
                activity("com.x.Hidden", false, false, null),  // not exported → skip
                activity("com.x.Deep", true, false, null)));   // eligible
        ComponentInfo c0 = SataAgent.selectTriggerCandidate(acts, new HashSet<String>(), "com.x.Main", 0, null);
        assertEquals("com.x.Deep", c0.className);
        ComponentInfo c1 = SataAgent.selectTriggerCandidate(acts, new HashSet<String>(), "com.x.Main", 1, null);
        assertEquals("com.x.Deep", c1.className);
    }

    @Test
    public void testCandidateNullWhenEmptyOrAllVisited() {
        assertNull(SataAgent.selectTriggerCandidate(
                new ArrayList<ComponentInfo>(), new HashSet<String>(), "com.x.Main", 0, null));
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.A", true, false, null)));
        assertNull(SataAgent.selectTriggerCandidate(
                acts, new HashSet<>(Arrays.asList("com.x.A")), "com.x.Main", 0, null));
    }

    // ---- E-mín: MOP-first launch ordering (task 5.1, INV-CT-09) --------------
    // MOP membership is the reachability-augmented activityHasMop truth, passed as a Set<String>
    // (null = flag-off round-robin) — NOT ComponentInfo.reachesTarget, which false-negatives
    // lambda-triggered activities on real apps (cryptoapp: all reachesTarget=false yet Cipher/
    // MessageDigest/Cryptography genuinely reach MOP per reachability[]).

    private static Set<String> mopSet(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    @Test
    public void testMopFirstPrefersReachingCandidate() {
        // Plain (not MOP-reaching) comes first in list order but Crypto (in the MOP set) must win —
        // Crypto models the lambda case: MOP-reaching yet components.reachesTarget would be false.
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Plain", true, false, null),
                activity("com.x.Crypto", true, false, null)));
        ComponentInfo c = SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, mopSet("com.x.Crypto"));
        assertNotNull(c);
        assertEquals("com.x.Crypto", c.className);
    }

    @Test
    public void testMopFirstFallsBackToNonMopWhenNoneReaching() {
        // Flag on but no eligible candidate is MOP-reaching (empty MOP set) — fall back to
        // round-robin over the eligible set; no candidate is skipped for lacking MOP.
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.A", true, false, null),
                activity("com.x.B", true, false, null)));
        ComponentInfo c0 = SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, mopSet());
        assertEquals("com.x.A", c0.className);
        ComponentInfo c1 = SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 1, mopSet());
        assertEquals("com.x.B", c1.className);
    }

    @Test
    public void testMopFirstOffIdenticalToRoundRobin() {
        // Flag off (null mopActivities) must ignore MOP membership and match the plain round-robin.
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Plain", true, false, null),
                activity("com.x.Crypto", true, false, null)));
        ComponentInfo off = SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, null);
        assertEquals("com.x.Plain", off.className); // first in list order, MOP ignored
    }

    // ---- mop-activity-consumers 2.1: framework/tooling denylist (INV-CT-06/10) ------
    // Framework/tooling activities (Compose preview, abstract ComponentActivity, leakcanary,
    // test scaffolds) are legitimate exported components of the app package in debug builds,
    // so they pass the exported/permission/main/visited conjunction; the class-namespace
    // prefix is the discriminator. cmpft4: 76% of 114 launches hit this garbage.

    /** 2.1(a): the Compose preview tooling activity is skipped for a genuine app activity. */
    @Test
    public void testCandidateSkipsComposePreviewTooling() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("androidx.compose.ui.tooling.PreviewActivity", true, false, null),
                activity("com.x.HistoryActivity", true, false, null)));
        ComponentInfo c = SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, null);
        assertNotNull(c);
        assertEquals("com.x.HistoryActivity", c.className);
    }

    /** 2.1(b): the abstract framework ComponentActivity is skipped. */
    @Test
    public void testCandidateSkipsAbstractComponentActivity() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("androidx.activity.ComponentActivity", true, false, null),
                activity("com.x.RealActivity", true, false, null)));
        ComponentInfo c = SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, null);
        assertNotNull(c);
        assertEquals("com.x.RealActivity", c.className);
    }

    /** 2.1(c): a list of only denylisted candidates returns null (falls through to SATA). */
    @Test
    public void testCandidateAllDenylistedReturnsNull() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("androidx.activity.ComponentActivity", true, false, null),
                activity("leakcanary.internal.activity.LeakActivity", true, false, null)));
        assertNull(SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, null));
    }

    /** 2.1(d): prefix match, not substring — an app class whose package merely contains
     * "androidx" stays eligible. */
    @Test
    public void testCandidatePrefixNotSubstring() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.foo.androidxutils.MainActivity", true, false, null)));
        ComponentInfo c = SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, null);
        assertNotNull(c);
        assertEquals("com.foo.androidxutils.MainActivity", c.className);
    }

    /** 2.1(e): triggerMopFirst two-group ordering unaffected for eligible candidates — the
     * denylist filters a denylisted MOP-member out of the MOP-first group without disturbing
     * the group ordering. */
    @Test
    public void testDenylistAppliesWithinMopFirstOrdering() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("androidx.compose.ui.tooling.PreviewActivity", true, false, null),
                activity("com.x.Plain", true, false, null),
                activity("com.x.Crypto", true, false, null)));
        ComponentInfo c = SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0,
                mopSet("androidx.compose.ui.tooling.PreviewActivity", "com.x.Crypto"));
        assertNotNull(c);
        assertEquals("com.x.Crypto", c.className); // denylisted MOP-member skipped, MOP-first still wins
    }

    // ---- mop-activity-consumers 3.3: Nav MOP tiebreak decision log (INV-MOP-33) -----
    // The log fires only on the decisive branch (density actually chose the path); the
    // all-equal random fallback stays silent so the per-refill line never floods traces.

    /** 3.3: decisive branch → exactly the formatted line. */
    @Test
    public void testNavTiebreakLogDecisiveBranchFormatsLine() {
        assertEquals("[APE-RV] Nav MOP tiebreak: density=2 paths=3",
                SataAgent.navMopTiebreakLog(2, 3, true));
    }

    /** 3.3: all-equal fallback → null (no line). */
    @Test
    public void testNavTiebreakLogAllEqualReturnsNull() {
        assertNull(SataAgent.navMopTiebreakLog(0, 3, false));
    }

    @Test
    public void testMopFirstEligibilityUnchanged() {
        // A MOP-reaching activity that is non-exported stays ineligible; ordering never widens
        // eligibility, so the eligible non-MOP activity is launched instead.
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.HiddenCrypto", false, false, null),
                activity("com.x.Plain", true, false, null)));
        ComponentInfo c = SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, mopSet("com.x.HiddenCrypto"));
        assertNotNull(c);
        assertEquals("com.x.Plain", c.className);
    }

    @Test
    public void testMopFirstRoundRobinWithinReachingGroup() {
        // Two eligible MOP-reaching candidates — the MOP group itself walks round-robin by rrIndex.
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Crypto1", true, false, null),
                activity("com.x.Plain", true, false, null),
                activity("com.x.Crypto2", true, false, null)));
        Set<String> mop = mopSet("com.x.Crypto1", "com.x.Crypto2");
        ComponentInfo c0 = SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, mop);
        assertEquals("com.x.Crypto1", c0.className);
        ComponentInfo c1 = SataAgent.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 1, mop);
        assertEquals("com.x.Crypto2", c1.className);
    }

    // ---- Lever B: buildDeepLinkUri (task 4.2, INV-CT-07 dispatch precondition) ----

    private static DataSpec data(List<String> schemes, List<String> hosts, List<String> paths) {
        return new DataSpec(schemes, hosts, null, paths, null, null, null);
    }

    private static ActivityInfo activityWithFilters(List<IntentFilter> filters) {
        return new ActivityInfo("com.x.Deep", false, true, filters, true, Collections.<String>emptyList());
    }

    @Test
    public void testDeepLinkSchemeOnly() {
        IntentFilter f = new IntentFilter(Arrays.asList("android.intent.action.VIEW"),
                Collections.<String>emptyList(), data(Arrays.asList("myapp"), null, null));
        assertEquals("myapp://", SataAgent.buildDeepLinkUri(activityWithFilters(Arrays.asList(f))));
    }

    @Test
    public void testDeepLinkSchemeHost() {
        IntentFilter f = new IntentFilter(Arrays.asList("android.intent.action.VIEW"),
                Collections.<String>emptyList(), data(Arrays.asList("https"), Arrays.asList("x.com"), null));
        assertEquals("https://x.com", SataAgent.buildDeepLinkUri(activityWithFilters(Arrays.asList(f))));
    }

    @Test
    public void testDeepLinkSchemeHostPath() {
        IntentFilter f = new IntentFilter(Arrays.asList("android.intent.action.VIEW"),
                Collections.<String>emptyList(),
                data(Arrays.asList("https"), Arrays.asList("x.com"), Arrays.asList("/detail")));
        assertEquals("https://x.com/detail", SataAgent.buildDeepLinkUri(activityWithFilters(Arrays.asList(f))));
    }

    @Test
    public void testDeepLinkViewlessFilterReturnsNull() {
        IntentFilter f = new IntentFilter(Arrays.asList("android.intent.action.MAIN"),
                Collections.<String>emptyList(), data(Arrays.asList("myapp"), null, null));
        assertNull(SataAgent.buildDeepLinkUri(activityWithFilters(Arrays.asList(f))));
    }

    @Test
    public void testDeepLinkEmptySchemesReturnsNull() {
        IntentFilter f = new IntentFilter(Arrays.asList("android.intent.action.VIEW"),
                Collections.<String>emptyList(), DataSpec.EMPTY);
        assertNull(SataAgent.buildDeepLinkUri(activityWithFilters(Arrays.asList(f))));
    }

    @Test
    public void testDeepLinkNoFiltersReturnsNull() {
        assertNull(SataAgent.buildDeepLinkUri(activityWithFilters(Collections.<IntentFilter>emptyList())));
    }
}

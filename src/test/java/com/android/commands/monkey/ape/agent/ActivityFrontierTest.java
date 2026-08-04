package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.agent.pipeline.DecisionPipeline;
import com.android.commands.monkey.ape.agent.pipeline.MopLauncherStage;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.ComponentInfo.ActivityInfo;
import com.android.commands.monkey.ape.utils.ComponentInfo.DataSpec;
import com.android.commands.monkey.ape.utils.ComponentInfo.IntentFilter;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
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
 * mop-census-launcher §2 — JVM tests for the pure seams of both levers.
 *
 * Lever A: {@link StatefulAgent#frontierBoost} (WTG-frontier decision) and the
 * {@code ActionType} predicates for the {@code EVENT_TRIGGER_ACTIVITY} constant.
 * Lever B: {@link MopLauncherStage#shouldFire} (cadence gate),
 * {@link MopLauncherStage#selectTriggerCandidate} (census-only candidate walk + round-robin) and
 * {@link MopLauncherStage#buildDeepLinkUri} (URI building),
 * plus {@link StatefulAgent#nonModelDecisionSource} (the INV-CT-07 attribution).
 *
 * The dedicated launcher step counter, the counter reset, the dispatch intent, and the WTG pass
 * wiring require the Android runtime / an agent instance and are covered by the deferred device
 * smoke (task 3.4).
 */
public class ActivityFrontierTest {

    // ---- ActionType predicates (INV-EXPL-13) ---------------------------------

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

    // ---- Lever A: frontierBoost (INV-WTG-06/07) ------------------------------

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

    // ---- INV-CT-07 attribution -----------------------------------------------

    @Test
    public void testNonModelDecisionSource() {
        assertEquals(ModelAction.DecisionSource.Component,
                StatefulAgent.nonModelDecisionSource(ActionType.EVENT_TRIGGER_ACTIVITY));
        assertEquals(ModelAction.DecisionSource.SATA,
                StatefulAgent.nonModelDecisionSource(ActionType.EVENT_NOP));
        assertEquals(ModelAction.DecisionSource.SATA,
                StatefulAgent.nonModelDecisionSource(ActionType.EVENT_ACTIVATE));
    }

    /**
     * A-5 (INV-SEL-05): a non-model action carries no provenance field of its own, so its
     * `pick_channel` is read off the type — the launcher is a named channel, everything else is
     * outside the four MOP-sensitive ones.
     */
    @Test
    public void testNonModelPickChannel() {
        assertEquals(ModelAction.PickChannel.LAUNCHER,
                StatefulAgent.nonModelPickChannel(ActionType.EVENT_TRIGGER_ACTIVITY));
        assertEquals(ModelAction.PickChannel.SATA_OTHER,
                StatefulAgent.nonModelPickChannel(ActionType.EVENT_NOP));
        assertEquals(ModelAction.PickChannel.SATA_OTHER,
                StatefulAgent.nonModelPickChannel(ActionType.EVENT_ACTIVATE));
    }

    // ---- Lever B: cadence firing predicate (mop-census-launcher, INV-CT-05/12) ----
    // shouldFire(stepsSinceFiring, cadence, launchesSoFar, maxPerRun).
    // Fires iff stepsSinceFiring == cadence && (maxPerRun == 0 || launchesSoFar < maxPerRun); being
    // enabled and having MOP data are assembly conditions, so a stage that exists has both. The call site resets the dedicated per-pass counter at every
    // firing point, so equality (not >=) never overshoots while firing is active. Decoupled from
    // graphStableCounter: firing is periodic in selection steps, independent of graph growth.

    @Test
    public void testLauncherFiresOnlyAtCadencePoint() {
        assertTrue(MopLauncherStage.shouldFire(10, 10, 0, 0));
        assertFalse("below cadence", MopLauncherStage.shouldFire(9, 10, 0, 0));
        assertFalse("above cadence", MopLauncherStage.shouldFire(11, 10, 0, 0));
        // same shape at the default cadence 50
        assertTrue(MopLauncherStage.shouldFire(50, 50, 0, 0));
        assertFalse(MopLauncherStage.shouldFire(49, 50, 0, 0));
    }

    @Test
    public void testLauncherClosedWhenDisabledOrNoMopData() {
        // The two conjuncts this used to assert on the predicate are now assembly conditions: a plan
        // without the launcher feature, and a plan without MOP at all, have no launcher stage to
        // fire (INV-DP-03). Asserted where they now live rather than dropped.
        assertFalse("disabled flag → no stage",
                DecisionPipeline.assembledCandidates(TestRunSpecs.spec(
                        "ape.mopDataPath", TestRunSpecs.MOP_PATH,
                        "ape.activityTriggerEnabled", "false"))
                        .contains(DecisionPipeline.Candidate.MOP_LAUNCHER));
        assertFalse("no MopData → no stage",
                DecisionPipeline.assembledCandidates(TestRunSpecs.spec())
                        .contains(DecisionPipeline.Candidate.MOP_LAUNCHER));
    }

    @Test
    public void testCapBlocksAfterBudgetExhausted() {
        // INV-CT-12: cap=2 fires while launchesSoFar < 2, blocked at >= 2 (the 3rd fire)
        assertTrue("0 launches", MopLauncherStage.shouldFire(10, 10, 0, 2));
        assertTrue("1 launch", MopLauncherStage.shouldFire(10, 10, 1, 2));
        assertFalse("2 launches — budget exhausted",
                MopLauncherStage.shouldFire(10, 10, 2, 2));
        assertFalse("3 launches — still blocked",
                MopLauncherStage.shouldFire(10, 10, 3, 2));
    }

    @Test
    public void testCapZeroMeansUnlimited() {
        // INV-CT-12: cap=0 (default) → never blocks, however many launches have occurred
        assertTrue(MopLauncherStage.shouldFire(10, 10, 10, 0));
        assertTrue(MopLauncherStage.shouldFire(10, 10, 999, 0));
    }

    // ---- Lever B: census-only candidate selection (mop-census-launcher, INV-CT-06/10) ----
    // selectTriggerCandidate(activities, visited, mainActivity, rrIndex, mopCensus): round-robin
    // walk returning the first census member that is not framework-namespaced, permission-free,
    // non-main, and unvisited. Exported status is NOT consulted. There is no non-census fallback.
    // The census is the reachability-augmented MopData.activityHasMop truth (INV-MOP-27), passed
    // as a Set<String> — NOT ComponentInfo.reachesTarget, which false-negatives lambda-triggered
    // activities (cryptoapp: all reachesTarget=false yet Cipher/MessageDigest genuinely reach MOP).

    private static ActivityInfo activity(String className, boolean exported, boolean isMain, String permission) {
        return new ActivityInfo(className, isMain, exported, Collections.<IntentFilter>emptyList(),
                true, Collections.<String>emptyList(), permission);
    }

    private static Set<String> mopSet(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    @Test
    public void testCandidatePicksEligibleCensusMember() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Settings", true, false, null)));
        ComponentInfo c = MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, mopSet("com.x.Settings"));
        assertNotNull(c);
        assertEquals("com.x.Settings", c.className);
    }

    @Test
    public void testNonExportedCensusMemberIsLaunched() {
        // exported term deleted: a non-exported census activity is a valid launch target — the
        // dispatch path (uid-2000 IActivityManager) needs no export. Gate 0 v3 structural-null fix.
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.CryptoActivity", false, false, null)));
        ComponentInfo c = MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, mopSet("com.x.CryptoActivity"));
        assertNotNull(c);
        assertEquals("com.x.CryptoActivity", c.className);
    }

    @Test
    public void testNonCensusActivityNeverLaunchedNoFallback() {
        // Exported, permission-free, non-main, unvisited — but NOT in the census. No fallback:
        // returns null even though it is the only otherwise-eligible activity, with either an
        // empty census or a non-empty census that does not contain it.
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.AboutActivity", true, false, null)));
        assertNull("empty census, no fallback", MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, mopSet()));
        assertNull("census present but excludes it", MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, mopSet("com.x.Other")));
    }

    @Test
    public void testCandidateSkipsPermissionGated() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Guarded", false, false, "android.permission.FOO")));
        assertNull(MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, mopSet("com.x.Guarded")));
    }

    @Test
    public void testCandidateSkipsMainByFlagAndByName() {
        List<ComponentInfo> byFlag = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Launcher", true, true, null)));
        assertNull(MopLauncherStage.selectTriggerCandidate(
                byFlag, new HashSet<String>(), "com.x.Other", 0, mopSet("com.x.Launcher")));
        List<ComponentInfo> byName = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Main", false, false, null)));
        assertNull(MopLauncherStage.selectTriggerCandidate(
                byName, new HashSet<String>(), "com.x.Main", 0, mopSet("com.x.Main")));
    }

    @Test
    public void testCandidateSkipsVisitedAtFireTime() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Seen", false, false, null)));
        Set<String> visited = new HashSet<>(Arrays.asList("com.x.Seen"));
        assertNull(MopLauncherStage.selectTriggerCandidate(
                acts, visited, "com.x.Main", 0, mopSet("com.x.Seen")));
    }

    @Test
    public void testCandidateNullWhenEmptyOrAllVisited() {
        assertNull(MopLauncherStage.selectTriggerCandidate(
                new ArrayList<ComponentInfo>(), new HashSet<String>(), "com.x.Main", 0, mopSet("com.x.A")));
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.A", false, false, null)));
        assertNull(MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<>(Arrays.asList("com.x.A")), "com.x.Main", 0, mopSet("com.x.A")));
    }

    @Test
    public void testCandidateNullCensusReturnsNull() {
        // No MopData / null census → nothing launched (defensive; the call site always passes the
        // census, so this is the belt-and-braces path, never the two-group flag-off of old E-mín).
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.A", true, false, null)));
        assertNull(MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, null));
    }

    @Test
    public void testCandidateRoundRobinOverCensus() {
        // Two eligible census members — the walk advances round-robin by rrIndex, skipping the
        // non-census activity between them.
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Crypto1", false, false, null),
                activity("com.x.Plain", true, false, null),    // not in census → skip
                activity("com.x.Crypto2", false, false, null)));
        Set<String> census = mopSet("com.x.Crypto1", "com.x.Crypto2");
        ComponentInfo c0 = MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, census);
        assertEquals("com.x.Crypto1", c0.className);
        ComponentInfo c1 = MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 1, census);
        assertEquals("com.x.Crypto2", c1.className);
    }

    @Test
    public void testCandidateRoundRobinWraps() {
        // Only index 2 is an eligible census member; starting rrIndex at 1 must wrap to find it.
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Main", true, true, null),      // main → skip
                activity("com.x.Plain", true, false, null),    // not in census → skip
                activity("com.x.Deep", false, false, null)));  // census, eligible
        Set<String> census = mopSet("com.x.Deep");
        ComponentInfo c0 = MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0, census);
        assertEquals("com.x.Deep", c0.className);
        ComponentInfo c1 = MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 1, census);
        assertEquals("com.x.Deep", c1.className);
    }

    // ---- Lever B: framework/tooling denylist (INV-CT-06/10) ------------------
    // Framework/tooling activities (Compose preview, abstract ComponentActivity, leakcanary, test
    // scaffolds) can appear in the census via over-approximated reachability; the class-namespace
    // prefix is the discriminator (a fixed correctness filter, not a tunable — no Config flag).

    @Test
    public void testCandidateSkipsComposePreviewTooling() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("androidx.compose.ui.tooling.PreviewActivity", false, false, null),
                activity("com.x.HistoryActivity", false, false, null)));
        ComponentInfo c = MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0,
                mopSet("androidx.compose.ui.tooling.PreviewActivity", "com.x.HistoryActivity"));
        assertNotNull(c);
        assertEquals("com.x.HistoryActivity", c.className);
    }

    @Test
    public void testCandidateSkipsAbstractComponentActivity() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("androidx.activity.ComponentActivity", false, false, null),
                activity("com.x.RealActivity", false, false, null)));
        ComponentInfo c = MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0,
                mopSet("androidx.activity.ComponentActivity", "com.x.RealActivity"));
        assertNotNull(c);
        assertEquals("com.x.RealActivity", c.className);
    }

    @Test
    public void testCandidateAllDenylistedReturnsNull() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("androidx.activity.ComponentActivity", false, false, null),
                activity("leakcanary.internal.activity.LeakActivity", false, false, null)));
        assertNull(MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0,
                mopSet("androidx.activity.ComponentActivity", "leakcanary.internal.activity.LeakActivity")));
    }

    @Test
    public void testCandidatePrefixNotSubstring() {
        // prefix match, not substring — an app class whose package merely contains "androidx" stays eligible
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.foo.androidxutils.MainActivity", false, false, null)));
        ComponentInfo c = MopLauncherStage.selectTriggerCandidate(
                acts, new HashSet<String>(), "com.x.Main", 0,
                mopSet("com.foo.androidxutils.MainActivity"));
        assertNotNull(c);
        assertEquals("com.foo.androidxutils.MainActivity", c.className);
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

    // ---- Lever B: buildDeepLinkUri (INV-CT-07 dispatch precondition) ----------

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
        assertEquals("myapp://", MopLauncherStage.buildDeepLinkUri(activityWithFilters(Arrays.asList(f))));
    }

    @Test
    public void testDeepLinkSchemeHost() {
        IntentFilter f = new IntentFilter(Arrays.asList("android.intent.action.VIEW"),
                Collections.<String>emptyList(), data(Arrays.asList("https"), Arrays.asList("x.com"), null));
        assertEquals("https://x.com", MopLauncherStage.buildDeepLinkUri(activityWithFilters(Arrays.asList(f))));
    }

    @Test
    public void testDeepLinkSchemeHostPath() {
        IntentFilter f = new IntentFilter(Arrays.asList("android.intent.action.VIEW"),
                Collections.<String>emptyList(),
                data(Arrays.asList("https"), Arrays.asList("x.com"), Arrays.asList("/detail")));
        assertEquals("https://x.com/detail", MopLauncherStage.buildDeepLinkUri(activityWithFilters(Arrays.asList(f))));
    }

    @Test
    public void testDeepLinkViewlessFilterReturnsNull() {
        IntentFilter f = new IntentFilter(Arrays.asList("android.intent.action.MAIN"),
                Collections.<String>emptyList(), data(Arrays.asList("myapp"), null, null));
        assertNull(MopLauncherStage.buildDeepLinkUri(activityWithFilters(Arrays.asList(f))));
    }

    @Test
    public void testDeepLinkEmptySchemesReturnsNull() {
        IntentFilter f = new IntentFilter(Arrays.asList("android.intent.action.VIEW"),
                Collections.<String>emptyList(), DataSpec.EMPTY);
        assertNull(MopLauncherStage.buildDeepLinkUri(activityWithFilters(Arrays.asList(f))));
    }

    @Test
    public void testDeepLinkNoFiltersReturnsNull() {
        assertNull(MopLauncherStage.buildDeepLinkUri(activityWithFilters(Collections.<IntentFilter>emptyList())));
    }
}

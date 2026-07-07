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

    // ---- Lever B: stagnation gate predicate (task 4.3, INV-CT-05/08) ---------

    @Test
    public void testGateFiresOnlyAtEqualityPoint() {
        int threshold = 200; // mid = 100
        assertTrue(SataAgent.shouldTriggerAtStagnation(true, true, 100, threshold));
        assertFalse("below mid", SataAgent.shouldTriggerAtStagnation(true, true, 99, threshold));
        assertFalse("above mid (>= would wrongly fire)",
                SataAgent.shouldTriggerAtStagnation(true, true, 101, threshold));
    }

    @Test
    public void testGateClosedWhenDisabledOrNoMopData() {
        int threshold = 200;
        assertFalse("disabled flag → never",
                SataAgent.shouldTriggerAtStagnation(false, true, 100, threshold));
        assertFalse("no MopData → never",
                SataAgent.shouldTriggerAtStagnation(true, false, 100, threshold));
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
        assertNull(SataAgent.selectTriggerCandidate(acts, new HashSet<String>(), "com.x.Main", 0));
    }

    @Test
    public void testCandidateSkipsPermissionGated() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Guarded", true, false, "android.permission.FOO")));
        assertNull(SataAgent.selectTriggerCandidate(acts, new HashSet<String>(), "com.x.Main", 0));
    }

    @Test
    public void testCandidateSkipsMainByFlagAndByName() {
        List<ComponentInfo> byFlag = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Launcher", true, true, null)));
        assertNull(SataAgent.selectTriggerCandidate(byFlag, new HashSet<String>(), "com.x.Other", 0));
        List<ComponentInfo> byName = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Main", true, false, null)));
        assertNull(SataAgent.selectTriggerCandidate(byName, new HashSet<String>(), "com.x.Main", 0));
    }

    @Test
    public void testCandidateSkipsVisitedAtFireTime() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Seen", true, false, null)));
        Set<String> visited = new HashSet<>(Arrays.asList("com.x.Seen"));
        assertNull(SataAgent.selectTriggerCandidate(acts, visited, "com.x.Main", 0));
    }

    @Test
    public void testCandidatePicksEligible() {
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.Settings", true, false, null)));
        ComponentInfo c = SataAgent.selectTriggerCandidate(acts, new HashSet<String>(), "com.x.Main", 0);
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
        ComponentInfo c0 = SataAgent.selectTriggerCandidate(acts, new HashSet<String>(), "com.x.Main", 0);
        assertEquals("com.x.Deep", c0.className);
        ComponentInfo c1 = SataAgent.selectTriggerCandidate(acts, new HashSet<String>(), "com.x.Main", 1);
        assertEquals("com.x.Deep", c1.className);
    }

    @Test
    public void testCandidateNullWhenEmptyOrAllVisited() {
        assertNull(SataAgent.selectTriggerCandidate(
                new ArrayList<ComponentInfo>(), new HashSet<String>(), "com.x.Main", 0));
        List<ComponentInfo> acts = new ArrayList<ComponentInfo>(Arrays.asList(
                activity("com.x.A", true, false, null)));
        assertNull(SataAgent.selectTriggerCandidate(
                acts, new HashSet<>(Arrays.asList("com.x.A")), "com.x.Main", 0));
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

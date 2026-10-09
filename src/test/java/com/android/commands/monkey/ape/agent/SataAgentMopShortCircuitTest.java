package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.llm.PromptFixtures.TestName;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.MopRetirement;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * mop-discriminative-boost (#2) — ranking logic of the MOP-target greedy short-circuit
 * ({@link SataAgent#pickBestMopTarget}). The unvisited/enabled/valid gating is the
 * {@code ENABLED_VALID_UNVISITED} filter applied by the caller and is device-gated along
 * with the rest of the selection chain; here we test the pure ranking the spec scenarios
 * pin (highest mopBoost wins, ties → highest priority, mopBoost ≤ 0 ignored, empty → null), and,
 * since llm-coordinate-single-base D21, the ordered ranking at the end of this class.
 *
 * <p>The {@link ModelAction} constructor does not dereference the State (see ModelActionTest),
 * so null-state actions with set boosts/priorities are sufficient.
 */
public class SataAgentMopShortCircuitTest {

    private static final String ACT = "com.x.MainActivity";
    private static final String OTHER = "com.x.OtherActivity";

    /** A {@code flag}-mode MOP action: the undivided weight equals the boost (design D18). */
    private static ModelAction action(int mopBoost, int priority) {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setMopBoost(mopBoost);
        a.setMopWeight(mopBoost);
        a.setPriority(priority);
        return a;
    }

    /** The picker with nothing interacted in the activity and no MOP context. */
    private static ModelAction pick(Iterable<ModelAction> candidates) {
        return pick(candidates, null);
    }

    private static ModelAction pick(Iterable<ModelAction> candidates, ModelAction excluded) {
        return SataAgent.pickBestMopTarget(candidates, excluded, ACT, new UICoverageTracker(),
                null);
    }

    @Test
    public void testUnvisitedMopTargetSelectedAheadOfNonMop() {
        ModelAction mop = action(500, 10);
        List<ModelAction> candidates = Arrays.asList(action(0, 99), mop, action(0, 50));
        assertSame(mop, pick(candidates));
    }

    @Test
    public void testHighestMopBoostWins() {
        ModelAction strong = action(500, 1);
        ModelAction weak = action(300, 99);
        assertSame(strong, pick(Arrays.asList(weak, strong)));
    }

    @Test
    public void testTieBrokenByHighestPriority() {
        ModelAction lowPriority = action(300, 5);
        ModelAction highPriority = action(300, 40);
        assertSame(highPriority,
                pick(Arrays.asList(lowPriority, highPriority)));
    }

    @Test
    public void testNoMopTargetIsNoOp() {
        List<ModelAction> candidates = Arrays.asList(action(0, 10), action(0, 99));
        assertNull(pick(candidates));
    }

    @Test
    public void testEmptyCandidatesReturnsNull() {
        assertNull(pick(new ArrayList<ModelAction>()));
        assertNull(pick(Collections.<ModelAction>emptyList()));
    }

    // -------------------------------------------------------------------------
    // form-completion (#1) INV-FORM-06: the submit candidate is excluded from the
    // short-circuit while the form is incomplete; once it is not excluded, it wins.
    // -------------------------------------------------------------------------

    @Test
    public void testExcludedSubmitNotPickedWhileFormIncomplete() {
        // The submit handler is the mopBoost=500 action; while a field is unfilled it is excluded,
        // so the short-circuit picks no MOP target here (the only other action is non-MOP).
        ModelAction submit = action(500, 10);
        ModelAction field = action(0, 5);
        List<ModelAction> candidates = Arrays.asList(field, submit);
        assertNull("submit excluded while form incomplete",
                pick(candidates, submit));
    }

    @Test
    public void testExcludedSubmitStillPicksOtherMopTarget() {
        // A second, non-submit MOP target remains eligible even while the submit is excluded.
        ModelAction submit = action(500, 10);
        ModelAction otherMop = action(300, 5);
        List<ModelAction> candidates = Arrays.asList(submit, otherMop);
        assertSame(otherMop, pick(candidates, submit));
    }

    @Test
    public void testSubmitPickedOnceNotExcluded() {
        // Once the fields are filled the submit is no longer excluded (excluded=null) and wins.
        ModelAction submit = action(500, 10);
        ModelAction field = action(0, 5);
        List<ModelAction> candidates = Arrays.asList(field, submit);
        assertSame(submit, pick(candidates, null));
    }

    // -------------------------------------------------------------------------
    // mop-target-revisit-cap (#3): per-(widget,type,activity) deterministic cap.
    // No JVM test can instantiate SataAgent, so the cap is exercised through its
    // pure static seam: mopPickKey / eligibleForMopPick / recordMopPick.
    // -------------------------------------------------------------------------

    private static Name fakeName(final String xpath) {
        return new Name() {
            @Override public Namer getNamer() { return null; }
            @Override public Name getLocalName() { return this; }
            @Override public boolean refinesTo(Name other) { return false; }
            @Override public String toXPath() { return xpath; }
            @Override public void appendXPathLocalProperties(StringBuilder sb) { sb.append(xpath); }
            @Override public void toXPath(StringBuilder sb) { sb.append(xpath); }
            @Override public int compareTo(Name o) { return xpath.compareTo(o.toXPath()); }
            @Override public int hashCode() { return xpath.hashCode(); }
            @Override public boolean equals(Object o) {
                return o instanceof Name && xpath.equals(((Name) o).toXPath());
            }
        };
    }

    private static ModelAction targeted(String xpath, ActionType type, int mopBoost) {
        ModelAction a = new ModelAction(null, fakeName(xpath), type);
        a.setMopBoost(mopBoost);
        return a;
    }

    @Test
    public void testMopPickKeyFormat() {
        ModelAction a = targeted("//X", ActionType.MODEL_CLICK, 300);
        assertEquals("//X|MODEL_CLICK|com.example.Act",
                SataAgent.mopPickKey(a, "com.example.Act"));
    }

    @Test
    public void testMopPickKeyNullTargetReturnsNull() {
        ModelAction nonTargeted = action(300, 10); // built with the (state,type) ctor → null target
        assertNull(SataAgent.mopPickKey(nonTargeted, "com.example.Act"));
        assertNull(SataAgent.mopPickKey(null, "com.example.Act"));
    }

    /** 3.1 (INV-SEL-MOP-04): the action type is part of the key — capping clicks leaves long-click free. */
    @Test
    public void testKeyIncludesActionType() {
        ModelAction click = targeted("//X", ActionType.MODEL_CLICK, 300);
        ModelAction longClick = targeted("//X", ActionType.MODEL_LONG_CLICK, 300);
        String clickKey = SataAgent.mopPickKey(click, "A");
        String longKey = SataAgent.mopPickKey(longClick, "A");
        assertNotEquals(clickKey, longKey);

        Map<String, Integer> picks = new HashMap<>();
        int cap = 3;
        // Three deterministic clicks on X exhaust the click allowance.
        for (int i = 0; i < 3; i++) {
            assertTrue(SataAgent.eligibleForMopPick(picks, clickKey, cap));
            SataAgent.recordMopPick(picks, clickKey, cap);
        }
        assertFalse("click key capped after 3 picks", SataAgent.eligibleForMopPick(picks, clickKey, cap));
        // The long-click on the same widget is a distinct key → still eligible.
        assertTrue("long-click on same widget stays eligible",
                SataAgent.eligibleForMopPick(picks, longKey, cap));
    }

    /** 3.2: same (widget,type) in different activities are independent keys. */
    @Test
    public void testDistinctActivitiesCountedIndependently() {
        ModelAction a = targeted("//X", ActionType.MODEL_CLICK, 300);
        String keyA = SataAgent.mopPickKey(a, "com.example.ActA");
        String keyB = SataAgent.mopPickKey(a, "com.example.ActB");
        assertNotEquals(keyA, keyB);

        Map<String, Integer> picks = new HashMap<>();
        int cap = 3;
        for (int i = 0; i < 3; i++) {
            SataAgent.recordMopPick(picks, keyA, cap);
        }
        assertFalse(SataAgent.eligibleForMopPick(picks, keyA, cap));
        assertTrue("other activity's key is independent",
                SataAgent.eligibleForMopPick(picks, keyB, cap));
    }

    /** 3.3 (INV-SEL-MOP-05): cap <= 0 disables the cap entirely — always eligible, no counting. */
    @Test
    public void testCapDisabledAlwaysEligible() {
        Map<String, Integer> picks = new HashMap<>();
        picks.put("//X|MODEL_CLICK|A", 999);
        assertTrue(SataAgent.eligibleForMopPick(picks, "//X|MODEL_CLICK|A", 0));
        assertTrue(SataAgent.eligibleForMopPick(picks, "//X|MODEL_CLICK|A", -1));
        // recordMopPick is a no-op when the cap is disabled (no counter update).
        assertFalse(SataAgent.recordMopPick(picks, "//Y|MODEL_CLICK|A", 0));
        assertFalse(picks.containsKey("//Y|MODEL_CLICK|A"));
    }

    /** 3.5: a null key (uncountable action) is always eligible and never counted. */
    @Test
    public void testNullKeyAlwaysEligible() {
        Map<String, Integer> picks = new HashMap<>();
        assertTrue(SataAgent.eligibleForMopPick(picks, null, 3));
        assertFalse(SataAgent.recordMopPick(picks, null, 3));
        assertTrue(picks.isEmpty());
    }

    /** 3.4: recordMopPick signals "reached the cap" exactly once — on the cap-th pick. */
    @Test
    public void testRecordMopPickReachesCapOnce() {
        Map<String, Integer> picks = new HashMap<>();
        String key = "//X|MODEL_CLICK|A";
        int cap = 3;
        assertFalse("1st pick", SataAgent.recordMopPick(picks, key, cap));
        assertFalse("2nd pick", SataAgent.recordMopPick(picks, key, cap));
        assertTrue("3rd pick reaches cap", SataAgent.recordMopPick(picks, key, cap));
        // A hypothetical later pick (filtered in practice) must not re-signal.
        assertFalse("4th pick does not re-signal", SataAgent.recordMopPick(picks, key, cap));
    }

    /** 3.6: the cap never mutates the action's mopBoost — it stays roulette-visible. */
    @Test
    public void testCappedActionKeepsMopBoost() {
        ModelAction click = targeted("//X", ActionType.MODEL_CLICK, 300);
        String key = SataAgent.mopPickKey(click, "A");
        Map<String, Integer> picks = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            SataAgent.recordMopPick(picks, key, 3);
        }
        assertFalse(SataAgent.eligibleForMopPick(picks, key, 3));
        assertEquals("mopBoost unchanged after capping", 300, click.getMopBoost());
    }

    // -------------------------------------------------------------------------
    // llm-coordinate-single-base Part B, design D21 (task 14.3): the ordered shortcut. Keys, in
    // order: (1) the (widget, action type) has no interaction in the activity; (2) larger
    // undivided weight (mopWeight), not the split mopBoost; (3) under distance only, more distinct
    // unretired targets within d + 1 of the weight's lookup; (4) larger priority. Candidates still
    // need mopBoost > 0, and the revisit cap filters before the ranking, at both sites.
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** A format-2 artifact whose activity {@link #ACT} has {@code widgets}. */
    private static MopData artifact(String widgets) throws Exception {
        String json = "{\"formatVersion\":2,\"targets\":30,"
                + "\"package\":\"com.x\",\"mainActivity\":\"" + ACT + "\","
                + "\"widgets\":{\"" + ACT + "\":{" + widgets + "}},"
                + "\"mopActivities\":[\"" + ACT + "\"],"
                + "\"handlers\":{}}";
        File f = File.createTempFile("mopshortcut", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(json);
        }
        MopData data = MopData.load(f.getAbsolutePath(), null, null, new NoopSink());
        assertNotNull(data);
        return data;
    }

    /** A {@code distance} context over {@code data}: thirty targets, {@code retired} retired. */
    private static MopContext distance(MopData data, int... retired) throws Exception {
        MopRetirement retirement = new MopRetirement(30, 1);
        Field f = MopRetirement.class.getDeclaredField("counters");
        f.setAccessible(true);
        int[] counters = (int[]) f.get(retirement);
        for (int i : retired) {
            counters[i] = 1;
        }
        return new MopContext(data, MopScoring.DISTANCE, 500, 300, 500, 400, 300, 1, 6,
                retirement);
    }

    private static MopContext flag(MopData data) {
        return new MopContext(data, MopScoring.FLAG, 500, 300, 500, 400, 300, 3, 6, null);
    }

    private static State state(String activity) throws Exception {
        StateKey key = allocate(StateKey.class);
        setField(key, "activity", activity);
        setField(key, "widgets", new Name[0]);
        State state = allocate(State.class);
        setField(state, "stateKey", key);
        setField(state, "actions", new ModelAction[0]);
        return state;
    }

    /**
     * An unvisited, valid action on widget {@code id} (resource id {@code com.x:id/<id>}, target
     * {@code //<id>}) with the boost and undivided weight the MOP-widget pass would have stored.
     */
    private static ModelAction mop(String id, ActionType type, int boost, int weight,
            int priority) {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName("android.widget.Button");
        node.setResourceID("com.x:id/" + id);
        ModelAction a = new ModelAction(null, new TestName("//" + id), type);
        a.resolveAt(1, 0, null, node, new GUITreeNode[] {node});
        a.setValid(true);
        a.setMopBoost(boost);
        a.setMopWeight(weight);
        a.setPriority(priority);
        return a;
    }

    private static ModelAction mop(String id, int boost, int weight, int priority) {
        return mop(id, ActionType.MODEL_CLICK, boost, weight, priority);
    }

    /** A tracker in which {@code done} have had an interaction in {@code activity}. */
    private static UICoverageTracker interacted(String activity, ModelAction... done)
            throws Exception {
        UICoverageTracker tracker = new UICoverageTracker();
        State elsewhere = state(activity); // another state of the activity
        for (ModelAction a : done) {
            tracker.recordInteraction(elsewhere, a);
        }
        return tracker;
    }

    private static ModelAction pick(List<ModelAction> candidates, UICoverageTracker tracker,
            MopContext mop) {
        return SataAgent.pickBestMopTarget(candidates, null, ACT, tracker, mop);
    }

    /** Scenario "widget new to the activity wins over a larger boost", in both modes. */
    @Test
    public void testAWidgetNewToTheActivityWinsOverALargerWeight() throws Exception {
        MopData data = artifact("\"x\":{\"dist\":{\"click\":[[1,1]]}},"
                + "\"y\":{\"dist\":{\"click\":[[2,3]]}}");
        ModelAction x = mop("x", 500, 500, 50);
        ModelAction y = mop("y", 300, 300, 1);
        UICoverageTracker tracker = interacted(ACT, x);

        assertSame(y, pick(Arrays.asList(x, y), tracker, flag(data)));
        assertSame(y, pick(Arrays.asList(x, y), tracker, distance(data)));
        // Novelty is per activity: an interaction elsewhere does not make x old in ACT.
        assertSame(x, pick(Arrays.asList(x, y), interacted(OTHER, x), flag(data)));
    }

    /** Novelty is per (widget, action type): a long-click on a clicked widget is new. */
    @Test
    public void testNoveltyIsPerWidgetAndActionType() throws Exception {
        MopData data = artifact("\"x\":{\"mop\":{\"click\":\"direct\"}}");
        ModelAction click = mop("x", ActionType.MODEL_CLICK, 500, 500, 50);
        ModelAction longClick = mop("x", ActionType.MODEL_LONG_CLICK, 300, 300, 1);
        assertSame(longClick,
                pick(Arrays.asList(click, longClick), interacted(ACT, click), flag(data)));
    }

    /** Scenario "a split tie does not invert the ranking under distance". */
    @Test
    public void testASplitTieDoesNotInvertTheRankingUnderDistance() throws Exception {
        MopData data = artifact("\"x\":{\"dist\":{\"click\":[[1,1]]}},"
                + "\"y\":{\"dist\":{\"click\":[[2,1]]}},"
                + "\"z\":{\"dist\":{\"click\":[[3,0]]}},"
                + "\"w\":{\"dist\":{\"click\":[[10,2],[11,3],[12,3]]}}");
        // X, Y, Z split the d <= 1 level: max(500 / 3, 400) = 400 each; W keeps 400 undivided,
        // has three targets within d + 1 = 3 (X has one within 2) and the highest priority.
        ModelAction w = mop("w", 400, 400, 99);
        ModelAction x = mop("x", 400, 500, 1);
        ModelAction y = mop("y", 400, 500, 1);
        ModelAction z = mop("z", 400, 500, 1);
        ModelAction picked = pick(Arrays.asList(w, x, y, z), new UICoverageTracker(),
                distance(data));
        assertTrue("one of X, Y, Z (undivided 500), never W (400)",
                picked == x || picked == y || picked == z);
    }

    /** Scenario "target count breaks a boost tie under distance", and retired targets do not count. */
    @Test
    public void testTheTargetCountBreaksAWeightTieUnderDistance() throws Exception {
        MopData data = artifact("\"x\":{\"dist\":{\"click\":[[1,2],[2,3],[3,3]]}},"
                + "\"y\":{\"dist\":{\"click\":[[4,2],[5,5]]}}");
        ModelAction x = mop("x", 400, 400, 1);
        ModelAction y = mop("y", 400, 400, 99);

        assertSame("three targets within 3 against one, before priority", x,
                pick(Arrays.asList(y, x), new UICoverageTracker(), distance(data)));
        // With targets 2 and 3 retired X counts one target too, and priority decides.
        assertSame(y, pick(Arrays.asList(x, y), new UICoverageTracker(), distance(data, 2, 3)));
    }

    /** Novelty and weight are consulted before the target count. */
    @Test
    public void testTheTargetCountComesAfterNoveltyAndWeight() throws Exception {
        MopData data = artifact("\"x\":{\"dist\":{\"click\":[[1,2],[2,3],[3,3]]}},"
                + "\"y\":{\"dist\":{\"click\":[[4,2]]}},"
                + "\"v\":{\"dist\":{\"click\":[[6,1]]}}");
        ModelAction x = mop("x", 400, 400, 1);
        ModelAction y = mop("y", 400, 400, 1);
        ModelAction v = mop("v", 500, 500, 1);
        assertSame("larger undivided weight first", v,
                pick(Arrays.asList(x, y, v), new UICoverageTracker(), distance(data)));
        assertSame("novelty first", y,
                pick(Arrays.asList(x, y), interacted(ACT, x), distance(data)));
    }

    /** Scenario "target count does not exist under flag". */
    @Test
    public void testTheTargetCountDoesNotExistUnderFlag() throws Exception {
        MopData data = artifact(
                "\"x\":{\"mop\":{\"click\":\"direct\"},\"dist\":{\"click\":[[1,2],[2,3],[3,3]]}},"
                        + "\"y\":{\"mop\":{\"click\":\"direct\"},\"dist\":{\"click\":[[4,2]]}}");
        ModelAction x = mop("x", 500, 500, 1);
        ModelAction y = mop("y", 500, 500, 99);
        assertSame(y, pick(Arrays.asList(x, y), new UICoverageTracker(), flag(data)));
    }

    /** Candidates still need mopBoost > 0, whatever their undivided weight. */
    @Test
    public void testAPositiveBoostIsStillRequired() throws Exception {
        MopData data = artifact("\"x\":{\"dist\":{\"click\":[[1,1]]}}");
        ModelAction zeroBoost = mop("x", 0, 500, 99);
        ModelAction boosted = mop("y", 300, 300, 1);
        assertNull(pick(Arrays.asList(zeroBoost), new UICoverageTracker(), distance(data)));
        assertSame(boosted,
                pick(Arrays.asList(zeroBoost, boosted), new UICoverageTracker(), distance(data)));
    }

    // Both sites, through an Unsafe-allocated agent: the epsilon-greedy site
    // (selectUnvisitedMopTarget) and the 0-step probe's call, pickCappedMopTarget(candidates, null,
    // activity), which is the one picker both reach.

    private SataAgent agent(UICoverageTracker tracker, MopContext mop, int cap) throws Exception {
        SataAgent agent = allocate(SataAgent.class);
        setField(agent, "_coverageTracker", tracker);
        setField(agent, "_mopContext", mop);
        setField(agent, "mopTargetPickCap", cap);
        setField(agent, "mopTargetPicks", new HashMap<String, Integer>());
        return agent;
    }

    /** The 0-step probe's call ({@code findGreedyActionForward}): no exclusion. */
    private static ModelAction capped(SataAgent agent, List<ModelAction> candidates) {
        return agent.pickCappedMopTarget(candidates, null, ACT);
    }

    /** The epsilon-greedy site ranks by novelty first. */
    @Test
    public void testTheEpsilonSiteRanksTheSameWay() throws Exception {
        MopData data = artifact("\"x\":{\"mop\":{\"click\":\"direct\"}},"
                + "\"y\":{\"mop\":{\"click\":\"transitive\"}}");
        ModelAction x = mop("x", 500, 500, 50);
        ModelAction y = mop("y", 300, 300, 1);
        SataAgent agent = agent(interacted(ACT, x), flag(data), 3);
        State st = state(ACT);
        setField(st, "actions", new ModelAction[] {x, y});
        agent.newState = st;
        assertSame(y, agent.selectUnvisitedMopTarget(null));
    }

    /** Scenario "the 0-step probe ranks the same way". */
    @Test
    public void testTheZeroStepProbeRanksTheSameWay() throws Exception {
        MopData data = artifact("\"x\":{\"mop\":{\"click\":\"direct\"}},"
                + "\"y\":{\"mop\":{\"click\":\"transitive\"}}");
        ModelAction x = mop("x", 500, 500, 50);
        ModelAction y = mop("y", 300, 300, 1);
        SataAgent agent = agent(interacted(ACT, x), flag(data), 3);
        assertSame(y, capped(agent, Arrays.asList(x, y)));
    }

    /** Scenario "the revisit cap still filters before the ranking", cap of 3 unchanged. */
    @Test
    public void testTheRevisitCapStillFiltersBeforeTheRanking() throws Exception {
        MopData data = artifact("\"x\":{\"mop\":{\"click\":\"transitive\"}},"
                + "\"y\":{\"mop\":{\"click\":\"direct\"}}");
        ModelAction x = mop("x", 300, 300, 1);
        ModelAction y = mop("y", 500, 500, 1);
        SataAgent agent = agent(interacted(ACT, x), flag(data), 3);
        for (int i = 0; i < 3; i++) {
            assertSame("Y, new to the activity, is picked while under the cap", y,
                    capped(agent, Arrays.asList(x, y)));
        }
        assertSame("Y's key reached the cap: X is the only candidate left", x,
                capped(agent, Arrays.asList(x, y)));
    }
}

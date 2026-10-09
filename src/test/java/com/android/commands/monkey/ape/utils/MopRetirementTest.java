package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * mop-guidance "Target Retirement" (llm-coordinate-single-base, design D19, API Design "Part B:
 * {@code MopRetirement}", INV-MOP-37), the scenarios that do not need the agent: what one first
 * interaction counts, when a target retires, what {@code nearest} skips, which lookup is the
 * weight's lookup, and that only {@code distance} builds a retirement. The agent-level scenarios
 * (counting at selection in {@code moveForward}, novelty, the off-tree tap) are in
 * {@code StatefulAgentMopRetirementTest}.
 */
public class MopRetirementTest {

    private static final String ACT = "com.x.MainActivity";

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static MopData.Lookup pairs(int[]... dist) {
        return new MopData.Lookup(false, false, dist, MopData.Lookup.Source.ID);
    }

    private static int[] pair(int i, int d) {
        return new int[] {i, d};
    }

    private static MopData artifact(String widgetsOfAct) throws Exception {
        String json = "{\"formatVersion\":2,\"targets\":30,"
                + "\"package\":\"com.x\",\"mainActivity\":\"" + ACT + "\","
                + "\"widgets\":{\"" + ACT + "\":{" + widgetsOfAct + "}},"
                + "\"mopActivities\":[\"" + ACT + "\"],"
                + "\"handlers\":{}}";
        File f = File.createTempFile("mopretirement", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(json);
        }
        MopData data = MopData.load(f.getAbsolutePath(), null, null, new NoopSink());
        assertNotNull(data);
        return data;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, type);
    }

    private static void set(Object target, Class<?> owner, String field, Object value)
            throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static GUITreeNode child(GUITreeNode parent, String className, String shortId) {
        GUITreeNode n = new GUITreeNode(parent);
        n.setClassName(className);
        if (shortId != null) {
            n.setResourceID("com.x:id/" + shortId);
        }
        if (parent != null) {
            parent.addChild(n);
        }
        return n;
    }

    private static ModelAction resolvedClick(GUITreeNode node) throws Exception {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setValid(true);
        set(a, ModelAction.class, "resolvedNode", node);
        Field ts = ModelAction.class.getDeclaredField("resovledTimestamp");
        ts.setAccessible(true);
        ts.setInt(a, 1);
        return a;
    }

    private static State state(ModelAction... actions) throws Exception {
        StateKey key = (StateKey) allocate(StateKey.class);
        set(key, StateKey.class, "activity", ACT);
        set(key, StateKey.class, "widgets", new Name[0]);
        State state = (State) allocate(State.class);
        set(state, State.class, "stateKey", key);
        set(state, State.class, "actions", actions);
        return state;
    }

    private static MopContext distance(MopData data, MopRetirement retirement) {
        return new MopContext(data, MopScoring.DISTANCE, 500, 300, 500, 400, 300, 3, 6,
                retirement);
    }

    // -------------------------------------------------------------------------
    // Counting
    // -------------------------------------------------------------------------

    /** Scenario "first interaction counts each target of the node's own lookup once". */
    @Test
    public void firstInteractionCountsEachTargetOfTheLookupOnce() {
        MopRetirement r = new MopRetirement(30, 3);
        int[] retired = r.onFirstInteraction(pairs(pair(16, 3), pair(17, 3), pair(18, 3)));
        assertEquals("none reached 3", 0, retired.length);
        assertEquals(1, r.count(16));
        assertEquals(1, r.count(17));
        assertEquals(1, r.count(18));
        assertEquals("another target is not counted", 0, r.count(15));
        assertFalse(r.retired(16));
    }

    /** "each index once": an index carried by two pairs of one lookup is counted once. */
    @Test
    public void anIndexIsCountedOncePerCall() {
        MopRetirement r = new MopRetirement(30, 3);
        r.onFirstInteraction(pairs(pair(5, 1), pair(5, 2)));
        assertEquals(1, r.count(5));
    }

    /** Scenario "a target retires at the third count". */
    @Test
    public void aTargetRetiresAtTheThirdCount() {
        MopRetirement r = new MopRetirement(30, 3);
        assertEquals(0, r.onFirstInteraction(pairs(pair(22, 2))).length);
        assertEquals(0, r.onFirstInteraction(pairs(pair(22, 2))).length);
        assertEquals(2, r.count(22));
        assertFalse(r.retired(22));

        assertArrayEquals(new int[] {22}, r.onFirstInteraction(pairs(pair(22, 2))));
        assertEquals(3, r.count(22));
        assertTrue(r.retired(22));
        assertEquals(1, r.retiredCount());

        // Retired for the rest of the run, and reported once: a later count does not report it.
        assertEquals(0, r.onFirstInteraction(pairs(pair(22, 2))).length);
        assertTrue(r.retired(22));
        assertEquals(1, r.retiredCount());
    }

    /** dec.ret carries each index once, ascending (event-sink "dec.ret"). */
    @Test
    public void indicesRetiredTogetherAreReturnedAscending() {
        MopRetirement r = new MopRetirement(30, 1);
        assertArrayEquals(new int[] {3, 9, 11},
                r.onFirstInteraction(pairs(pair(11, 1), pair(9, 1), pair(3, 2))));
        assertEquals(3, r.retiredCount());
    }

    @Test
    public void retireAfterIsTheCountAtWhichATargetRetires() {
        MopRetirement r = new MopRetirement(30, 5);
        for (int k = 0; k < 4; k++) {
            assertEquals(0, r.onFirstInteraction(pairs(pair(0, 1))).length);
        }
        assertFalse(r.retired(0));
        assertArrayEquals(new int[] {0}, r.onFirstInteraction(pairs(pair(0, 1))));
        assertTrue(r.retired(0));
    }

    @Test
    public void aLookupWithoutPairsCountsNothing() {
        MopRetirement r = new MopRetirement(30, 1);
        assertEquals(0, r.onFirstInteraction(MopData.Lookup.NONE).length);
        assertEquals(0, r.retiredCount());
    }

    // -------------------------------------------------------------------------
    // nearest
    // -------------------------------------------------------------------------

    @Test
    public void nearestSkipsRetiredTargetsAndHonorsDmax() {
        MopRetirement r = new MopRetirement(30, 1);
        int[][] dist = {pair(4, 1), pair(7, 2), pair(9, 5)};
        assertEquals(1, r.nearest(dist, 6));
        r.onFirstInteraction(pairs(pair(4, 1)));
        assertEquals("target 4 retired", 2, r.nearest(dist, 6));
        assertEquals("d = 5 is beyond dmax 4", 2, r.nearest(dist, 4));
        r.onFirstInteraction(pairs(pair(7, 2)));
        assertEquals(5, r.nearest(dist, 6));
        assertEquals(Integer.MAX_VALUE, r.nearest(dist, 4));
        assertEquals(Integer.MAX_VALUE, r.nearest(new int[0][], 6));
    }

    /** With no retirement (null), nothing is retired: every pair counts. */
    @Test
    public void nearestOverANullRetirementReadsEveryPair() {
        int[][] dist = {pair(4, 3), pair(7, 2)};
        assertEquals(2, MopRetirement.nearest(null, dist, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, MopRetirement.nearest(null, dist, 1));
    }

    // -------------------------------------------------------------------------
    // The weight's lookup and the scoring it changes
    // -------------------------------------------------------------------------

    /**
     * Scenario "a weight won through containment retires its own targets": a clickable
     * {@code LinearLayout} with no id and no stamp, whose child {@code TextView} resolves to
     * {@code [[7,2]]}. The weight (400) comes from the child, so the weight's lookup is the child's
     * and its target is the one counted.
     */
    @Test
    public void aWeightWonThroughContainmentRetiresItsOwnTargets() throws Exception {
        GUITreeNode layout = child(null, "android.widget.LinearLayout", null);
        child(layout, "android.widget.TextView", "label");
        MopData data = artifact("\"label\":{\"mop\":{\"click\":\"transitive\"},"
                + "\"dist\":{\"click\":[[7,2]]}}");
        MopRetirement r = new MopRetirement(data.targetCount(), 3);
        MopContext mop = distance(data, r);
        ModelAction click = resolvedClick(layout);

        MopScorer.Scored scored = MopScorer.weigh(mop, state(click), click);
        assertEquals(400, scored.weight);
        assertEquals(2, scored.distance);
        assertEquals("the child, a containment hit", 1, scored.index);
        assertArrayEquals(pair(7, 2), scored.lookup.dist[0]);

        r.onFirstInteraction(scored.lookup);
        assertEquals(1, r.count(7));
    }

    /**
     * The weight's lookup is the containment lookup holding the smallest unretired distance; ties
     * go to the earlier node, and a retired pair does not hold the minimum.
     */
    @Test
    public void theWeightsLookupHoldsTheSmallestUnretiredDistance() throws Exception {
        GUITreeNode self = child(null, "android.widget.LinearLayout", "self");
        GUITreeNode c1 = child(self, "android.widget.TextView", "c1");
        child(self, "android.widget.TextView", "c2");
        MopData data = artifact(
                "\"self\":{\"dist\":{\"click\":[[1,3]]}},"
                        + "\"c1\":{\"dist\":{\"click\":[[2,2]]}},"
                        + "\"c2\":{\"dist\":{\"click\":[[3,2],[4,1]]}}");
        MopRetirement r = new MopRetirement(data.targetCount(), 1);
        MopContext mop = distance(data, r);
        ModelAction click = resolvedClick(self);

        MopScorer.Scored scored = MopScorer.weigh(mop, state(click), click);
        assertEquals("c2 holds d = 1", 2, scored.index);
        assertEquals(500, scored.weight);

        // Target 4 retires: c1 and c2 both hold d = 2, and the tie goes to the earlier node (c1).
        r.onFirstInteraction(pairs(pair(4, 1)));
        scored = MopScorer.weigh(mop, state(click), click);
        assertEquals(1, scored.index);
        assertSame(c1, MopScorer.containmentNodes(self).get(scored.index));
        assertEquals(400, scored.weight);
    }

    // -------------------------------------------------------------------------
    // Who builds it
    // -------------------------------------------------------------------------

    /** Scenario "no counting under flag": no retirement exists under {@code flag}. */
    @Test
    public void onlyTheDistanceModeBuildsARetirement() throws Exception {
        MopData data = artifact("");
        assertNull(MopContext.fromSpec(data, TestRunSpecs.mopSpec().mop()).retirement());

        MopContext mop = MopContext.fromSpec(data,
                TestRunSpecs.mopSpec("ape.mopScoring", "distance", "ape.mopRetireAfter", "2").mop());
        MopRetirement r = mop.retirement();
        assertNotNull(r);
        // Sized by the artifact's targets (30), retiring at the plan's count (2).
        assertEquals(0, r.onFirstInteraction(pairs(pair(29, 1))).length);
        assertArrayEquals(new int[] {29}, r.onFirstInteraction(pairs(pair(29, 1))));
    }
}

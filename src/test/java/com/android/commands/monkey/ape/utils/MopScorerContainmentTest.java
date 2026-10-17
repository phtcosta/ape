package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import org.junit.Before;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Containment over nodes (INV-MOP-23 amended, mop-guidance "Parent/child widget granularity
 * reconciliation", design D17): one node walk — the node, up to two ancestors, then its descendants
 * two levels down — shared by MOP resolution ({@link MopData#lookup} on each node) and typed-input
 * resolution (the id key on each node).
 *
 * <p>{@link GUITreeNode}'s Android references are in method signatures only; Java verifies those
 * lazily, so constructing a node and using its android-free surface ({@code GUITreeNode(parent)},
 * {@code setResourceID}, the stamp setters, {@code addChild}, {@code getParent},
 * {@code getChildren}) links and runs on the JVM. States and resolved actions are allocated through
 * the same Unsafe seam {@code MopScorerTest} uses.
 */
public class MopScorerContainmentTest {

    /**
     * {@code MopData.load} reads the MOP activity source from the plan (guidance-groups
     * INV-GRP-06), so every case runs under one: the jar defaults, whose source is the widgets.
     */
    @Before
    public void installThePlan() {
        TestRunSpecs.install();
    }

    private static final String ACT = "com.x.MainActivity";
    private static final int W_DIRECT = 917;
    private static final int W_TRANSITIVE = 613;

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private static GUITreeNode child(GUITreeNode parent, String shortId) {
        GUITreeNode n = new GUITreeNode(parent);
        if (shortId != null) {
            n.setResourceID("com.x:id/" + shortId);
        }
        if (parent != null) {
            parent.addChild(n);
        }
        return n;
    }

    /** grandgrandparent -> grandparent -> parent -> node -> child -> grandchild (ids as named). */
    private static GUITreeNode[] chain() {
        GUITreeNode ggp = child(null, "ggp");
        GUITreeNode gp = child(ggp, "gp");
        GUITreeNode p = child(gp, "p");
        GUITreeNode node = child(p, "self");
        GUITreeNode c = child(node, "c");
        GUITreeNode gc = child(c, "gc");
        return new GUITreeNode[] {ggp, gp, p, node, c, gc};
    }

    private static MopData artifact(String widgetsOfAct, String handlers) throws Exception {
        String json = "{\"formatVersion\":2,\"targets\":30,"
                + "\"package\":\"com.x\",\"mainActivity\":\"" + ACT + "\","
                + "\"widgets\":{\"" + ACT + "\":{" + widgetsOfAct + "}},"
                + "\"mopActivities\":[\"" + ACT + "\"],"
                + "\"handlers\":{" + handlers + "}}";
        File f = File.createTempFile("mopcontainment", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(json);
        }
        MopData data = MopData.load(f.getAbsolutePath(), null, null, new NoopSink());
        assertNotNull(data);
        return data;
    }

    private static MopContext flag(MopData data) {
        return TestMopContexts.flag(data, W_DIRECT, W_TRANSITIVE);
    }

    private static Object unsafe() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return theUnsafe.get(null);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Object unsafe = unsafe();
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, type);
    }

    private static void set(Object target, Class<?> owner, String field, Object value)
            throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** An action of {@code type} resolved at timestamp 1 to {@code node}. */
    private static ModelAction resolved(ActionType type, GUITreeNode node) throws Exception {
        ModelAction a = new ModelAction(null, type);
        a.setValid(true);
        set(a, ModelAction.class, "resolvedNode", node);
        Field ts = ModelAction.class.getDeclaredField("resovledTimestamp");
        ts.setAccessible(true);
        ts.setInt(a, 1);
        return a;
    }

    /** A state on {@code activity} offering {@code actions}. */
    private static State state(String activity, ModelAction... actions) throws Exception {
        StateKey key = (StateKey) allocate(StateKey.class);
        set(key, StateKey.class, "activity", activity);
        State state = (State) allocate(State.class);
        set(state, State.class, "stateKey", key);
        set(state, State.class, "actions", actions);
        return state;
    }

    // -------------------------------------------------------------------------
    // The walk
    // -------------------------------------------------------------------------

    @Test
    public void containmentNodes_selfAncestorsThenDescendants() {
        GUITreeNode[] n = chain();
        List<GUITreeNode> nodes = MopScorer.containmentNodes(n[3]);
        // self first, then ancestors (<=2 up), then descendants (child then grandchild)
        assertEquals(5, nodes.size());
        assertSame(n[3], nodes.get(0));
        assertSame(n[2], nodes.get(1));
        assertSame(n[1], nodes.get(2));
        assertSame(n[4], nodes.get(3));
        assertSame(n[5], nodes.get(4));
    }

    /** The existing descendant order is kept: each child, then that child's children. */
    @Test
    public void containmentNodes_descendantOrderIsEachChildThenItsChildren() {
        GUITreeNode node = child(null, "self");
        GUITreeNode c1 = child(node, "c1");
        GUITreeNode g1 = child(c1, "g1");
        GUITreeNode c2 = child(node, "c2");
        GUITreeNode g2 = child(c2, "g2");
        child(g2, "too_deep");
        List<GUITreeNode> nodes = MopScorer.containmentNodes(node);
        assertEquals(5, nodes.size());
        assertSame(node, nodes.get(0));
        assertSame(c1, nodes.get(1));
        assertSame(g1, nodes.get(2));
        assertSame(c2, nodes.get(3));
        assertSame(g2, nodes.get(4));
    }

    @Test
    public void containmentLookups_followTheWalkOrder() throws Exception {
        GUITreeNode[] n = chain();
        MopData data = artifact(
                "\"self\":{\"mop\":{\"click\":\"none\"}},"
                        + "\"p\":{\"mop\":{\"click\":\"transitive\"}},"
                        + "\"gc\":{\"mop\":{\"click\":\"direct\"}}", "");
        List<MopData.Lookup> lookups = MopScorer.containmentLookups(data, ACT, n[3], "click");
        assertEquals(5, lookups.size());
        assertEquals(MopData.Lookup.Source.ID, lookups.get(0).source);
        assertFalse(lookups.get(0).direct || lookups.get(0).transitive);
        assertTrue(lookups.get(1).transitive);
        assertEquals(MopData.Lookup.Source.NONE, lookups.get(2).source);
        assertEquals(MopData.Lookup.Source.NONE, lookups.get(3).source);
        assertTrue(lookups.get(4).direct);
    }

    // -------------------------------------------------------------------------
    // Scoring through the walk (flag mode)
    // -------------------------------------------------------------------------

    /** Scenario "Static flags parent, runtime resolves child", with the flag on a stamp. */
    @Test
    public void stampedAncestorIsFoundThroughTheWalk() throws Exception {
        GUITreeNode[] n = chain();
        n[2].setHandlerClick("com.x.Foo$1");
        MopData data = artifact("", "\"com.x.Foo$1\":{\"mop\":{\"click\":\"direct\"}}");
        ModelAction click = resolved(ActionType.MODEL_CLICK, n[3]);
        State st = state(ACT, click);
        assertEquals(W_DIRECT, MopScorer.score(flag(data), st, click));
        MopScorer.Scored scored = MopScorer.weigh(flag(data), st, click);
        assertEquals(W_DIRECT, scored.weight);
        assertEquals("won by the parent, a containment hit", 1, scored.index);
        assertEquals(MopData.Lookup.Source.STAMP, scored.lookup.source);
    }

    @Test
    public void stampedGrandchildIsFoundThroughTheWalk() throws Exception {
        GUITreeNode[] n = chain();
        n[5].setHandlerClick("com.x.Foo$1");
        MopData data = artifact("", "\"com.x.Foo$1\":{\"mop\":{\"click\":\"transitive\"}}");
        ModelAction click = resolved(ActionType.MODEL_CLICK, n[3]);
        MopScorer.Scored scored = MopScorer.weigh(flag(data), state(ACT, click), click);
        assertEquals(W_TRANSITIVE, scored.weight);
        assertEquals(4, scored.index);
    }

    /** Scenario "Depth bound respected": a stamp three levels up is not reached. */
    @Test
    public void stampedAncestorAtDepthThreeIsNotReached() throws Exception {
        GUITreeNode[] n = chain();
        n[0].setHandlerClick("com.x.Foo$1");
        MopData data = artifact("", "\"com.x.Foo$1\":{\"mop\":{\"click\":\"direct\"}}");
        ModelAction click = resolved(ActionType.MODEL_CLICK, n[3]);
        assertEquals(0, MopScorer.score(flag(data), state(ACT, click), click));
    }

    /** The best lookup wins wherever it comes from; the node's own is not privileged. */
    @Test
    public void strongerContainmentFlagBeatsOwnTransitive() throws Exception {
        GUITreeNode[] n = chain();
        MopData data = artifact(
                "\"self\":{\"mop\":{\"click\":\"transitive\"}},"
                        + "\"c\":{\"mop\":{\"click\":\"direct\"}}", "");
        ModelAction click = resolved(ActionType.MODEL_CLICK, n[3]);
        MopScorer.Scored scored = MopScorer.weigh(flag(data), state(ACT, click), click);
        assertEquals(W_DIRECT, scored.weight);
        assertEquals(3, scored.index);
    }

    /** Ties go to the earlier node: an equal flag elsewhere is not a containment hit. */
    @Test
    public void equalContainmentFlagIsNotAHit() throws Exception {
        GUITreeNode[] n = chain();
        MopData data = artifact(
                "\"self\":{\"mop\":{\"click\":\"direct\"}},"
                        + "\"p\":{\"mop\":{\"click\":\"direct\"}}", "");
        ModelAction click = resolved(ActionType.MODEL_CLICK, n[3]);
        MopScorer.Scored scored = MopScorer.weigh(flag(data), state(ACT, click), click);
        assertEquals(W_DIRECT, scored.weight);
        assertEquals(0, scored.index);
    }

    /** Scenario "listed stamp with none wins over a flagged id key": scores 0 under flag. */
    @Test
    public void listedStampWithNoneScoresZeroUnderFlag() throws Exception {
        GUITreeNode node = child(null, "btn");
        node.setHandlerClick("com.x.Foo$1");
        MopData data = artifact("\"btn\":{\"mop\":{\"click\":\"direct\"}}",
                "\"com.x.Foo$1\":{\"mop\":{\"click\":\"none\"}}");
        ModelAction click = resolved(ActionType.MODEL_CLICK, node);
        assertEquals(0, MopScorer.score(flag(data), state(ACT, click), click));
    }

    /** Scenario "Click-only listener boosts click action only" (unstamped node). */
    @Test
    public void clickOnlyListenerBoostsClickOnly() throws Exception {
        GUITreeNode node = child(null, "b");
        MopData data = artifact("\"b\":{\"mop\":{\"click\":\"direct\",\"longclick\":\"none\"}}", "");
        ModelAction click = resolved(ActionType.MODEL_CLICK, node);
        ModelAction longClick = resolved(ActionType.MODEL_LONG_CLICK, node);
        State st = state(ACT, click, longClick);
        assertEquals(W_DIRECT, MopScorer.score(flag(data), st, click));
        assertEquals(0, MopScorer.score(flag(data), st, longClick));
    }

    @Test
    public void unresolvedActionOrNullContextScoresZero() throws Exception {
        MopData data = artifact("\"b\":{\"mop\":{\"click\":\"direct\"}}", "");
        ModelAction unresolved = new ModelAction(null, ActionType.MODEL_CLICK);
        State st = state(ACT, unresolved);
        MopScorer.Scored scored = MopScorer.weigh(flag(data), st, unresolved);
        assertEquals(0, scored.weight);
        assertEquals(-1, scored.index);
        assertSame(MopData.Lookup.NONE, scored.lookup);
        ModelAction click = resolved(ActionType.MODEL_CLICK, child(null, "b"));
        assertEquals(0, MopScorer.score(null, state(ACT, click), click));
    }

    // -------------------------------------------------------------------------
    // State MOP density: the own node's lookup, no containment
    // -------------------------------------------------------------------------

    @Test
    public void stateMopDensityReadsTheOwnNodeLookupOnly() throws Exception {
        GUITreeNode[] n = chain();
        // The child is flagged, the action's own node is not: density does not walk containment.
        MopData data = artifact("\"c\":{\"mop\":{\"click\":\"direct\"}}", "");
        ModelAction click = resolved(ActionType.MODEL_CLICK, n[3]);
        assertEquals("floor only", 1, MopScorer.stateMopDensity(state(ACT, click), flag(data), 1));
    }

    @Test
    public void stateMopDensityCountsAStampedOwnNode() throws Exception {
        GUITreeNode stamped = child(null, null);
        stamped.setHandlerClick("com.x.Foo$1");
        GUITreeNode listedNone = child(null, "btn");
        listedNone.setHandlerClick("com.x.Bar$1");
        MopData data = artifact("\"btn\":{\"mop\":{\"click\":\"direct\"}}",
                "\"com.x.Foo$1\":{\"mop\":{\"click\":\"transitive\"}},"
                        + "\"com.x.Bar$1\":{\"mop\":{\"click\":\"none\"}}");
        State st = state(ACT, resolved(ActionType.MODEL_CLICK, stamped),
                resolved(ActionType.MODEL_CLICK, listedNone));
        assertEquals("1 + the stamped node; the listed none hides the flagged id",
                2, MopScorer.stateMopDensity(st, flag(data), 1));
    }
}

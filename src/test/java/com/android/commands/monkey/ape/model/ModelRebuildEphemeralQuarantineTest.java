package com.android.commands.monkey.ape.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeAction;
import com.android.commands.monkey.ape.tree.GUITreeTransition;

/**
 * INV-MODEL-16 (refinement-crash-recovery, task 2.4): the ephemeral quarantine must survive a model
 * rebuild. The F-D gate (design.md D7 outcome 1) proved the cmpv2 in-loop terminator: a refinement
 * removes a state whose edges include the ephemeral {@code MODEL_LLM_TAP} edge, and
 * {@code Model.rebuild}'s replay re-anchors it via {@code State.getAction(type)} — which throws
 * {@code IllegalStateException: No such action [MODEL_LLM_TAP]} because an ephemeral action is
 * never a member of {@code State.getActions()} (INV-MODEL-14).
 *
 * <p>Covers both holes: (1) the replay collection ({@code Model.collectReplayTreeTransitions},
 * extracted from {@code rebuild()} — the full rebuild needs the Android runtime, per the
 * {@code RebuildCountTest} precedent) must drop ephemeral edges and purge their tree transitions
 * from the graph's history so {@code rebuildHistory} cannot resurrect a dangling edge; (2) the
 * post-refinement re-anchor ({@code Model.update(ModelAction, GUITreeAction)}, reached from
 * {@code StatefulAgent.updateModel}) must return an ephemeral reference unchanged — its identity is
 * its payload (INV-MODEL-13), not state membership.
 *
 * <p>Uses {@code sun.misc.Unsafe} allocation for Android-dependent classes (the
 * {@code RebuildCountTest}/{@code StateTest} convention) and stdout capture for the {@code [APE-RV]}
 * telemetry (the {@code SaveActionHistoryToleranceTest} convention).
 */
public class ModelRebuildEphemeralQuarantineTest {

    private final PrintStream realOut = System.out;
    private ByteArrayOutputStream captured;

    @Before
    public void captureStdout() {
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
    }

    @After
    public void restoreStdout() {
        System.setOut(realOut);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        java.lang.reflect.Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return (T) allocate.invoke(unsafe, clazz);
    }

    private static void set(Object target, Class<?> declaring, String field, Object value)
            throws Exception {
        Field f = declaring.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static State stateWithKey(String activity) throws Exception {
        StateKey sk = allocate(StateKey.class);
        set(sk, StateKey.class, "activity", activity);
        State s = allocate(State.class);
        set(s, State.class, "stateKey", sk);
        return s;
    }

    /** Registers the state in the graph so {@code Model.isStale(state)} is false. */
    @SuppressWarnings("unchecked")
    private static void register(Graph g, State s) throws Exception {
        Field f = Graph.class.getDeclaredField("keyToState");
        f.setAccessible(true);
        ((Map<StateKey, State>) f.get(g)).put(s.getStateKey(), s);
    }

    @SuppressWarnings("unchecked")
    private static List<GUITreeTransition> treeHistory(Graph g) throws Exception {
        Field f = Graph.class.getDeclaredField("treeTransitionHistory");
        f.setAccessible(true);
        return (List<GUITreeTransition>) f.get(g);
    }

    private static GUITreeTransition tt() throws Exception {
        return allocate(GUITreeTransition.class);
    }

    // ------------------------------------------------------------------
    // Hole #1: rebuild replay collection
    // ------------------------------------------------------------------

    @Test
    public void collectSkipsEphemeralEdgeAndPurgesItsTreeHistory() throws Exception {
        Graph g = new Graph();
        Model m = new Model(g, null);
        State s1 = stateWithKey("A");
        State s2 = stateWithKey("B");

        LlmTapAction tap = new LlmTapAction(s1, 540, 462, false);
        ModelAction back = new ModelAction(s1, ActionType.MODEL_BACK);
        StateTransition ephemeralEdge = new StateTransition(s1, tap, s1);
        StateTransition normalEdge = new StateTransition(s1, back, s2);

        GUITreeTransition tapTt = tt();
        GUITreeTransition backTt = tt();
        ephemeralEdge.append(tapTt);
        normalEdge.append(backTt);
        treeHistory(g).addAll(Arrays.asList(tapTt, backTt));

        List<GUITreeTransition> replay =
                m.collectReplayTreeTransitions(Arrays.asList(ephemeralEdge, normalEdge));

        assertEquals("only the non-ephemeral edge's transitions are replayed", 1, replay.size());
        assertSame(backTt, replay.get(0));
        assertEquals("the ephemeral tree transition is purged from the graph history",
                Arrays.asList(backTt), treeHistory(g));
        assertTrue("the drop is reported under the [APE-RV] tag: " + captured,
                captured.toString().contains("[APE-RV]") && captured.toString().contains("ephemeral"));
    }

    @Test
    public void collectKeepsNonEphemeralEdgesInOrderAndLeavesHistoryUntouched() throws Exception {
        Graph g = new Graph();
        Model m = new Model(g, null);
        State s1 = stateWithKey("A");
        State s2 = stateWithKey("B");

        ModelAction back = new ModelAction(s1, ActionType.MODEL_BACK);
        ModelAction menu = new ModelAction(s2, ActionType.MODEL_MENU);
        StateTransition e1 = new StateTransition(s1, back, s2);
        StateTransition e2 = new StateTransition(s2, menu, s1);
        GUITreeTransition t1 = tt();
        GUITreeTransition t2 = tt();
        e1.append(t1);
        e2.append(t2);
        treeHistory(g).addAll(Arrays.asList(t1, t2));

        List<GUITreeTransition> replay = m.collectReplayTreeTransitions(Arrays.asList(e1, e2));

        assertEquals(Arrays.asList(t1, t2), replay);
        assertEquals("history untouched on the no-ephemeral path",
                Arrays.asList(t1, t2), treeHistory(g));
    }

    // ------------------------------------------------------------------
    // Hole #2: post-refinement re-anchor of agent references
    // ------------------------------------------------------------------

    /**
     * Fixture reproducing the exact production shape: the tap's birth state was removed by the
     * rebuild (stale), the tree's current state is the freshly rebuilt (registered) state, and that
     * state's action inventory — as always, per INV-MODEL-14 — does not contain the tap.
     */
    private Object[] staleTapFixture() throws Exception {
        Graph g = new Graph();
        Model m = new Model(g, null);

        // Distinct keys, as in production: the removed state's key was minted under the
        // pre-refinement naming, the rebuilt state's under the refined one. (Equal keys on
        // distinct State objects trip Graph.contains' duplicate-state sanity check.)
        State staleState = stateWithKey("A@pre-refinement-naming");
        State rebuiltState = stateWithKey("A");
        ModelAction back = new ModelAction(rebuiltState, ActionType.MODEL_BACK);
        set(rebuiltState, State.class, "actions", new ModelAction[] { back });
        register(g, rebuiltState);

        GUITree tree = allocate(GUITree.class);
        set(tree, GUITree.class, "currentState", rebuiltState);
        GUITreeAction guiAction = allocate(GUITreeAction.class);
        set(guiAction, GUITreeAction.class, "tree", tree);

        return new Object[] { m, staleState, rebuiltState, back, guiAction };
    }

    @Test
    public void updateReturnsEphemeralActionUnchangedInsteadOfThrowing() throws Exception {
        Object[] fx = staleTapFixture();
        Model m = (Model) fx[0];
        State staleState = (State) fx[1];
        GUITreeAction guiAction = (GUITreeAction) fx[4];

        LlmTapAction tap = new LlmTapAction(staleState, 540, 462, false);

        ModelAction updated = m.update(tap, guiAction);

        assertSame("an ephemeral reference is payload-bound and must never be re-anchored by "
                + "State.getActions() membership", tap, updated);
    }

    @Test
    public void updateStillReanchorsNonEphemeralTargetlessAction() throws Exception {
        Object[] fx = staleTapFixture();
        Model m = (Model) fx[0];
        State staleState = (State) fx[1];
        ModelAction registeredBack = (ModelAction) fx[3];
        GUITreeAction guiAction = (GUITreeAction) fx[4];

        ModelAction staleBack = new ModelAction(staleState, ActionType.MODEL_BACK);

        ModelAction updated = m.update(staleBack, guiAction);

        assertSame("a non-ephemeral targetless action is re-anchored to the rebuilt state's "
                + "registered instance, exactly as before", registeredBack, updated);
    }

    /** Pins the terminator signature: without the tap guard this is the cmpv2 crash. */
    @Test
    public void unguardedMembershipLookupIsTheProvenTerminator() throws Exception {
        Object[] fx = staleTapFixture();
        State rebuiltState = (State) fx[2];
        try {
            rebuiltState.getAction(ActionType.MODEL_LLM_TAP);
            fail("State.getAction(MODEL_LLM_TAP) must throw for a state that never contains the tap"
                    + " (INV-MODEL-14) — the guard in Model.update exists because of this");
        } catch (IllegalStateException expected) {
            assertTrue("unexpected message: " + expected.getMessage(),
                    expected.getMessage().contains("No such action [MODEL_LLM_TAP]"));
        }
    }
}

package com.android.commands.monkey.ape.model;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Set;

import org.junit.Test;

/**
 * INV-MODEL-14 (llm-coordinate-tap, task 7.3): {@link Graph#markVisited(ModelAction, int)} must
 * accept an ephemeral action — one synthesized per decision and never a member of
 * {@code State.actions}, so {@code Graph.addActions} never registered it.
 *
 * <p>Both of the method's branches otherwise assume registration: the unvisited branch requires
 * {@code unvisitedActions.remove(action)} to succeed, and the fallback requires
 * {@code visitedActions.contains(action)}. An unregistered model action hits
 * {@code RuntimeException: sanity check failed, action should be added} — which is what the 6.3
 * device smoke crashed on, from both call sites ({@code StatefulAgent.updateStateInternal} at
 * selection and {@code Graph.addTransition} at edge recording).
 *
 * <p>{@code markVisited(ModelAction, int)} never dereferences the action's state, so a null state
 * is sufficient here (the {@link ModelActionTest} convention) and no {@code Unsafe} fixture is
 * needed. The control case pins the invariant: the sanity check must stay sharp for every
 * non-ephemeral action.
 */
public class GraphEphemeralActionTest {

    @SuppressWarnings("unchecked")
    private static Set<ModelAction> actionSet(Graph g, String field) throws Exception {
        Field f = Graph.class.getDeclaredField(field);
        f.setAccessible(true);
        return (Set<ModelAction>) f.get(g);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        java.lang.reflect.Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        return (T) allocateInstance.invoke(unsafe, clazz);
    }

    private static void set(Object target, Class<?> declaring, String field, Object value) throws Exception {
        Field f = declaring.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static State stateWithKey(String activity) throws Exception {
        StateKey sk = allocate(StateKey.class);
        set(sk, StateKey.class, "activity", activity);
        // equals/hashCode walk the widgets array; an empty one keeps the fixture inert
        set(sk, StateKey.class, "widgets", new com.android.commands.monkey.ape.naming.Name[0]);
        State s = allocate(State.class);
        set(s, State.class, "stateKey", sk);
        // State.toString (used by the guard/ctor logging) walks the actions array
        set(s, State.class, "actions", new ModelAction[0]);
        return s;
    }

    @Test
    public void staleEphemeralActionIsSkippedAtTransitionRecording() throws Exception {
        // INV-MODEL-17 (llm-tap-injection, cmpm forensics A2): a rebuild removes the state an
        // in-flight ephemeral tap is anchored to, and INV-MODEL-16 deliberately leaves the agent's
        // reference unchanged. When that stale pair reaches transition recording, the edge must be
        // skipped — not turned into StateTransition's IllegalStateException, which terminated the
        // floflacards cmpm run.
        Graph g = new Graph();
        State anchor = stateWithKey("com.example.A"); // removed by the rebuild
        State source = stateWithKey("com.example.B"); // the rebuilt current state
        State target = stateWithKey("com.example.C");
        LlmTapAction tap = new LlmTapAction(anchor, 540, 1158, false);

        java.io.PrintStream realOut = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        StateTransition edge;
        try {
            System.setOut(new java.io.PrintStream(captured));
            edge = g.addTransition(source, tap, target, null, null, null);
        } finally {
            System.setOut(realOut);
        }

        assertNull("a stale ephemeral edge must be dropped, not recorded", edge);
        assertTrue("the skip must be observable in the trace",
                captured.toString().contains("stale ephemeral edge dropped"));
    }

    @Test
    public void freshEphemeralActionWithMatchingSourceStillCreatesTheEdge() throws Exception {
        // Negative branch of the guard: a tap whose anchor IS the source (the normal, no-rebuild
        // case) must keep producing an observational edge. Exercised through addStateTransition to
        // stay clear of the GUITree machinery, which needs a device.
        Graph g = new Graph();
        State source = stateWithKey("com.example.A");
        State target = stateWithKey("com.example.B");
        LlmTapAction tap = new LlmTapAction(source, 540, 1158, false);

        java.lang.reflect.Method m = Graph.class.getDeclaredMethod("addStateTransition",
                State.class, ModelAction.class, State.class);
        m.setAccessible(true);
        StateTransition edge = (StateTransition) m.invoke(g, source, tap, target);

        assertNotNull("a fresh ephemeral edge must still be recorded", edge);
        assertEquals(source, edge.source);
        assertEquals(target, edge.target);
    }

    @Test
    public void staleNonEphemeralActionStillThrows() throws Exception {
        // The skip is scoped to ephemeral actions: a non-ephemeral action whose state does not
        // match the source signals genuine model corruption and must keep failing loudly.
        Graph g = new Graph();
        State anchor = stateWithKey("com.example.A");
        State source = stateWithKey("com.example.B");
        State target = stateWithKey("com.example.C");
        ModelAction click = new ModelAction(anchor, ActionType.MODEL_CLICK);
        try {
            g.addTransition(source, click, target, null, null, null);
            fail("a non-ephemeral source mismatch must still raise IllegalStateException");
        } catch (IllegalStateException expected) {
            // unchanged pre-existing behavior (StateTransition.<init> source invariant)
        }
    }

    @Test
    public void markVisitedAcceptsEphemeralTapAndLeavesInventoryUntouched() throws Exception {
        Graph g = new Graph();
        LlmTapAction tap = new LlmTapAction(null, 540, 462, false);

        g.markVisited(tap, 3);

        assertTrue("an ephemeral action must not enter the unvisited inventory",
                actionSet(g, "unvisitedActions").isEmpty());
        assertTrue("an ephemeral action must not enter the visited inventory",
                actionSet(g, "visitedActions").isEmpty());
        assertEquals("the visit is still recorded on the action itself", 1, tap.getVisitedCount());
    }

    @Test
    public void markVisitedIsCleanOnBothCallSites() throws Exception {
        // The same tap object is marked twice per execution: once at selection
        // (StatefulAgent.updateStateInternal) and once when its edge is recorded
        // (Graph.addTransition). The second call must not fall into the untracked-action branch.
        Graph g = new Graph();
        LlmTapAction tap = new LlmTapAction(null, 540, 462, false);

        g.markVisited(tap, 3);
        g.markVisited(tap, 4);

        assertTrue(actionSet(g, "unvisitedActions").isEmpty());
        assertTrue(actionSet(g, "visitedActions").isEmpty());
        assertEquals(2, tap.getVisitedCount());
    }

    @Test
    public void unregisteredNonEphemeralActionStillFailsTheSanityCheck() {
        // The exemption is scoped to ephemeral actions only: a widget action that reaches the
        // bookkeeping unregistered is still a real defect and must still be reported.
        Graph g = new Graph();
        ModelAction widgetAction = new ModelAction(null, ActionType.MODEL_CLICK);
        try {
            g.markVisited(widgetAction, 3);
            fail("an unregistered non-ephemeral model action must still raise the sanity check");
        } catch (RuntimeException expected) {
            assertTrue("unexpected message: " + expected.getMessage(),
                    expected.getMessage().contains("action should be added"));
        }
    }
}

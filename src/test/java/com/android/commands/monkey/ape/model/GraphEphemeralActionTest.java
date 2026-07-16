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

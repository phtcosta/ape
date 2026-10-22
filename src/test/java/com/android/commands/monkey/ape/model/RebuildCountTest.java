package com.android.commands.monkey.ape.model;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

/**
 * INV-MODEL-11: activity and state counters across {@code Model.rebuild()}'s replay. The full
 * rebuild path constructs {@code GUITree}/{@code Naming} graphs that need the Android runtime, so
 * this exercises the decision point itself, {@link Graph#markVisited(State, int)} under
 * {@link Graph#setRebuilding(boolean)}, with plans with and without {@code APE_FIXES}.
 *
 * <p>With the member (an ADDONS plan) the replay does not re-mark activities; without it (the jar
 * defaults) each replayed {@code markVisited(State)} counts the activity, as APE does. Per-state
 * source counters are incremented in both plans (the deferred note on INV-MODEL-11).
 *
 * <p>Uses {@code sun.misc.Unsafe} to allocate a {@link State} without its Android-dependent
 * constructor (same technique as {@code StateTest}).
 */
public class RebuildCountTest {

    @After
    public void resetPlan() {
        RunContext.resetForTest();
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

    private static State stateInActivity(String activity) throws Exception {
        StateKey sk = allocate(StateKey.class);
        Field af = StateKey.class.getDeclaredField("activity");
        af.setAccessible(true);
        af.set(sk, activity);
        State s = allocate(State.class);
        Field skf = State.class.getDeclaredField("stateKey");
        skf.setAccessible(true);
        skf.set(s, sk);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static void seedActivity(Graph g, String key, ActivityNode node) throws Exception {
        Field af = Graph.class.getDeclaredField("activities");
        af.setAccessible(true);
        ((Map<String, ActivityNode>) af.get(g)).put(key, node);
    }

    @Test
    public void rebuildDoesNotReMarkActivityButStateStillIncrements() throws Exception {
        TestRunSpecs.install("ape.addons", "true");
        Graph g = new Graph();
        ActivityNode a = new ActivityNode("A");
        seedActivity(g, "A", a);
        State s = stateInActivity("A");

        // Live exploration (rebuilding=false by default): each transition marks state + activity.
        g.markVisited(s, 1);
        g.markVisited(s, 2);
        g.markVisited(s, 3);
        assertEquals(3, a.getVisitedCount());
        assertEquals(3, s.getVisitedCount());

        // Rebuild replay: activity counter is preserved; state counter still bumps (deferred).
        g.setRebuilding(true);
        g.markVisited(s, 4);
        g.markVisited(s, 5);
        g.setRebuilding(false);

        assertEquals("activity count preserved across rebuild replay", 3, a.getVisitedCount());
        assertEquals("state source counter still increments (INV-MODEL-11 deferred note)",
                5, s.getVisitedCount());
    }

    @Test
    public void activityCountIsIdempotentAcrossManyRebuilds() throws Exception {
        TestRunSpecs.install("ape.addons", "true");
        Graph g = new Graph();
        ActivityNode a = new ActivityNode("A");
        seedActivity(g, "A", a);
        State s = stateInActivity("A");

        g.markVisited(s, 1);
        g.markVisited(s, 2);
        int live = a.getVisitedCount(); // 2

        for (int r = 0; r < 3; r++) {
            g.setRebuilding(true);
            g.markVisited(s, 10 + r);
            g.setRebuilding(false);
        }
        assertEquals("activity count identical regardless of rebuild count", live, a.getVisitedCount());
    }

    @Test
    public void rebuildCountsActivityVisitsAgainWithoutMember() throws Exception {
        TestRunSpecs.install();
        Graph g = new Graph();
        ActivityNode a = new ActivityNode("A");
        seedActivity(g, "A", a);
        State s = stateInActivity("A");

        for (int t = 1; t <= 10; t++) {
            g.markVisited(s, t);
        }
        assertEquals(10, a.getVisitedCount());

        // Rebuild replay calls markVisited(State) 4 times for states of the activity.
        g.setRebuilding(true);
        for (int t = 11; t <= 14; t++) {
            g.markVisited(s, t);
        }
        g.setRebuilding(false);

        assertEquals("each replayed markVisited(State) counts the activity", 14, a.getVisitedCount());
        assertEquals(14, s.getVisitedCount());
    }

    @Test
    public void rebuildPreservesActivityVisitsWithMember() throws Exception {
        TestRunSpecs.install("ape.addons", "true");
        Graph g = new Graph();
        ActivityNode a = new ActivityNode("A");
        seedActivity(g, "A", a);
        State s = stateInActivity("A");

        for (int t = 1; t <= 10; t++) {
            g.markVisited(s, t);
        }
        g.setRebuilding(true);
        for (int t = 11; t <= 14; t++) {
            g.markVisited(s, t);
        }
        g.setRebuilding(false);

        assertEquals("activity count preserved across rebuild replay", 10, a.getVisitedCount());
        assertEquals(14, s.getVisitedCount());
    }
}

package com.android.commands.monkey.ape.model;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Map;

import org.junit.Test;

/**
 * INV-MODEL-11 (task 2.3): {@code Model.rebuild()} must be count-preserving for activity (and edge)
 * counters. The full rebuild path constructs {@code GUITree}/{@code Naming} graphs that need the
 * Android runtime, so this exercises the exact code the fix changed — {@link Graph#markVisited(State)}
 * gated by {@link Graph#setRebuilding(boolean)} — which is where the activity double-count occurred.
 *
 * <p>During replay the flag is set, so activities are NOT re-marked (their counts are preserved,
 * being abstraction-invariant and surviving {@code ActivityNode.removeState}). Per-state source
 * counters are intentionally still incremented — the deferred concern documented on INV-MODEL-11.
 *
 * <p>Uses {@code sun.misc.Unsafe} to allocate a {@link State} without its Android-dependent
 * constructor (same technique as {@code StateTest}).
 */
public class RebuildCountTest {

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
}

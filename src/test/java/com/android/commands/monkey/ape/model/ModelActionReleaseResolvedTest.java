package com.android.commands.monkey.ape.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.junit.Before;
import org.junit.Test;

import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeNode;

/**
 * rearch-06 group 4 (V24): {@link ModelAction#releaseResolved(GUITree)} must drop the references
 * into a released tree, leave an action resolved against a live tree alone, and never touch
 * {@code resolvedSaturation} — the one cross-step semantic output of a resolve (audit row B4,
 * INV-MODEL-19).
 *
 * <p>{@code GUITree} and {@code GUITreeNode} are {@code Unsafe}-allocated: they are assembled from
 * {@code AccessibilityNodeInfo} in production and cannot be built on the JVM (rearch-01 finding
 * 2.1-a). Here they are only ever compared by identity and stored, which is exactly what
 * {@code resolveAt}/{@code releaseResolved} do with them, so blank instances are faithful.
 * {@code GUITreeAction}'s constructor is pure field assignment, so the resolve path runs unchanged.
 *
 * <p>The {@code Model.release} sweep that calls this method (task 4.2) is not unit-testable on the
 * JVM: it goes through {@code namingManager.release} and needs a {@code State} whose construction
 * runs {@code NamerFactory.decodeActions} (the wall rearch-01 recorded as finding 2.1-b). What is
 * asserted here is the mechanism; the wiring is one null-guarded loop at the single release point
 * both call sites already reach.
 */
public class ModelActionReleaseResolvedTest {

    private static final int STEP = 7;
    private static final int THROTTLE = 200;

    private GUITree tree;
    private GUITree liveTree;
    private GUITreeNode node;

    @Before
    public void setUp() throws Exception {
        tree = blank(GUITree.class);
        liveTree = blank(GUITree.class);
        node = blank(GUITreeNode.class);
    }

    @Test
    public void releaseClearsEveryReferenceIntoTheReleasedTree() {
        ModelAction action = targeted();
        action.resolveAt(STEP, THROTTLE, tree, node, new GUITreeNode[] { node });

        assertSame(tree, action.getResolvedGUITree());
        assertNotNull(action.getResolvedGUITreeAction());
        assertSame(node, action.getResolvedNode());
        assertNotNull(action.getResolvedNodes());
        assertTrue(action.isResolvedAt(STEP));

        action.releaseResolved(tree);

        assertNull("resolvedTree still reachable", action.getResolvedGUITree());
        assertNull("resolvedGUITreeAction still reachable", action.getResolvedGUITreeAction());
        assertNull("resolvedNode still reachable", action.getResolvedNode());
        assertNull("resolvedNodes still reachable", action.getResolvedNodes());
        assertFalse(action.isResolvedAt(STEP));
    }

    @Test
    public void isResolvedAtIsFalseForEveryStepAfterRelease() {
        ModelAction action = targeted();
        action.resolveAt(STEP, THROTTLE, tree, node, new GUITreeNode[] { node });

        action.releaseResolved(tree);

        for (int t = 0; t <= 32; t++) {
            assertFalse("resolved at step " + t + " after release", action.isResolvedAt(t));
        }
    }

    @Test
    public void aReleasedTargetlessActionIsIndistinguishableFromANeverResolvedOne() {
        // The timestamp goes back to -1, the field's own initial value, so a released action is in
        // the pre-resolve state rather than a third state. For a targetless action isResolvedAt
        // short-circuits on requireTarget(), so -1 answers true for both — it is the unresolved
        // sentinel, never an agent step (steps start at 0 and increase). Pinning the equivalence is
        // what keeps that from looking like a defect introduced by the release.
        ModelAction released = targetless();
        released.resolveAt(STEP, THROTTLE, tree, null, null);
        released.releaseResolved(tree);

        ModelAction neverResolved = targetless();

        for (int t = -1; t <= 32; t++) {
            assertEquals("divergence at " + t, neverResolved.isResolvedAt(t), released.isResolvedAt(t));
        }
        assertNull(released.getResolvedGUITree());
        assertNull(released.getResolvedGUITreeAction());
    }

    @Test
    public void saturationSurvivesTheRelease() {
        ModelAction action = targeted();
        action.visitedAt(3);
        action.resolveAt(STEP, THROTTLE, tree, node, new GUITreeNode[] { node });
        assertEquals(1.0F, action.getResolvedSaturation(), 0.0F);
        assertTrue(action.isSaturated());

        action.releaseResolved(tree);

        // Release changes retention, never a decision input: isSaturated() feeds the action filters
        // and the SATA ladder on later steps.
        assertEquals(1.0F, action.getResolvedSaturation(), 0.0F);
        assertTrue(action.isSaturated());
    }

    @Test
    public void provenanceAndPrioritySurviveTheRelease() {
        ModelAction action = targeted();
        action.setDecisionSource(ModelAction.DecisionSource.LLM);
        int priority = action.getPriority();
        action.resolveAt(STEP, THROTTLE, tree, node, new GUITreeNode[] { node });

        action.releaseResolved(tree);

        assertEquals(ModelAction.DecisionSource.LLM, action.getDecisionSource());
        assertEquals(priority, action.getPriority());
    }

    @Test
    public void anActionResolvedAgainstALiveTreeIsUntouched() {
        ModelAction action = targeted();
        action.resolveAt(STEP, THROTTLE, liveTree, node, new GUITreeNode[] { node });

        action.releaseResolved(tree);

        assertSame(liveTree, action.getResolvedGUITree());
        assertSame(node, action.getResolvedNode());
        assertNotNull(action.getResolvedGUITreeAction());
        assertTrue(action.isResolvedAt(STEP));
    }

    @Test
    public void releasingAnUnresolvedActionIsASafeNoOp() {
        ModelAction action = targeted();

        action.releaseResolved(tree);

        assertNull(action.getResolvedGUITree());
        assertNull(action.getResolvedNode());
        assertFalse(action.isResolvedAt(STEP));
    }

    @Test
    public void aGuardedReaderSkipsAClearedActionExactlyAsItSkipsAStaleResolve() {
        // What every scoring pass and adjustActionsByGUITree do is `if (!isResolvedAt(timestamp))
        // continue`. A released action must reach that guard the same way a resolve from an earlier
        // step does — no new case for the readers to know about.
        ModelAction stale = targeted();
        stale.resolveAt(STEP - 1, THROTTLE, tree, node, new GUITreeNode[] { node });

        ModelAction cleared = targeted();
        cleared.resolveAt(STEP, THROTTLE, tree, node, new GUITreeNode[] { node });
        cleared.releaseResolved(tree);

        assertFalse(stale.isResolvedAt(STEP));
        assertFalse(cleared.isResolvedAt(STEP));
    }

    @Test
    public void aReResolveAfterReleaseRestoresEverything() {
        ModelAction action = targeted();
        action.resolveAt(STEP, THROTTLE, tree, node, new GUITreeNode[] { node });
        action.releaseResolved(tree);

        action.resolveAt(STEP + 1, THROTTLE, liveTree, node, new GUITreeNode[] { node });

        assertSame(liveTree, action.getResolvedGUITree());
        assertSame(node, action.getResolvedNode());
        assertTrue(action.isResolvedAt(STEP + 1));
    }

    // --- fixtures -------------------------------------------------------------------------

    /** A model action that requires a target (the resolve path that fills nodes and saturation). */
    private static ModelAction targeted() {
        return new ModelAction(null, ActionType.MODEL_CLICK);
    }

    /** A targetless model action — resolveAt returns right after setting tree and tree-action. */
    private static ModelAction targetless() {
        return new ModelAction(null, ActionType.MODEL_BACK);
    }

    @SuppressWarnings("unchecked")
    private static <T> T blank(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }
}

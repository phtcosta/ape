package com.android.commands.monkey.ape.tree;

import android.graphics.Rect;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * O4 (action-selection INV-SEL-10): {@code patchGUITree} records that it wrote a node's
 * clickability, in both directions.
 *
 * <p>The bit exists because the write is otherwise invisible: {@code ActionPatchNamer} prints the
 * already-patched value, so no artifact distinguished a click on a natively clickable widget from a
 * click on one the patch fabricated. Offline reconstruction could only estimate the share through a
 * (class, resource-id) type proxy — a 36.0% point estimate at type level, itself conditional on the
 * patch log being complete, which in 64 of 800 calibration runs it was not.
 */
public class GUITreePatchProvenanceTest {

    private static GUITreeNode node(GUITreeNode parent, boolean clickable, Rect bounds) {
        GUITreeNode node = new GUITreeNode(parent);
        node.setClassName("android.widget.FrameLayout");
        node.setBoundsInScreen(bounds);
        node.setClickable(clickable);
        if (parent != null) parent.addChild(node);
        return node;
    }

    @Test
    public void aChildMadeClickableByThePatchIsMarked() {
        // A clickable container whose single child covers it: the child becomes the click target.
        GUITreeNode container = node(null, true, new Rect(0, 0, 400, 200));
        GUITreeNode child = node(container, false, new Rect(0, 0, 400, 200));

        GUITreeBuilder.patchGUITree(container);

        assertTrue("the patch granted the child its clickability", child.isClickable());
        assertTrue(child.isPatchedClickable());
    }

    @Test
    public void aParentDemotedByThePatchIsAlsoMarked() {
        // The children cover the container's centre, so the container loses clickability. The bit
        // records that the patch *wrote* the attribute, in either direction.
        GUITreeNode container = node(null, true, new Rect(0, 0, 400, 200));
        node(container, false, new Rect(0, 0, 400, 200));

        GUITreeBuilder.patchGUITree(container);

        assertFalse(container.isClickable());
        assertTrue(container.isPatchedClickable());
    }

    @Test
    public void aNativelyClickableNodeIsNotMarked() {
        // Nothing to patch: the container is not clickable, so doPatchingChildren declines and the
        // child keeps the clickability the AccessibilityNodeInfo reported.
        GUITreeNode container = node(null, false, new Rect(0, 0, 400, 200));
        GUITreeNode child = node(container, true, new Rect(0, 0, 400, 200));

        GUITreeBuilder.patchGUITree(container);

        assertTrue(child.isClickable());
        assertFalse("a native attribute must never be reported as patched", child.isPatchedClickable());
        assertFalse(container.isPatchedClickable());
    }

    @Test
    public void nodesStartUnpatched() {
        assertFalse(new GUITreeNode(null).isPatchedClickable());
    }
}

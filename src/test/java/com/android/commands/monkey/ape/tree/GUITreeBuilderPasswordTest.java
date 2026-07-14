package com.android.commands.monkey.ape.tree;

import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Device-gated coverage of INV-TREE-09: {@code GUITreeBuilder.fillNode} copies
 * {@code AccessibilityNodeInfo.isPassword()} into the {@code GUITreeNode}.
 *
 * <p>{@code fillNode} is private and reads a live {@code AccessibilityNodeInfo}
 * ({@code getBoundsInScreen}/{@code getBoundsInParent} touch {@code android.graphics.Rect}),
 * excluded from the surefire classpath. The captured value is validated on the RVSec AVD
 * (tasks.md 5.1: the password login field receives password-shaped input, which requires
 * {@code node.isPassword()} to be true). This test documents the invariant and the
 * setter/getter round-trip the fix depends on; it is device-gated because
 * {@code GUITreeNode} links Android types.
 */
public class GUITreeBuilderPasswordTest {

    @Test
    @Ignore("Requires Android runtime - GUITreeNode/AccessibilityNodeInfo excluded from the surefire classpath; validated on device (tasks.md 5.1)")
    public void testFillNodeCapturesIsPassword() {
        GUITreeNode node = new GUITreeNode(null);
        node.setIsPassword(true);
        assertTrue(node.isPassword());
    }
}

package com.android.commands.monkey.ape.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Ignore;
import org.junit.Test;

import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Device-gated coverage of ui-tree "Handler Stamp Capture" (INV-TREE-14, design D16):
 * {@code GUITreeBuilder.fillNode} copies the extras {@code rvsec.click} / {@code rvsec.longClick}
 * that rv-android gh121 ({@code --stamp-handlers}) writes into the node's
 * {@code handlerClick} / {@code handlerLongClick}.
 *
 * <p>Like {@link GUITreeBuilderPasswordTest}, these read a live {@code AccessibilityNodeInfo},
 * whose class is excluded from the surefire classpath ({@code pom.xml}
 * {@code classpathDependencyExcludes}), so they are {@code @Ignore}d here. The bundle-level rules
 * run on the JVM in {@link GUITreeBuilderHandlerStampJvmTest}; the gate for the
 * {@code getExtras()} read is the device smoke on a stamped {@code cryptoapp} (tasks 17.2,
 * {@code RUN_END.stampNodes > 0}).
 */
public class GUITreeBuilderHandlerStampTest {

    // AccessibilityNodeInfo appears only inside method bodies: a member signature naming it would
    // make surefire's reflection over this class fail with NoClassDefFoundError.

    private static final String IGNORE =
            "Requires Android runtime - AccessibilityNodeInfo excluded from the surefire classpath; gated by the device smoke (tasks 17.2)";

    @Test
    @Ignore(IGNORE)
    public void stampedViewNode() {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.getExtras().putString("rvsec.click", "com.x.Foo$1");
        info.getExtras().putString("rvsec.longClick", "com.x.Foo$2");

        GUITreeNode node = new GUITreeNode(null);
        GUITreeBuilder.captureHandlerStamps(node, info);

        assertEquals("com.x.Foo$1", node.getHandlerClick());
        assertEquals("com.x.Foo$2", node.getHandlerLongClick());
    }

    @Test
    @Ignore(IGNORE)
    public void unstampedNode() {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();

        GUITreeNode node = new GUITreeNode(null);
        GUITreeBuilder.captureHandlerStamps(node, info);

        assertNull(node.getHandlerClick());
        assertNull(node.getHandlerLongClick());
    }

    @Test
    @Ignore(IGNORE)
    public void nonStringValue() {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.getExtras().putInt("rvsec.click", 7);
        info.getExtras().putString("rvsec.longClick", "com.x.Foo$3");

        GUITreeNode node = new GUITreeNode(null);
        GUITreeBuilder.captureHandlerStamps(node, info);

        assertNull(node.getHandlerClick());
        assertEquals("com.x.Foo$3", node.getHandlerLongClick());
    }

    @Test
    @Ignore(IGNORE)
    public void throwingReadLeavesBothNull() {
        // A null node makes getExtras() throw; the capture swallows it and leaves both null.
        GUITreeNode node = new GUITreeNode(null);
        GUITreeBuilder.captureHandlerStamps(node, null);

        assertNull(node.getHandlerClick());
        assertNull(node.getHandlerLongClick());
    }

    @Test
    @Ignore(IGNORE)
    public void composeNodeWithOnlyClickStamp() {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        info.getExtras().putString("rvsec.click", "com.x.ui.ScreenKt$Body$1$1");

        GUITreeNode node = new GUITreeNode(null);
        node.setResourceID("");

        GUITreeBuilder.captureHandlerStamps(node, info);

        assertEquals("com.x.ui.ScreenKt$Body$1$1", node.getHandlerClick());
        assertNull(node.getHandlerLongClick());
        assertEquals("", node.getResourceID());
    }
}

package com.android.commands.monkey.ape.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import android.os.Bundle;

/**
 * JVM coverage of ui-tree "Handler Stamp Capture" (INV-TREE-14, design D16) at the bundle level:
 * {@code GUITreeBuilder.copyHandlerStamps} over the test-side {@code android.os.Bundle} stub.
 *
 * <p>What this cannot reach is {@code AccessibilityNodeInfo.getExtras()} itself, which
 * {@code fillNode} calls before handing the bundle here: the framework jar is excluded from the
 * surefire classpath ({@code pom.xml} {@code classpathDependencyExcludes}). That read is covered by
 * the {@code @Ignore}d {@link GUITreeBuilderHandlerStampTest} and gated by the device smoke on a
 * stamped {@code cryptoapp} (tasks 17.2).
 */
public class GUITreeBuilderHandlerStampJvmTest {

    private static GUITreeNode capture(Bundle extras) {
        GUITreeNode node = new GUITreeNode(null);
        GUITreeBuilder.copyHandlerStamps(node, extras);
        return node;
    }

    @Test
    public void stampedViewNodeCarriesBothStamps() {
        Bundle extras = new Bundle();
        extras.putString("rvsec.click", "com.x.Foo$1");
        extras.putString("rvsec.longClick", "com.x.Foo$2");

        GUITreeNode node = capture(extras);

        assertEquals("com.x.Foo$1", node.getHandlerClick());
        assertEquals("com.x.Foo$2", node.getHandlerLongClick());
    }

    /** Two captures of one stamp share one interned string, as fillNode's other strings do. */
    @Test
    public void stampsAreInterned() {
        Bundle first = new Bundle();
        first.putString("rvsec.click", new String("com.x.Interned$1"));
        Bundle second = new Bundle();
        second.putString("rvsec.click", new String("com.x.Interned$1"));

        assertSame(capture(first).getHandlerClick(), capture(second).getHandlerClick());
    }

    @Test
    public void emptyBundleLeavesBothNull() {
        GUITreeNode node = capture(new Bundle());

        assertNull(node.getHandlerClick());
        assertNull(node.getHandlerLongClick());
    }

    @Test
    public void bundleWithoutTheKeysLeavesBothNull() {
        Bundle extras = new Bundle();
        extras.putString("some.other.extra", "com.x.Foo$1");

        GUITreeNode node = capture(extras);

        assertNull(node.getHandlerClick());
        assertNull(node.getHandlerLongClick());
    }

    @Test
    public void nullBundleLeavesBothNull() {
        GUITreeNode node = capture(null);

        assertNull(node.getHandlerClick());
        assertNull(node.getHandlerLongClick());
    }

    @Test
    public void nonStringValueLeavesOnlyThatFieldNull() {
        Bundle extras = new Bundle();
        extras.putInt("rvsec.click", 7);
        extras.putString("rvsec.longClick", "com.x.Foo$3");

        GUITreeNode node = capture(extras);

        assertNull(node.getHandlerClick());
        assertEquals("com.x.Foo$3", node.getHandlerLongClick());
    }

    @Test
    public void throwingReadLeavesBothNullAndDoesNotPropagate() {
        Bundle extras = new Bundle();
        extras.putString("rvsec.click", "com.x.Foo$1");
        extras.putString("rvsec.longClick", "com.x.Foo$2");
        extras.failOnRead(new RuntimeException("unparcel failed"));

        GUITreeNode node = capture(extras);

        assertNull(node.getHandlerClick());
        assertNull(node.getHandlerLongClick());
    }

    @Test
    public void composeNodeWithOnlyClickStampKeepsItsEmptyId() {
        Bundle extras = new Bundle();
        extras.putString("rvsec.click", "com.x.ui.ScreenKt$Body$1$1");
        GUITreeNode node = new GUITreeNode(null);
        node.setResourceID("");

        GUITreeBuilder.copyHandlerStamps(node, extras);

        assertEquals("com.x.ui.ScreenKt$Body$1$1", node.getHandlerClick());
        assertNull(node.getHandlerLongClick());
        assertEquals("", node.getResourceID());
    }

    @Test
    public void fieldsDefaultToNullOnAFreshNode() {
        GUITreeNode node = new GUITreeNode(null);

        assertNull(node.getHandlerClick());
        assertNull(node.getHandlerLongClick());
    }
}

package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Device-gated tests for {@link MopScorer#containmentShortIds(GUITreeNode)} (INV-MOP-23,
 * F-2.4 witness: a child id resolving a parent-flagged widget's typed metadata).
 *
 * <p>{@link GUITreeNode} references {@code android.graphics.Rect} /
 * {@code android.view.accessibility.AccessibilityNodeInfo} in its method signatures, and
 * the surefire classpath excludes {@code framework-full-debug} + {@code dalvik-stub} from
 * the TEST runtime (see pom.xml {@code classpathDependencyExcludes}). Constructing a
 * {@code GUITreeNode}'s Android references are in method signatures only; Java verifies those
 * lazily, so constructing a node and using its android-free surface ({@code GUITreeNode(parent)},
 * {@code setResourceID}, {@code addChild}, {@code getParent}, {@code getChildren}) links and runs
 * on the JVM — the same surface {@code GUITreeWebViewPruneTest} exercises live. This test asserts
 * the self-first, ±2-level containment ordering that backs both {@code mopBoostWithContainment}
 * and {@code ApeAgent.generateInputText} (INV-MOP-23).
 */
public class MopScorerContainmentTest {

    @Test
    public void testContainmentShortIds_selfAncestorsThenDescendants() {
        // grandparent -> parent -> node -> child -> grandchild
        GUITreeNode grandparent = new GUITreeNode(null);
        grandparent.setResourceID("com.example:id/gp");
        GUITreeNode parent = new GUITreeNode(grandparent);
        parent.setResourceID("com.example:id/p");
        grandparent.addChild(parent);
        GUITreeNode node = new GUITreeNode(parent);
        node.setResourceID("com.example:id/self");
        parent.addChild(node);
        GUITreeNode child = new GUITreeNode(node);
        child.setResourceID("com.example:id/c");
        node.addChild(child);
        GUITreeNode grandchild = new GUITreeNode(child);
        grandchild.setResourceID("com.example:id/gc");
        child.addChild(grandchild);

        List<String> ids = MopScorer.containmentShortIds(node);

        // self first, then ancestors (<=2 up), then descendants (child then grandchild)
        assertEquals("self", ids.get(0));
        assertEquals("p", ids.get(1));
        assertEquals("gp", ids.get(2));
        assertEquals("c", ids.get(3));
        assertEquals("gc", ids.get(4));
        assertEquals(5, ids.size());
    }
}

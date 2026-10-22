package com.android.commands.monkey.ape.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * WebView-pruning correctness (INV-TREE-10, INV-TREE-11).
 *
 * <p>Both halves of the pruning path are exercised on the JVM:
 * <ul>
 *   <li>{@link GUITreeNode#clearChildren()} must empty <em>all</em> DOM children and the in-memory
 *       child list. A forward index walk over the live {@code NodeList} removes only every other
 *       node. Verified against a real {@code org.w3c.dom} element built by a
 *       {@link DocumentBuilder}.</li>
 *   <li>{@link GUITreeBuilder#countActionableDescendants(GUITreeNode)} must count only actionable
 *       (clickable / checkable / scrollable / long-clickable) descendants — the metric the
 *       {@code checkAndRemoveWebView} threshold has always named in its comment.</li>
 * </ul>
 *
 * <p>These use only the android-free surface of {@link GUITreeNode} (no-arg-parent ctor,
 * {@code addChild}, {@code setDomNode}, the boolean action getters), so no framework class is
 * resolved at runtime — the framework/dalvik jars are excluded from the surefire classpath. The
 * {@code NamingFactory} guard (INV-NAME-14) and {@code MonkeySourceApe.generateClickEventAt}
 * off-screen drop (INV-EXPL-19) are Android-gated and covered by device tasks 5.1/5.2.
 */
public class GUITreeWebViewPruneTest {

    private static Element elementWithChildren(int n) throws Exception {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = builder.newDocument();
        Element parent = doc.createElement("node");
        for (int i = 0; i < n; i++) {
            parent.appendChild(doc.createElement("child" + i));
        }
        return parent;
    }

    /** INV-TREE-10: 10 DOM children → 0 after clearChildren, and the in-memory list is empty. */
    @Test
    public void clearChildrenEmptiesEvenChildCount() throws Exception {
        Element parent = elementWithChildren(10);
        assertEquals(10, parent.getChildNodes().getLength());

        GUITreeNode node = new GUITreeNode(null);
        node.setDomNode(parent);
        for (int i = 0; i < 10; i++) {
            node.addChild(new GUITreeNode(null));
        }
        assertEquals(10, node.getChildCount());
        node.clearChildren();

        assertEquals(0, parent.getChildNodes().getLength());
        assertNull(parent.getFirstChild());
        assertEquals(0, node.getChildCount());
    }

    /**
     * INV-TREE-10: an odd child count, where a skipping walk would also leave the final node
     * behind.
     */
    @Test
    public void clearChildrenEmptiesOddChildCount() throws Exception {
        Element parent = elementWithChildren(7);
        GUITreeNode node = new GUITreeNode(null);
        node.setDomNode(parent);
        node.clearChildren();

        assertEquals(0, parent.getChildNodes().getLength());
        assertNull(parent.getFirstChild());
        assertEquals(0, node.getChildCount());
    }

    private static GUITreeNode leaf(boolean clickable, boolean checkable, boolean scrollable,
            boolean longClickable) {
        GUITreeNode n = new GUITreeNode(null);
        n.setClickable(clickable);
        n.setCheckable(checkable);
        n.setScrollable(scrollable);
        n.setLongClickable(longClickable);
        return n;
    }

    /**
     * INV-TREE-11 (spec scenario): a WebView subtree with 100 non-actionable descendants and 10
     * actionable ones is measured as 10 — under the default threshold of 64, so it is kept.
     */
    @Test
    public void countsActionableDescendantsOnly() {
        GUITreeNode root = new GUITreeNode(null);
        for (int i = 0; i < 100; i++) {
            root.addChild(leaf(false, false, false, false));
        }
        for (int i = 0; i < 10; i++) {
            root.addChild(leaf(true, false, false, false));
        }
        assertEquals(10, GUITreeBuilder.countActionableDescendants(root));
    }

    /** INV-TREE-11: each actionable flavour counts once; nesting is traversed. */
    @Test
    public void countsNestedActionableDescendants() {
        GUITreeNode root = new GUITreeNode(null);

        GUITreeNode clickable = leaf(true, false, false, false);
        GUITreeNode scrollable = leaf(false, false, true, false);
        clickable.addChild(scrollable);          // nested actionable
        clickable.addChild(leaf(false, false, false, false)); // nested non-actionable

        GUITreeNode inert = leaf(false, false, false, false);
        inert.addChild(leaf(false, false, false, true)); // nested long-clickable

        root.addChild(clickable);
        root.addChild(inert);
        root.addChild(leaf(false, true, false, false));  // checkable

        // actionable: clickable, scrollable, long-clickable leaf, checkable = 4
        assertEquals(4, GUITreeBuilder.countActionableDescendants(root));
    }
}

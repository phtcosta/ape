package com.android.commands.monkey.ape.agent.pipeline;

import org.junit.Test;

import com.android.commands.monkey.ape.tree.DumpTrees;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import android.graphics.Rect;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The dynamic-region predicate (INV-RTR-26, design D8), first on real screens, then clause by clause.
 *
 * <p>The fixtures are {@code uiautomator dump --compressed} files captured on the API 30 emulator
 * from the experiment's APKs ({@code evidence.md}). The compressed dump is the tree the jar sees:
 * not-important views are left out on both sides. The LibGDX screens are the only ones the rule may
 * accept; every stuck dialog, camera capture, splash and ordinary screen is one it must reject.
 */
public class DynamicRegionTest {

    private static final String VIEW = "android.view.View";

    private static boolean dump(String name) throws Exception {
        return LlmGate.hasDynamicRegion(DumpTrees.load("/dynamic-region/" + name + ".xml"));
    }

    // -------------------------------------------------------------------------
    // Real screens
    // -------------------------------------------------------------------------

    @Test
    public void libgdxScreensHaveARegion() throws Exception {
        assertTrue(dump("retrowars_menu"));
        assertTrue(dump("retrowars_ingame"));
        assertTrue(dump("spd_title"));
    }

    @Test
    public void progressAndTextDialogsHaveNone() throws Exception {
        for (String name : new String[] {"mtgfam_dbupdate", "urlchecker_hosts", "smoking_loading",
                "flyingcarpet_about", "osm_empty_languages_dialog", "osm_layout_desc_dialog",
                "osm_availlayouts_connecting"}) {
            assertFalse(name, dump(name));
        }
    }

    @Test
    public void cameraCapturesHaveNone() throws Exception {
        for (String name : new String[] {"createpdf_capture", "deepr_capture", "paperwork_qrscan"}) {
            assertFalse(name, dump(name));
        }
    }

    @Test
    public void splashAndLoadingScreensHaveNone() throws Exception {
        for (String name : new String[] {"myne_splash", "myne_welcome", "myne_home_loading",
                "myne_detail_loading", "passport_main_withad"}) {
            assertFalse(name, dump(name));
        }
    }

    @Test
    public void ordinaryScreensHaveNone() throws Exception {
        for (String name : new String[] {"ord_flyingcarpet_main", "ord_mtgfam_main", "ord_myne_home",
                "ord_osmtracker_main", "ord_smoking_settings", "ord_urlchecker_main",
                "ord_urlchecker_modules", "ord_urlchecker_tutorial"}) {
            assertFalse(name, dump(name));
        }
    }

    // -------------------------------------------------------------------------
    // Synthetic trees, one clause at a time. The baseline is a 1000x1000 root holding one focusable,
    // text-less android.view.View leaf of the given bounds, which passes every clause at 0.5.
    // -------------------------------------------------------------------------

    private static GUITreeNode node(GUITreeNode parent, String className, Rect bounds) {
        GUITreeNode node = new GUITreeNode(parent);
        node.setClassName(className);
        node.setBoundsInScreen(bounds);
        if (parent != null) {
            parent.addChild(node);
        }
        return node;
    }

    private static GUITreeNode root() {
        return node(null, "android.widget.FrameLayout", new Rect(0, 0, 1000, 1000));
    }

    private static GUITreeNode surface(GUITreeNode parent, Rect bounds) {
        GUITreeNode leaf = node(parent, VIEW, bounds);
        leaf.setFocusable(true);
        return leaf;
    }

    private static boolean region(GUITreeNode root) throws Exception {
        return LlmGate.hasDynamicRegion(DumpTrees.treeOf(root));
    }

    @Test
    public void areaThresholdIsHalfTheRoot() throws Exception {
        GUITreeNode below = root();
        surface(below, new Rect(0, 0, 1000, 490));
        assertFalse("0.49 of the root", region(below));

        GUITreeNode at = root();
        surface(at, new Rect(0, 0, 1000, 500));
        assertTrue("0.5 of the root", region(at));
    }

    @Test
    public void areaIsMeasuredInsideTheRoot() throws Exception {
        // A leaf larger than the root counts only the part it shares with it.
        GUITreeNode r = root();
        surface(r, new Rect(0, 510, 1000, 3000));
        assertFalse(region(r));
    }

    @Test
    public void textRejects() throws Exception {
        GUITreeNode r = root();
        surface(r, new Rect(0, 0, 1000, 1000)).setText("Loading");
        assertFalse(region(r));
    }

    @Test
    public void contentDescRejects() throws Exception {
        GUITreeNode r = root();
        surface(r, new Rect(0, 0, 1000, 1000)).setContentDesc("Map");
        assertFalse(region(r));
    }

    @Test
    public void inputIsRequired() throws Exception {
        GUITreeNode none = root();
        node(none, VIEW, new Rect(0, 0, 1000, 1000));
        assertFalse("not focusable, clickable or long-clickable", region(none));

        GUITreeNode clickable = root();
        node(clickable, VIEW, new Rect(0, 0, 1000, 1000)).setClickable(true);
        assertTrue(region(clickable));

        GUITreeNode longClickable = root();
        node(longClickable, VIEW, new Rect(0, 0, 1000, 1000)).setLongClickable(true);
        assertTrue(region(longClickable));
    }

    @Test
    public void patchedClickabilityDoesNotCount() throws Exception {
        // patchGUITree copies a clickable container's clickability onto its label-less child; the
        // child does not take touch itself, so it is no region (WR-01).
        GUITreeNode patched = root();
        GUITreeNode container = node(patched, "android.widget.FrameLayout",
                new Rect(0, 0, 1000, 1000));
        container.setClickable(true);
        GUITreeNode leaf = node(container, VIEW, new Rect(0, 0, 1000, 1000));
        assertFalse(LlmGate.hasDynamicRegion(DumpTrees.patchedTreeOf(patched)));
        assertTrue("the patch ran", leaf.isClickable() && leaf.isPatchedClickable());

        GUITreeNode nativeClick = root();
        GUITreeNode nativeContainer = node(nativeClick, "android.widget.FrameLayout",
                new Rect(0, 0, 1000, 1000));
        nativeContainer.setClickable(true);
        node(nativeContainer, VIEW, new Rect(0, 0, 1000, 1000)).setClickable(true);
        assertTrue("the same leaf, natively clickable",
                LlmGate.hasDynamicRegion(DumpTrees.patchedTreeOf(nativeClick)));
    }

    @Test
    public void aChildRejects() throws Exception {
        GUITreeNode r = root();
        GUITreeNode parent = surface(r, new Rect(0, 0, 1000, 1000));
        node(parent, "android.widget.TextView", new Rect(0, 0, 10, 10));
        assertFalse(region(r));
    }

    @Test
    public void classMustBeExactlyView() throws Exception {
        GUITreeNode r = root();
        node(r, "android.view.SurfaceView", new Rect(0, 0, 1000, 1000)).setFocusable(true);
        assertFalse(region(r));
    }

    @Test
    public void composeViewSubtreeIsNotEntered() throws Exception {
        GUITreeNode r = root();
        GUITreeNode compose = node(r, "androidx.compose.ui.platform.ComposeView",
                new Rect(0, 0, 1000, 1000));
        GUITreeNode inner = node(compose, VIEW, new Rect(0, 0, 1000, 1000));
        surface(inner, new Rect(0, 0, 1000, 1000));
        assertFalse(region(r));
    }

    @Test
    public void webViewSubtreeIsNotEntered() throws Exception {
        GUITreeNode r = root();
        GUITreeNode web = node(r, "android.webkit.WebView", new Rect(0, 0, 1000, 1000));
        surface(web, new Rect(0, 0, 1000, 1000));
        assertFalse(region(r));
    }

    @Test
    public void aRegionBesideWidgetsCounts() throws Exception {
        GUITreeNode r = root();
        node(r, "android.widget.Button", new Rect(0, 0, 100, 100)).setClickable(true);
        surface(r, new Rect(0, 100, 1000, 1000));
        assertTrue(region(r));
    }

    @Test
    public void nullTreeHasNone() {
        assertFalse(LlmGate.hasDynamicRegion(null));
    }

    @Test
    public void emptyRootBoundsHaveNone() throws Exception {
        GUITreeNode r = node(null, "android.widget.FrameLayout", new Rect(0, 0, 0, 0));
        surface(r, new Rect(0, 0, 1000, 1000));
        assertFalse(region(r));
    }
}

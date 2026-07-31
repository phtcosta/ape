package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Coordinate mapping in {@link LlmRouter#mapToModelAction} — the ActionType filter (B6(i)), the
 * fixTextEdit conversion (B6(iv)) and edge-based snapping (B4).
 *
 * <p>Runs on the JVM: the surefire classpath carries the {@code android.graphics.Rect} stub instead
 * of the framework jar, so resolved widget bounds are real and both matching passes execute exactly
 * as they do on a device.
 */
public class LlmRouterCoordinateMappingTest {

    private static final int W = 1080;
    private static final int H = 1794;

    private static LlmRouter router() {
        return new LlmRouter(new java.util.Random(42));
    }

    /** Minimal {@link Name}: the mapping only ever asks it for an XPath. */
    private static final class TestName implements Name {
        private final String xpath;

        TestName(String xpath) { this.xpath = xpath; }

        public Namer getNamer() { return null; }
        public Name getLocalName() { return this; }
        public boolean refinesTo(Name other) { return this.equals(other); }
        public String toXPath() { return xpath; }
        public void appendXPathLocalProperties(StringBuilder sb) { }
        public void toXPath(StringBuilder sb) { sb.append(xpath); }
        public int compareTo(Name other) { return xpath.compareTo(other.toXPath()); }
    }

    /** A resolved candidate action of the given type over the given bounds. */
    private static ModelAction action(ActionType type, String className, Rect bounds) {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName(className);
        node.setBoundsInScreen(bounds);
        ModelAction action = new ModelAction(null, new TestName("//" + className + type), type);
        action.resolveAt(1, 0, null, node, new GUITreeNode[]{node});
        action.setValid(true);   // both matching passes skip invalid actions
        return action;
    }

    private static List<ModelAction> actions(ModelAction... items) {
        List<ModelAction> list = new ArrayList<>();
        for (ModelAction item : items) list.add(item);
        return list;
    }

    // -------------------------------------------------------------------------
    // B6(i) — the tool the model called constrains the ActionType
    // -------------------------------------------------------------------------

    @Test
    public void clickAnswerDoesNotReturnALongClickThatContainsThePoint() {
        ModelAction longClick = action(ActionType.MODEL_LONG_CLICK, "android.widget.TextView",
                new Rect(100, 200, 300, 250));

        ModelAction result = router().mapToModelAction(
                200, 230, "click", null, actions(longClick), null, W, H);

        assertNotSame("a click answer must never resolve to a MODEL_LONG_CLICK", longClick, result);
        // Both passes apply the filter, so the only remaining outcome for an in-bounds click
        // coordinate is the off-tree synthesis.
        assertTrue("the click falls through to off-tree synthesis", result instanceof LlmTapAction);
    }

    @Test
    public void clickAnswerDoesNotReturnAScrollThatContainsThePoint() {
        ModelAction scroll = action(ActionType.MODEL_SCROLL_TOP_DOWN, "android.widget.ListView",
                new Rect(0, 100, 1080, 900));

        ModelAction result = router().mapToModelAction(
                540, 500, "click", null, actions(scroll), null, W, H);

        assertNotSame(scroll, result);
        assertTrue(result instanceof LlmTapAction);
    }

    @Test
    public void clickAnswerStillReturnsTheClickItContains() {
        ModelAction click = action(ActionType.MODEL_CLICK, "android.widget.Button",
                new Rect(100, 200, 300, 250));
        ModelAction longClick = action(ActionType.MODEL_LONG_CLICK, "android.widget.Button",
                new Rect(100, 200, 300, 250));

        ModelAction result = router().mapToModelAction(
                200, 230, "click", null, actions(longClick, click), null, W, H);

        assertSame("the filter must not cost the click its own match", click, result);
    }

    @Test
    public void longClickStillFallsBackToClickWhenNoLongClickMatches() {
        ModelAction click = action(ActionType.MODEL_CLICK, "android.widget.Button",
                new Rect(100, 200, 300, 250));

        ModelAction result = router().mapToModelAction(
                200, 230, "long_click", null, actions(click), null, W, H);

        assertSame("long_click keeps its documented fallback to MODEL_CLICK", click, result);
    }
}

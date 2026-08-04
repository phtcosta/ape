package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Coordinate mapping in {@link CoordinateMapper#map} — the ActionType filter (B6(i)), the
 * fixTextEdit conversion (B6(iv)) and edge-based snapping (B4).
 *
 * <p>Runs on the JVM: the surefire classpath carries the {@code android.graphics.Rect} stub instead
 * of the framework jar, so resolved widget bounds are real and both matching passes execute exactly
 * as they do on a device.
 */
public class CoordinateMapperMappingTest {

    /**
     * A mapper wired from a plan carrying only the LLM feature, so the snap floor and the two
     * boundary bands are the jar defaults every assertion here was written against.
     */
    private static CoordinateMapper newMapper() {
        return new CoordinateMapper(
                TestRunSpecs.spec("ape.llmUrl", "http://localhost:9999/v1").llm());
    }

    private static final int W = 1080;
    private static final int H = 1794;

    private static CoordinateMapper router() {
        return newMapper();
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

        ModelAction result = router().map(
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

        ModelAction result = router().map(
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

        ModelAction result = router().map(
                200, 230, "click", null, actions(longClick, click), null, W, H);

        assertSame("the filter must not cost the click its own match", click, result);
    }

    @Test
    public void longClickStillFallsBackToClickWhenNoLongClickMatches() {
        ModelAction click = action(ActionType.MODEL_CLICK, "android.widget.Button",
                new Rect(100, 200, 300, 250));

        ModelAction result = router().map(
                200, 230, "long_click", null, actions(click), null, W, H);

        assertSame("long_click keeps its documented fallback to MODEL_CLICK", click, result);
    }

    // -------------------------------------------------------------------------
    // B6(iv) — fixTextEdit: a press on an input widget is a text entry
    // -------------------------------------------------------------------------

    /**
     * The two halves of fixTextEdit live in different classes: the router decides <i>that</i> the
     * decision is a text entry and returns the action whose dispatch carries text; {@code
     * ApeAgent.checkInput} supplies the text itself. This file covers the router half — the agent
     * half needs a live agent (MopData, foreground activity) and is covered by
     * {@link com.android.commands.monkey.ape.agent.ApeAgentLlmInputTest} for its guard and by the
     * device smoke for the generation.
     */
    @Test
    public void clickOnAnEditTextReturnsTheClickThatCarriesText() {
        // APE types a node's inputText while dispatching a MODEL_CLICK on it, so a click that
        // resolved to an input widget is already the text-entry action — nothing to swap.
        ModelAction click = action(ActionType.MODEL_CLICK, "android.widget.EditText",
                new Rect(50, 300, 400, 350));

        ModelAction result = router().map(
                225, 325, "click", null, actions(click), null, W, H);

        assertSame(click, result);
        assertTrue(ApePromptBuilder.isInputClass(result.getResolvedNode()));
    }

    @Test
    public void longClickOnAnInputWidgetIsConvertedToTheClickOnTheSameNode() {
        // MODEL_LONG_CLICK dispatch does not type, so a long press on an input widget must not be
        // returned bare: the click on the same node is what carries the generated text.
        GUITreeNode field = new GUITreeNode(null);
        field.setClassName("android.widget.SearchView");
        field.setBoundsInScreen(new Rect(50, 300, 400, 350));

        ModelAction longClick = new ModelAction(null, new TestName("//SearchView"),
                ActionType.MODEL_LONG_CLICK);
        longClick.resolveAt(1, 0, null, field, new GUITreeNode[]{field});
        longClick.setValid(true);
        ModelAction click = new ModelAction(null, new TestName("//SearchView"),
                ActionType.MODEL_CLICK);
        click.resolveAt(1, 0, null, field, new GUITreeNode[]{field});
        click.setValid(true);

        ModelAction result = router().map(
                225, 325, "long_click", null, actions(longClick, click), null, W, H);

        assertSame("the press is converted to the action that can carry text", click, result);
    }

    @Test
    public void longClickOnAnInputWidgetWithNoClickAvailableStaysAsItIs() {
        // Documented boundary: nothing on this widget can carry text, so there is nothing to
        // convert into.
        ModelAction longClick = action(ActionType.MODEL_LONG_CLICK, "android.widget.EditText",
                new Rect(50, 300, 400, 350));

        ModelAction result = router().map(
                225, 325, "long_click", null, actions(longClick), null, W, H);

        assertSame(longClick, result);
    }

    @Test
    public void typeTextAnswersAreUnaffected() {
        ModelAction click = action(ActionType.MODEL_CLICK, "android.widget.EditText",
                new Rect(50, 300, 400, 350));

        ModelAction result = router().map(
                225, 325, "type_text", "user@example.com", actions(click), null, W, H);

        assertSame("a well-behaved type_text answer keeps its existing path", click, result);
    }

    // -------------------------------------------------------------------------
    // B4 — snapping measures distance to the widget's edge, not to its centre
    // -------------------------------------------------------------------------

    @Test
    public void aPointJustAboveAWideBarSnapsToIt() {
        // The bar is 1080×150 at [0,200,1080,350]; the point is 20 px above its top edge and 95 px
        // from its centre (540,275). Tolerance is max(50, min(1080,150)/2) = 75.
        ModelAction bar = action(ActionType.MODEL_CLICK, "android.widget.Button",
                new Rect(0, 200, 1080, 350));

        ModelAction result = router().map(
                540, 180, "click", null, actions(bar), null, W, H);

        assertSame("edge distance 20 is within the 75 px tolerance", bar, result);
        // Regression lock on the geometry this replaced: the retired centre-distance rule measured
        // 95 px here and rejected the bar, leaving ~450 px of its own edge unsnappable.
        double centreDistance = Math.hypot(540 - 540, 275 - 180);
        assertEquals(95.0, centreDistance, 0.001);
        assertTrue("the retired rule would have rejected it", centreDistance > 75);
    }

    @Test
    public void aPointFarOutsideEveryWidgetStillDoesNotSnap() {
        ModelAction bar = action(ActionType.MODEL_CLICK, "android.widget.Button",
                new Rect(0, 200, 1080, 350));

        // 300 px below the bar's bottom edge — well past the 75 px tolerance.
        ModelAction result = router().map(
                540, 650, "click", null, actions(bar), null, W, H);

        assertTrue("no snap, so the coordinate becomes an off-tree tap", result instanceof LlmTapAction);
    }

    @Test
    public void aPressOnANonInputWidgetIsNotConverted() {
        ModelAction longClick = action(ActionType.MODEL_LONG_CLICK, "android.widget.Button",
                new Rect(100, 200, 300, 250));
        ModelAction click = action(ActionType.MODEL_CLICK, "android.widget.Button",
                new Rect(100, 200, 300, 250));

        ModelAction result = router().map(
                200, 230, "long_click", null, actions(longClick, click), null, W, H);

        assertSame("only input-capable widgets are converted", longClick, result);
    }

    // -------------------------------------------------------------------------
    // Nearest-widget geometry, which describes the screen rather than the decision
    // -------------------------------------------------------------------------

    @Test
    public void nearestReportsTheClosestTargetableWidgetByCentreDistance() {
        // Centre distance, not the edge distance the snap pass uses: this answers "how far off was
        // the model" offline, where a centre is the stable anchor across widget shapes.
        ModelAction near = action(ActionType.MODEL_CLICK, "android.widget.Button",
                new Rect(100, 200, 300, 300));   // centre (200, 250)
        ModelAction far = action(ActionType.MODEL_CLICK, "android.widget.TextView",
                new Rect(100, 900, 300, 1000));  // centre (200, 950)

        CoordinateMapper.Nearest nearest = router().nearest(actions(near, far), 200, 260);

        assertEquals("Button", nearest.className);
        assertEquals(10.0, nearest.distance, 0.001);
        assertEquals("both are targetable and both are counted", 2, nearest.widgetCount);
    }

    @Test
    public void nearestIsNotFilteredByTheToolTheModelCalled() {
        // Deliberately unfiltered: the report describes the screen, so a click answer's nearest
        // widget may be one no click could ever have matched. Filtering it would make the field
        // answer a different question from the one an offline reader asks of it.
        ModelAction longClickOnly = action(ActionType.MODEL_LONG_CLICK, "android.widget.TextView",
                new Rect(100, 200, 300, 300));

        CoordinateMapper.Nearest nearest = router().nearest(actions(longClickOnly), 200, 250);

        assertEquals("TextView", nearest.className);
        assertEquals(1, nearest.widgetCount);
    }

    @Test
    public void nearestOnAScreenWithNoTargetableWidgetSaysSo() {
        // The sentinel is -1 rather than 0, because 0 is a real distance: a coordinate landing dead
        // centre on a widget. An offline reader must be able to tell "nothing to measure against"
        // from "measured, and it was exact".
        CoordinateMapper.Nearest nearest = router().nearest(actions(), 200, 250);

        assertEquals("none", nearest.className);
        assertEquals(-1.0, nearest.distance, 0.001);
        assertEquals(0, nearest.widgetCount);
    }
}

package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * What the engine decides about an answer, and what it does with the text in one.
 *
 * <p>The pipeline itself is not exercised here and cannot be: its first step asks
 * {@link ScreenshotStep#deviceDimensions} for the display, which reaches an Android reflection
 * surface the JVM suite does not carry and raises {@code NoClassDefFoundError} — an {@code Error},
 * which the never-throws catch does not cover and is not meant to. The sequence is therefore
 * validated by the parity oracle and the device smoke, exactly as it was before the decomposition.
 *
 * <p>What the decomposition did make reachable is the engine's own judgement. Classifying an answer
 * and applying its text used to happen inside that device-gated method; they are pure functions of
 * what the mapping returned, so the fields an offline join reads — {@code result=},
 * {@code reason=}, {@code matched_class=} — are pinned below without a screen, a server or a model.
 */
public class LlmEngineTest {

    /** Minimal {@link Name}: nothing under test asks it for more than an XPath. */
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

    /** A resolved candidate action over a widget of the given class. */
    private static ModelAction action(ActionType type, String className) {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName(className);
        node.setBoundsInScreen(new Rect(100, 200, 300, 250));
        ModelAction action = new ModelAction(null, new TestName("//" + className), type);
        action.resolveAt(1, 0, null, node, new GUITreeNode[]{node});
        action.setValid(true);
        return action;
    }

    /** An action the mapping produced but that never resolved to a node. */
    private static ModelAction unresolvedAction() {
        return new ModelAction(null, new TestName("//unresolved"), ActionType.MODEL_CLICK);
    }

    private static ToolCallParser.ParsedAction parsed(String actionType, int x, int y, String text) {
        return new ToolCallParser.ParsedAction(actionType, x, y, text, null, "none");
    }

    // -------------------------------------------------------------------------
    // classify — what an answer was
    // -------------------------------------------------------------------------

    @Test
    public void anOffTreeTapIsCountedAsItsOwnOutcome() {
        // Separate from matched precisely because it matched nothing: the coordinate is honest
        // about being off-tree, and folding it into either neighbour would hide the effect.
        LlmEngine.Verdict verdict = LlmEngine.classify(
                new LlmTapAction(null, 500, 499, false), false, parsed("click", 500, 499, null));

        assertEquals("llm_tap", verdict.result);
        assertNull("a selecting outcome gives no reason", verdict.noMatchReason);
        assertEquals("an off-tree tap has no widget to name", "none", verdict.matchedClass);
    }

    @Test
    public void aMatchedAnswerNamesTheWidgetItLandedOn() {
        LlmEngine.Verdict verdict = LlmEngine.classify(
                action(ActionType.MODEL_CLICK, "android.widget.Button"), false,
                parsed("click", 500, 499, null));

        assertEquals("matched", verdict.result);
        assertNull(verdict.noMatchReason);
        assertEquals("the line carries the simple name, not the package",
                "Button", verdict.matchedClass);
    }

    @Test
    public void aMatchWithNoResolvedNodeStillClassifiesAsMatched() {
        // The class is a property of the widget, not of the outcome: an action that selected
        // something without resolving a node is still a decision the agent will execute.
        LlmEngine.Verdict verdict = LlmEngine.classify(
                unresolvedAction(), false, parsed("click", 500, 499, null));

        assertEquals("matched", verdict.result);
        assertEquals("none", verdict.matchedClass);
    }

    @Test
    public void aRefusedAnswerIsANoMatchWithTheBanAsItsReason() {
        // The ban is the third no_match mechanism and the only one that had an answer to refuse.
        // It is what bucket D of the falsification gate counts, so it must be separable from the
        // two mapping failures below.
        LlmEngine.Verdict verdict = LlmEngine.classify(null, true, parsed("click", 500, 499, null));

        assertEquals("no_match", verdict.result);
        assertEquals("dead_pair", verdict.noMatchReason);
        assertEquals("none", verdict.matchedClass);
    }

    @Test
    public void anAnswerAtTheOriginIsDegenerateRatherThanOutOfBounds() {
        // A (0,0) emission is the model collapsing, not a coordinate the bands turned down, and the
        // two are counted apart because only one of them says anything about the model.
        LlmEngine.Verdict verdict = LlmEngine.classify(null, false, parsed("click", 0, 0, null));

        assertEquals("no_match", verdict.result);
        assertEquals("degenerate", verdict.noMatchReason);
    }

    @Test
    public void anAnswerThatMappedToNothingElsewhereIsABoundary() {
        LlmEngine.Verdict verdict = LlmEngine.classify(null, false, parsed("type_text", 500, 12, "x"));

        assertEquals("no_match", verdict.result);
        assertEquals("boundary", verdict.noMatchReason);
    }

    // -------------------------------------------------------------------------
    // applyTypedText — what the harness does with the text in an answer
    // -------------------------------------------------------------------------

    @Test
    public void aTypeTextAnswerLeavesItsTextOnTheNodeThatWillDispatchIt() {
        ModelAction match = action(ActionType.MODEL_CLICK, "android.widget.EditText");

        LlmEngine.applyTypedText(match, parsed("type_text", 500, 499, "hello"));

        assertEquals("APE types the node's inputText when it dispatches the click",
                "hello", match.getResolvedNode().getInputText());
    }

    @Test
    public void aClickAnswerLeavesTheNodeAloneEvenOnAnInputWidget() {
        // The conversion of a click on an input widget into a text-entry decision is the mapper's
        // (fixTextEdit); the text itself only ever comes from a type_text answer, and a click
        // carries none to give.
        ModelAction match = action(ActionType.MODEL_CLICK, "android.widget.EditText");

        LlmEngine.applyTypedText(match, parsed("click", 500, 499, "hello"));

        assertNull(match.getResolvedNode().getInputText());
    }

    @Test
    public void aTypeTextAnswerWithoutTextLeavesTheNodeAlone() {
        ModelAction match = action(ActionType.MODEL_CLICK, "android.widget.EditText");

        LlmEngine.applyTypedText(match, parsed("type_text", 500, 499, null));

        assertNull(match.getResolvedNode().getInputText());
    }

    @Test
    public void anUnresolvedMatchCostsTheDecisionItsTextAndNothingMore() {
        // The decision still executes: a node that cannot receive the text is a degraded answer,
        // not a failed pipeline, and the engine's never-throws contract starts here.
        LlmEngine.applyTypedText(unresolvedAction(), parsed("type_text", 500, 499, "hello"));
    }
}

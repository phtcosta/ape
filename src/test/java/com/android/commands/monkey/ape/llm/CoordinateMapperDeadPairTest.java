package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.NoopSink;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * B1 dead-pair ban (llm-routing INV-RTR-15/16) — the record, the death rule and the input-capable
 * exemption, exercised on the two key shapes the ban ships with.
 *
 * <p>Everything here is pure JVM logic: {@link CoordinateMapper#recordLlmOutcome} and the ban check take
 * {@code ModelAction}s, and a {@code ModelAction} can be built and resolved without a device (a
 * {@code GUITreeNode} carrying a class name is enough — the ban never reads bounds). The state is
 * left null, so every action in a test shares one state key, which is exactly the single-state
 * situation the death rule describes.
 *
 * <p>What is NOT reachable here: the ban's effect inside {@code selectAction()} — that a banned
 * decision returns null with {@code result=no_match reason=dead_pair} while
 * {@code breaker.recordSuccess()} still runs. That path needs a screenshot, an HTTP call and a
 * parse, so it is covered by smoke gate (b) on the device. What this file does assert about the
 * breaker is the property the ban record itself must have: consulting or feeding it never touches
 * the breaker at all.
 */
public class CoordinateMapperDeadPairTest {

    /**
     * A mapper wired from a plan carrying only the LLM feature, so the snap floor and the two
     * boundary bands are the jar defaults every assertion here was written against.
     */
    private static CoordinateMapper newMapper() {
        return new CoordinateMapper(
                TestRunSpecs.spec("ape.llmUrl", "http://localhost:9999/v1").llm());
    }

    /** Minimal {@link Name} whose only meaningful behavior is the XPath the ban keys on. */
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

    private static GUITreeNode node(String className) {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName(className);
        return node;
    }

    /** A matched-widget action: a Name (the ban anchor), an event type, and one resolved node. */
    private static ModelAction matched(String xpath, ActionType type, String className) {
        return matchedOn(xpath, type, node(className));
    }

    private static ModelAction matchedOn(String xpath, ActionType type, GUITreeNode... nodes) {
        ModelAction action = new ModelAction(null, new TestName(xpath), type);
        action.resolveAt(1, 0, null, nodes[0], nodes);
        return action;
    }

    /** Feed the pair {@code n} unproductive executions. */
    private static void strike(CoordinateMapper mapper, ModelAction action, int n) {
        for (int i = 0; i < n; i++) {
            mapper.recordLlmOutcome(action, false);
        }
    }

    private static boolean dead(CoordinateMapper mapper, ModelAction action) {
        return mapper.isDeadPair(mapper.banKey(action));
    }

    // -------------------------------------------------------------------------
    // Key identity
    // -------------------------------------------------------------------------

    @Test
    public void tapKeyIsTheExactCoordinate() {
        CoordinateMapper mapper = newMapper();
        String at499 = mapper.banKey(new LlmTapAction(null, 500, 499, false));
        String again = mapper.banKey(new LlmTapAction(null, 500, 499, false));
        String at500 = mapper.banKey(new LlmTapAction(null, 500, 500, false));

        assertEquals("same state and same coordinate is the same pair", at499, again);
        assertNotEquals("one pixel apart is a different pair", at499, at500);
    }

    @Test
    public void matchedKeyIsNameXPathPlusEventType() {
        CoordinateMapper mapper = newMapper();
        String clickHelp = mapper.banKey(
                matched("//Button[@text='Help']", ActionType.MODEL_CLICK, "android.widget.Button"));
        String clickHelpAgain = mapper.banKey(
                matched("//Button[@text='Help']", ActionType.MODEL_CLICK, "android.widget.Button"));
        String longClickHelp = mapper.banKey(
                matched("//Button[@text='Help']", ActionType.MODEL_LONG_CLICK, "android.widget.Button"));
        String clickOther = mapper.banKey(
                matched("//Button[@text='Cancel']", ActionType.MODEL_CLICK, "android.widget.Button"));

        assertEquals("a second action object on the same Name is the same pair",
                clickHelp, clickHelpAgain);
        assertNotEquals("the event type is part of the key", clickHelp, longClickHelp);
        assertNotEquals("a different Name is a different pair", clickHelp, clickOther);
    }

    @Test
    public void targetlessNonTapActionHasNoBanKey() {
        // A "back" answer returns the state's BACK action: no Name, no coordinate, nothing to ban.
        assertNull(newMapper().banKey(new ModelAction(null, ActionType.MODEL_BACK)));
    }

    // -------------------------------------------------------------------------
    // Death rule
    // -------------------------------------------------------------------------

    @Test
    public void pairSurvivesFourDeadExecutionsAndDiesOnTheFifth() {
        CoordinateMapper mapper = newMapper();
        ModelAction help = matched("//Button[@text='Help']", ActionType.MODEL_CLICK,
                "android.widget.Button");

        strike(mapper, help, 4);
        assertFalse("four unproductive executions must not kill the pair", dead(mapper, help));

        strike(mapper, help, 1);
        assertTrue("the fifth unproductive execution kills it", dead(mapper, help));
    }

    @Test
    public void thresholdIsUniformAcrossTheClassesTheBanCovers() {
        CoordinateMapper mapper = newMapper();
        ModelAction aSwitch = matched("//Switch[1]", ActionType.MODEL_CLICK, "android.widget.Switch");
        ModelAction spinner = matched("//Spinner[1]", ActionType.MODEL_CLICK, "android.widget.Spinner");
        ModelAction button = matched("//Button[1]", ActionType.MODEL_CLICK, "android.widget.Button");

        for (ModelAction action : new ModelAction[]{aSwitch, spinner, button}) {
            strike(mapper, action, 4);
            assertFalse(action + " must survive four", dead(mapper, action));
            strike(mapper, action, 1);
            assertTrue(action + " must die on the fifth, like every covered class",
                    dead(mapper, action));
        }
    }

    @Test
    public void productiveExecutionNeitherKillsNorResets() {
        CoordinateMapper mapper = newMapper();
        ModelAction help = matched("//Button[@text='Help']", ActionType.MODEL_CLICK,
                "android.widget.Button");

        strike(mapper, help, 2);
        mapper.recordLlmOutcome(help, true);   // new_state=true: not a strike, and not a reset
        strike(mapper, help, 2);
        assertFalse("the productive execution must not have counted toward death", dead(mapper, help));

        strike(mapper, help, 1);
        assertTrue("the count resumed at 4, so this fifth unproductive execution kills it",
                dead(mapper, help));
    }

    @Test
    public void banRecordIsPerRun() {
        ModelAction help = matched("//Button[@text='Help']", ActionType.MODEL_CLICK,
                "android.widget.Button");
        CoordinateMapper firstRun = newMapper();
        strike(firstRun, help, 5);
        assertTrue(dead(firstRun, help));

        // The record lives on the mapper, which lives exactly one run: a fresh mapper starts empty.
        CoordinateMapper secondRun = newMapper();
        assertFalse("a new run must not inherit the previous run's bans", dead(secondRun, help));
        assertEquals(0, secondRun.deadPairRecordSize());
    }

    // -------------------------------------------------------------------------
    // Abstraction-level semantics of the matched key
    // -------------------------------------------------------------------------

    @Test
    public void banCoversEveryNodeTheNameResolvesTo() {
        CoordinateMapper mapper = newMapper();
        GUITreeNode first = node("android.widget.Button");
        GUITreeNode second = node("android.widget.Button");
        GUITreeNode third = node("android.widget.Button");
        ModelAction onFirst = matchedOn("//ListView/Button", ActionType.MODEL_CLICK,
                first, second, third);

        strike(mapper, onFirst, 5);

        // The key is the Name's XPath, not the node: a later answer resolving the same Name to a
        // different one of its three nodes hits the same dead pair. One ban, three widgets withdrawn
        // — which is why a ban count is a count of abstract pairs, never of widgets.
        ModelAction onSecond = matchedOn("//ListView/Button", ActionType.MODEL_CLICK,
                second, first, third);
        assertTrue(dead(mapper, onSecond));
    }

    // -------------------------------------------------------------------------
    // Input-capable exemption
    // -------------------------------------------------------------------------

    @Test
    public void inputCapableTargetsNeverDieAndNeverEnterTheRecord() {
        String[] inputClasses = {
                "android.widget.EditText",
                "android.widget.AutoCompleteTextView",
                "android.widget.SearchView",
                "androidx.appcompat.widget.SearchView"
        };
        for (String className : inputClasses) {
            CoordinateMapper mapper = newMapper();
            ModelAction field = matched("//" + className, ActionType.MODEL_CLICK, className);

            strike(mapper, field, 6);   // one more than the threshold

            assertFalse(className + " must never become dead", dead(mapper, field));
            assertEquals(className + " must never enter the record at all — the exemption is"
                    + " realized by not recording the strike, not by filtering at the check",
                    0, mapper.deadPairRecordSize());
        }
    }

    @Test
    public void exemptionFollowsTheWidgetThroughTheTextEntryConversion() {
        CoordinateMapper mapper = newMapper();
        // fixTextEdit converts a click that resolves to an input widget into a text-entry action on
        // that same widget (the text is generated by APE's typed-input path). The exemption keys on
        // the widget, not on the event type, so the converted decision is exempt too.
        GUITreeNode searchView = node("android.widget.SearchView");
        ModelAction typed = matchedOn("//SearchView[1]", ActionType.MODEL_CLICK, searchView);
        searchView.setInputText("harness-generated");

        strike(mapper, typed, 6);

        assertFalse("a converted text-entry decision on an input widget is never withdrawn",
                dead(mapper, typed));
        assertEquals(0, mapper.deadPairRecordSize());
    }

    @Test
    public void offTreeTapIsBannedWithNoExemptionConsulted() {
        CoordinateMapper mapper = newMapper();
        // An llm_tap has no matched widget — matched_class=none in 1,033 of 1,033 corpus
        // occurrences — so there is no class to exempt, whatever is rendered at the coordinate.
        LlmTapAction tap = new LlmTapAction(null, 500, 499, false);
        assertNull("the tap carries no resolved node, so no class is knowable", tap.getResolvedNode());

        strike(mapper, tap, 4);
        assertFalse(dead(mapper, tap));
        strike(mapper, tap, 1);
        assertTrue("the tap dies at the same threshold as any covered pair", dead(mapper, tap));
    }

    // -------------------------------------------------------------------------
    // Breaker
    // -------------------------------------------------------------------------

    @Test
    public void feedingAndConsultingTheBanNeverTouchesTheBreaker() {
        // The ban and the breaker now live in different units, which states INV-RTR-16
        // structurally: this mapper holds no breaker reference and could not open one if it tried.
        // The assertion survives anyway, across the seam a run actually has — an engine holding
        // both — where a ban storm must leave the client's breaker exactly as it found it.
        CoordinateMapper mapper = newMapper();
        LlmClient client = new LlmClient(
                TestRunSpecs.spec("ape.llmUrl", "http://localhost:9999/v1").llm(), new NoopSink());
        ModelAction help = matched("//Button[@text='Help']", ActionType.MODEL_CLICK,
                "android.widget.Button");
        LlmCircuitBreaker breaker = client.getBreaker();
        String stateBefore = breaker.getStateName();

        strike(mapper, help, 10);
        assertTrue(dead(mapper, help));

        assertEquals("a ban storm must never open the breaker: the pipeline succeeded, only its"
                + " answer was refused", 0, breaker.getFailureCount());
        assertEquals(0, breaker.getTripCount());
        assertEquals(stateBefore, breaker.getStateName());
        assertTrue(breaker.shouldAttempt());
    }
}

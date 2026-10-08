package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.llm.PromptFixtures.TestName;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.android.commands.monkey.ape.llm.PromptFixtures.H;
import static com.android.commands.monkey.ape.llm.PromptFixtures.W;
import static com.android.commands.monkey.ape.llm.PromptFixtures.action;
import static com.android.commands.monkey.ape.llm.PromptFixtures.list;
import static com.android.commands.monkey.ape.llm.PromptFixtures.node;
import static com.android.commands.monkey.ape.llm.PromptFixtures.userText;
import static com.android.commands.monkey.ape.llm.PromptFixtures.visited;
import static org.junit.Assert.*;

/**
 * llm-prompt "Element List of the v13 and v17 Variants" (design D10): one line per widget, keyed
 * by (target {@code Name}, resolved-node bounds), in first-appearance order, with no ordinal.
 */
public class ApePromptBuilderElementListTest {

    private static final String V13 = ApePromptBuilder.VARIANT_V13;
    private static final String V17 = ApePromptBuilder.VARIANT_V17;

    /** The element-list lines of a v13 or v17 user text, without the header and the footer. */
    private static List<String> elementLines(String user) {
        List<String> lines = new ArrayList<>();
        boolean in = false;
        for (String line : user.split("\n")) {
            if (line.equals("UI elements:") || line.equals("ELEMENTS:")) { in = true; continue; }
            if (in && line.isEmpty()) break;
            if (in) lines.add(line);
        }
        assertTrue("element list header not found:\n" + user, in);
        return lines;
    }

    private static List<String> lines(String variant, List<ModelAction> actions) {
        return elementLines(userText(variant, W, H, actions, null));
    }

    private static Rect email() { return new Rect(0, 480, 1080, 596); }

    /** Scenario "widget with a click and a long-click is listed once". */
    @Test
    public void clickAndLongClickOnOneWidgetAreOneLine() {
        TestName name = new TestName("//EditText[@id='email']");
        GUITreeNode field = node("android.widget.EditText", "Email", email());
        List<String> lines = lines(V13, list(
                action(name, ActionType.MODEL_CLICK, field),
                action(name, ActionType.MODEL_LONG_CLICK, field)));
        assertEquals(1, lines.size());
        assertEquals("  EditText \"Email\" @(500,299)", lines.get(0));
    }

    /** Scenario "two scroll directions on one widget are listed once". */
    @Test
    public void twoScrollDirectionsAreOneLine() {
        TestName name = new TestName("//ScrollView");
        Rect bounds = new Rect(0, 200, 1080, 1600);
        for (String variant : new String[]{V13, V17}) {
            List<String> lines = lines(variant, list(
                    action(name, ActionType.MODEL_SCROLL_BOTTOM_UP, node("android.widget.ScrollView", null, bounds)),
                    action(name, ActionType.MODEL_SCROLL_TOP_DOWN, node("android.widget.ScrollView", null, bounds))));
            assertEquals(variant, 1, lines.size());
            assertTrue(variant, lines.get(0).startsWith("  ScrollView"));
        }
    }

    /** Scenario "two alias nodes with the same bounds are listed once". */
    @Test
    public void aliasNodesWithTheSameBoundsAreOneLineFromTheFirstAction() {
        TestName name = new TestName("//EditText");
        List<String> lines = lines(V13, list(
                action(name, ActionType.MODEL_CLICK, node("android.widget.EditText", "First", email())),
                action(name, ActionType.MODEL_LONG_CLICK, node("android.widget.EditText", "Second", email()))));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("\"First\""));
    }

    /** Scenario "same bounds, different target Names keep a line each". */
    @Test
    public void containerAndChildWithTheSameBoundsKeepALineEach() {
        Rect bounds = new Rect(100, 900, 980, 1050);
        List<String> lines = lines(V13, list(
                action(new TestName("//FrameLayout"), ActionType.MODEL_CLICK,
                        node("android.widget.FrameLayout", null, bounds)),
                action(new TestName("//Button[@text='OK']"), ActionType.MODEL_CLICK,
                        node("android.widget.Button", "OK", new Rect(bounds)))));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0), lines.get(0).startsWith("  FrameLayout"));
        assertTrue(lines.get(1), lines.get(1).startsWith("  Button \"OK\""));
    }

    /** Scenario "same target Name, different bounds keep a line each". */
    @Test
    public void oneNameResolvedToDifferentBoundsKeepsALineEach() {
        TestName name = new TestName("//EditText");
        List<String> lines = lines(V13, list(
                action(name, ActionType.MODEL_CLICK, node("android.widget.EditText", "Email", email())),
                action(name, ActionType.MODEL_LONG_CLICK,
                        node("android.widget.EditText", "Email", new Rect(0, 700, 1080, 816)))));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0), lines.get(0).endsWith("@(500,299)"));
        assertTrue(lines.get(1), lines.get(1).endsWith("@(500,422)"));
    }

    /** Scenario "no ordinal prefix", with the order of each widget's first action. */
    @Test
    public void noOrdinalAndFirstAppearanceOrder() {
        TestName a = new TestName("//A");
        TestName b = new TestName("//B");
        TestName c = new TestName("//C");
        GUITreeNode nodeA = node("android.widget.Button", "A", new Rect(0, 100, 1080, 200));
        GUITreeNode nodeB = node("android.widget.Button", "B", new Rect(0, 300, 1080, 400));
        GUITreeNode nodeC = node("android.widget.Button", "C", new Rect(0, 500, 1080, 600));
        for (String variant : new String[]{V13, V17}) {
            List<String> lines = lines(variant, list(
                    action(a, ActionType.MODEL_CLICK, nodeA),
                    action(b, ActionType.MODEL_CLICK, nodeB),
                    action(a, ActionType.MODEL_LONG_CLICK, nodeA),
                    action(c, ActionType.MODEL_CLICK, nodeC)));
            assertEquals(variant, 3, lines.size());
            assertTrue(variant, lines.get(0).contains("\"A\""));
            assertTrue(variant, lines.get(1).contains("\"B\""));
            assertTrue(variant, lines.get(2).contains("\"C\""));
            for (String line : lines) {
                assertFalse(variant + ": " + line, Character.isDigit(line.trim().charAt(0)));
            }
        }
    }

    /** Scenario "v17 status tag counts the widget's visits". */
    @Test
    public void v17StatusTagSumsTheWidgetsVisits() {
        TestName name = new TestName("//Button");
        GUITreeNode button = node("android.widget.Button", "Go", new Rect(0, 100, 1080, 200));
        List<String> lines = lines(V17, list(
                visited(action(name, ActionType.MODEL_CLICK, button), 2),
                visited(action(name, ActionType.MODEL_LONG_CLICK, button), 1)));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("[TESTED-3x]"));
    }

    /** Scenario "v17 screen line counts widgets on both sides". */
    @Test
    public void v17ScreenLineCountsWidgets() {
        TestName button = new TestName("//Button");
        GUITreeNode buttonNode = node("android.widget.Button", "Go", new Rect(0, 100, 1080, 200));
        String user = userText(V17, W, H, list(
                visited(action(button, ActionType.MODEL_CLICK, buttonNode), 1),
                action(button, ActionType.MODEL_LONG_CLICK, buttonNode),
                action(new TestName("//TextView"), ActionType.MODEL_CLICK,
                        node("android.widget.TextView", "Help", new Rect(0, 300, 1080, 400)))), null);
        assertTrue(user, user.contains(" | 1/2 elements tested | "));
        assertFalse(user, user.contains("actions tested"));
    }

    /** An action with no resolved node keeps a line of its own, with no coordinate. */
    @Test
    public void unresolvedActionsKeepALineEach() {
        TestName name = new TestName("//gone");
        for (String variant : new String[]{V13, V17}) {
            List<String> lines = lines(variant, list(
                    new ModelAction(null, name, ActionType.MODEL_CLICK),
                    new ModelAction(null, name, ActionType.MODEL_LONG_CLICK)));
            assertEquals(variant, 2, lines.size());
            for (String line : lines) assertFalse(variant + ": " + line, line.contains("@("));
        }
    }

    @Test
    public void emptyListStillSaysSo() {
        for (String variant : new String[]{V13, V17}) {
            List<String> lines = lines(variant, list());
            assertEquals(variant, 1, lines.size());
            assertEquals(variant, "  (no elements)", lines.get(0));
        }
    }

    /**
     * Scenario "model actions unchanged": the mapper still sees both actions of a collapsed line. A
     * Button, not an input widget: on an input widget fixTextEdit turns any click or long-click into
     * the MODEL_CLICK, independently of the list.
     */
    @Test
    public void aLongClickAnswerAtACollapsedLineStillMatchesItsLongClick() {
        TestName name = new TestName("//Button");
        GUITreeNode button = node("android.widget.Button", "Save", email());
        ModelAction click = action(name, ActionType.MODEL_CLICK, button);
        ModelAction longClick = action(name, ActionType.MODEL_LONG_CLICK, button);
        List<ModelAction> actions = list(click, longClick);

        String line = lines(V13, actions).get(0);
        Matcher m = Pattern.compile("@\\((\\d+),(\\d+)\\)").matcher(line);
        assertTrue(line, m.find());
        CoordinateMapper mapper = new CoordinateMapper(
                TestRunSpecs.spec("ape.llmUrl", "http://localhost:9999/v1").llm());
        int[] px = mapper.toPixels(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), W, H);
        assertSame(longClick, mapper.map(px[0], px[1], "long_click", null, actions, null, W, H));
    }
}

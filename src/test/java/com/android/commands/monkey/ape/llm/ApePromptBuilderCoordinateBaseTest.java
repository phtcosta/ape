package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.llm.ApePromptBuilder.ActionHistoryEntry;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.android.commands.monkey.ape.llm.PromptFixtures.H;
import static com.android.commands.monkey.ape.llm.PromptFixtures.W;
import static com.android.commands.monkey.ape.llm.PromptFixtures.click;
import static com.android.commands.monkey.ape.llm.PromptFixtures.lineWith;
import static com.android.commands.monkey.ape.llm.PromptFixtures.list;
import static com.android.commands.monkey.ape.llm.PromptFixtures.node;
import static com.android.commands.monkey.ape.llm.PromptFixtures.unresolvedClick;
import static com.android.commands.monkey.ape.llm.PromptFixtures.userText;
import static org.junit.Assert.*;

/**
 * llm-prompt "Single Coordinate Base" (INV-PRM-06): every coordinate the prompt shows is the
 * centre of the widget's visible part, normalized by {@code CoordinateNormalizer.toNormalized}
 * against the display pair {@code build} received, never against the root node.
 */
public class ApePromptBuilderCoordinateBaseTest {

    /** Every variant that lists coordinates. */
    private static final String[] COORDINATE_VARIANTS = {
            ApePromptBuilder.VARIANT_APE_CURRENT,
            ApePromptBuilder.VARIANT_APE_REASONING,
            ApePromptBuilder.VARIANT_COMPACT_V1,
            ApePromptBuilder.VARIANT_V13,
            ApePromptBuilder.VARIANT_V17,
    };

    private static String listedLine(String variant, String text, Rect bounds) {
        String user = userText(variant, W, H, list(click("android.widget.Button", text, bounds)), null);
        return lineWith(user, "\"" + text + "\"");
    }

    // -------------------------------------------------------------------------
    // Element list, per coordinate-bearing variant
    // -------------------------------------------------------------------------

    /** Scenario "full-screen window whose root extends behind the navigation bar". */
    @Test
    public void fullScreenWidgetUsesTheDisplayHeight() {
        for (String variant : COORDINATE_VARIANTS) {
            String line = listedLine(variant, "Toolbar", new Rect(0, 105, 1080, 252));
            assertTrue(variant + ": " + line, line.contains("@(500,99)"));
            assertFalse(variant + ": " + line, line.contains("@(500,92)"));
        }
    }

    /** Scenario "dialog window". */
    @Test
    public void dialogWidgetUsesTheDisplayNotTheWindow() {
        for (String variant : COORDINATE_VARIANTS) {
            String line = listedLine(variant, "OK", new Rect(745, 1040, 897, 1140));
            assertTrue(variant + ": " + line, line.contains("@(760,607)"));
        }
    }

    /** Scenario "widget cut by the display edge is listed at its visible centre". */
    @Test
    public void edgeCutWidgetIsListedAtItsVisibleCentre() {
        for (String variant : COORDINATE_VARIANTS) {
            String line = listedLine(variant, "Item", new Rect(0, 1740, 1080, 1974));
            assertTrue(variant + ": " + line, line.contains("@(500,984)"));
            assertFalse(variant + ": " + line, line.contains("@(500,999)"));
        }
    }

    /** Scenario "widget entirely off screen renders no coordinate". */
    @Test
    public void offScreenWidgetKeepsItsLineWithoutCoordinate() {
        for (String variant : COORDINATE_VARIANTS) {
            String line = listedLine(variant, "Below", new Rect(0, 1800, 1080, 1900));
            assertFalse(variant + ": " + line, line.contains("@("));
        }
    }

    /** A zero-height node inside the display intersects it ({@code Rect.intersect}) and keeps its centre. */
    @Test
    public void zeroHeightWidgetInsideTheDisplayKeepsItsCoordinate() {
        for (String variant : COORDINATE_VARIANTS) {
            String line = listedLine(variant, "Flat", new Rect(0, 500, 1080, 500));
            assertTrue(variant + ": " + line, line.contains("@(500,278)"));
        }
    }

    /** Scenario "unresolved node renders no coordinate". */
    @Test
    public void unresolvedNodeKeepsItsLineWithoutCoordinate() {
        for (String variant : COORDINATE_VARIANTS) {
            String user = userText(variant, W, H, list(unresolvedClick()), null);
            assertFalse(variant + ": " + user, user.contains("@("));
            assertFalse(variant + ": the line must still appear\n" + user, user.contains("(no elements)"));
            assertTrue(variant + ": the line must still appear\n" + user, user.contains("View"));
        }
    }

    /** The same widget on another display pair is normalized by that pair, not by a constant. */
    @Test
    public void listFollowsTheDimensionsBuildReceived() {
        String user = userText(ApePromptBuilder.VARIANT_V13, 1080, 1920,
                list(click("android.widget.Button", "Toolbar", new Rect(0, 105, 1080, 252))), null);
        assertTrue(user, lineWith(user, "\"Toolbar\"").contains("@(500,92)"));
    }

    // -------------------------------------------------------------------------
    // Action history, rendered at build time (llm-prompt "Action History")
    // -------------------------------------------------------------------------

    private static ActionHistoryEntry clickEntry(String cls, String text, Rect bounds, String result) {
        return new ActionHistoryEntry("click", cls, text, bounds, null, result);
    }

    private static String historyText(String variant, int w, int h, ActionHistoryEntry... entries) {
        return userText(variant, w, h, null, Arrays.asList(entries));
    }

    /** The {@code - ...} history lines of a user text, in order. */
    private static List<String> historyLines(String user) {
        List<String> lines = new java.util.ArrayList<>();
        for (String line : user.split("\n")) {
            if (line.startsWith("- ")) lines.add(line);
        }
        return lines;
    }

    /** Scenario "history entry normalized at build time". */
    @Test
    public void historyEntryIsNormalizedWithTheDimensionsBuildReceived() {
        ActionHistoryEntry entry = clickEntry("Button", "Toolbar", new Rect(0, 105, 1080, 252), "same");
        String onDisplay = historyText(ApePromptBuilder.VARIANT_V13, W, H, entry);
        assertTrue(onDisplay, onDisplay.contains("- click @(500,99) Button \"Toolbar\" \u2192 same"));
        String onFullScreen = historyText(ApePromptBuilder.VARIANT_V13, 1080, 1920, entry);
        assertTrue(onFullScreen, onFullScreen.contains("- click @(500,92) Button \"Toolbar\""));
    }

    /** Scenario "history entry of a widget cut by or outside the display". */
    @Test
    public void historyEntryOfAWidgetCutByOrOutsideTheDisplay() {
        String user = historyText(ApePromptBuilder.VARIANT_V13, W, H,
                clickEntry("Button", "Cut", new Rect(0, 1740, 1080, 1974), "same"),
                clickEntry("Button", "Below", new Rect(0, 1800, 1080, 1900), "same"));
        assertTrue(user, lineWith(user, "\"Cut\"").contains("@(500,984)"));
        assertFalse(user, lineWith(user, "\"Below\"").contains("@("));
        Matcher m = Pattern.compile("@\\((-?\\d+),(-?\\d+)\\)").matcher(user);
        while (m.find()) {
            for (int g = 1; g <= 2; g++) {
                int v = Integer.parseInt(m.group(g));
                assertTrue(m.group() + " outside [0,999]", v >= 0 && v <= 999);
            }
        }
    }

    /** Scenario "same widget, same coordinate in list and history". */
    @Test
    public void listAndHistoryShowOneCoordinateForOneWidget() {
        Rect bounds = new Rect(0, 105, 1080, 252);
        for (String variant : new String[]{ApePromptBuilder.VARIANT_APE_CURRENT, ApePromptBuilder.VARIANT_V13}) {
            String user = userText(variant, W, H,
                    list(click("android.widget.Button", "Toolbar", bounds)),
                    Collections.singletonList(clickEntry("Button", "Toolbar", new Rect(bounds), "same")));
            for (String line : user.split("\n")) {
                if (line.contains("\"Toolbar\"")) {
                    assertTrue(variant + ": " + line, line.contains("@(500,99)"));
                }
            }
        }
    }

    /** Scenario "off-tree LLM tap in history": the one-pixel rectangle at the tap's own pixel. */
    @Test
    public void offTreeLlmTapIsShownAtItsOwnPixel() {
        String user = historyText(ApePromptBuilder.VARIANT_V13, W, H,
                new ActionHistoryEntry("click", null, null, new Rect(600, 900, 601, 901), null, "same"));
        assertTrue(user, user.contains("- click @(555,501) \u2192 same"));
        assertFalse(user, user.contains("@(0,0)"));
    }

    /** Scenario "menu press in history". */
    @Test
    public void menuPressIsShownWithoutCoordinates() {
        String user = historyText(ApePromptBuilder.VARIANT_V13, W, H,
                new ActionHistoryEntry("menu", null, null, null, null, "same"));
        assertTrue(user, user.contains("- menu \u2192 same"));
        assertFalse(user, user.contains("@("));
    }

    /** Scenario "v17 history goes through the formatter". */
    @Test
    public void v17HistoryUsesTheFormattersCompactForm() {
        String user = historyText(ApePromptBuilder.VARIANT_V17, W, H,
                clickEntry("Button", "Toolbar", new Rect(0, 105, 1080, 252), "new screen"),
                new ActionHistoryEntry("back", null, null, null, null, "previous screen"));
        assertTrue(user, user.contains("Recent: click@(500,99), back\n"));
        assertFalse(user, user.contains("back@(0,0)"));
    }

    /** v17's compact form omits the coordinate of an entry wholly off screen and names a menu press. */
    @Test
    public void v17CompactFormWithoutCoordinates() {
        String user = historyText(ApePromptBuilder.VARIANT_V17, W, H,
                clickEntry("Button", "Below", new Rect(0, 1800, 1080, 1900), "same"),
                new ActionHistoryEntry("menu", null, null, null, null, "same"));
        assertTrue(user, user.contains("Recent: click, menu\n"));
    }

    /** Scenario "history entry names its widget as the list does" (design D13). */
    @Test
    public void historyEntryNamesItsWidgetAsTheListDoes() {
        GUITreeNode icon = node("android.widget.ImageButton", null, new Rect(960, 70, 1060, 170));
        icon.setContentDesc("Settings");
        ActionHistoryEntry entry = new ActionHistoryEntry("click",
                ApePromptBuilder.simpleClassName(icon), ApePromptBuilder.textOrContentDescription(icon),
                icon.getBoundsInScreen(), null, "same");
        ModelAction listed = PromptFixtures.action(new PromptFixtures.TestName("//icon"),
                com.android.commands.monkey.ape.model.ActionType.MODEL_CLICK, icon);

        String user = userText(ApePromptBuilder.VARIANT_APE_CURRENT, W, H, list(listed),
                Collections.singletonList(entry));
        assertTrue(user, user.contains("- click @(935,66) ImageButton \"Settings\" \u2192 same"));
        assertFalse(user, user.contains("android.widget.ImageButton"));
        assertTrue(user, user.contains("ImageButton \"Settings\" @(935,66)"));
    }

    /** {@code formatHistoryEntry} is the only renderer: v13 and visual_only show the default branch's line. */
    @Test
    public void everyVariantRendersAnEntryAlike() {
        ActionHistoryEntry[] entries = {
                clickEntry("Button", "Toolbar", new Rect(0, 105, 1080, 252), "new screen"),
                new ActionHistoryEntry("type_text", "EditText", "", new Rect(0, 800, 1080, 948), "google.com", "same"),
                new ActionHistoryEntry("menu", null, null, null, null, "same"),
                new ActionHistoryEntry("back", null, null, null, null, "previous screen"),
        };
        List<String> expected = Arrays.asList(
                "- click @(500,99) Button \"Toolbar\" \u2192 new screen",
                "- type_text @(500,487) \"google.com\" \u2192 same",
                "- menu \u2192 same",
                "- back \u2192 previous screen");
        for (String variant : new String[]{ApePromptBuilder.VARIANT_APE_CURRENT, ApePromptBuilder.VARIANT_V13,
                ApePromptBuilder.VARIANT_VISUAL_ONLY}) {
            assertEquals(variant, expected, historyLines(historyText(variant, W, H, entries)));
        }
    }

    /** An entry whose result the next update has not filled yet renders without the arrow (design D12). */
    @Test
    public void entryWithEmptyResultRendersWithoutArrow() {
        String user = historyText(ApePromptBuilder.VARIANT_V13, W, H,
                clickEntry("Button", "Toolbar", new Rect(0, 105, 1080, 252), ""));
        assertTrue(user, user.contains("- click @(500,99) Button \"Toolbar\"\n"));
        assertFalse(user, user.contains("\u2192"));
    }
}

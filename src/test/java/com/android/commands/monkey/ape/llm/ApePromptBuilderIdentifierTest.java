package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * N1 (llm-prompt INV-PRM-05) — every element line whose node carries text, a content-description or
 * a resource id renders a non-empty identifier, by that fallback order.
 *
 * <p>Reads the identifier back out of the built user message, which is where it matters: the
 * element list is the only anchor the model has for the coordinates it answers with.
 */
public class ApePromptBuilderIdentifierTest {

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

    /** One ImageView action with the given text / content-description / resource id. */
    private static List<ModelAction> imageView(String text, String contentDesc, String resourceId) {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName("android.widget.ImageView");
        node.setBoundsInScreen(new Rect(100, 200, 200, 300));
        if (text != null) node.setText(text);
        if (contentDesc != null) node.setContentDesc(contentDesc);
        if (resourceId != null) node.setResourceID(resourceId);
        ModelAction action = new ModelAction(null, new TestName("//ImageView"), ActionType.MODEL_CLICK);
        action.resolveAt(1, 0, null, node, new GUITreeNode[]{node});
        action.setValid(true);
        List<ModelAction> actions = new ArrayList<>();
        actions.add(action);
        return actions;
    }

    /** The element list as the model receives it. */
    private static String userText(List<ModelAction> actions) {
        List<SglangClient.ContentPart> parts = new ApePromptBuilder()
                .build(null, null, actions, null, "data", null).get(1).getContentParts();
        for (SglangClient.ContentPart part : parts) {
            if ("text".equals(part.getType())) return part.getText();
        }
        return "";
    }

    @Test
    public void textWinsOverEverythingElse() {
        assertTrue(userText(imageView("Encrypt", "Encrypt button", "com.example:id/fab_encrypt"))
                .contains("\"Encrypt\""));
    }

    @Test
    public void contentDescriptionIsUsedWhenThereIsNoText() {
        assertTrue("an icon button with only a content-description must still be identifiable",
                userText(imageView(null, "Add account", null)).contains("\"Add account\""));
    }

    @Test
    public void shortResourceIdIsUsedWhenTextAndContentDescriptionAreEmpty() {
        String line = userText(imageView(null, null, "com.example:id/fab_add"));

        assertTrue("the short resource id is the last identifier available", line.contains("id=fab_add"));
        assertFalse("and it replaces the empty identifier the model could not anchor on",
                line.contains("ImageView \"\""));
    }

    @Test
    public void nodeWithNoneOfTheThreeRendersAnEmptyIdentifierWithoutBreakingTheLine() {
        String text = userText(imageView(null, null, null));

        // Nothing can be rendered, so the identifier is empty — but the line keeps its shape:
        // class, identifier slot, coordinates and visit count all still present on one line.
        assertTrue(text.contains("ImageView \"\""));
        assertTrue(text.contains("@("));
        assertTrue(text.contains("(v:0)"));
    }

    @Test
    public void multiLineWidgetTextIsFlattenedOntoOneElementLine() {
        // A8 (INV-PRM-05): one element, one line of the [APE-LLM-PROMPT] user_text dump.
        String text = userText(imageView("Sign\nIn", null, null));

        assertTrue(text.contains("\"Sign In\""));
        for (String line : text.split("\n")) {
            assertFalse("no element line may be split by widget text", line.trim().equals("In\""));
        }
    }

    @Test
    public void multiLineContentDescriptionIsFlattenedToo() {
        assertTrue(userText(imageView(null, "Add\naccount", null)).contains("\"Add account\""));
    }

    @Test
    public void resourceIdWithoutTheIdSegmentYieldsNoIdentifier() {
        // extractShortId only recognizes the ":id/" form; anything else is not an identifier.
        assertTrue(userText(imageView(null, null, "com.example.something")).contains("ImageView \"\""));
    }
}

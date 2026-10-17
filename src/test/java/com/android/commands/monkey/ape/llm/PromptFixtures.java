package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Real {@link GUITreeNode} / {@link ModelAction} fixtures for the prompt-builder tests, built as
 * {@code ApePromptBuilderIdentifierTest} builds them: a node with class, text and bounds, and an
 * action resolved to it. Shared by the coordinate-base, element-list and system-message tests,
 * and by the agent's action-history test.
 */
public final class PromptFixtures {

    /** The campaign AVD's display, the pair {@code ScreenshotStep.deviceDimensions} returns. */
    public static final int W = 1080;
    public static final int H = 1794;

    private PromptFixtures() {}

    /**
     * A target {@link Name} compared by identity. Production names are interned by
     * {@code NameManager.getCachedName}, so one instance per target is what the jar has as well.
     */
    public static final class TestName implements Name {
        private final String xpath;

        public TestName(String xpath) { this.xpath = xpath; }

        public Namer getNamer() { return null; }
        public Name getLocalName() { return this; }
        public boolean refinesTo(Name other) { return this.equals(other); }
        public String toXPath() { return xpath; }
        public void appendXPathLocalProperties(StringBuilder sb) { }
        public void toXPath(StringBuilder sb) { sb.append(xpath); }
        public int compareTo(Name other) { return xpath.compareTo(other.toXPath()); }
        @Override public String toString() { return xpath; }
    }

    public static GUITreeNode node(String className, String text, Rect bounds) {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName(className);
        node.setBoundsInScreen(bounds);
        if (text != null) node.setText(text);
        return node;
    }

    /** An action of {@code type} on {@code target}, resolved to {@code node}. */
    public static ModelAction action(Name target, ActionType type, GUITreeNode node) {
        ModelAction action = new ModelAction(null, target, type);
        action.resolveAt(1, 0, null, node, new GUITreeNode[]{node});
        action.setValid(true);
        return action;
    }

    /** A click on a fresh target, resolved to a fresh node. */
    public static ModelAction click(String className, String text, Rect bounds) {
        return action(new TestName("//" + text), ActionType.MODEL_CLICK, node(className, text, bounds));
    }

    /** A target-requiring action whose node was never resolved. */
    public static ModelAction unresolvedClick() {
        ModelAction action = new ModelAction(null, new TestName("//unresolved"), ActionType.MODEL_CLICK);
        action.setValid(true);
        return action;
    }

    /** Mark {@code action} visited {@code times} times. */
    public static ModelAction visited(ModelAction action, int times) {
        for (int i = 0; i < times; i++) action.visitedAt(i + 1);
        return action;
    }

    public static List<ModelAction> list(ModelAction... actions) {
        List<ModelAction> list = new ArrayList<>();
        for (ModelAction a : actions) list.add(a);
        return list;
    }

    public static List<SglangClient.Message> build(String variant, int w, int h, List<ModelAction> actions,
                                            List<ApePromptBuilder.ActionHistoryEntry> recent) {
        return new ApePromptBuilder(variant).build(w, h, null, actions, "data", recent);
    }

    /** The user text as the model receives it. */
    public static String userText(String variant, int w, int h, List<ModelAction> actions,
                           List<ApePromptBuilder.ActionHistoryEntry> recent) {
        List<SglangClient.ContentPart> parts = build(variant, w, h, actions, recent).get(1).getContentParts();
        for (SglangClient.ContentPart part : parts) {
            if ("text".equals(part.getType())) return part.getText();
        }
        return "";
    }

    public static String systemText(String variant, List<ModelAction> actions) {
        return build(variant, W, H, actions, null).get(0).getTextContent();
    }

    /** The one line of {@code text} containing {@code needle}; fails when there is not exactly one. */
    public static String lineWith(String text, String needle) {
        String found = null;
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                if (found != null) throw new AssertionError("two lines contain " + needle + ":\n" + text);
                found = line;
            }
        }
        if (found == null) throw new AssertionError("no line contains " + needle + ":\n" + text);
        return found;
    }
}

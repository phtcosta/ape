package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.MopData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds multimodal LLM prompts from APE-RV internal data structures.
 *
 * <p>Produces exactly 2 messages: [system, user].
 * The user message has 2 content parts: [image, text] in that order.
 *
 * <p>Coordinate space: [0, 1000) normalized (matching Qwen3-VL conventions).
 * Conversion formula: normX = (int)((centerPixelX / deviceWidth) * 1000).
 */
public class ApePromptBuilder {

    // --- Input field class names that enable type_text ---
    private static final Set<String> INPUT_CLASS_NAMES = new HashSet<>(Arrays.asList(
            "android.widget.EditText",
            "android.widget.AutoCompleteTextView",
            "android.widget.SearchView",
            "androidx.appcompat.widget.SearchView"
    ));

    // Text truncation limits
    private static final int MAX_TEXT_LEN  = 50;
    private static final int MAX_HINT_LEN  = 30;
    private static final int MAX_HISTORY   = 5;

    // -------------------------------------------------------------------------
    // ActionHistoryEntry — immutable record of a past action
    // -------------------------------------------------------------------------

    /**
     * A single entry in the recent action history.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code actionType} — LLM tool name: "click", "long_click", "type_text", "back"</li>
     *   <li>{@code widgetClass} — simple class name of the target widget (may be null for back)</li>
     *   <li>{@code widgetText} — visible text / content description of the widget (may be null)</li>
     *   <li>{@code normX}, {@code normY} — normalized [0,1000) coordinates (0 for back)</li>
     *   <li>{@code typedText} — text that was typed (only relevant for type_text)</li>
     *   <li>{@code result} — outcome description, e.g. "ok", "new state", "same state"</li>
     * </ul>
     */
    public static class ActionHistoryEntry {
        public final String actionType;
        public final String widgetClass;
        public final String widgetText;
        public final int    normX;
        public final int    normY;
        public final String typedText;
        public final String result;

        public ActionHistoryEntry(String actionType, String widgetClass, String widgetText,
                                  int normX, int normY, String typedText, String result) {
            this.actionType  = actionType  != null ? actionType  : "";
            this.widgetClass = widgetClass != null ? widgetClass : "";
            this.widgetText  = widgetText  != null ? widgetText  : "";
            this.normX       = normX;
            this.normY       = normY;
            this.typedText   = typedText   != null ? typedText   : "";
            this.result      = result      != null ? result      : "";
        }
    }

    // -------------------------------------------------------------------------
    // Main build method
    // -------------------------------------------------------------------------

    /**
     * Build the 2-message multimodal prompt for the LLM decision step.
     *
     * @param tree          current GUITree (used for device dimensions via root node bounds)
     * @param state         current abstract state (for activity name and visit count)
     * @param actions       candidate ModelActions to present to the LLM
     * @param mopData       MOP reachability data (may be null → MOP markers omitted)
     * @param base64Image   base-64 encoded JPEG screenshot
     * @param recentActions up to MAX_HISTORY past actions (oldest first); may be null/empty
     * @return list of exactly 2 messages: [system, user]
     */
    public List<SglangClient.Message> build(GUITree tree,
                                            State state,
                                            List<ModelAction> actions,
                                            MopData mopData,
                                            String base64Image,
                                            List<ActionHistoryEntry> recentActions) {
        // Determine device dimensions from root-node screen bounds (defensive fallback 1080x1920)
        int deviceWidth  = 1080;
        int deviceHeight = 1920;
        try {
            if (tree != null && tree.getRootNode() != null) {
                Rect rootBounds = tree.getRootNode().getBoundsInScreen();
                if (rootBounds.right > 0)  deviceWidth  = rootBounds.right;
                if (rootBounds.bottom > 0) deviceHeight = rootBounds.bottom;
            }
        } catch (Exception ignored) { /* never throw */ }

        // Derive activity names safely
        String activity      = safeGetActivity(state);        // full class name
        String activitySimple = simpleActivityName(activity); // simple name for display

        // Decide whether to include type_text in the tool schema
        boolean includeTypeText = hasInputField(actions);

        // --- System message ---
        String systemText = buildSystemMessage(includeTypeText, deviceWidth, deviceHeight);

        // --- User text part ---
        String userText = buildUserText(
                tree, state, actions, mopData, recentActions,
                activity, activitySimple, deviceWidth, deviceHeight);

        // --- Assemble messages ---
        List<SglangClient.ContentPart> userParts = new ArrayList<>();
        // Image first
        String imageUrl = (base64Image != null && !base64Image.isEmpty())
                ? "data:image/jpeg;base64," + base64Image
                : "data:image/jpeg;base64,";
        userParts.add(SglangClient.ContentPart.imageUrl(imageUrl));
        // Text second
        userParts.add(SglangClient.ContentPart.text(userText));

        List<SglangClient.Message> messages = new ArrayList<>(2);
        messages.add(new SglangClient.Message("system", systemText));
        messages.add(new SglangClient.Message("user",   userParts));
        return messages;
    }

    // -------------------------------------------------------------------------
    // System message
    // -------------------------------------------------------------------------

    private String buildSystemMessage(boolean includeTypeText, int deviceWidth, int deviceHeight) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an Android UI testing agent exploring an app.\n");
        sb.append("Screen: ").append(deviceWidth).append('x').append(deviceHeight).append(" pixels.\n");
        sb.append("DIALOG: If permission/error dialog visible, dismiss it first (click Allow/OK).\n");
        sb.append("PRIORITY: [DM]/[M] elements > unvisited (v:0) > visited.\n");
        sb.append("AVOID: status bar (top), navigation bar (bottom).\n");
        sb.append("RULES: Don't click same position twice. ");
        sb.append("Use type_text for input fields with valid data ");
        sb.append("(email: user@example.com, password: Test1234!, domain: example.com, search: relevant term).\n");
        sb.append("Tools (coordinates in [0,1000) normalized space):\n");
        sb.append("  click(x, y) — tap element\n");
        sb.append("  long_click(x, y) — long press element\n");
        if (includeTypeText) {
            sb.append("  type_text(x, y, text) — type into field\n");
        }
        sb.append("  back() — press back\n");
        sb.append("Respond with one JSON: {\"name\": \"<action>\", \"arguments\": {<args>}}");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // User text
    // -------------------------------------------------------------------------

    private String buildUserText(GUITree tree,
                                 State state,
                                 List<ModelAction> actions,
                                 MopData mopData,
                                 List<ActionHistoryEntry> recentActions,
                                 String activity,
                                 String activitySimple,
                                 int deviceWidth,
                                 int deviceHeight) {
        StringBuilder sb = new StringBuilder();

        // Screen header
        sb.append("Screen \"").append(activitySimple).append("\":\n");

        // Widget list — all actions in order
        if (actions != null) {
            for (int i = 0; i < actions.size(); i++) {
                ModelAction action = actions.get(i);
                String line = formatActionLine(i, action, mopData, activity, deviceWidth, deviceHeight);
                sb.append(line).append('\n');
            }
        }

        // Action history
        if (recentActions != null && !recentActions.isEmpty()) {
            sb.append("Recent:\n");
            int start = Math.max(0, recentActions.size() - MAX_HISTORY);
            for (int i = start; i < recentActions.size(); i++) {
                sb.append("- ").append(formatHistoryEntry(recentActions.get(i))).append('\n');
            }
        }

        // Exploration context
        String explorationContext = buildExplorationContext(state, actions, mopData, activity);
        if (!explorationContext.isEmpty()) {
            sb.append(explorationContext).append('\n');
        }

        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Action line formatting (3.3 / 3.4)
    // -------------------------------------------------------------------------

    private String formatActionLine(int index,
                                    ModelAction action,
                                    MopData mopData,
                                    String activity,
                                    int deviceWidth,
                                    int deviceHeight) {
        if (action == null) {
            return "[" + index + "] null";
        }

        ActionType type = action.getType();

        // Non-target actions: BACK and MENU
        if (!type.requireTarget()) {
            return "[" + index + "] " + type.name() + " (key)";
        }

        // Target actions — need the resolved node
        GUITreeNode node = safeGetResolvedNode(action);

        // Widget class (simple name)
        String className = safeGetSimpleClassName(node);

        // Visible text / content description
        String displayText = safeGetDisplayText(node);

        // Coordinates
        int normX = 0;
        int normY = 0;
        if (node != null) {
            try {
                Rect bounds = node.getBoundsInScreen();
                int centerX = (bounds.left + bounds.right) / 2;
                int centerY = (bounds.top  + bounds.bottom) / 2;
                normX = (int) ((centerX / (double) deviceWidth)  * 1000);
                normY = (int) ((centerY / (double) deviceHeight) * 1000);
                // Clamp to [0, 999]
                normX = Math.max(0, Math.min(normX, 999));
                normY = Math.max(0, Math.min(normY, 999));
            } catch (Exception ignored) { /* never throw */ }
        }

        // Visit count
        int visitCount = action.getVisitedCount();

        // MOP marker
        String mopMarker = buildMopMarker(node, mopData, activity);

        // Determine if this is an input-hint widget
        boolean isInputField = isInputClass(node);

        StringBuilder sb = new StringBuilder();
        sb.append('[').append(index).append("] ");
        sb.append(className);

        // text / content description (truncated)
        String truncText = truncate(displayText, MAX_TEXT_LEN);
        sb.append(" \"").append(truncText).append("\"");

        // hint (only for input fields)
        if (isInputField) {
            String hint = safeGetHint(node);
            if (hint != null && !hint.isEmpty()) {
                sb.append(" hint=\"").append(truncate(hint, MAX_HINT_LEN)).append("\"");
            }
        }

        sb.append(" @(").append(normX).append(',').append(normY).append(')');

        if (!mopMarker.isEmpty()) {
            sb.append(' ').append(mopMarker);
        }

        sb.append(" (v:").append(visitCount).append(')');

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // MOP marker (3.4)
    // -------------------------------------------------------------------------

    /**
     * Returns "[DM]", "[M]", or "" for the given node / activity.
     */
    private String buildMopMarker(GUITreeNode node, MopData mopData, String activity) {
        if (mopData == null || node == null) return "";
        try {
            String resourceId = node.getResourceID();
            String shortId = MopData.extractShortId(resourceId);
            if (shortId.isEmpty()) return "";
            MopData.WidgetMopFlags flags = mopData.getWidget(activity, shortId);
            if (flags == null) return "";
            if (flags.directMop)    return "[DM]";
            if (flags.transitiveMop) return "[M]";
        } catch (Exception ignored) { /* never throw */ }
        return "";
    }

    // -------------------------------------------------------------------------
    // Action history formatting (3.5)
    // -------------------------------------------------------------------------

    private String formatHistoryEntry(ActionHistoryEntry entry) {
        if (entry == null) return "";
        String type = entry.actionType;
        StringBuilder sb = new StringBuilder();
        if ("back".equals(type)) {
            sb.append("back");
        } else if ("type_text".equals(type)) {
            sb.append("type_text @(").append(entry.normX).append(',').append(entry.normY).append(')');
            if (!entry.typedText.isEmpty()) {
                sb.append(" \"").append(truncate(entry.typedText, MAX_TEXT_LEN)).append("\"");
            }
        } else {
            // click or long_click
            sb.append(type).append(" @(").append(entry.normX).append(',').append(entry.normY).append(')');
            if (!entry.widgetClass.isEmpty()) {
                sb.append(' ').append(entry.widgetClass);
            }
            if (!entry.widgetText.isEmpty()) {
                sb.append(" \"").append(truncate(entry.widgetText, MAX_TEXT_LEN)).append("\"");
            }
        }
        if (!entry.result.isEmpty()) {
            sb.append(" \u2192 ").append(entry.result);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Exploration context (3.6)
    // -------------------------------------------------------------------------

    private String buildExplorationContext(State state,
                                           List<ModelAction> actions,
                                           MopData mopData,
                                           String activity) {
        if (state == null) return "";

        int stateVisits = state.getVisitedCount();
        boolean hasMopData = (mopData != null);

        // Count MOP widgets in actions
        int mopCount = 0;
        int totalTargets = 0;
        if (hasMopData && actions != null) {
            for (ModelAction action : actions) {
                if (action != null && action.requireTarget()) {
                    totalTargets++;
                    GUITreeNode node = safeGetResolvedNode(action);
                    if (node != null) {
                        String resourceId = node.getResourceID();
                        String shortId = MopData.extractShortId(resourceId);
                        if (!shortId.isEmpty()) {
                            MopData.WidgetMopFlags flags = mopData.getWidget(activity, shortId);
                            if (flags != null && (flags.directMop || flags.transitiveMop)) {
                                mopCount++;
                            }
                        }
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        boolean isNewState = (stateVisits == 0);

        if (isNewState) {
            sb.append("NEW state.");
        } else {
            sb.append("Visited ").append(stateVisits).append("x.");
        }

        if (hasMopData) {
            sb.append(' ').append(mopCount).append('/').append(totalTargets).append(" MOP.");
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if any action in the list targets an input-type widget.
     */
    private boolean hasInputField(List<ModelAction> actions) {
        if (actions == null) return false;
        for (ModelAction action : actions) {
            if (action == null || !action.requireTarget()) continue;
            GUITreeNode node = safeGetResolvedNode(action);
            if (isInputClass(node)) return true;
        }
        return false;
    }

    private boolean isInputClass(GUITreeNode node) {
        if (node == null) return false;
        try {
            String cn = node.getClassName();
            if (cn == null) return false;
            return INPUT_CLASS_NAMES.contains(cn);
        } catch (Exception ignored) { return false; }
    }

    private GUITreeNode safeGetResolvedNode(ModelAction action) {
        try { return action.getResolvedNode(); } catch (Exception ignored) { return null; }
    }

    private String safeGetActivity(State state) {
        if (state == null) return "";
        try { return state.getActivity(); } catch (Exception ignored) { return ""; }
    }

    /**
     * Returns the simple class name: last component after '.' (handles null).
     * Example: "com.example.MainActivity" → "MainActivity"
     * If full class name is unknown, returns the full string.
     */
    private String simpleActivityName(String fullName) {
        if (fullName == null || fullName.isEmpty()) return "Unknown";
        int dot = fullName.lastIndexOf('.');
        return dot >= 0 ? fullName.substring(dot + 1) : fullName;
    }

    /**
     * Simple class name from a GUITreeNode (last component after '.').
     */
    private String safeGetSimpleClassName(GUITreeNode node) {
        if (node == null) return "View";
        try {
            String cn = node.getClassName();
            if (cn == null || cn.isEmpty()) return "View";
            int dot = cn.lastIndexOf('.');
            return dot >= 0 ? cn.substring(dot + 1) : cn;
        } catch (Exception ignored) { return "View"; }
    }

    /**
     * Returns the best display text for a node: prefers getText(), falls back to
     * getContentDesc(), then returns empty string.
     */
    private String safeGetDisplayText(GUITreeNode node) {
        if (node == null) return "";
        try {
            String text = node.getText();
            if (text != null && !text.isEmpty()) return text;
            String cd = node.getContentDesc();
            if (cd != null && !cd.isEmpty()) return cd;
        } catch (Exception ignored) { /* fall through */ }
        return "";
    }

    /**
     * Returns the hint text stored in the inputText field (APE uses this as hint for EditText).
     * Returns null if not available.
     */
    private String safeGetHint(GUITreeNode node) {
        if (node == null) return null;
        try { return node.getInputText(); } catch (Exception ignored) { return null; }
    }

    /**
     * Truncate a string to maxLen characters, appending "…" if truncated.
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "\u2026";
    }
}

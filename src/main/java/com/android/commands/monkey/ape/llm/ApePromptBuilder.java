package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Config;
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
    // Prompt variant names — selected via system property ape.llm.prompt_variant
    // -------------------------------------------------------------------------

    static final String VARIANT_APE_CURRENT = "ape_current";
    static final String VARIANT_APE_REASONING = "ape_reasoning";
    static final String VARIANT_COMPACT_V1 = "compact_v1";
    static final String VARIANT_V13 = "v13";
    static final String VARIANT_V17 = "v17";
    static final String VARIANT_VISUAL_ONLY = "visual_only";

    static String getPromptVariant() {
        return Config.llmPromptVariant;
    }

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

        // --- Dispatch by variant ---
        String variant = getPromptVariant();
        String systemText = buildSystemMessageForVariant(variant, includeTypeText);
        String userText;
        switch (variant) {
            case VARIANT_V13:
                userText = buildRvsmartV13UserText(
                        actions, mopData, recentActions, activity, activitySimple,
                        deviceWidth, deviceHeight);
                break;
            case VARIANT_V17:
                userText = buildRvsmartV17UserText(
                        state, actions, mopData, recentActions, activity, activitySimple,
                        deviceWidth, deviceHeight);
                break;
            case VARIANT_VISUAL_ONLY:
                userText = buildVisualOnlyUserText(
                        state, actions, mopData, recentActions, activity, activitySimple);
                break;
            default:
                userText = buildUserText(
                        tree, state, actions, mopData, recentActions,
                        activity, activitySimple, deviceWidth, deviceHeight);
                break;
        }

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
    // System message — dispatched by variant
    // -------------------------------------------------------------------------

    private static String buildSystemMessageForVariant(String variant, boolean includeTypeText) {
        switch (variant) {
            case VARIANT_COMPACT_V1:    return buildCompactV1System(includeTypeText);
            case VARIANT_V13:   return buildV13System(includeTypeText);
            case VARIANT_V17:   return buildV17System(includeTypeText);
            case VARIANT_VISUAL_ONLY:   return buildVisualOnlySystem(includeTypeText);
            case VARIANT_APE_REASONING:
            case VARIANT_APE_CURRENT:
            default:                    return buildApeCurrentSystem(includeTypeText);
        }
    }

    private static String buildApeCurrentSystem(boolean includeTypeText) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an Android UI testing agent exploring an app.\n");
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

    private static String buildCompactV1System(boolean includeTypeText) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an Android UI testing agent.\n");
        sb.append("CRITICAL RULES:\n");
        sb.append("1. You MUST call a tool — NEVER respond with text only.\n");
        sb.append("2. Dismiss dialogs first (click Allow/OK).\n");
        sb.append("3. Prioritize [DM]/[M] > unvisited (v:0) > visited.\n");
        sb.append("Tools (coordinates in [0,1000) normalized space):\n");
        sb.append("  click(x, y)\n");
        sb.append("  long_click(x, y)\n");
        if (includeTypeText) {
            sb.append("  type_text(x, y, text)\n");
        }
        sb.append("  back()\n");
        sb.append("Respond with one JSON: {\"name\": \"<action>\", \"arguments\": {<args>}}");
        return sb.toString();
    }

    private static String buildV13System(boolean includeTypeText) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an Android UI testing agent. Your task is to explore the app by interacting with UI elements.\n");
        sb.append("DIALOG HANDLING: If you see a permission dialog, click Allow/Accept/OK.\n");
        sb.append("  If you see an error or modal dialog, dismiss it before any other action.\n");
        sb.append("PRIORITY: [DM]/[M] elements > navigation to new screens > unvisited elements > visited elements.\n");
        sb.append("Available actions:\n");
        sb.append("  click(x, y) — tap an element at normalized coordinates [0,1000)\n");
        sb.append("  long_click(x, y) — long press at normalized coordinates\n");
        if (includeTypeText) {
            sb.append("  type_text(x, y, text) — type text into an input field\n");
        }
        sb.append("  back() — press the system back button\n");
        sb.append("RULE: Do not click the same position twice in a row.\n");
        sb.append("Respond with exactly one action as JSON: {\"name\": \"<action>\", \"arguments\": {<args>}}");
        return sb.toString();
    }

    private static final String SYSTEM_V17_HEADER =
            "You are an Android UI automation assistant.\n" +
            "\n" +
            "REASONING STEPS:\n" +
            "1. SCREEN: Identify screen type (dialog, form, list, menu).\n" +
            "2. DIALOG: If blocking dialog present, handle it first.\n" +
            "3. MOP CHECK: If [DM] or [M] elements are shown, prioritize them.\n" +
            "4. NAVIGATION: Check for actions leading to unvisited screens.\n" +
            "5. ELEMENTS: Select unvisited element if no navigation or MOP target available.\n" +
            "6. ACTION: Call the action with normalized coordinates [0,1000).\n" +
            "\n" +
            "DIALOG HANDLING:\n" +
            "- Permission dialogs: Click \"Allow\", \"Accept\", \"OK\"\n" +
            "- Error dialogs: Dismiss before other actions\n" +
            "- Use back() if no dismiss button visible\n" +
            "\n" +
            "PRIORITY:\n" +
            "- Elements reaching monitored operations ([DM] direct / [M] transitive) > other actions\n" +
            "- Actions leading to NEW screens > same-screen actions\n" +
            "- Unvisited (v:0) > visited\n" +
            "\n" +
            "RULES:\n" +
            "- Do not click the same position consecutively\n" +
            "- If last action had no effect, try a different element\n" +
            "- Explore new screens before testing same screen deeply\n" +
            "\n" +
            "AVOID: navigation bar (bottom), status bar (top)\n" +
            "\n" +
            "Available actions:\n";

    private static String buildV17System(boolean includeTypeText) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_V17_HEADER);
        sb.append("  click(x, y) — tap at normalized coordinates [0,1000)\n");
        sb.append("  long_click(x, y) — long press\n");
        if (includeTypeText) {
            sb.append("  type_text(x, y, text) — type into input field\n");
        }
        sb.append("  back() — press system back\n");
        sb.append("\nRespond with exactly one action as JSON: {\"name\": \"<action>\", \"arguments\": {<args>}}");
        return sb.toString();
    }

    private static String buildVisualOnlySystem(boolean includeTypeText) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an Android UI testing agent.\n");
        sb.append("Analyze the screenshot and choose the best action to explore the app.\n");
        sb.append("Dismiss dialogs first. Avoid status bar and navigation bar.\n");
        sb.append("Tools (coordinates in [0,1000) normalized space):\n");
        sb.append("  click(x, y) — tap element\n");
        sb.append("  long_click(x, y) — long press\n");
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
    // RVSmart V13 user text — simple numbered element list
    // -------------------------------------------------------------------------

    private String buildRvsmartV13UserText(List<ModelAction> actions, MopData mopData,
                                            List<ActionHistoryEntry> recentActions,
                                            String activity, String activitySimple,
                                            int deviceWidth, int deviceHeight) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current activity: ").append(activitySimple).append("\n\n");
        sb.append("UI elements:\n");

        int index = 1;
        if (actions != null) {
            for (ModelAction action : actions) {
                if (action == null || !action.getType().requireTarget()) continue;
                GUITreeNode node = safeGetResolvedNode(action);
                String className = safeGetSimpleClassName(node);
                String displayText = safeGetDisplayText(node);
                String mopMarker = buildMopMarker(node, mopData, activity);
                int normX = 0, normY = 0;
                if (node != null) {
                    try {
                        Rect bounds = node.getBoundsInScreen();
                        normX = Math.max(0, Math.min((int)((((bounds.left + bounds.right) / 2) / (double) deviceWidth) * 1000), 999));
                        normY = Math.max(0, Math.min((int)((((bounds.top + bounds.bottom) / 2) / (double) deviceHeight) * 1000), 999));
                    } catch (Exception ignored) {}
                }
                sb.append("  ").append(index++).append(". ").append(className);
                if (!displayText.isEmpty()) {
                    sb.append(" \"").append(truncate(displayText, 40)).append("\"");
                }
                sb.append(" @(").append(normX).append(",").append(normY).append(")");
                if (!mopMarker.isEmpty()) sb.append(" ").append(mopMarker);
                sb.append("\n");
            }
        }
        if (index == 1) sb.append("  (no elements)\n");

        // Action history
        if (recentActions != null && !recentActions.isEmpty()) {
            sb.append("\nRecent:\n");
            int start = Math.max(0, recentActions.size() - MAX_HISTORY);
            for (int i = start; i < recentActions.size(); i++) {
                sb.append("- ").append(formatHistoryEntry(recentActions.get(i))).append('\n');
            }
        }

        sb.append("\nChoose ONE action to explore new UI states or trigger monitored operations.");
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // RVSmart V17 user text — status tags, MOP navigation, screen info
    // -------------------------------------------------------------------------

    private String buildRvsmartV17UserText(State state, List<ModelAction> actions,
                                            MopData mopData,
                                            List<ActionHistoryEntry> recentActions,
                                            String activity, String activitySimple,
                                            int deviceWidth, int deviceHeight) {
        StringBuilder sb = new StringBuilder();

        // Recent actions
        if (recentActions != null && !recentActions.isEmpty()) {
            sb.append("Recent: ");
            int start = Math.max(0, recentActions.size() - MAX_HISTORY);
            boolean first = true;
            for (int i = start; i < recentActions.size(); i++) {
                if (!first) sb.append(", ");
                ActionHistoryEntry e = recentActions.get(i);
                sb.append(e.actionType).append("@(").append(e.normX).append(",").append(e.normY).append(")");
                first = false;
            }
            sb.append("\n");
        }

        sb.append("\nELEMENTS:\n");
        int index = 1;
        int testedCount = 0;
        int totalTargets = 0;
        if (actions != null) {
            for (ModelAction action : actions) {
                if (action == null || !action.getType().requireTarget()) continue;
                totalTargets++;
                GUITreeNode node = safeGetResolvedNode(action);
                String className = safeGetSimpleClassName(node);
                String displayText = safeGetDisplayText(node);
                String mopMarker = buildMopMarker(node, mopData, activity);
                int visitCount = action.getVisitedCount();
                if (visitCount > 0) testedCount++;

                // Status tag
                String statusTag;
                if (visitCount == 0) statusTag = "[UNTESTED]";
                else if (visitCount < 5) statusTag = "[TESTED-" + visitCount + "x]";
                else statusTag = "[WELL-TESTED]";

                int normX = 0, normY = 0;
                if (node != null) {
                    try {
                        Rect bounds = node.getBoundsInScreen();
                        normX = Math.max(0, Math.min((int)((((bounds.left + bounds.right) / 2) / (double) deviceWidth) * 1000), 999));
                        normY = Math.max(0, Math.min((int)((((bounds.top + bounds.bottom) / 2) / (double) deviceHeight) * 1000), 999));
                    } catch (Exception ignored) {}
                }

                sb.append("  ").append(index++).append(". ").append(className);
                if (!displayText.isEmpty()) {
                    sb.append(" \"").append(truncate(displayText, 40)).append("\"");
                }
                sb.append(" @(").append(normX).append(",").append(normY).append(")");
                sb.append(" ").append(statusTag);
                if (!mopMarker.isEmpty()) sb.append(" ").append(mopMarker);
                sb.append("\n");
            }
        }
        if (index == 1) sb.append("  (no elements)\n");

        // Screen info line
        int stateVisits = (state != null) ? state.getVisitedCount() : 0;
        sb.append("\nSCREEN: ").append(activitySimple)
                .append(" | ").append(testedCount).append("/").append(totalTargets)
                .append(" actions tested | visits: ").append(stateVisits).append("\n");

        // MOP navigation context
        String explorationCtx = buildExplorationContext(state, actions, mopData, activity);
        if (!explorationCtx.isEmpty()) {
            sb.append("\nMOP NAVIGATION:\n").append(explorationCtx).append("\n");
        }

        sb.append("\nSelect action. Prioritize elements reaching monitored operations, then navigation to new screens.");
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Visual-only user text — no widget list
    // -------------------------------------------------------------------------

    private String buildVisualOnlyUserText(State state,
                                            List<ModelAction> actions,
                                            MopData mopData,
                                            List<ActionHistoryEntry> recentActions,
                                            String activity,
                                            String activitySimple) {
        StringBuilder sb = new StringBuilder();
        sb.append("Screen \"").append(activitySimple).append("\".\n");

        if (recentActions != null && !recentActions.isEmpty()) {
            sb.append("Recent:\n");
            int start = Math.max(0, recentActions.size() - MAX_HISTORY);
            for (int i = start; i < recentActions.size(); i++) {
                sb.append("- ").append(formatHistoryEntry(recentActions.get(i))).append('\n');
            }
        }

        String explorationContext = buildExplorationContext(state, actions, mopData, activity);
        if (!explorationContext.isEmpty()) {
            sb.append(explorationContext).append('\n');
        }

        sb.append("Choose the best element to interact with based on the screenshot.");
        return sb.toString().trim();
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

package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.MopData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds multimodal LLM prompts from APE-RV internal data structures.
 *
 * <p>Produces exactly 2 messages: [system, user].
 * The user message has 2 content parts: [image, text] in that order.
 *
 * <p>Coordinate space: [0, 1000) normalized (matching Qwen3-VL conventions). Every coordinate the
 * prompt shows is the centre of the widget's visible part, converted by
 * {@link CoordinateNormalizer#toNormalized} with the display dimensions {@link #build} received:
 * the pair the caller determined for the decision, which the screenshot was captured at and the
 * answer is mapped with. Never the root node's bounds (INV-PRM-06).
 *
 * <p>The text is a base prompt plus what the mounted {@link PromptContribution}s add at its
 * extension points (llm-prompt "Prompt Built From Group Contributions"). The base is the group-free
 * wording; the builder never asks whether a group or its data is present (INV-PRM-08), it renders
 * what the contributions return, in the order they were mounted (STATIC before MOP).
 */
public class ApePromptBuilder {

    /**
     * The canonical "input-capable" widget set. One definition serves every consumer of the
     * notion: whether the system message offers {@code type_text}, the {@code type_text} filter in
     * CoordinateMapper, the fixTextEdit conversion of a click on such a widget, and the dead-pair
     * ban's exemption (llm-routing INV-RTR-15). Kept in one place so that if the
     * notion of "input-capable" is ever wrong, it is wrong once — a second, independently
     * maintained list would let the prompt, the mapping and the ban drift apart.
     */
    static final Set<String> INPUT_CLASS_NAMES = new HashSet<>(Arrays.asList(
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
    // Prompt variant names — selected via config key ape.llmPromptVariant
    // -------------------------------------------------------------------------

    static final String VARIANT_APE_CURRENT = "ape_current";
    static final String VARIANT_APE_REASONING = "ape_reasoning";
    static final String VARIANT_COMPACT_V1 = "compact_v1";
    static final String VARIANT_V13 = "v13";
    static final String VARIANT_V17 = "v17";
    static final String VARIANT_VISUAL_ONLY = "visual_only";

    // -------------------------------------------------------------------------
    // Extension points of the text, as PromptContribution.systemSentence receives them. A
    // variant's own name is the clause after "PRIORITY: " (after "3. Prioritize " in compact_v1);
    // these are the others.
    // -------------------------------------------------------------------------

    /** {@code v17}'s reasoning step after {@code DIALOG}; the builder numbers the steps. */
    static final String POINT_V17_STEP = "v17.step";
    /** {@code v17}'s {@code ELEMENTS} step, after "if no navigation". */
    static final String POINT_V17_ELEMENTS = "v17.elements";
    /** {@code v17}'s {@code PRIORITY} list, lines before its first line. */
    static final String POINT_V17_PRIORITY = "v17.priority";
    /** {@code v13}'s closing line, after "explore new UI states". */
    static final String POINT_V13_CLOSE = "v13.close";
    /** {@code v17}'s closing line, after "Prioritize ". */
    static final String POINT_V17_CLOSE = "v17.close";

    /**
     * The prompt variant this builder writes, injected at construction from the resolved plan
     * (INV-DP-12). The variant is run-frozen, so taking it once is the same value the read site
     * used to fetch on every prompt — by a route that a test can state and a run cannot change
     * midway.
     */
    private final String promptVariant;

    /** The mounted groups' contributions, in mounting order; empty for the base prompt. */
    private final List<PromptContribution> contributions;

    /** A builder of the base prompt: no group contributes. */
    public ApePromptBuilder(String promptVariant) {
        this(promptVariant, Collections.<PromptContribution>emptyList());
    }

    /**
     * @param promptVariant the run's prompt variant
     * @param contributions the contributions of the plan's mounted groups, STATIC before MOP
     */
    public ApePromptBuilder(String promptVariant, List<PromptContribution> contributions) {
        this.promptVariant = promptVariant;
        this.contributions = Collections.unmodifiableList(new ArrayList<>(contributions));
    }

    /** The mounted contributions, in mounting order. */
    public List<PromptContribution> contributions() {
        return contributions;
    }

    // -------------------------------------------------------------------------
    // ActionHistoryEntry — immutable record of a past action
    // -------------------------------------------------------------------------

    /**
     * A single entry in the recent action history.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code actionType} — "click", "long_click", "type_text", "back", "menu"</li>
     *   <li>{@code widgetClass} — simple class name of the target widget (empty for back, menu and
     *       an off-tree LLM tap)</li>
     *   <li>{@code widgetText} — the widget's text, else its content-description (may be empty)</li>
     *   <li>{@code bounds} — a copy of the executed widget's bounds in screen pixels, owned by the
     *       entry; for an off-tree LLM tap the one-pixel rectangle at the tap's own pixel; null for
     *       back and menu. Normalized only when a prompt is built, with that prompt's dimensions, so
     *       the list and the history of one prompt share one base (INV-PRM-06)</li>
     *   <li>{@code typedText} — text that was typed (only relevant for type_text)</li>
     *   <li>{@code result} — the outcome of this entry's own action: "same", "new screen" or
     *       "previous screen"; empty until the next state update fills it</li>
     * </ul>
     */
    public static class ActionHistoryEntry {
        public final String actionType;
        public final String widgetClass;
        public final String widgetText;
        public final Rect   bounds;
        public final String typedText;
        public final String result;

        public ActionHistoryEntry(String actionType, String widgetClass, String widgetText,
                                  Rect bounds, String typedText, String result) {
            this.actionType  = actionType  != null ? actionType  : "";
            this.widgetClass = widgetClass != null ? widgetClass : "";
            this.widgetText  = widgetText  != null ? widgetText  : "";
            this.bounds      = bounds != null ? new Rect(bounds) : null;
            this.typedText   = typedText   != null ? typedText   : "";
            this.result      = result      != null ? result      : "";
        }

        /** A copy of this entry carrying {@code result}; the entry itself stays immutable. */
        public ActionHistoryEntry withResult(String result) {
            return new ActionHistoryEntry(actionType, widgetClass, widgetText, bounds, typedText, result);
        }
    }

    // -------------------------------------------------------------------------
    // Main build method
    // -------------------------------------------------------------------------

    /**
     * Build the 2-message multimodal prompt for the LLM decision step.
     *
     * @param deviceWidth   display width in pixels, the one the caller determined for this decision
     *                      ({@code ScreenshotStep.deviceDimensions}); every listed and history
     *                      coordinate is normalized against it, never against the root node
     * @param deviceHeight  display height in pixels, from the same pair
     * @param state         current abstract state (for activity name and visit count)
     * @param actions       candidate ModelActions to present to the LLM; the state's own actions,
     *                      so each action's state is {@code state}
     * @param base64Image   base-64 encoded JPEG screenshot
     * @param recentActions up to MAX_HISTORY past actions (oldest first); may be null/empty
     * @return list of exactly 2 messages: [system, user]
     */
    public List<SglangClient.Message> build(int deviceWidth,
                                            int deviceHeight,
                                            State state,
                                            List<ModelAction> actions,
                                            String base64Image,
                                            List<ActionHistoryEntry> recentActions) {
        // Derive activity names safely
        String activity      = safeGetActivity(state);        // full class name
        String activitySimple = simpleActivityName(activity); // simple name for display

        // Decide whether to include type_text in the tool schema
        boolean includeTypeText = hasInputField(actions);

        // --- Dispatch by variant ---
        String variant = promptVariant;
        String systemText = buildSystemMessageForVariant(variant, includeTypeText);
        String userText;
        switch (variant) {
            case VARIANT_V13:
                userText = buildRvsmartV13UserText(
                        state, actions, recentActions, activity, activitySimple,
                        deviceWidth, deviceHeight);
                break;
            case VARIANT_V17:
                userText = buildRvsmartV17UserText(
                        state, actions, recentActions, activity, activitySimple,
                        deviceWidth, deviceHeight);
                break;
            case VARIANT_VISUAL_ONLY:
                userText = buildVisualOnlyUserText(
                        state, actions, recentActions, activity, activitySimple,
                        deviceWidth, deviceHeight);
                break;
            default:
                userText = buildUserText(
                        state, actions, recentActions,
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

    private String buildSystemMessageForVariant(String variant, boolean includeTypeText) {
        switch (variant) {
            case VARIANT_COMPACT_V1:    return buildCompactV1System(includeTypeText);
            case VARIANT_V13:   return buildV13System(includeTypeText);
            case VARIANT_V17:   return buildV17System(includeTypeText);
            case VARIANT_VISUAL_ONLY:   return buildVisualOnlySystem(includeTypeText);
            case VARIANT_APE_REASONING:
            case VARIANT_APE_CURRENT:
            default:                    return buildApeCurrentSystem(variant, includeTypeText);
        }
    }

    /** @param variant {@code ape_current} or {@code ape_reasoning}, which share this message */
    private String buildApeCurrentSystem(String variant, boolean includeTypeText) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an Android UI testing agent exploring an app.\n");
        sb.append("DIALOG: If permission/error dialog visible, dismiss it first (click Allow/OK).\n");
        sb.append("PRIORITY: ").append(sentence(variant)).append("unvisited (v:0) > visited.\n");
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

    private String buildCompactV1System(boolean includeTypeText) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an Android UI testing agent.\n");
        sb.append("CRITICAL RULES:\n");
        sb.append("1. You MUST call a tool — NEVER respond with text only.\n");
        sb.append("2. Dismiss dialogs first (click Allow/OK).\n");
        sb.append("3. Prioritize ").append(sentence(VARIANT_COMPACT_V1)).append("unvisited (v:0) > visited.\n");
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

    private String buildV13System(boolean includeTypeText) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an Android UI testing agent. Your task is to explore the app by interacting with UI elements.\n");
        sb.append("DIALOG HANDLING: If you see a permission dialog, click Allow/Accept/OK.\n");
        sb.append("  If you see an error or modal dialog, dismiss it before any other action.\n");
        sb.append("PRIORITY: ").append(sentence(VARIANT_V13)).append("navigation to new screens.\n");
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

    /**
     * {@code v17}'s system message. The reasoning steps are numbered here, because a contribution
     * may add one ({@link #POINT_V17_STEP}) and the steps after it follow on.
     */
    private String buildV17System(boolean includeTypeText) {
        List<String> steps = new ArrayList<>();
        steps.add("SCREEN: Identify screen type (dialog, form, list, menu).");
        steps.add("DIALOG: If blocking dialog present, handle it first.");
        String contributedStep = sentence(POINT_V17_STEP);
        if (!contributedStep.isEmpty()) steps.add(contributedStep);
        steps.add("NAVIGATION: Check for actions leading to unvisited screens.");
        steps.add("ELEMENTS: Select unvisited element if no navigation" + sentence(POINT_V17_ELEMENTS)
                + " target available.");
        steps.add("ACTION: Call the action with normalized coordinates [0,1000).");

        StringBuilder sb = new StringBuilder();
        sb.append("You are an Android UI automation assistant.\n");
        sb.append("\n");
        sb.append("REASONING STEPS:\n");
        for (int i = 0; i < steps.size(); i++) {
            sb.append(i + 1).append(". ").append(steps.get(i)).append('\n');
        }
        sb.append("\n");
        sb.append("DIALOG HANDLING:\n");
        sb.append("- Permission dialogs: Click \"Allow\", \"Accept\", \"OK\"\n");
        sb.append("- Error dialogs: Dismiss before other actions\n");
        sb.append("- Use back() if no dismiss button visible\n");
        sb.append("\n");
        sb.append("PRIORITY:\n");
        sb.append(sentence(POINT_V17_PRIORITY));
        sb.append("- Actions leading to NEW screens > same-screen actions\n");
        sb.append("- Unvisited (v:0) > visited\n");
        sb.append("\n");
        sb.append("RULES:\n");
        sb.append("- Do not click the same position consecutively\n");
        sb.append("- If last action had no effect, try a different element\n");
        sb.append("- Explore new screens before testing same screen deeply\n");
        sb.append("\n");
        sb.append("AVOID: navigation bar (bottom), status bar (top)\n");
        sb.append("\n");
        sb.append("Available actions:\n");
        sb.append("  click(x, y) — tap at normalized coordinates [0,1000)\n");
        sb.append("  long_click(x, y) — long press\n");
        if (includeTypeText) {
            sb.append("  type_text(x, y, text) — type into input field\n");
        }
        sb.append("  back() — press system back\n");
        sb.append("\nRespond with exactly one action as JSON: {\"name\": \"<action>\", \"arguments\": {<args>}}");
        return sb.toString();
    }

    private String buildVisualOnlySystem(boolean includeTypeText) {
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

    private String buildUserText(State state,
                                 List<ModelAction> actions,
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
                String line = formatActionLine(i, action, deviceWidth, deviceHeight);
                sb.append(line).append('\n');
            }
        }

        // Action history
        if (recentActions != null && !recentActions.isEmpty()) {
            sb.append("Recent:\n");
            int start = Math.max(0, recentActions.size() - MAX_HISTORY);
            for (int i = start; i < recentActions.size(); i++) {
                sb.append("- ").append(formatHistoryEntry(recentActions.get(i), deviceWidth, deviceHeight, false))
                        .append('\n');
            }
        }

        // Exploration context
        String explorationContext = buildExplorationContext(state, actions);
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
        String className = simpleClassName(node);

        // Visible text / content description
        String displayText = safeGetDisplayText(node);

        // Coordinates: null when the node is unresolved or wholly off screen
        int[] norm = normalizedCentre(safeGetBounds(node), deviceWidth, deviceHeight);

        // Visit count
        int visitCount = action.getVisitedCount();

        // The contributions' marker of the line's one action (design D22)
        String marker = lineMarker(Collections.singletonList(action));

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

        if (norm != null) {
            sb.append(' ').append(at(norm));
        }

        if (!marker.isEmpty()) {
            sb.append(' ').append(marker);
        }

        sb.append(" (v:").append(visitCount).append(')');

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Contributions: what the mounted groups add at each extension point
    // -------------------------------------------------------------------------

    /** The contributions' text at the extension point {@code point}, in mounting order. */
    private String sentence(String point) {
        StringBuilder sb = new StringBuilder();
        for (PromptContribution c : contributions) sb.append(c.systemSentence(point));
        return sb.toString();
    }

    /** The contributions' marker of a line rendering {@code lineActions}, or "". */
    private String lineMarker(List<ModelAction> lineActions) {
        StringBuilder sb = new StringBuilder();
        for (PromptContribution c : contributions) sb.append(c.lineMarker(lineActions));
        return sb.toString();
    }

    /** The contributions' detail of the line {@code action} is rendered from, or "". */
    private String lineDetail(ModelAction action) {
        StringBuilder sb = new StringBuilder();
        for (PromptContribution c : contributions) sb.append(c.lineDetail(action));
        return sb.toString();
    }

    /** The contributions' text after the visit context, or "". */
    private String contextLine(State state, List<ModelAction> actions) {
        StringBuilder sb = new StringBuilder();
        for (PromptContribution c : contributions) sb.append(c.contextLine(state, actions));
        return sb.toString();
    }

    /** The {@code v17} navigation heading: the contributions', else the neutral one. */
    private String navigationHeading(State state, List<ModelAction> actions) {
        StringBuilder sb = new StringBuilder();
        for (PromptContribution c : contributions) sb.append(c.section(state, actions));
        return sb.length() > 0 ? sb.toString() : "NAVIGATION:";
    }

    // -------------------------------------------------------------------------
    // Action history formatting (3.5)
    // -------------------------------------------------------------------------

    /**
     * The only place a history entry becomes text, for every variant (llm-prompt "Action History").
     * The coordinate is the entry's bounds rendered by the list's own rule ({@link #normalizedCentre})
     * with the dimensions {@link #build} received; {@code back} and {@code menu}, and an entry whose
     * bounds are null or wholly off screen, render without one.
     *
     * @param compact {@code v17}'s inline token, {@code <type>@(x,y)} or {@code <type>}; otherwise the
     *        full line {@code <type> @(x,y) <Class> "<text>" → <result>}
     */
    private String formatHistoryEntry(ActionHistoryEntry entry, int deviceWidth, int deviceHeight,
                                      boolean compact) {
        if (entry == null) return "";
        String type = entry.actionType;
        boolean keyPress = "back".equals(type) || "menu".equals(type);
        int[] norm = keyPress ? null : normalizedCentre(entry.bounds, deviceWidth, deviceHeight);
        StringBuilder sb = new StringBuilder(type);
        if (compact) {
            if (norm != null) sb.append(at(norm));
            return sb.toString();
        }
        if (norm != null) {
            sb.append(' ').append(at(norm));
        }
        if ("type_text".equals(type)) {
            if (!entry.typedText.isEmpty()) {
                sb.append(" \"").append(truncate(entry.typedText, MAX_TEXT_LEN)).append("\"");
            }
        } else if (!keyPress) {
            // click or long_click
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

    /**
     * The visit context, {@code NEW state.} or {@code Visited <N>x.}, which every cell shows, then
     * what the contributions add to it (MOP's {@code  <N>/<M> MOP.}). Empty without a state.
     */
    private String buildExplorationContext(State state, List<ModelAction> actions) {
        if (state == null) return "";

        int stateVisits = state.getVisitedCount();
        StringBuilder sb = new StringBuilder();
        if (stateVisits == 0) {
            sb.append("NEW state.");
        } else {
            sb.append("Visited ").append(stateVisits).append("x.");
        }
        sb.append(contextLine(state, actions));
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // v13 / v17 element list: one line per widget (design D10)
    // -------------------------------------------------------------------------

    /**
     * What makes two target-requiring actions one widget of the {@code v13}/{@code v17} list: the
     * same target {@code Name} and resolved nodes with the same four bound edges. Names are interned
     * by {@code NameManager.getCachedName}, so equal targets are one instance; the edges are compared
     * as ints rather than through {@code Rect.equals}.
     */
    private static final class WidgetKey {
        private final Name target;
        private final int left, top, right, bottom;

        WidgetKey(Name target, Rect bounds) {
            this.target = target;
            this.left = bounds.left;
            this.top = bounds.top;
            this.right = bounds.right;
            this.bottom = bounds.bottom;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof WidgetKey)) return false;
            WidgetKey k = (WidgetKey) o;
            return Objects.equals(target, k.target)
                    && left == k.left && top == k.top && right == k.right && bottom == k.bottom;
        }

        @Override
        public int hashCode() {
            return Objects.hash(target, left, top, right, bottom);
        }
    }

    /**
     * The target-requiring actions of {@code actions} grouped into widgets, one group per line, in
     * the order of each widget's first action. An action whose node is unresolved, or whose bounds
     * cannot be read, is a group of its own. The line is rendered from the group's first action.
     */
    private static List<List<ModelAction>> widgetGroups(List<ModelAction> actions) {
        List<List<ModelAction>> groups = new ArrayList<>();
        if (actions == null) return groups;
        Map<WidgetKey, List<ModelAction>> byKey = new LinkedHashMap<>();
        for (ModelAction action : actions) {
            if (action == null || !action.getType().requireTarget()) continue;
            Rect bounds = safeGetBounds(safeGetResolvedNode(action));
            List<ModelAction> group = null;
            if (bounds != null) {
                WidgetKey key = new WidgetKey(action.getTarget(), bounds);
                group = byKey.get(key);
                if (group == null) {
                    group = new ArrayList<>();
                    byKey.put(key, group);
                    groups.add(group);
                }
            } else {
                group = new ArrayList<>();
                groups.add(group);
            }
            group.add(action);
        }
        return groups;
    }

    // -------------------------------------------------------------------------
    // RVSmart V13 user text — simple element list
    // -------------------------------------------------------------------------

    private String buildRvsmartV13UserText(State state, List<ModelAction> actions,
                                            List<ActionHistoryEntry> recentActions,
                                            String activity, String activitySimple,
                                            int deviceWidth, int deviceHeight) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current activity: ").append(activitySimple).append("\n\n");
        sb.append("UI elements:\n");

        List<List<ModelAction>> widgets = widgetGroups(actions);
        for (List<ModelAction> widget : widgets) {
            GUITreeNode node = safeGetResolvedNode(widget.get(0));
            String className = simpleClassName(node);
            String displayText = safeGetDisplayText(node);
            String marker = lineMarker(widget);
            int[] norm = normalizedCentre(safeGetBounds(node), deviceWidth, deviceHeight);
            sb.append("  ").append(className);
            if (!displayText.isEmpty()) {
                sb.append(" \"").append(truncate(displayText, 40)).append("\"");
            }
            if (norm != null) sb.append(" ").append(at(norm));
            if (!marker.isEmpty()) sb.append(" ").append(marker);
            sb.append(lineDetail(widget.get(0)));
            sb.append("\n");
        }
        if (widgets.isEmpty()) sb.append("  (no elements)\n");

        // Action history
        if (recentActions != null && !recentActions.isEmpty()) {
            sb.append("\nRecent:\n");
            int start = Math.max(0, recentActions.size() - MAX_HISTORY);
            for (int i = start; i < recentActions.size(); i++) {
                sb.append("- ").append(formatHistoryEntry(recentActions.get(i), deviceWidth, deviceHeight, false))
                        .append('\n');
            }
        }

        sb.append("\nChoose ONE action to explore new UI states").append(sentence(POINT_V13_CLOSE))
                .append('.');
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // RVSmart V17 user text — status tags, screen info, navigation block
    // -------------------------------------------------------------------------

    private String buildRvsmartV17UserText(State state, List<ModelAction> actions,
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
                sb.append(formatHistoryEntry(recentActions.get(i), deviceWidth, deviceHeight, true));
                first = false;
            }
            sb.append("\n");
        }

        sb.append("\nELEMENTS:\n");
        int testedCount = 0;
        List<List<ModelAction>> widgets = widgetGroups(actions);
        for (List<ModelAction> widget : widgets) {
            GUITreeNode node = safeGetResolvedNode(widget.get(0));
            String className = simpleClassName(node);
            String displayText = safeGetDisplayText(node);
            String marker = lineMarker(widget);
            int visitCount = 0;
            for (ModelAction action : widget) visitCount += action.getVisitedCount();
            if (visitCount > 0) testedCount++;

            // Status tag, from the widget's visits over all its actions
            String statusTag;
            if (visitCount == 0) statusTag = "[UNTESTED]";
            else if (visitCount < 5) statusTag = "[TESTED-" + visitCount + "x]";
            else statusTag = "[WELL-TESTED]";

            int[] norm = normalizedCentre(safeGetBounds(node), deviceWidth, deviceHeight);

            sb.append("  ").append(className);
            if (!displayText.isEmpty()) {
                sb.append(" \"").append(truncate(displayText, 40)).append("\"");
            }
            if (norm != null) sb.append(" ").append(at(norm));
            sb.append(" ").append(statusTag);
            if (!marker.isEmpty()) sb.append(" ").append(marker);
            sb.append(lineDetail(widget.get(0)));
            sb.append("\n");
        }
        if (widgets.isEmpty()) sb.append("  (no elements)\n");

        // Screen info line, counted in widgets on both sides
        int stateVisits = (state != null) ? state.getVisitedCount() : 0;
        sb.append("\nSCREEN: ").append(activitySimple)
                .append(" | ").append(testedCount).append("/").append(widgets.size())
                .append(" elements tested | visits: ").append(stateVisits).append("\n");

        // Navigation block: the visit context, in every cell, under the contributions' heading
        String explorationCtx = buildExplorationContext(state, actions);
        if (!explorationCtx.isEmpty()) {
            sb.append('\n').append(navigationHeading(state, actions)).append('\n')
                    .append(explorationCtx).append("\n");
        }

        sb.append("\nSelect action. Prioritize ").append(sentence(POINT_V17_CLOSE))
                .append("navigation to new screens.");
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Visual-only user text — no widget list
    // -------------------------------------------------------------------------

    private String buildVisualOnlyUserText(State state,
                                            List<ModelAction> actions,
                                            List<ActionHistoryEntry> recentActions,
                                            String activity,
                                            String activitySimple,
                                            int deviceWidth,
                                            int deviceHeight) {
        StringBuilder sb = new StringBuilder();
        sb.append("Screen \"").append(activitySimple).append("\".\n");

        if (recentActions != null && !recentActions.isEmpty()) {
            sb.append("Recent:\n");
            int start = Math.max(0, recentActions.size() - MAX_HISTORY);
            for (int i = start; i < recentActions.size(); i++) {
                sb.append("- ").append(formatHistoryEntry(recentActions.get(i), deviceWidth, deviceHeight, false))
                        .append('\n');
            }
        }

        String explorationContext = buildExplorationContext(state, actions);
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
     *
     * <p>One predicate decides both halves of the same offer: whether the system message lists
     * {@code type_text}, and whether the wire schema LlmClient sends carries it. Static so both
     * can ask it without a builder instance — prompt and wire can then never disagree.
     */
    static boolean hasInputField(List<ModelAction> actions) {
        if (actions == null) return false;
        for (ModelAction action : actions) {
            if (action == null || !action.requireTarget()) continue;
            GUITreeNode node = safeGetResolvedNode(action);
            if (isInputClass(node)) return true;
        }
        return false;
    }

    /**
     * The canonical input-capable predicate, read by the prompt builder, CoordinateMapper and the
     * dead-pair ban. Static so every consumer reaches the same set
     * ({@link #INPUT_CLASS_NAMES}) without holding a builder instance.
     */
    public static boolean isInputClass(GUITreeNode node) {
        if (node == null) return false;
        try {
            String cn = node.getClassName();
            if (cn == null) return false;
            return INPUT_CLASS_NAMES.contains(cn);
        } catch (Exception ignored) { return false; }
    }

    private static GUITreeNode safeGetResolvedNode(ModelAction action) {
        try { return action.getResolvedNode(); } catch (Exception ignored) { return null; }
    }

    /** The node's screen bounds, or null when the node is null or its bounds cannot be read. */
    private static Rect safeGetBounds(GUITreeNode node) {
        if (node == null) return null;
        try { return node.getBoundsInScreen(); } catch (Exception ignored) { return null; }
    }

    /**
     * The pixel a widget is shown at: the centre of its bounds intersected with the display
     * {@code [0,width) x [0,height)}, the part the screenshot shows and the jar would tap. Null when
     * {@code bounds} is null or {@link Rect#intersect} finds no intersection (the widget is wholly
     * off screen). {@code Rect.intersect} compares edges, so a zero-width or zero-height rect inside
     * the display intersects it and keeps its centre. {@code bounds} is not modified.
     */
    private static int[] visibleCentre(Rect bounds, int width, int height) {
        if (bounds == null) return null;
        Rect visible = new Rect(bounds);
        if (!visible.intersect(0, 0, width, height)) return null;
        return new int[]{visible.centerX(), visible.centerY()};
    }

    /**
     * The [0,1000) coordinate a widget is shown at, or null when it has none: the visible centre
     * through {@link CoordinateNormalizer#toNormalized}, with the dimensions {@link #build} received.
     * Every list site and the history formatter go through it (INV-PRM-06).
     */
    private static int[] normalizedCentre(Rect bounds, int width, int height) {
        int[] centre = visibleCentre(bounds, width, height);
        if (centre == null) return null;
        return CoordinateNormalizer.toNormalized(centre[0], centre[1], width, height);
    }

    /** {@code @(x,y)} for a normalized coordinate. */
    private static String at(int[] norm) {
        return "@(" + norm[0] + "," + norm[1] + ")";
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
     * Simple class name from a GUITreeNode (last component after '.'); {@code "View"} for a null
     * node, a null or empty class, or a throwing read. The element list and the action history
     * name a widget's class through this one definition (design D13).
     */
    public static String simpleClassName(GUITreeNode node) {
        if (node == null) return "View";
        try {
            String cn = node.getClassName();
            if (cn == null || cn.isEmpty()) return "View";
            int dot = cn.lastIndexOf('.');
            return dot >= 0 ? cn.substring(dot + 1) : cn;
        } catch (Exception ignored) { return "View"; }
    }

    /**
     * The node's identifier for the element line, by fallback: visible text, then
     * content-description ({@link #textOrContentDescription}), then the short resource id rendered
     * as {@code id=<shortId>} (INV-PRM-05). Only a node carrying none of the three yields an empty
     * identifier.
     *
     * <p>The resource-id step is what N1 adds. Without it, 35.8% of grounding tests rendered
     * elements the model had nothing to anchor on, and it hit 33.1% on those lines against 71.4%
     * on lines with an identifier. {@code ImageView} was the canonical victim at 0/210: icon
     * buttons routinely carry a content-description or a resource id and no text at all.
     */
    private String safeGetDisplayText(GUITreeNode node) {
        String label = textOrContentDescription(node);
        if (!label.isEmpty() || node == null) return label;
        try {
            String shortId = MopData.extractShortId(node.getResourceID());
            if (!shortId.isEmpty()) return "id=" + shortId;
        } catch (Exception ignored) { /* fall through */ }
        return "";
    }

    /**
     * The node's text, else its content-description, flattened to one line; {@code ""} when it has
     * neither, and never {@code id=…}. The first two steps of the element list's identifier, shared
     * with the action history so that both name a widget the same way (design D13).
     */
    public static String textOrContentDescription(GUITreeNode node) {
        if (node == null) return "";
        try {
            String text = node.getText();
            if (text != null && !text.isEmpty()) return flatten(text);
            String cd = node.getContentDesc();
            if (cd != null && !cd.isEmpty()) return flatten(cd);
        } catch (Exception ignored) { /* fall through */ }
        return "";
    }

    /**
     * Widget text with {@code \n}/{@code \r} replaced by spaces, so one element occupies one line
     * of the element list and of the LLM sub-event's {@code user} dump field (INV-PRM-05). The
     * metadata suffix already does this via {@code capMeta}; the identifier is the other half. The
     * prompt's own multi-line structure is intentional and untouched — only widget-derived text is
     * flattened.
     */
    private static String flatten(String text) {
        return text.replace('\n', ' ').replace('\r', ' ');
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

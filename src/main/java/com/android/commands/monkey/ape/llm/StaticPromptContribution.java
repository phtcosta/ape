package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.StaticGuiModel;

import java.util.List;

/**
 * The STATIC group's part of the LLM prompt (llm-prompt "Prompt Built From Group Contributions"),
 * mounted iff {@code Feature.STATIC} is in the plan: the widget metadata suffix of the {@code v13}
 * and {@code v17} lines (gh13 T1.1), read from {@link StaticGuiModel} and from nowhere else
 * (INV-PRM-09).
 *
 * <p>The widget is the line's node's short resource id in the action's state's activity; the
 * prompt lists {@code state.getActions()}, so that is the prompt's activity.
 *
 * <p>The model exists only once the agent has loaded the artifact, after this contribution was
 * built; the agent binds it through {@code LlmEngine.bindStaticModel}. Unbound, no line carries
 * metadata.
 */
public final class StaticPromptContribution implements PromptContribution {

    private static final int META_FIELD_CAP = 80;
    private static final int META_ENTRY_CAP = 10;

    private StaticGuiModel model;

    /**
     * Binds the run's static GUI model, which the metadata reads. Called once, after the artifact
     * loads.
     *
     * @param model the run's static GUI model
     */
    public void bind(StaticGuiModel model) {
        this.model = model;
    }

    /** The metadata suffix of the widget {@code a} acts on, or "". */
    @Override
    public String lineDetail(ModelAction a) {
        if (model == null || a == null || a.getState() == null) return "";
        try {
            GUITreeNode node = a.getResolvedNode();
            if (node == null) return "";
            String shortId = MopData.extractShortId(node.getResourceID());
            if (shortId.isEmpty()) return "";
            return widgetMetadata(model.widget(a.getState().getActivity(), shortId));
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Render the T1.1 metadata suffix for a widget: prompt / spinnerMode / contentDescription /
     * tooltipText / entries / inputType / hint, each emitted only when non-null/non-empty
     * (INV-MOP-10). Per-value 80-char cap, newlines flattened, quotes escaped; entries capped to 10
     * with a trailing ", …".
     */
    static String widgetMetadata(StaticGuiModel.WidgetMeta w) {
        if (w == null) return "";
        StringBuilder sb = new StringBuilder();
        appendMetaField(sb, "prompt", w.prompt);
        appendMetaField(sb, "spinnerMode", w.spinnerMode);
        appendMetaField(sb, "contentDescription", w.contentDescription);
        appendMetaField(sb, "tooltipText", w.tooltipText);
        appendMetaEntries(sb, w.entries);
        appendMetaField(sb, "inputType", w.inputType);
        appendMetaField(sb, "hint", w.hint);
        return sb.toString();
    }

    private static void appendMetaField(StringBuilder sb, String name, String value) {
        if (value == null || value.isEmpty()) return;
        sb.append(' ').append(name).append("=\"").append(capMeta(value)).append('"');
    }

    private static void appendMetaEntries(StringBuilder sb, List<String> entries) {
        if (entries == null || entries.isEmpty()) return;
        sb.append(" entries=[");
        int n = Math.min(entries.size(), META_ENTRY_CAP);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(capMeta(entries.get(i)));
        }
        if (entries.size() > META_ENTRY_CAP) sb.append(", …");
        sb.append(']');
    }

    /** Flatten newlines, escape quotes, and cap at 80 chars with an ellipsis. */
    private static String capMeta(String value) {
        String flat = value.replace('\n', ' ').replace('\r', ' ').replace("\"", "\\\"");
        if (flat.length() > META_FIELD_CAP) {
            return flat.substring(0, META_FIELD_CAP) + "…";
        }
        return flat;
    }
}

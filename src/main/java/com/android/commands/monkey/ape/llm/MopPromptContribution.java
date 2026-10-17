package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopScorer;

import java.util.List;

/**
 * The MOP group's part of the LLM prompt (llm-prompt "Prompt Built From Group Contributions"),
 * mounted iff {@code Feature.MOP} is in the plan.
 *
 * <p>It contributes every MOP word the model reads: the {@code [DM]}/{@code [M]} line markers, the
 * {@code  N/M MOP.} count after the visit context, the {@code MOP NAVIGATION:} heading of
 * {@code v17}, and the MOP clauses of the system messages and of the {@code v13}/{@code v17}
 * closing lines. Each clause is the text the builder's MOP-free sentence lacks at that point, so
 * with this contribution mounted the prompt is the E6 prompt byte for byte, and without it no MOP
 * text exists (INV-PRM-07).
 *
 * <p>The marker and the count share one per-action test, the scoring's own weight under the run's
 * {@code ape.mopScoring} mode ({@link MopScorer#weigh}, design D22), so a line is marked iff one of
 * its actions is counted. The activity is the action's state's: the prompt lists
 * {@code state.getActions()}, so it is the prompt's state.
 *
 * <p>The run's {@link MopContext} exists only once the agent has loaded the artifact, after this
 * contribution was built; the agent binds it through {@code LlmEngine.bindMopContext}. Unbound, no
 * action weighs anything: no marker, and a count of {@code 0}.
 */
public final class MopPromptContribution implements PromptContribution {

    /** The action is not MOP: weight 0, no resolved node, or no context. */
    private static final int MOP_NONE = 0;
    /** The action has a positive MOP weight ({@code [M]}). */
    private static final int MOP_WEIGHTED = 1;
    /** The action has a positive MOP weight and is direct ({@code [DM]}). */
    private static final int MOP_DIRECT = 2;

    private MopContext mop;

    /**
     * Binds the run's MOP context, which the markers and the count read. Called once, after the
     * artifact loads.
     *
     * @param mop the run's MOP context
     */
    public void bind(MopContext mop) {
        this.mop = mop;
    }

    /**
     * The MOP clause the builder's sentence takes at {@code point}: the {@code PRIORITY} clause of a
     * variant's system message (the point is the variant's name), and the points
     * {@link ApePromptBuilder#POINT_V17_STEP}, {@link ApePromptBuilder#POINT_V17_ELEMENTS},
     * {@link ApePromptBuilder#POINT_V17_PRIORITY}, {@link ApePromptBuilder#POINT_V13_CLOSE} and
     * {@link ApePromptBuilder#POINT_V17_CLOSE}. {@code visual_only} has none.
     */
    @Override
    public String systemSentence(String point) {
        switch (point) {
            case ApePromptBuilder.VARIANT_APE_CURRENT:
            case ApePromptBuilder.VARIANT_APE_REASONING:
            case ApePromptBuilder.VARIANT_V13:
                return "[DM]/[M] elements > ";
            case ApePromptBuilder.VARIANT_COMPACT_V1:
                return "[DM]/[M] > ";
            case ApePromptBuilder.POINT_V17_STEP:
                return "MOP CHECK: If [DM] or [M] elements are shown, prioritize them.";
            case ApePromptBuilder.POINT_V17_ELEMENTS:
                return " or MOP";
            case ApePromptBuilder.POINT_V17_PRIORITY:
                return "- Elements reaching monitored operations ([DM] direct / [M] transitive) > other actions\n";
            case ApePromptBuilder.POINT_V13_CLOSE:
                return " or trigger monitored operations";
            case ApePromptBuilder.POINT_V17_CLOSE:
                return "elements reaching monitored operations, then ";
            default:
                return "";
        }
    }

    /**
     * {@code [DM]}, {@code [M]} or "" for a line ("MOP Marker Annotation"): {@code [DM]} when one of
     * its actions is {@link #MOP_DIRECT}, else {@code [M]} when one is {@link #MOP_WEIGHTED}.
     */
    @Override
    public String lineMarker(List<ModelAction> lineActions) {
        int verdict = MOP_NONE;
        for (ModelAction action : lineActions) {
            verdict = Math.max(verdict, verdict(action));
        }
        if (verdict == MOP_DIRECT) return "[DM]";
        if (verdict == MOP_WEIGHTED) return "[M]";
        return "";
    }

    /**
     * {@code " <N>/<M> MOP."}: {@code M} target-requiring actions, {@code N} of them MOP under the
     * marker's test ("Exploration Context in Prompt").
     */
    @Override
    public String contextLine(State s, List<ModelAction> actions) {
        int mopCount = 0;
        int totalTargets = 0;
        if (actions != null) {
            for (ModelAction action : actions) {
                if (action != null && action.requireTarget()) {
                    totalTargets++;
                    if (verdict(action) != MOP_NONE) {
                        mopCount++;
                    }
                }
            }
        }
        return " " + mopCount + "/" + totalTargets + " MOP.";
    }

    /** The {@code v17} navigation heading, in place of the builder's neutral {@code NAVIGATION:}. */
    @Override
    public String section(State s, List<ModelAction> actions) {
        return "MOP NAVIGATION:";
    }

    /**
     * The MOP verdict of one action under the run's mode (design D22). The weight and its lookup
     * are the scoring's ({@link MopScorer#weigh}: the D17 lookup over the containment nodes, under
     * {@code distance} ignoring retired targets), before the tie split. A positive weight is
     * {@link #MOP_WEIGHTED}; it is {@link #MOP_DIRECT} when the action is also direct under the
     * mode: under {@code flag} the weight's lookup is a direct flag, under {@code distance} its
     * smallest unretired distance is 0.
     */
    private int verdict(ModelAction action) {
        if (mop == null || action == null || action.getState() == null) return MOP_NONE;
        try {
            MopScorer.Scored scored = MopScorer.weigh(mop, action.getState(), action);
            if (scored.weight <= 0) return MOP_NONE;
            switch (mop.scoring()) {
                case FLAG:
                    return scored.lookup.direct ? MOP_DIRECT : MOP_WEIGHTED;
                case DISTANCE:
                    return scored.distance == 0 ? MOP_DIRECT : MOP_WEIGHTED;
                default:
                    return MOP_WEIGHTED;
            }
        } catch (RuntimeException ignored) {
            return MOP_NONE; // never throw (build's contract)
        }
    }
}

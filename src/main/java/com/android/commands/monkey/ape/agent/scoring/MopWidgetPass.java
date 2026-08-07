package com.android.commands.monkey.ape.agent.scoring;

import java.util.List;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.MopScorer;

/**
 * rv-scoring-pipeline: the MOP-widget boost, extracted verbatim from the inline block in
 * {@code StatefulAgent.adjustActionsByGUITree()}. Scoring semantics owned by {@code mop-guidance}
 * (MopScorer — Priority Boost); this pass only re-homes the wiring. Enabled whenever MOP data is
 * present ({@code mopData != null}).
 */
public final class MopWidgetPass implements ScoringPass {

    private final boolean enabled;
    private final int weightDirect;
    private final int weightTransitive;

    /**
     * Where the exposure pair goes. Handed in like every other collaborator rather than reached for
     * through the ambient run context, which INV-DP-12 forbids of an injected unit — and which would
     * also make this pass untestable without a whole run behind it.
     */
    private final EventSink sink;

    public MopWidgetPass(ScoringContext ctx, ScoringParams params, EventSink sink) {
        this.enabled = ctx.getMopData() != null;
        this.weightDirect = params.mopWeightDirect();
        this.weightTransitive = params.mopWeightTransitive();
        this.sink = sink;
    }

    @Override
    public String name() {
        return "MopWidgetPass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void apply(State state, ModelAction[] actions, ScoringContext ctx) {
        MopData mopData = ctx.getMopData();
        int timestamp = ctx.getTimestamp();
        String activity = state.getActivity();
        int boostedCount = 0;
        int totalTarget = 0;
        int maxBoost = 0;
        int containmentBoostedCount = 0;
        for (ModelAction action : actions) {
            if (!action.requireTarget() || !action.isValid()) continue;
            if (!action.isResolvedAt(timestamp)) continue;
            totalTarget++;
            GUITreeNode node = action.getResolvedNode();
            if (node == null) continue;
            boolean[] containmentHit = new boolean[]{false};
            int boost = mopBoostWithContainment(activity, node, MopScorer.eventTypeOf(action),
                    containmentHit, mopData, weightDirect, weightTransitive);
            if (boost > 0) {
                action.setPriority(action.getPriority() + boost);
                action.setMopBoost(boost);
                boostedCount++;
                if (containmentHit[0]) containmentBoostedCount++;
                if (boost > maxBoost) maxBoost = boost;
            }
        }
        Logger.iformat(
                "[APE-RV] MOP boost: state=%s#%s, boosted=%d/%d, maxBoost=%d, containment=%d",
                activity, state.getStateKey(), boostedCount, totalTarget, maxBoost,
                containmentBoostedCount);
        // The exposure pair goes onto the step's record from here, where it is computed, rather
        // than being threaded back out to the decision site: it is the denominator that turns "the
        // MOP scorer fired on 0.4 % of decisions" into a statement about opportunity rather than
        // about luck, and it is per-step — a state can realise more than one pair within a run.
        sink.mopExposure(boostedCount, totalTarget);
    }

    /**
     * MOP boost for an action's node, reconciling parent/child widget granularity (B3, INV-MOP):
     * scores the node's own short id plus its ancestors (≤ 2 up) and descendants (≤ 2 down) via
     * {@link MopScorer#score}, returning the maximum. {@code containmentHit[0]} is set true when the
     * winning boost came from an ancestor/descendant rather than the exact id (hit-rate telemetry).
     */
    private static int mopBoostWithContainment(String activity, GUITreeNode node,
                                               String eventType, boolean[] containmentHit,
                                               MopData mopData, int weightDirect,
                                               int weightTransitive) {
        List<String> ids = MopScorer.containmentShortIds(node);
        int best = MopScorer.score(
                activity, ids.get(0), mopData, eventType, weightDirect, weightTransitive);
        for (int i = 1; i < ids.size(); i++) {
            int b = MopScorer.score(
                    activity, ids.get(i), mopData, eventType, weightDirect, weightTransitive);
            if (b > best) {
                best = b;
                containmentHit[0] = true;
            }
        }
        return best;
    }
}

package com.android.commands.monkey.ape.agent.scoring;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.android.commands.monkey.ape.agent.StatefulAgent;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopData;

/**
 * rv-scoring-pipeline: the activity-frontier boost (Lever A), extracted from the (formerly interleaved)
 * WTG/frontier loop in {@code StatefulAgent.adjustActionsByGUITree()}. Rewards a widget whose WTG
 * transition targets an as-yet-unvisited activity ({@code Graph.getActivityNode == null}), independent
 * of MOP-reachability. Scoring semantics owned by {@code wtg-navigation} (WTG Frontier Boost — the
 * archived {@code activity-frontier} change).
 *
 * <p>The frontier term reads {@code MopData.getWtgTransitions}, so it carries the SAME MopData/WTG-data
 * precondition as {@link WtgPass}, not {@code frontierBoostWeight > 0} alone. Splitting it from WtgPass
 * is parity-safe only with this re-guard (else it would NPE or change behavior when MopData is null):
 * {@code isEnabled = mopData != null && hasWtgData() && frontierBoostWeight > 0}. {@code setPriority} is
 * the steering mechanism; {@code wtgBoost} accumulation is telemetry (read-modify-write so it stacks on
 * top of WtgPass's boost).
 */
public final class FrontierPass implements ScoringPass {

    private final boolean enabled;
    private final int weight;

    public FrontierPass(ScoringContext ctx, ScoringParams params) {
        MopData mopData = ctx.getMopData();
        this.weight = params.frontierBoostWeight();
        this.enabled = mopData != null && mopData.hasWtgData() && weight > 0;
    }

    @Override
    public String name() {
        return "FrontierPass";
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
        // Precompute which transition targets are already visited so the frontier decision is a pure
        // set lookup (INV-WTG-06). isEnabled already guarantees the weight is positive.
        List<MopData.WtgTransition> transitions = mopData.getWtgTransitions(activity);
        Set<String> visitedTargets = new HashSet<>();
        for (MopData.WtgTransition t : transitions) {
            if (ctx.getGraph().getActivityNode(t.targetActivity) != null) {
                visitedTargets.add(t.targetActivity);
            }
        }
        int frontierBoostedCount = 0;
        int frontierMaxBoost = 0;
        int wtgTotalTarget = 0;
        for (ModelAction action : actions) {
            if (!action.requireTarget() || !action.isValid()) continue;
            if (!action.isResolvedAt(timestamp)) continue;
            wtgTotalTarget++;
            GUITreeNode node = action.getResolvedNode();
            if (node == null) continue;
            String shortId = MopData.extractShortId(node.getResourceID());
            int fBoost = StatefulAgent.frontierBoost(shortId, transitions, visitedTargets, weight);
            if (fBoost > 0) {
                action.setPriority(action.getPriority() + fBoost);
                action.setWtgBoost(action.getWtgBoost() + fBoost);
                action.markWtgSource(ModelAction.WTG_SOURCE_FRONTIER);
                frontierBoostedCount++;
                if (fBoost > frontierMaxBoost) frontierMaxBoost = fBoost;
            }
        }
        if (frontierBoostedCount > 0) {
            Logger.iformat("[APE-RV] Frontier boost: state=%s#%s, boosted=%d/%d, maxBoost=%d",
                    activity, state.getStateKey(), frontierBoostedCount, wtgTotalTarget, frontierMaxBoost);
        }
    }
}

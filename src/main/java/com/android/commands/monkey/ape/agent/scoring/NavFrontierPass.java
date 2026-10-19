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
import com.android.commands.monkey.ape.utils.StaticGuiModel;

/**
 * The navigation-frontier scorer, the STATIC member {@code NAV_FRONTIER} (wtg-navigation "WTG
 * Frontier Boost for Unvisited Activities", design D-A1). Rewards a widget whose WTG transition
 * targets an as-yet-unvisited activity ({@code Graph.getActivityNode == null}). It reads the WTG
 * from {@link StaticGuiModel} and no MOP data, so its term is the same in a cell with and without
 * MOP (INV-WTG-08).
 *
 * <p>Enabled iff {@code NAV_FRONTIER} is mounted (a positive {@code frontierBoostWeight}) and the
 * static model carries WTG data. Under rule R1 the term is computed from the screen, the graph and
 * the WTG alone, written to its own field {@code navFrontierBoost}, and added to the priority —
 * the steering mechanism, since the field is telemetry-only.
 */
public final class NavFrontierPass implements ScoringPass {

    private final boolean enabled;
    private final int weight;

    public NavFrontierPass(ScoringContext ctx, ScoringParams params) {
        StaticGuiModel staticModel = ctx.getStaticModel();
        this.weight = params.frontierBoostWeight();
        this.enabled = weight > 0 && staticModel != null && staticModel.hasWtgData();
    }

    @Override
    public String name() {
        return "NavFrontierPass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void apply(State state, ModelAction[] actions, ScoringContext ctx) {
        int timestamp = ctx.getTimestamp();
        String activity = state.getActivity();
        // Precompute which transition targets are already visited so the frontier decision is a pure
        // set lookup (INV-WTG-06). isEnabled already guarantees the weight is positive.
        List<StaticGuiModel.WtgTransition> transitions =
                ctx.getStaticModel().wtgTransitions(activity);
        Set<String> visitedTargets = new HashSet<>();
        for (StaticGuiModel.WtgTransition t : transitions) {
            if (ctx.getGraph().getActivityNode(t.targetActivity) != null) {
                visitedTargets.add(t.targetActivity);
            }
        }
        int boostedCount = 0;
        int maxBoost = 0;
        int totalTarget = 0;
        for (ModelAction action : actions) {
            if (!action.requireTarget() || !action.isValid()) continue;
            if (!action.isResolvedAt(timestamp)) continue;
            totalTarget++;
            GUITreeNode node = action.getResolvedNode();
            if (node == null) continue;
            String shortId = MopData.extractShortId(node.getResourceID());
            int boost = StatefulAgent.frontierBoost(shortId, transitions, visitedTargets, weight);
            if (boost > 0) {
                action.setPriority(action.getPriority() + boost);
                action.setNavFrontierBoost(boost);
                boostedCount++;
                if (boost > maxBoost) maxBoost = boost;
            }
        }
        if (boostedCount > 0) {
            Logger.iformat("[APE-RV] Frontier boost: state=%s#%s, boosted=%d/%d, maxBoost=%d",
                    activity, state.getStateKey(), boostedCount, totalTarget, maxBoost);
        }
    }
}

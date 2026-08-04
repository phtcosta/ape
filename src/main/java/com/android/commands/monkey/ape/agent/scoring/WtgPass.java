package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.MopScorer;

/**
 * rv-scoring-pipeline: the WTG-reach boost, extracted from the (formerly interleaved) WTG/frontier
 * loop in {@code StatefulAgent.adjustActionsByGUITree()}. Boosts widgets whose WTG transition targets
 * a MOP-reachable activity. Scoring semantics owned by {@code mop-guidance} (WTG Scoring Pass).
 *
 * <p>Splitting the WTG term from the frontier term ({@link FrontierPass}) is parity-safe: both were a
 * read-modify-write on the same {@code wtgBoost} and both add to {@code priority}, so the sum is
 * order-independent, and WtgPass runs before FrontierPass in pipeline order exactly as in the inline
 * loop (WTG boost then frontier boost). Enabled only when {@code mopData != null && hasWtgData() &&
 * mopWeightWtg != 0} — {@link MopScorer#scoreWtg} already returns 0 when {@code mopWeightWtg == 0}, so
 * the extra gate is an inert short-circuit.
 */
public final class WtgPass implements ScoringPass {

    private final boolean enabled;
    private final int weightWtg;

    public WtgPass(ScoringContext ctx, ScoringParams params) {
        MopData mopData = ctx.getMopData();
        this.weightWtg = params.mopWeightWtg();
        this.enabled = mopData != null && mopData.hasWtgData() && weightWtg != 0;
    }

    @Override
    public String name() {
        return "WtgPass";
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
        int wtgBoostedCount = 0;
        int wtgTotalTarget = 0;
        int wtgMaxBoost = 0;
        for (ModelAction action : actions) {
            if (!action.requireTarget() || !action.isValid()) continue;
            if (!action.isResolvedAt(timestamp)) continue;
            wtgTotalTarget++;
            GUITreeNode node = action.getResolvedNode();
            if (node == null) continue;
            String shortId = MopData.extractShortId(node.getResourceID());
            int boost = MopScorer.scoreWtg(activity, shortId, mopData, weightWtg);
            if (boost > 0) {
                action.setPriority(action.getPriority() + boost);
                action.setWtgBoost(boost);
                wtgBoostedCount++;
                if (boost > wtgMaxBoost) wtgMaxBoost = boost;
            }
        }
        if (wtgBoostedCount > 0) {
            Logger.iformat("[APE-RV] WTG boost: state=%s#%s, boosted=%d/%d, maxBoost=%d",
                    activity, state.getStateKey(), wtgBoostedCount, wtgTotalTarget, wtgMaxBoost);
        }
    }
}

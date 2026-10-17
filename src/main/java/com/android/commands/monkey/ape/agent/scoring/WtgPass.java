package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.MopScorer;
import com.android.commands.monkey.ape.utils.StaticGuiModel;

/**
 * The WTG-reach scorer, the MOP member {@code WTG}: boosts widgets whose WTG transition targets a
 * MOP-reachable activity. Scoring semantics owned by {@code mop-guidance} (WTG Scoring Pass) and
 * {@code wtg-navigation} ({@link MopScorer#scoreWtg}). It reads both providers (plan P-1): the WTG
 * edges from {@link StaticGuiModel}, the MOP activity set from {@code MopData}.
 *
 * <p>Enabled iff {@code WTG} is mounted (a non-zero {@code mopWeightWtg}), MOP data is present and
 * the static model carries WTG data. Under rule R1 its term is written to {@code wtgBoost}, of which
 * it is the only writer, and added to the priority; it is independent of the navigation frontier
 * ({@link NavFrontierPass}), which writes its own field.
 */
public final class WtgPass implements ScoringPass {

    private final boolean enabled;
    private final int weightWtg;

    public WtgPass(ScoringContext ctx, ScoringParams params) {
        StaticGuiModel staticModel = ctx.getStaticModel();
        this.weightWtg = params.mopWeightWtg();
        this.enabled = weightWtg != 0 && ctx.getMopData() != null && staticModel != null
                && staticModel.hasWtgData();
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
        StaticGuiModel staticModel = ctx.getStaticModel();
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
            int boost = MopScorer.scoreWtg(activity, shortId, staticModel, mopData,
                    weightWtg);
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

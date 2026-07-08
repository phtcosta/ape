package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

/**
 * rv-scoring-pipeline: the per-action coverage boost, extracted verbatim from the inline block in
 * {@code StatefulAgent.adjustActionsByGUITree()}. Boosts widgets untested anywhere in the current
 * Activity (activity-scoped novelty), decaying with per-state visits. Scoring semantics owned by
 * {@code ui-coverage} (Coverage Boost — Per-Action). Enabled when {@code coverageBoostWeight != 0}.
 */
public final class CoveragePass implements ScoringPass {

    private final boolean enabled;

    public CoveragePass(ScoringContext ctx) {
        this.enabled = Config.coverageBoostWeight != 0;
    }

    @Override
    public String name() {
        return "CoveragePass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void apply(State state, ModelAction[] actions, ScoringContext ctx) {
        UICoverageTracker coverageTracker = ctx.getCoverageTracker();
        int timestamp = ctx.getTimestamp();
        int covBoostedCount = 0;
        int covTotalTarget = 0;
        float coverageGap = coverageTracker.getCoverageGap(state);
        int stateVisits = state.getVisitedCount();
        int decayedWeight = Config.coverageBoostWeight / (1 + stateVisits / 5);
        for (ModelAction action : actions) {
            if (!action.requireTarget() || !action.isValid()) continue;
            if (!action.isResolvedAt(timestamp)) continue;
            covTotalTarget++;
            String widgetId = UICoverageTracker.widgetId(action);
            boolean interacted =
                    coverageTracker.hasActivityInteraction(state.getActivity(), widgetId);
            if (!interacted && decayedWeight > 0) {
                action.setPriority(action.getPriority() + decayedWeight);
                action.setCoverageBoost(decayedWeight);
                covBoostedCount++;
            }
        }
        if (covBoostedCount > 0) {
            Logger.iformat("[APE-RV] Coverage boost: state=%s#%s, boosted=%d/%d, gap=%.2f",
                    state.getActivity(), state.getStateKey(), covBoostedCount, covTotalTarget, coverageGap);
        }
    }
}

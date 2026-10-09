package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopScorer;

/**
 * rv-scoring-pipeline: the MOP-widget boost, extracted verbatim from the inline block in
 * {@code StatefulAgent.adjustActionsByGUITree()}. Scoring semantics owned by {@code mop-guidance}
 * (MopScorer — Priority Boost); this pass only re-homes the wiring. Enabled whenever MOP data is
 * present ({@code mopContext != null}).
 *
 * <p>The mode and the widget weights come from the run's {@link MopContext}
 * ({@link ScoringContext#getMopContext()}, design D17 "Plumbing"), which also carries what the
 * {@code distance} mode reads; {@link ScoringParams} still supplies every other pass's weights.
 */
public final class MopWidgetPass implements ScoringPass {

    private final boolean enabled;

    /**
     * Where the exposure pair goes. Handed in like every other collaborator rather than reached for
     * through the ambient run context, which INV-DP-12 forbids of an injected unit — and which would
     * also make this pass untestable without a whole run behind it.
     */
    private final EventSink sink;

    /** This pass's weights are the {@link MopContext}'s, read at {@link #apply}. */
    public MopWidgetPass(ScoringContext ctx, EventSink sink) {
        this.enabled = ctx.getMopContext() != null;
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
        MopContext mop = ctx.getMopContext();
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
            if (action.getResolvedNode() == null) continue;
            // B3, INV-MOP-23: the lookup runs on the node, up to two ancestors and two levels of
            // descendants; the winning lookup's position says whether containment won the boost.
            MopScorer.Scored scored = MopScorer.weigh(mop, state, action);
            int boost = scored.weight;
            if (boost > 0) {
                action.setPriority(action.getPriority() + boost);
                action.setMopBoost(boost);
                boostedCount++;
                if (scored.index > 0) containmentBoostedCount++;
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
}

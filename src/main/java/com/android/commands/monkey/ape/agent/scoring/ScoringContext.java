package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

/**
 * rv-scoring-pipeline. The seam through which every {@link ScoringPass} reads the collaborators the
 * inline scoring blocks used to reach off {@code StatefulAgent} directly: the run-fixed {@link MopData}
 * (nullable — MOP disabled), the {@link UICoverageTracker}, the exploration {@link Graph}, the current
 * step timestamp, and the per-run BACK/MENU pick eligibility (the one pick-counter query the scoring
 * path makes — the counter itself lives on the agent, consulted here through {@link #menuPickEligible}).
 *
 * <p>Passing collaborators through the context (rather than letting each pass hold them) is what keeps
 * a pass stateless and unit-testable with a stub: a test supplies a fake context, no agent required.
 * The production implementation delegates to the owning agent so timestamp and graph are read live.
 */
public interface ScoringContext {

    /** The run's static-analysis data, or {@code null} when MOP guidance is disabled. */
    MopData getMopData();

    /** The activity-scoped UI coverage tracker. */
    UICoverageTracker getCoverageTracker();

    /** The exploration graph (for the frontier pass's visited-activity lookup). */
    Graph getGraph();

    /** The current step timestamp used by the action {@code isResolvedAt} filters. */
    int getTimestamp();

    /**
     * Whether the gh13 OPTIONSMENU gateway boost may still be applied on {@code activity} — the
     * back-menu-pick-cap gate. Delegates to the agent's per-(activity, MODEL_MENU) pick count; the
     * base agent is always eligible, {@code SataAgent} applies the cap.
     */
    boolean menuPickEligible(String activity);
}

package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.agent.MopInteractionLedger;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.StaticGuiModel;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

/**
 * rv-scoring-pipeline. The seam through which every {@link ScoringPass} reads the collaborators the
 * inline scoring blocks used to reach off {@code StatefulAgent} directly: the run's two providers —
 * the {@link StaticGuiModel} (null unless STATIC or MOP is mounted) and the {@link MopContext} with
 * its {@link MopData} (null unless MOP is mounted) — the {@link UICoverageTracker}, the exploration
 * {@link Graph} and the current step timestamp. A pass reads only its own group's provider
 * (scoring-pipeline INV-ARCH-14): a STATIC pass the static model, a MOP pass both, an ADDONS pass
 * neither.
 *
 * <p>Passing collaborators through the context (rather than letting each pass hold them) is what keeps
 * a pass stateless and unit-testable with a stub: a test supplies a fake context, no agent required.
 * The production implementation delegates to the owning agent so timestamp and graph are read live.
 */
public interface ScoringContext {

    /**
     * The run's static GUI model — WTG, widget metadata, components — or {@code null} when no group
     * that reads the artifact is mounted.
     */
    StaticGuiModel getStaticModel();

    /**
     * The run's MOP context — the targets, the scoring mode, the weights and the retirement
     * (design D17, Plumbing) — or {@code null} when MOP is not mounted.
     */
    MopContext getMopContext();

    /**
     * The run's MOP targets, or {@code null} when MOP is not mounted: the data of
     * {@link #getMopContext()}, derived rather than held a second time.
     */
    default MopData getMopData() {
        MopContext mop = getMopContext();
        return mop == null ? null : mop.data();
    }

    /**
     * The run's MOP interaction ledger (mop-guidance "MopInteractionLedger"), or {@code null} when
     * MOP is not mounted. A pass reads it and never records into it.
     */
    default MopInteractionLedger getMopLedger() {
        return null;
    }

    /** The activity-scoped UI coverage tracker. */
    UICoverageTracker getCoverageTracker();

    /** The exploration graph (for the frontier passes' visited-activity lookup). */
    Graph getGraph();

    /** The current step timestamp used by the action {@code isResolvedAt} filters. */
    int getTimestamp();
}

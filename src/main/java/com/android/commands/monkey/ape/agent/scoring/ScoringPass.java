package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;

/**
 * rv-scoring-pipeline (INV-ARCH-02). A single priority-boost pass over the current state's actions.
 *
 * <p>The RV scoring path used to be a wall of inline {@code if}-blocks in
 * {@code StatefulAgent.adjustActionsByGUITree()}. Each block is now one {@code ScoringPass}, assembled
 * in fixed order by {@link ScoringPipeline#fromParams}. A pass reads every collaborator (MopData,
 * coverage tracker, graph, per-run pick eligibility) through the {@link ScoringContext} handed to
 * {@link #apply}; it holds no run-mutable field of its own, so it stays unit-testable with a stub
 * context and all cross-step state lives on the agent reached through the context.
 *
 * <p>{@link #isEnabled()} is decided once, in the constructor, from the injected
 * {@link ScoringParams} (and the run-fixed {@code MopData}); it is never re-evaluated per
 * {@code apply}. A disabled pass is not added to the
 * assembled pipeline and is therefore a strict no-op for the run — zero priority mutations, zero
 * provenance writes, zero log lines.
 */
public interface ScoringPass {

    /** Stable identifier for the {@code [APE-ARCH] passes=[...]} assembly log and for tests. */
    String name();

    /**
     * Decided in the constructor from the injected {@link ScoringParams}; not re-evaluated per
     * {@link #apply} call.
     */
    boolean isEnabled();

    /**
     * Adds this pass's priority boost (and its provenance field) to the actions of {@code state}.
     * {@code actions} is {@code state.getActions()}, passed explicitly so a pass never has to reach
     * back into the state for it. Runs only when the pass is in the assembled pipeline (i.e. enabled).
     */
    void apply(State state, ModelAction[] actions, ScoringContext ctx);
}

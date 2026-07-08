package com.android.commands.monkey.ape.agent.scoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.Logger;

/**
 * rv-scoring-pipeline (INV-ARCH-03/04/05). The single assembly point and runner for the RV scoring
 * passes. {@link #fromConfig} constructs the six passes in fixed order, keeps only the enabled ones,
 * and logs the assembly once as {@code [APE-ARCH] passes=[...]}; nothing else builds a pipeline.
 * {@link #apply} is the one RV addition to {@code StatefulAgent.adjustActionsByGUITree()} after the
 * (byte-identical upstream) base-priority loop.
 */
public final class ScoringPipeline {

    private final List<ScoringPass> passes;

    /**
     * Keeps the enabled passes, in the given order, and logs the assembly once. Package-private: the
     * only public entry is {@link #fromConfig} (INV-ARCH-04). Tests in this package construct a
     * pipeline directly from stub passes.
     */
    ScoringPipeline(List<ScoringPass> candidatePasses) {
        List<ScoringPass> enabled = new ArrayList<>();
        for (ScoringPass p : candidatePasses) {
            if (p.isEnabled()) {
                enabled.add(p);
            }
        }
        this.passes = enabled;
        Logger.iformat("[APE-ARCH] passes=[%s]", String.join(", ", passNames()));
    }

    /**
     * Builds the pipeline from configuration. Constructs the passes in the fixed order
     * {@code MopWidget → MenuGateway → WTG → Frontier → MopFrontier → Coverage → FormCompletion}
     * (INV-ARCH-03; the frontier family stays contiguous) and retains only those whose
     * {@code isEnabled()} is true. The six original passes are the exact order of the pre-refactor
     * inline blocks; {@code MopFrontierPass} (Lever B) is additive, disabled unless
     * {@code mopFrontierWeight > 0}. Each pass decides {@code isEnabled()} from {@link Config}'s gates
     * and the run-fixed {@link ScoringContext#getMopData()} at construction.
     *
     * @param cfg present for signature fidelity with the spec; the pass gates read {@code Config}'s
     *            {@code public static final} fields directly.
     */
    public static ScoringPipeline fromConfig(Config cfg, ScoringContext ctx) {
        List<ScoringPass> candidates = new ArrayList<>();
        candidates.add(new MopWidgetPass(ctx));
        candidates.add(new MenuGatewayPass(ctx));
        candidates.add(new WtgPass(ctx));
        candidates.add(new FrontierPass(ctx));
        candidates.add(new MopFrontierPass(ctx));
        candidates.add(new CoveragePass(ctx));
        candidates.add(new FormCompletionPass(ctx));
        return new ScoringPipeline(candidates);
    }

    /**
     * Runs the enabled passes over {@code actions} ({@code == state.getActions()}). An empty pipeline
     * is a strict no-op (INV-ARCH-02): it touches nothing, so the pure arm's priorities equal the
     * upstream base loop's output. When non-empty, provenance boosts are cleared first — this is the
     * {@code resetBoosts()} that used to sit at the top of the inline block, moved here so the base
     * loop stays byte-identical to upstream (the base loop never reads a boost field, so the move is
     * behavior-preserving).
     */
    public void apply(State state, ModelAction[] actions, ScoringContext ctx) {
        if (passes.isEmpty()) {
            return;
        }
        for (ModelAction action : actions) {
            action.resetBoosts();
        }
        for (ScoringPass p : passes) {
            p.apply(state, actions, ctx);
        }
    }

    /** The enabled passes' names, in pipeline order — the content of the {@code [APE-ARCH]} line. */
    public List<String> passNames() {
        List<String> names = new ArrayList<>(passes.size());
        for (ScoringPass p : passes) {
            names.add(p.name());
        }
        return Collections.unmodifiableList(names);
    }

    /** Number of enabled passes in the pipeline. */
    public int size() {
        return passes.size();
    }
}

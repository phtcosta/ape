package com.android.commands.monkey.ape.agent.scoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;

/**
 * rv-scoring-pipeline (INV-ARCH-03/04/05). The single assembly point and runner for the RV scoring
 * passes. {@link #fromParams} constructs the seven passes in the fixed order
 *
 * <pre>MopWidget → MenuGateway → WTG → Frontier → MopFrontier → Coverage → FormCompletion</pre>
 *
 * and keeps only the enabled ones; nothing else builds a pipeline. The assembly is recorded once by
 * the caller, which is where the decision stages' roster is also in scope and the {@code PIPELINE}
 * record wants both. Seven are constructed, and a default plan keeps six of them: the
 * MOP-frontier weight is zero unless a plan states otherwise, which is a gate being shut and not a
 * pass being missing — {@link #candidates} is what says so in the trace.
 *
 * <p>{@link #apply} is the one RV addition to {@code StatefulAgent.adjustActionsByGUITree()} after
 * the (byte-identical upstream) base-priority loop.
 */
public final class ScoringPipeline {

    private final List<ScoringPass> passes;
    private final Map<String, Boolean> candidates;

    /**
     * Keeps the enabled passes, in the given order, and records the census of every candidate.
     * Package-private: the only public entry is {@link #fromParams} (INV-ARCH-04). Tests in this
     * package construct a pipeline directly from stub passes.
     *
     * <p>The census is taken here because here is the last moment the disabled passes exist: the
     * pipeline drops them and keeps no handle on them, and holding them in a field just to be able
     * to enumerate them later would keep objects alive for telemetry's sake. Recording their names
     * and their verdict costs nothing and outlives them.
     */
    ScoringPipeline(List<ScoringPass> candidatePasses) {
        List<ScoringPass> enabled = new ArrayList<>();
        Map<String, Boolean> census = new LinkedHashMap<>();
        for (ScoringPass p : candidatePasses) {
            census.put(p.name(), p.isEnabled());
            if (p.isEnabled()) {
                enabled.add(p);
            }
        }
        this.passes = enabled;
        this.candidates = Collections.unmodifiableMap(census);
    }

    /**
     * Builds the pipeline from the run's scoring parameters. Constructs the seven passes in the
     * fixed order {@code MopWidget → MenuGateway → WTG → Frontier → MopFrontier → Coverage →
     * FormCompletion} (INV-ARCH-03; the frontier family stays contiguous) and retains only those
     * whose {@code isEnabled()} is true. The first six are the exact order of the pre-refactor
     * inline blocks; {@code MopFrontierPass} (Lever B) is additive, disabled unless its weight is
     * positive — which it is not by default, so a default plan assembles six passes, not seven.
     *
     * <p>Each pass decides {@code isEnabled()} once, here, from the weights it is handed and the
     * run-fixed {@link ScoringContext#getMopData()}. This is the only place a scoring parameter is
     * read: below it, a pass is a function of the numbers it was constructed with, which is what
     * lets a test state a weight instead of arranging for a global to hold it (INV-ARCH-11).
     */
    public static ScoringPipeline fromParams(ScoringParams params, ScoringContext ctx) {
        List<ScoringPass> candidates = new ArrayList<>();
        candidates.add(new MopWidgetPass(ctx, params));
        candidates.add(new MenuGatewayPass(ctx, params));
        candidates.add(new WtgPass(ctx, params));
        candidates.add(new FrontierPass(ctx, params));
        candidates.add(new MopFrontierPass(ctx, params));
        candidates.add(new CoveragePass(ctx, params));
        candidates.add(new FormCompletionPass(ctx, params));
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

    /**
     * Every candidate pass in declaration order, mapped to whether it was constructed — the
     * {@code PIPELINE.candidates} member of the run's trace.
     *
     * <p>It is a <b>sibling</b> of {@link #passNames()}, never a widening of it (INV-ARCH-04): a
     * consumer reading {@code passes} still sees exactly the constructed passes. What it adds is
     * that the pass list becomes readable as a data-dependent outcome rather than a configuration
     * echo. Across the decisive campaign's
     * 360 runs that line took three values, split the same way in every arm, because the whole
     * frontier family is never constructed in 25 of the 40 applications — and the only evidence of
     * that in the trace was three names missing from a list every analyst read as configuration.
     *
     * <p>No {@code reason} accompanies an entry, and there is no {@code disabledReason()} on
     * {@link ScoringPass}. Each gate is a conjunction of {@code mopData != null},
     * {@code hasWtgData()} and a weight, and all three conjuncts are already recorded elsewhere in
     * the same trace ({@code MOP_DATA.status}, {@code MOP_DATA.wtgEdges}, {@code RUN_START.params}),
     * so the reason is a lookup rather than a field. It would also be unreliable: the passes do not
     * evaluate their conjuncts in one order, so a "first failing conjunct" would report source
     * order rather than cause, and two passes absent for the same reason could disagree about it.
     */
    public Map<String, Boolean> candidates() {
        return candidates;
    }

    /** The enabled passes' names, in pipeline order — the {@code PIPELINE.passes} member. */
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

package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.runtime.RunSpec;

/**
 * Every weight and gate the scoring path reads, as one value derived from the resolved plan
 * (INV-ARCH-11). It is what makes the passes' parameters arrive by constructor instead of being
 * fetched from a static field or an ambient run context at the moment they are needed.
 *
 * <p>The eight values span two scopes of the plan — six belong to the MOP family, two
 * ({@code coverageBoostWeight}, {@code formCompletionEnabled}) do not — and a pass has no business
 * knowing which. Collapsing them here is what lets {@link ScoringPipeline#fromParams} be the one
 * place that reads a plan, and every pass below it be a function of the numbers it was handed.
 *
 * <p><b>An absent feature reads as its off value</b>, never as an error: a plan without MOP has no
 * {@code MopParams} at all, and a plan with MOP but without the WTG feature carries no
 * {@code ape.mopWeightWtg}. Both mean the same thing to a pass — the weight is zero, so the gate is
 * shut — and {@link #fromSpec} is where that equivalence is stated once instead of at each of the
 * seven construction sites.
 *
 * <p>Nothing else observes the plan-to-params mapping: the parity goldens never execute scoring
 * (it runs in {@code adjustActionsByGUITree()}, above their entry point), the pass unit tests
 * supply their own values, and the {@code RUN_START} echo is written and never read back. That is
 * why {@code ScoringParamsDefaultsTest} asserts this mapping against the jar defaults as literals:
 * it is the only thing that fails when a scoring default changes silently (INV-ARCH-12).
 */
public final class ScoringParams {

    private final int mopWeightDirect;
    private final int mopWeightTransitive;
    private final int mopWeightOpenMenu;
    private final int mopWeightWtg;
    private final int frontierBoostWeight;
    private final int mopFrontierWeight;
    private final int coverageBoostWeight;
    private final boolean formCompletionEnabled;

    /**
     * The eight values in the order the passes consume them, which is pipeline order
     * (INV-ARCH-03): MOP-widget, menu gateway, WTG, frontier, MOP-frontier, coverage, form.
     *
     * <p>Public because a test states the values it wants directly rather than mutating anything
     * global (INV-ARCH-11); production derives them from the plan through {@link #fromSpec}.
     */
    public ScoringParams(int mopWeightDirect, int mopWeightTransitive, int mopWeightOpenMenu,
            int mopWeightWtg, int frontierBoostWeight, int mopFrontierWeight,
            int coverageBoostWeight, boolean formCompletionEnabled) {
        this.mopWeightDirect = mopWeightDirect;
        this.mopWeightTransitive = mopWeightTransitive;
        this.mopWeightOpenMenu = mopWeightOpenMenu;
        this.mopWeightWtg = mopWeightWtg;
        this.frontierBoostWeight = frontierBoostWeight;
        this.mopFrontierWeight = mopFrontierWeight;
        this.coverageBoostWeight = coverageBoostWeight;
        this.formCompletionEnabled = formCompletionEnabled;
    }

    /**
     * The scoring parameters of a resolved plan. A plan with no MOP feature has no
     * {@code MopParams}, which is the same thing to every MOP-family pass as a zero weight, so the
     * six MOP values collapse to zero together.
     */
    public static ScoringParams fromSpec(RunSpec spec) {
        RunSpec.MopParams mop = spec.mop();
        RunSpec.ExplorationParams exploration = spec.exploration();
        return new ScoringParams(
                mop == null ? 0 : mop.weightDirect(),
                mop == null ? 0 : mop.weightTransitive(),
                mop == null ? 0 : mop.weightOpenMenu(),
                mop == null ? 0 : mop.weightWtg(),
                mop == null ? 0 : mop.frontierBoostWeight(),
                mop == null ? 0 : mop.frontierWeight(),
                exploration.coverageBoostWeight(),
                exploration.formCompletionEnabled());
    }

    /** Boost for a widget flagged as directly reaching a monitored operation. */
    public int mopWeightDirect() {
        return mopWeightDirect;
    }

    /** Boost for a widget that reaches a monitored operation transitively. */
    public int mopWeightTransitive() {
        return mopWeightTransitive;
    }

    /** Boost for the {@code MODEL_MENU} action of an activity whose OPTIONSMENU is a gateway. */
    public int mopWeightOpenMenu() {
        return mopWeightOpenMenu;
    }

    /** Boost for a widget whose WTG transition targets a MOP-reachable activity. */
    public int mopWeightWtg() {
        return mopWeightWtg;
    }

    /** Boost for a widget whose WTG transition targets an unvisited activity, MOP or not. */
    public int frontierBoostWeight() {
        return frontierBoostWeight;
    }

    /** Boost for a WTG target that is unvisited AND MOP-bearing; zero by jar default. */
    public int mopFrontierWeight() {
        return mopFrontierWeight;
    }

    /** Boost for a widget not yet interacted with anywhere in the current activity. */
    public int coverageBoostWeight() {
        return coverageBoostWeight;
    }

    /** Whether unfilled fields and a submit candidate are boosted so forms get completed. */
    public boolean formCompletionEnabled() {
        return formCompletionEnabled;
    }
}

package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.RunSpec;

/**
 * Every weight and gate the scoring path reads, as one value derived from the resolved plan
 * (INV-ARCH-11). It is what makes the passes' parameters arrive by constructor instead of being
 * fetched from a static field or an ambient run context at the moment they are needed.
 *
 * <p>The six values span the three scorer groups — four belong to MOP members, one
 * ({@code frontierBoostWeight}) to the STATIC member {@code NAV_FRONTIER}, two
 * ({@code coverageBoostWeight}, {@code formCompletionEnabled}) to ADDONS members — and a pass has
 * no business knowing which. The MOP-widget weights are not here: the MOP-widget pass reads them,
 * with the scoring mode and the distance weights, from the run's {@code MopContext}, built from the same
 * plan (llm-coordinate-single-base, design D17). Collapsing them here is what lets {@link ScoringPipeline#fromParams} be the one
 * place that reads a plan, and every pass below it be a function of the numbers it was handed.
 *
 * <p><b>Each value is its pass's mounting feature</b> (scoring-pipeline "Scorer Ownership by
 * Group"): {@link #fromSpec} reads a weight only when the member feature that owns it is in the
 * plan, and reads the off value — 0, or false — otherwise. A pass is therefore enabled iff its
 * feature is mounted (and its data gate holds), and {@link #fromSpec} is where that is stated once
 * instead of at each of the seven construction sites. {@code MopWidgetPass} has no value here: the
 * group root {@code MOP} mounts it, which the scoring context's {@code MopContext} states.
 *
 * <p>Nothing else observes the plan-to-params mapping: the parity goldens never execute scoring
 * (it runs in {@code adjustActionsByGUITree()}, above their entry point), the pass unit tests
 * supply their own values, and the {@code RUN_START} echo is written and never read back. That is
 * why {@code ScoringParamsDefaultsTest} asserts this mapping against the jar defaults as literals:
 * it is the only thing that fails when a scoring default changes silently (INV-ARCH-12).
 */
public final class ScoringParams {

    private final int mopWeightOpenMenu;
    private final int mopWeightWtg;
    private final int frontierBoostWeight;
    private final int mopFrontierWeight;
    private final int coverageBoostWeight;
    private final boolean formCompletionEnabled;

    /**
     * The six values in the order the passes consume them, which is pipeline order
     * (INV-ARCH-03): menu gateway, WTG, frontier, MOP-frontier, coverage, form.
     *
     * <p>Public because a test states the values it wants directly rather than mutating anything
     * global (INV-ARCH-11); production derives them from the plan through {@link #fromSpec}.
     */
    public ScoringParams(int mopWeightOpenMenu, int mopWeightWtg, int frontierBoostWeight, int mopFrontierWeight,
            int coverageBoostWeight, boolean formCompletionEnabled) {
        this.mopWeightOpenMenu = mopWeightOpenMenu;
        this.mopWeightWtg = mopWeightWtg;
        this.frontierBoostWeight = frontierBoostWeight;
        this.mopFrontierWeight = mopFrontierWeight;
        this.coverageBoostWeight = coverageBoostWeight;
        this.formCompletionEnabled = formCompletionEnabled;
    }

    /**
     * The scoring parameters of a resolved plan: each value read through its mounting feature
     * ({@code MENU_GATEWAY}, {@code WTG}, {@code NAV_FRONTIER}, {@code MOP_FRONTIER},
     * {@code COVERAGE_BOOST}, {@code FORM_COMPLETION}), the off value when the feature is absent.
     */
    public static ScoringParams fromSpec(RunSpec spec) {
        RunSpec.MopParams mop = spec.mop();
        RunSpec.StaticParams staticData = spec.staticData();
        RunSpec.ExplorationParams exploration = spec.exploration();
        return new ScoringParams(
                spec.has(Feature.MENU_GATEWAY) ? mop.weightOpenMenu() : 0,
                spec.has(Feature.WTG) ? mop.weightWtg() : 0,
                spec.has(Feature.NAV_FRONTIER) ? staticData.frontierBoostWeight() : 0,
                spec.has(Feature.MOP_FRONTIER) ? mop.frontierWeight() : 0,
                spec.has(Feature.COVERAGE_BOOST) ? exploration.coverageBoostWeight() : 0,
                spec.has(Feature.FORM_COMPLETION) && exploration.formCompletionEnabled());
    }

    /** Boost for the {@code MODEL_MENU} action of an activity whose OPTIONSMENU is a gateway. */
    public int mopWeightOpenMenu() {
        return mopWeightOpenMenu;
    }

    /** Boost for a widget whose WTG transition targets a MOP-reachable activity. */
    public int mopWeightWtg() {
        return mopWeightWtg;
    }

    /** Boost for a widget whose WTG transition targets an unvisited activity ({@code NAV_FRONTIER}). */
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

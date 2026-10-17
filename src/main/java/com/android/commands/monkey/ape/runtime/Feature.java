/*
 * Copyright 2020 Advanced Software Technologies Lab at ETH Zurich, Switzerland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.commands.monkey.ape.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The optional mechanisms of a run, with their metadata as data the compiler binds.
 *
 * <p>Each constant declares four things: how it is activated, which keys it owns, which other
 * features it requires, and what the neutral value of each owned key is. Ownership is therefore a
 * relation the compiler binds to the enum, not a list of property-name literals maintained beside
 * the fields they name.
 *
 * <p><b>Activation.</b> A feature is active when its activation key says so — present for a path
 * or a URL, {@code true} for a gate, non-zero or positive for a weight. Activation is evaluated
 * over <em>effective</em> values (explicit input over jar defaults), with one rule that keeps a
 * bare run valid: a default may not activate a feature whose dependencies are unmet. That is why
 * {@code ape.activityTriggerEnabled} defaulting to {@code true} does not conjure a launcher on a
 * run without the MOP group — the feature is simply absent from the plan.
 *
 * <p><b>Groups.</b> Behaviour is organised in groups (guidance-groups): BASE, which has no feature
 * of its own, and ADDONS, STATIC, MOP and LLM, each a feature switched by one boolean key. Every
 * member declares its group as a dependency, so the rule above decides membership: a member whose
 * group is off is absent without an abort, and stating its activation key explicitly aborts.
 * STATIC and MOP also require {@link #STATIC_DATA}, the artifact path. A use site asks for its
 * member, never for the group; only composition roots read a group to choose an implementation.
 *
 * <p><b>Neutral values.</b> A key of an inactive feature is accepted only at its neutral value,
 * and the definition is uniform: for an activation key the neutral is the off value ({@code false}
 * for a gate, {@code 0} for a weight), for a sentinel key it is the sentinel, and for every other
 * sub-parameter it is the jar default — the value at which stating the key is indistinguishable
 * from omitting it. You may state OFF for a mechanism that does not exist; you may not state ON.
 * This is what lets every current arm keep pushing {@code ape.llmPercentageNoSubstrate=-1} on a
 * run with no LLM.
 *
 * <p><b>Purity is structural.</b> There is no kill-switch. A feature absent from the plan has no
 * constructed mechanism, so a sub-parameter of it parameterizes nothing and needs no exemption
 * list to say so.
 *
 * <p><b>Telemetry is deliberately not here.</b> It was, as {@code STEP_TELEMETRY}, and
 * {@code rearch-04-step-ndjson-telemetry} removed it: this enum is the set of mechanisms an arm may
 * turn on or off, and the event sink is an instrument every arm carries alike (event-sink
 * INV-SNK-07's Telemetry Neutrality clause — "no arm-level flag disables or alters it"). Membership
 * here is exactly what would have made such a flag expressible, which is how
 * {@code ape.stepTelemetryEnabled} became the baseline arm's blindness switch under the old
 * INV-ARCH-01. Its two surviving sub-parameters — the prompt dump and the logcat heartbeat — are
 * base keys now: they are volume and join levers over an instrument that is always present, not
 * activations of a mechanism that may be absent.
 */
public enum Feature {

    // --- Roots: no dependencies. ------------------------------------------------------------

    /**
     * The static-analysis artifact. Active exactly when the harness pushed one; it is the data
     * {@link #STATIC} and {@link #MOP} read, and it mounts no mechanism of its own.
     */
    STATIC_DATA("ape.staticDataPath", Rule.PRESENT, null),
    /** The fork's own improvements, which need no external data (guidance-groups). */
    ADDONS("ape.addons", Rule.TRUE, "false"),
    /** LLM routing infrastructure. Needs a server URL, which it owns without a neutral value. */
    LLM("ape.llm", Rule.TRUE, "false"),
    /** Random input fuzzing. BASE: a root, not a group member. */
    FUZZING("ape.doFuzzing", Rule.TRUE, "false"),

    // --- Groups over the static-analysis artifact: each requires STATIC_DATA. ----------------

    /** The consumers of the static GUI model: WTG frontier, typed input, component triggering. */
    STATIC("ape.static", Rule.TRUE, "false"),
    /** The consumers of the specification targets. */
    MOP("ape.mop", Rule.TRUE, "false"),

    // --- ADDONS members: each requires ADDONS. -----------------------------------------------

    /** The fork's OPTIONSMENU action in {@code State.getActions()}. */
    MODEL_MENU("ape.modelMenuEnabled", Rule.TRUE, "false"),
    /** The form-completion scoring pass and the deterministic-fill branch. */
    FORM_COMPLETION("ape.formCompletionEnabled", Rule.TRUE, "false"),
    /** The three GUITreeBuilder perception enhancements. */
    TREE_ENHANCEMENTS("ape.treeEnhancementsEnabled", Rule.TRUE, "false"),
    /** The per-activity budget tracker and its check in action selection. */
    ACTIVITY_BUDGET("ape.activityBudgetEnabled", Rule.TRUE, "false"),
    /** Epsilon adapted at runtime between its bounds, instead of the fixed default. */
    DYNAMIC_EPSILON("ape.dynamicEpsilon", Rule.TRUE, "false"),
    /** Heuristic (label-aware) input generation. */
    HEURISTIC_INPUT("ape.heuristicInput", Rule.TRUE, "false"),
    /** Deflect screens owned by a foreign package with one raw BACK. */
    FOREIGN_ACTIVITY_GUARD("ape.foreignActivityGuard", Rule.TRUE, "false"),
    /** Refetch instead of modeling when the tree's package disagrees with the top activity. */
    TREE_PACKAGE_GUARD("ape.treePackageGuard", Rule.TRUE, "false"),
    /** The UI-coverage scoring pass. */
    COVERAGE_BOOST("ape.coverageBoostWeight", Rule.POSITIVE, "0"),
    /** The per-(activity, type) cap on discretionary BACK/MENU re-picks. */
    BACK_MENU_CAP("ape.backMenuPickCap", Rule.POSITIVE, "0"),
    /** The restart after an activity stays stable for a threshold of steps. */
    ACTIVITY_STABLE_RESTART("ape.activityStableRestartThreshold", Rule.POSITIVE, "0"),

    // --- STATIC members: each requires STATIC. -----------------------------------------------

    /** Frontier boost on actions whose WTG transition targets an unvisited activity. */
    NAV_FRONTIER("ape.frontierBoostWeight", Rule.POSITIVE, "0"),
    /** Type-aware input fuzzing from the analysed {@code inputType}/{@code hint}. */
    TYPED_FUZZ("ape.fuzzInputTyped", Rule.TRUE, "false"),
    /** The probabilistic component-trigger pool over the manifest's components. */
    COMPONENT_TRIGGER("ape.componentPercentage", Rule.POSITIVE, "0"),

    // --- MOP members: each requires MOP. -----------------------------------------------------

    /** WTG-reach boost on actions whose transition targets a MOP-reachable activity. */
    WTG("ape.mopWeightWtg", Rule.NONZERO, "0"),
    /** OPTIONSMENU-gateway boost. Needs both the MOP targets and the menu action to boost. */
    MENU_GATEWAY("ape.mopWeightOpenMenu", Rule.POSITIVE, "0"),
    /** MOP-conditioned frontier boost. */
    MOP_FRONTIER("ape.mopFrontierWeight", Rule.POSITIVE, "0"),
    /** The cadence-bounded MOP activity launcher. */
    MOP_LAUNCHER("ape.activityTriggerEnabled", Rule.TRUE, "false"),
    /** Widen the MOP activity set with the component and reachability sources. */
    MOP_ACTIVITY_SOURCE("ape.mopActivitySourceComponents", Rule.TRUE, "false"),
    /** The MOP coin: a first interaction with a targeted action, at probability {@code p_M}. */
    MOP_COIN("ape.mopCoinPercentage", Rule.POSITIVE, "0"),

    // --- LLM members: each requires LLM. -----------------------------------------------------

    /** Route to the LLM on entering a new state. */
    LLM_NEW_STATE("ape.llmOnNewState", Rule.TRUE, "false"),
    /** Route to the LLM on stagnation. */
    LLM_STAGNATION("ape.llmOnStagnation", Rule.TRUE, "false"),
    /** Route to the LLM on a coin, at probability {@code p_L}. */
    LLM_COIN("ape.llmPercentage", Rule.POSITIVE, "0");

    /** How an activation key's value turns its feature on. */
    public enum Rule {
        /** The key is present with a non-empty value (a path, a URL). */
        PRESENT,
        /** The key parses as {@code true}. */
        TRUE,
        /** The key's number is strictly greater than zero. */
        POSITIVE,
        /** The key's number is anything other than zero. */
        NONZERO
    }

    private final String activationKey;
    private final Rule rule;
    private final Map<String, String> neutralValues = new LinkedHashMap<>();
    // A LinkedHashSet rather than an EnumSet: EnumSet.noneOf reads the enum's constants, which do
    // not exist yet while the constants themselves are being constructed.
    private final Set<Feature> dependencies = new LinkedHashSet<>();

    Feature(String activationKey, Rule rule, String activationNeutral) {
        this.activationKey = activationKey;
        this.rule = rule;
        this.neutralValues.put(activationKey, activationNeutral);
    }

    // Sub-parameters and dependencies are wired after every constant exists: an enum constructor
    // may not reference other constants of its own type, and a sub-parameter table is easier to
    // audit as a block than as a constructor argument list.
    static {
        // The package check is on the static model, so it belongs to the data root (D-B14.3).
        STATIC_DATA.own("ape.mopStrictPackageMatch", "false");

        MOP.own("ape.mopWeightDirect", "0");
        MOP.own("ape.mopWeightTransitive", "0");
        // The scoring mode and the distance mode's keys (llm-coordinate-single-base, D18–D20):
        // zero for a weight, as for ape.mopWeightDirect; the default for the mode, the retirement
        // count and the launcher bound. With MOP in the plan, the keys the selected mode does not
        // read are inert for a second reason (INV-RUN-09), which RunSpec applies; these neutrals
        // concern only a plan without MOP (INV-RUN-05).
        MOP.own(MopScoring.KEY, "flag");
        MOP.own("ape.mopWeightD1", "0");
        MOP.own("ape.mopWeightD2", "0");
        MOP.own("ape.mopWeightD3", "0");
        MOP.own("ape.mopRetireAfter", "3");
        MOP.own("ape.mopLauncherDmax", "6");

        MOP_LAUNCHER.own("ape.activityTriggerStagnationStep", "50");
        MOP_LAUNCHER.own("ape.activityTriggerMaxPerRun", "0");

        ACTIVITY_BUDGET.own("ape.activityBaseBudget", "50");
        ACTIVITY_BUDGET.own("ape.activityBudgetPerWidget", "5");

        DYNAMIC_EPSILON.own("ape.maxEpsilon", "0.15");
        DYNAMIC_EPSILON.own("ape.minEpsilon", "0.02");

        COVERAGE_BOOST.own("ape.coverageMaxStates", "2000");

        FUZZING.own("ape.fuzzingRate", "0.02");
        FUZZING.own("ape.fuzzingActivityVisitThreshold", "10");

        // The server URL has no neutral value: stating it without the group is a plan error, and
        // the group without it is one too (RunSpec, design D-B1 and D-B14.4).
        LLM.own("ape.llmUrl", null);
        LLM.own("ape.llmModel", "default");
        LLM.own("ape.llmTemperature", "0.3");
        LLM.own("ape.llmTopP", "0.6");
        LLM.own("ape.llmTopK", "50");
        LLM.own("ape.llmTimeoutMs", "15000");
        LLM.own("ape.llmPromptVariant", "ape_current");
        LLM.own("ape.llmMaxTokens", "1024");
        LLM.own("ape.llmSnapTolerancePx", "50");
        LLM.own("ape.llmBoundaryTopPct", "0.035");
        // The gate and breaker parameters (D-B8): the neutral value is the default, at which
        // stating the key is indistinguishable from omitting it.
        LLM.own("ape.llmGateMinActions", "3");
        LLM.own("ape.llmBreakerFailures", "3");
        LLM.own("ape.llmBreakerOpenMs", "60000");

        // The -1 sentinel means "no override"; it is the value every baseline arm pushes on a run
        // with no LLM, and the reason the inert rule exists at all.
        LLM_COIN.own("ape.llmPercentageNoSubstrate", "-1");

        STATIC.dependsOn(STATIC_DATA);
        MOP.dependsOn(STATIC_DATA);
        for (Feature f : new Feature[] {MODEL_MENU, FORM_COMPLETION, TREE_ENHANCEMENTS,
                ACTIVITY_BUDGET, DYNAMIC_EPSILON, HEURISTIC_INPUT, FOREIGN_ACTIVITY_GUARD,
                TREE_PACKAGE_GUARD, COVERAGE_BOOST, BACK_MENU_CAP, ACTIVITY_STABLE_RESTART}) {
            f.dependsOn(ADDONS);
        }
        for (Feature f : new Feature[] {NAV_FRONTIER, TYPED_FUZZ, COMPONENT_TRIGGER}) {
            f.dependsOn(STATIC);
        }
        for (Feature f : new Feature[] {WTG, MENU_GATEWAY, MOP_FRONTIER, MOP_LAUNCHER,
                MOP_ACTIVITY_SOURCE, MOP_COIN}) {
            f.dependsOn(MOP);
        }
        // The gateway boost steers the menu action, so it needs the action to exist as well as
        // the targets that decide the menu is worth opening.
        MENU_GATEWAY.dependsOn(MODEL_MENU);
        for (Feature f : new Feature[] {LLM_NEW_STATE, LLM_STAGNATION, LLM_COIN}) {
            f.dependsOn(LLM);
        }
    }

    private void own(String key, String neutralValue) {
        neutralValues.put(key, neutralValue);
    }

    private void dependsOn(Feature... required) {
        Collections.addAll(dependencies, required);
    }

    /** The key whose value decides whether this feature is in the plan. */
    public String activationKey() {
        return activationKey;
    }

    public Rule activationRule() {
        return rule;
    }

    /** Every key this feature owns, activation key first. */
    public Set<String> ownedKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(neutralValues.keySet()));
    }

    /**
     * The owned keys that have no neutral value and are not the activation key: keys the feature
     * cannot run without once it is in the plan ({@code ape.llmUrl} for {@link #LLM}).
     */
    public Set<String> requiredKeys() {
        Set<String> required = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : neutralValues.entrySet()) {
            if (entry.getValue() == null && !entry.getKey().equals(activationKey)) {
                required.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(required);
    }

    /** The features that must also be active for this one to be expressible. */
    public Set<Feature> dependencies() {
        return Collections.unmodifiableSet(dependencies);
    }

    /**
     * The value at which stating {@code key} for an inactive feature is accepted as inert, or null
     * when the key has no neutral value: the artifact path, whose presence activates
     * {@link #STATIC_DATA}, and the LLM server URL, which {@link #LLM} requires once mounted.
     */
    public String neutralValue(String key) {
        return neutralValues.get(key);
    }

    /** Whether this feature's activation key says it is on, given the effective values. */
    public boolean isActive(Map<String, String> effective) {
        String raw = effective.get(activationKey);
        switch (rule) {
            case PRESENT:
                return raw != null && !raw.trim().isEmpty();
            case TRUE:
                return raw != null && Boolean.parseBoolean(raw.trim());
            case POSITIVE:
                return raw != null && toNumber(raw) > 0.0d;
            case NONZERO:
                return raw != null && toNumber(raw) != 0.0d;
            default:
                return false;
        }
    }

    private static double toNumber(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            // Type validation runs before feature derivation, so an unparsable number never
            // reaches here in resolution. Treating it as off keeps the predicate total for the
            // direct callers a test may have.
            return 0.0d;
        }
    }
}

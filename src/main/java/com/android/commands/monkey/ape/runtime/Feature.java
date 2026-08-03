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
 * <p>This enum replaces three string registries — {@code rvForcedOffValues}, {@code rvUnsetKeys}
 * and {@code rvExemptReasons} — that listed property names as literals the compiler could not
 * relate to the fields they named. Each constant here declares four things instead: how it is
 * activated, which keys it owns, which other features it requires, and what the neutral value of
 * each owned key is.
 *
 * <p><b>Activation.</b> A feature is active when its activation key says so — present for a path
 * or a URL, {@code true} for a gate, non-zero or positive for a weight. Activation is evaluated
 * over <em>effective</em> values (explicit input over jar defaults), with one rule that keeps a
 * bare run valid: a default may not activate a feature whose dependencies are unmet. That is why
 * {@code ape.activityTriggerEnabled} defaulting to {@code true} does not conjure a launcher on a
 * run with no MOP data — the feature is simply absent from the plan.
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
 */
public enum Feature {

    // --- Roots: no dependencies. ------------------------------------------------------------

    /** MOP guidance. Active exactly when the harness pushed a static-analysis artifact. */
    MOP("ape.mopDataPath", Rule.PRESENT, null),
    /** LLM routing infrastructure. Active exactly when a server URL was configured. */
    LLM("ape.llmUrl", Rule.PRESENT, null),

    /** The fork's OPTIONSMENU action in {@code State.getActions()}. */
    MODEL_MENU("ape.modelMenuEnabled", Rule.TRUE, "false"),
    /** The form-completion scoring pass and the deterministic-fill branch. */
    FORM_COMPLETION("ape.formCompletionEnabled", Rule.TRUE, "false"),
    /** The per-step {@code [APE-STEP]} telemetry line and its timing. */
    STEP_TELEMETRY("ape.stepTelemetryEnabled", Rule.TRUE, "false"),
    /** The priority tiebreak in {@code State.greedyPickLeastVisited()}. */
    LEAST_VISITED_TIEBREAK("ape.leastVisitedPriorityTiebreak", Rule.TRUE, "false"),
    /** The three GUITreeBuilder perception enhancements. */
    TREE_ENHANCEMENTS("ape.treeEnhancementsEnabled", Rule.TRUE, "false"),
    /** The per-activity budget tracker and its check in action selection. */
    ACTIVITY_BUDGET("ape.activityBudgetEnabled", Rule.TRUE, "false"),
    /** Epsilon adapted at runtime between its bounds, instead of the fixed default. */
    DYNAMIC_EPSILON("ape.dynamicEpsilon", Rule.TRUE, "false"),
    /** Heuristic (label-aware) input generation. */
    HEURISTIC_INPUT("ape.heuristicInput", Rule.TRUE, "false"),
    /** Type-aware input fuzzing; off falls back to the legacy random-string generator. */
    TYPED_FUZZ("ape.fuzzInputTyped", Rule.TRUE, "false"),
    /** Deflect screens owned by a foreign package with one raw BACK. */
    FOREIGN_ACTIVITY_GUARD("ape.foreignActivityGuard", Rule.TRUE, "false"),
    /** Refetch instead of modeling when the tree's package disagrees with the top activity. */
    TREE_PACKAGE_GUARD("ape.treePackageGuard", Rule.TRUE, "false"),
    /** The UI-coverage scoring pass. */
    COVERAGE_BOOST("ape.coverageBoostWeight", Rule.POSITIVE, "0"),
    /** Random input fuzzing. */
    FUZZING("ape.doFuzzing", Rule.TRUE, "false"),

    // --- MOP family: each requires MOP. ------------------------------------------------------

    /** WTG-reach boost on actions whose transition targets a MOP-reachable activity. */
    WTG("ape.mopWeightWtg", Rule.NONZERO, "0"),
    /** OPTIONSMENU-gateway boost. Needs both the MOP substrate and the menu action to boost. */
    MENU_GATEWAY("ape.mopWeightOpenMenu", Rule.POSITIVE, "0"),
    /** Frontier boost on actions whose WTG transition targets an unvisited activity. */
    FRONTIER("ape.frontierBoostWeight", Rule.POSITIVE, "0"),
    /** MOP-conditioned frontier boost. */
    MOP_FRONTIER("ape.mopFrontierWeight", Rule.POSITIVE, "0"),
    /** The stagnation-bounded activity launcher. */
    ACTIVITY_TRIGGER("ape.activityTriggerEnabled", Rule.TRUE, "false"),
    /** The probabilistic component-trigger pool. */
    COMPONENT_TRIGGER("ape.componentPercentage", Rule.POSITIVE, "0"),
    /** Widen the MOP activity set with the component and reachability sources. */
    MOP_ACTIVITY_SOURCE("ape.mopActivitySourceComponents", Rule.TRUE, "false"),

    // --- LLM family: each requires LLM. ------------------------------------------------------

    /** Route to the LLM on entering a new state. */
    LLM_NEW_STATE("ape.llmOnNewState", Rule.TRUE, "false"),
    /** Route to the LLM on stagnation. */
    LLM_STAGNATION("ape.llmOnStagnation", Rule.TRUE, "false"),
    /** Route to the LLM at random, at a configured rate. */
    LLM_RANDOM("ape.llmPercentage", Rule.POSITIVE, "0");

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
        MOP.own("ape.mopWeightDirect", "0");
        MOP.own("ape.mopWeightTransitive", "0");
        MOP.own("ape.mopTargetPickCap", "3");
        MOP.own("ape.mopStrictPackageMatch", "false");

        ACTIVITY_TRIGGER.own("ape.activityTriggerStagnationStep", "50");
        ACTIVITY_TRIGGER.own("ape.activityTriggerMaxPerRun", "0");

        ACTIVITY_BUDGET.own("ape.activityBaseBudget", "50");
        ACTIVITY_BUDGET.own("ape.activityBudgetPerWidget", "5");

        DYNAMIC_EPSILON.own("ape.maxEpsilon", "0.15");
        DYNAMIC_EPSILON.own("ape.minEpsilon", "0.02");

        COVERAGE_BOOST.own("ape.coverageMaxStates", "2000");

        FUZZING.own("ape.fuzzingRate", "0.02");
        FUZZING.own("ape.fuzzingActivityVisitThreshold", "10");

        LLM.own("ape.llmModel", "default");
        LLM.own("ape.llmTemperature", "0.3");
        LLM.own("ape.llmTopP", "0.6");
        LLM.own("ape.llmTopK", "50");
        LLM.own("ape.llmTimeoutMs", "15000");
        LLM.own("ape.llmPromptVariant", "ape_current");
        LLM.own("ape.llmMaxTokens", "1024");
        LLM.own("ape.llmSnapTolerancePx", "50");
        LLM.own("ape.llmBoundaryTopPct", "0.05");
        LLM.own("ape.llmBoundaryBottomPct", "0.94");

        // The -1 sentinel means "no override"; it is the value every baseline arm pushes on a run
        // with no LLM, and the reason the inert rule exists at all.
        LLM_RANDOM.own("ape.llmPercentageNoSubstrate", "-1");

        for (Feature f : new Feature[] {WTG, MENU_GATEWAY, FRONTIER, MOP_FRONTIER,
                ACTIVITY_TRIGGER, COMPONENT_TRIGGER, MOP_ACTIVITY_SOURCE}) {
            f.dependsOn(MOP);
        }
        // The gateway boost steers the menu action, so it needs the action to exist as well as
        // the substrate that decides the menu is worth opening.
        MENU_GATEWAY.dependsOn(MODEL_MENU);
        for (Feature f : new Feature[] {LLM_NEW_STATE, LLM_STAGNATION, LLM_RANDOM}) {
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

    /** The features that must also be active for this one to be expressible. */
    public Set<Feature> dependencies() {
        return Collections.unmodifiableSet(dependencies);
    }

    /**
     * The value at which stating {@code key} for an inactive feature is accepted as inert, or null
     * when the key has no neutral value (a path or a URL: stating it is what activates the
     * feature, so it can never be present while the feature is off).
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

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
package com.android.commands.monkey.ape.agent.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.utils.Logger;

/**
 * The action-selection policy, as an ordered list of named stages instead of a column of guarded
 * {@code if} blocks.
 *
 * <p>The stage order is a fixed sequence of <b>owners</b> (design D-B7): the guards ({@code Budget},
 * {@code Restart}), the LLM ({@code LlmNewState}, {@code LlmStagnation}, {@code LlmCoin}), the MOP
 * launcher, the component trigger (a side effect), the MOP coin, and the SATA chain. It is hard
 * precedence — the first stage that selects ends the step and no later stage's {@code decide()}
 * runs (INV-DP-02) — and it is fixed in code: there is no configuration key that reorders stages
 * and no weighted arbitration between them (owner decision Q1, report Sec. 7).
 *
 * <p><b>Step open.</b> Before any stage decides, {@link #decide} opens the step's
 * {@link StepRouting}, which draws the coins of the mounted groups from their own streams
 * (INV-DP-13), and broadcasts {@link DecisionStage#onStepOpen} to every assembled stage, so per-step
 * bookkeeping such as the launcher's cadence does not depend on which stage takes the step
 * (INV-DP-08). <b>Step close.</b> The selecting stage's label is the step's executed owner
 * ({@code dec.src}); the pipeline writes {@code dec.own} on every step, with the first claimer as
 * {@code i} (design D-B14.6).
 *
 * <p>What the pipeline adds is that the policy can be <em>read</em>. The roster is a list of names
 * echoed once per run, so a trace states which stages ran instead of leaving it to be inferred from
 * the arm dictionary. And a feature the plan does not carry has no stage at all (INV-DP-03) — absence
 * is structural, not a stage object that returns "disabled", which is the same rule the scoring
 * pipeline already follows for its passes.
 */
public final class DecisionPipeline {

    /**
     * The fixed candidate order, and the plan condition that assembles each one.
     *
     * <p>This is the census a trace needs to be self-describing: the roster alone says which stages
     * ran, while the candidate list says which ones <em>could have</em>, so "no launcher fired" and
     * "there was no launcher" stop looking alike. It is a static declaration of names by design — the
     * absent candidate has no object, because INV-DP-03 is exactly the rule that there is nothing to
     * enumerate at runtime.
     *
     * <p>Each condition names the leaf feature only. {@code LLM_NEW_STATE} cannot be in a plan whose
     * {@code LLM} is absent and {@code MOP_LAUNCHER} cannot be in one without {@code MOP} —
     * {@link Feature} declares those dependencies and plan resolution enforces them, so repeating the
     * root here would be a second guard for a fact the plan already guarantees. The one candidate
     * gated by a BASE key rather than a feature is {@code RESTART} ({@code ape.checkRestart}, design
     * D-B14.12).
     */
    public enum Candidate {

        /** The activity-budget guard: navigates away from an exhausted activity, or passes. */
        BUDGET("Budget", Feature.ACTIVITY_BUDGET),
        /** The restart guard: selects the restart action when a restart is due. */
        RESTART("Restart", null, spec -> spec.exploration().checkRestart()),
        /** LLM routing on entering a new state. */
        LLM_NEW_STATE("LlmNewState", Feature.LLM_NEW_STATE),
        /** LLM routing at the stagnation midpoint, once per episode. */
        LLM_STAGNATION("LlmStagnation", Feature.LLM_STAGNATION),
        /** LLM routing on the coin {@code u_L < p_L}. */
        LLM_COIN("LlmCoin", Feature.LLM_COIN),
        /** The MOP activity launcher, on a global cadence. */
        MOP_LAUNCHER("MopLauncher", Feature.MOP_LAUNCHER),
        /** The probabilistic component trigger — the one stage that acts without deciding. */
        COMPONENT_TRIGGER("ComponentTrigger", Feature.COMPONENT_TRIGGER),
        /** The MOP coin: the first interaction with a MOP-targeted action, at {@code p_M}. */
        MOP_COIN("MopCoin", Feature.MOP_COIN),
        /** The SATA strategy chain: the terminal stage of every plan (INV-DP-06). */
        SATA_CHAIN("SataChain", null);

        private final String stageName;
        private final Feature gate;
        private final Predicate<RunSpec> condition;

        Candidate(String stageName, Feature gate) {
            this(stageName, gate, spec -> gate == null || spec.has(gate));
        }

        Candidate(String stageName, Feature gate, Predicate<RunSpec> condition) {
            this.stageName = stageName;
            this.gate = gate;
            this.condition = condition;
        }

        /** The name this candidate's stage reports, and the label in both echo lines. */
        public String stageName() {
            return stageName;
        }

        /**
         * The feature whose presence assembles this candidate, or {@code null} for the terminal
         * stage, which every plan carries, and for {@code RESTART}, which a BASE key mounts.
         */
        public Feature gate() {
            return gate;
        }

        /** Whether {@code spec} assembles this candidate. */
        public boolean assembledFor(RunSpec spec) {
            return condition.test(spec);
        }
    }

    private final List<DecisionStage> stages;

    /** The step's coins, claimer and LLM call budget, shared with the stages that read them. */
    private final StepRouting routing;

    /** What the side-effect stages did on the step being decided; cleared at each {@link #decide}. */
    private final List<String> sideEffects = new ArrayList<>();

    /**
     * Fixes the roster for the run and echoes it once.
     *
     * <p>Package-private: a run's roster comes from its plan, and a pipeline built from an arbitrary
     * stage list is a test fixture. Tests in this package build one directly from stub stages, the way
     * the scoring pipeline's tests do.
     *
     * @param stages the roster in the order {@link #decide} consults it — copied, so the caller's list
     *        cannot reorder a run's policy after the echo has already reported it
     * @param routing the routing record the stages were built with, opened at every step
     */
    DecisionPipeline(List<DecisionStage> stages, StepRouting routing) {
        this.stages = Collections.unmodifiableList(new ArrayList<>(stages));
        this.routing = routing;
        Logger.iformat("[APE-ARCH] stages=[%s]", String.join(", ", stageNames()));
    }

    /**
     * A pipeline over stub stages that read no coin: its routing draws nothing.
     *
     * @param stages the roster, as for {@link #DecisionPipeline(List, StepRouting)}
     */
    DecisionPipeline(List<DecisionStage> stages) {
        this(stages, new StepRouting(false, false, false));
    }

    /**
     * Builds the run's pipeline: the candidates {@code spec} assembles, constructed in candidate
     * order, echoed once (INV-DP-01).
     *
     * <p><b>Why the collaborators are a parameter.</b> Gating is a question about the plan and
     * construction is a question about the agent, and only the first belongs to {@link RunSpec}. The
     * stages that produce actions need the agent's action producers, so assembly binds each into the
     * narrow function object its stage takes; naming {@link StageCollaborators} rather than the agent
     * is what keeps this method — where INV-DP-01 and INV-DP-03 actually live — assertable from a
     * plan and a fake, with no device.
     *
     * <p><b>The context is not a parameter, and the pipeline is not the context's.</b> The stages'
     * run-scoped collaborators reach them through {@link StepContext} per step, so a context parameter
     * here would be one nothing reads. Ownership runs the same way round: this method binds the
     * agent's action producers, and the run context is established before any agent exists, so the
     * pipeline belongs to the agent that assembles it. The LLM units are the context's because they
     * have no agent in them (design D15).
     *
     * @param spec the run's resolved plan — the sole authority on which candidates assemble
     * @param collaborators the agent behaviours the assembled stages invoke
     * @return the pipeline for this run; never empty, since the terminal candidate has no gate
     */
    public static DecisionPipeline fromSpec(RunSpec spec, StageCollaborators collaborators) {
        StepRouting routing = new StepRouting(spec.has(Feature.LLM), spec.has(Feature.MOP),
                spec.has(Feature.COMPONENT_TRIGGER));
        LlmCall llm = spec.has(Feature.LLM) ? llmCall(spec, collaborators, routing) : null;
        List<DecisionStage> stages = new ArrayList<>();
        for (Candidate candidate : assembledCandidates(spec)) {
            switch (candidate) {
                case BUDGET:
                    stages.add(new BudgetStage(collaborators::budgetNavigation));
                    break;
                case RESTART:
                    stages.add(new RestartStage(collaborators::restartDue,
                            collaborators::restartAction));
                    break;
                case LLM_NEW_STATE:
                    stages.add(new LlmNewStateStage(llm));
                    break;
                case LLM_STAGNATION:
                    stages.add(new LlmStagnationStage(llm,
                            spec.exploration().graphStableRestartThreshold()));
                    break;
                case LLM_COIN:
                    stages.add(new LlmCoinStage(llm, spec.llm().dbl("ape.llmPercentage")));
                    break;
                case MOP_LAUNCHER:
                    stages.add(new MopLauncherStage(spec.mop().activityTriggerStagnationStep(),
                            spec.mop().activityTriggerMaxPerRun(), spec.mop().scoring(),
                            spec.mop().launcherDmax(), routing, collaborators::abandonBuffer));
                    break;
                case COMPONENT_TRIGGER:
                    stages.add(new ComponentTriggerStage(spec.staticData().componentPercentage(),
                            routing, collaborators::componentTargetCount,
                            collaborators::triggerComponent));
                    break;
                case MOP_COIN:
                    stages.add(new MopCoinStage(spec.mop().coinPercentage(),
                            spec.has(Feature.FORM_COMPLETION), routing, collaborators::mopLedger,
                            collaborators::abandonBuffer));
                    break;
                case SATA_CHAIN:
                    stages.add(new SataChainStage(collaborators::logActionSelected,
                            collaborators::selectNewActionFromBuffer,
                            collaborators::selectNewActionBackToActivity,
                            collaborators::selectNewActionEarlyStageForward,
                            collaborators::selectNewActionForTrivialActivity,
                            collaborators::selectNewActionEarlyStageBackward,
                            collaborators::selectNewActionEpsilonGreedyRandomly,
                            collaborators::handleNullAction));
                    break;
                default:
                    // Every candidate is constructed above, so this is reachable only by adding a
                    // constant without its construction step — which would otherwise assemble a
                    // roster quietly missing a stage the plan asked for (INV-DP-03 read backwards).
                    throw new IllegalStateException("no construction step for candidate " + candidate);
            }
        }
        return new DecisionPipeline(stages, routing);
    }

    /**
     * The LLM stages' shared unit: the gate chain the plan parameterizes, mounted in the order
     * {@link MinActionsGate}, then {@link BreakerGate}, a gate whose key is {@code 0} not mounted
     * (llm-routing INV-RTR-23).
     *
     * <p>A null answer's {@code dec.own.fb} is the engine's own account of it,
     * {@link LlmEngine#lastFallback()}.
     */
    static LlmCall llmCall(RunSpec spec, StageCollaborators collaborators, StepRouting routing) {
        List<LlmGate> gates = new ArrayList<>();
        if (spec.llm().gateMinActions() > 0) {
            gates.add(new MinActionsGate(spec.llm().gateMinActions()));
        }
        if (spec.llm().breakerFailures() > 0) {
            gates.add(new BreakerGate(collaborators::llmBreakerAllows));
        }
        LlmEngine engine = collaborators.llmEngine();
        return new LlmCall(engine, gates, routing, collaborators::resolveSynthesizedTap,
                collaborators::abandonBuffer, engine::lastFallback);
    }

    /**
     * Which candidates {@code spec} assembles, in the fixed candidate order.
     *
     * <p>The plan-to-roster mapping, separated from stage construction so that what a plan implies
     * about the policy is a pure function of the plan — assertable per preset without a device, a
     * model or a live stage.
     *
     * @param spec the run's resolved plan
     * @return the assembled candidates in candidate order; never empty, since the terminal stage has
     *         no gate
     */
    public static List<Candidate> assembledCandidates(RunSpec spec) {
        List<Candidate> assembled = new ArrayList<>();
        for (Candidate candidate : Candidate.values()) {
            if (candidate.assembledFor(spec)) {
                assembled.add(candidate);
            }
        }
        return Collections.unmodifiableList(assembled);
    }

    /**
     * Runs the step's decision: opens the step, then the first stage that selects decides it.
     *
     * <p>The step opens with the routing's coin draws and the {@link DecisionStage#onStepOpen}
     * broadcast to every assembled stage, before any {@code decide()}. A {@code SIDE_EFFECT} is
     * recorded and the walk continues (INV-DP-05) — the component trigger dispatches a broadcast and
     * the step is still decided by a later stage. A {@code CONTINUE} advances. A stage that cannot
     * decide never throws, which is what makes the LLM's fallback structural rather than an error
     * path (INV-DP-11). On the {@code SELECT} the step's {@code dec.own} is written.
     *
     * @param ctx the step being decided
     * @return the action the step will execute
     * @throws IllegalStateException if the walk reaches the end of the roster. The terminal stage
     *         selects an action or throws {@code BadStateException} (INV-DP-06), so falling off the
     *         end means the roster was assembled without its terminal stage — a wiring bug that would
     *         otherwise surface as a null action somewhere downstream.
     */
    public Action decide(StepContext ctx) {
        sideEffects.clear();
        openStep(ctx);
        for (DecisionStage stage : stages) {
            StageResult result = stage.decide(ctx);
            switch (result.kind()) {
                case SELECT:
                    routing.record(result.decisionSource(), ctx.sink());
                    return result.action();
                case SIDE_EFFECT:
                    sideEffects.add(stage.name() + ": " + result.description());
                    break;
                case CONTINUE:
                    break;
                default:
                    // A fourth variant would arrive here silently treated as CONTINUE, which is the
                    // one failure mode a closed sum type is supposed to make impossible.
                    throw new IllegalStateException("unhandled stage result " + result.kind());
            }
        }
        throw new IllegalStateException(
                "the pipeline " + stageNames() + " decided nothing: its last stage passed instead of"
                        + " selecting or throwing");
    }

    /**
     * Opens the step: the routing draws the mounted groups' coins (INV-DP-13), then every assembled
     * stage's {@link DecisionStage#onStepOpen} runs, in roster order, before any {@code decide()}.
     * Package-private so a test can open a step and then ask one stage directly.
     *
     * @param ctx the step that opened
     */
    void openStep(StepContext ctx) {
        routing.open(ctx.stepRng());
        for (DecisionStage stage : stages) {
            stage.onStepOpen(ctx);
        }
    }

    /**
     * Forwards a visited transition to every stage's episode-state reset channel, once and in roster
     * order (INV-DP-07).
     *
     * @param edge the transition just visited
     */
    public void onStateTransition(StateTransition edge) {
        for (DecisionStage stage : stages) {
            stage.onStateTransition(edge);
        }
    }

    /** The assembled stages, in order. */
    public List<DecisionStage> stages() {
        return stages;
    }

    /** The assembled stages' names, in order — the content of the {@code [APE-ARCH]} echo. */
    public List<String> stageNames() {
        List<String> names = new ArrayList<>(stages.size());
        for (DecisionStage stage : stages) {
            names.add(stage.name());
        }
        return Collections.unmodifiableList(names);
    }

    /** Number of assembled stages. */
    public int size() {
        return stages.size();
    }

    /**
     * What the side-effect stages did on the last step decided, in the order they acted.
     *
     * <p>This is where INV-DP-05's "recorded" lives until stage 4's step record consumes it. It is
     * deliberately not a new trace line: the dispatch already logs itself, and inventing a line here
     * would put a format into the trace that stage 4 immediately restructures.
     */
    public List<String> lastStepSideEffects() {
        return Collections.unmodifiableList(new ArrayList<>(sideEffects));
    }
}

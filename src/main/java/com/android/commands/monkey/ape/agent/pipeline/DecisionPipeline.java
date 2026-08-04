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

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.utils.Logger;

/**
 * The action-selection policy, as an ordered list of named stages instead of a column of guarded
 * {@code if} blocks.
 *
 * <p>The policy itself is unchanged and deliberately so: the stage order below is the verified ladder
 * (V1 of {@code docs/analise_fable-selecao.md}), it is hard precedence — the first stage that selects
 * ends the step and no later stage runs, including the counter increments and RNG draws it would have
 * performed (INV-DP-02) — and it is fixed in code. Precedence is research semantics, stable per
 * release; there is no configuration key that reorders stages and no weighted arbitration between
 * them (owner decision Q1, report Sec. 7).
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
     * {@code LLM} is absent and {@code ACTIVITY_TRIGGER} cannot be in one without {@code MOP} —
     * {@link Feature} declares those dependencies and plan resolution enforces them, so repeating the
     * root here would be a second guard for a fact the plan already guarantees.
     */
    public enum Candidate {

        /** The gh9 activity-budget gate: navigates away from an exhausted activity, or passes. */
        BUDGET("Budget", Feature.ACTIVITY_BUDGET),
        /** LLM routing on entering a new state. */
        LLM_NEW_STATE("LlmNewState", Feature.LLM_NEW_STATE),
        /** LLM routing at the stagnation midpoint, once per episode. */
        LLM_STAGNATION("LlmStagnation", Feature.LLM_STAGNATION),
        /** LLM routing at a configured rate. */
        LLM_RANDOM("LlmRandom", Feature.LLM_RANDOM),
        /** The cadence-based MOP activity launcher. */
        MOP_LAUNCHER("MopLauncher", Feature.ACTIVITY_TRIGGER),
        /** The probabilistic component trigger — the one stage that acts without deciding. */
        COMPONENT_TRIGGER("ComponentTrigger", Feature.COMPONENT_TRIGGER),
        /** The SATA strategy chain: the terminal stage of every plan (INV-DP-06). */
        SATA_CHAIN("SataChain", null);

        private final String stageName;
        private final Feature gate;

        Candidate(String stageName, Feature gate) {
            this.stageName = stageName;
            this.gate = gate;
        }

        /** The name this candidate's stage reports, and the label in both echo lines. */
        public String stageName() {
            return stageName;
        }

        /**
         * The feature whose presence assembles this candidate, or {@code null} for the terminal
         * stage, which every plan carries.
         */
        public Feature gate() {
            return gate;
        }

        /** Whether {@code spec} assembles this candidate. */
        public boolean assembledFor(RunSpec spec) {
            return gate == null || spec.has(gate);
        }
    }

    private final List<DecisionStage> stages;

    /** What the side-effect stages did on the step being decided; cleared at each {@link #decide}. */
    private final List<String> sideEffects = new ArrayList<>();

    /**
     * Fixes the roster for the run and echoes it once.
     *
     * <p>Package-private: a run's roster comes from its plan, and a pipeline built from an arbitrary
     * stage list is a test fixture. Tests in this package build one directly from stub stages, the way
     * the scoring pipeline's tests do.
     */
    DecisionPipeline(List<DecisionStage> stages) {
        this.stages = Collections.unmodifiableList(new ArrayList<>(stages));
        Logger.iformat("[APE-ARCH] stages=[%s]", String.join(", ", stageNames()));
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
     * <p><b>The context is not one.</b> Design D3 sketched assembly as reading the plan and the run
     * context; the stages' run-scoped collaborators reach them through {@link StepContext} per step
     * instead, so a context parameter here would be one nothing reads. Task 7.1, which moves the LLM
     * units and the pipeline itself onto {@code RunContext}, is where that changes.
     *
     * @param spec the run's resolved plan — the sole authority on which candidates assemble
     * @param collaborators the agent behaviours the assembled stages invoke
     * @return the pipeline for this run; never empty, since the terminal candidate has no gate
     */
    public static DecisionPipeline fromSpec(RunSpec spec, StageCollaborators collaborators) {
        List<DecisionStage> stages = new ArrayList<>();
        for (Candidate candidate : assembledCandidates(spec)) {
            switch (candidate) {
                case BUDGET:
                    stages.add(new BudgetStage(collaborators::selectNewActionForTrivialActivity));
                    break;
                case LLM_NEW_STATE:
                    stages.add(new LlmNewStateStage(collaborators.llmRouter(),
                            collaborators::resolveSynthesizedTap));
                    break;
                case LLM_STAGNATION:
                    stages.add(new LlmStagnationStage(collaborators.llmRouter(),
                            collaborators::resolveSynthesizedTap));
                    break;
                case LLM_RANDOM:
                    stages.add(new LlmRandomStage(collaborators.llmRouter(),
                            collaborators::resolveSynthesizedTap));
                    break;
                case MOP_LAUNCHER:
                    stages.add(new MopLauncherStage(spec.mop().activityTriggerStagnationStep(),
                            spec.mop().activityTriggerMaxPerRun()));
                    break;
                case SATA_CHAIN:
                    stages.add(new InlineLadderStage(collaborators));
                    break;
                default:
                    // The candidates between these two are blocks the extraction has not reached
                    // yet: they still run inside the terminal stage, in their ladder positions, so
                    // constructing nothing here is what keeps a step's decision unchanged. The last
                    // extraction task turns this branch into the error it will then be.
                    break;
            }
        }
        return new DecisionPipeline(stages);
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
     * Runs the step's decision: the first stage that selects decides it.
     *
     * <p>A {@code SIDE_EFFECT} is recorded and the walk continues (INV-DP-05) — the component trigger
     * dispatches a broadcast and the step is still decided by a later stage. A {@code CONTINUE}
     * advances. Nothing else happens: a stage that cannot decide never throws, which is what makes the
     * LLM's fallback structural rather than an error path (INV-DP-11).
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
        for (DecisionStage stage : stages) {
            StageResult result = stage.decide(ctx);
            switch (result.kind()) {
                case SELECT:
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

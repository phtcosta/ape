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

import com.android.commands.monkey.ape.model.StateTransition;

/**
 * One rung of the precedence ladder, as an object.
 *
 * <p>A stage is asked once per step, in the pipeline's fixed order, and answers with a
 * {@link StageResult}: it decided the step, it passed, or it performed an effect and passed. The
 * order between stages is the research semantics (hard preemption, owner decision Q1) and lives in
 * {@link DecisionPipeline}'s assembly, never in a stage — a stage knows its own predicate and
 * nothing about who runs before or after it.
 *
 * <p><b>Why {@link #name()} is part of the contract.</b> The assembled roster is echoed once per run
 * as {@code [APE-ARCH] stages=[...]}, which is how a trace states which policy actually ran. A run
 * whose plan omitted a feature has no stage for it (INV-DP-03), so the echo is the difference between
 * two arms being readable off the log instead of inferred from the arm dictionary.
 *
 * <p><b>Why the transition hook is here.</b> Episode-scoped state — the stagnation single shot, the
 * launcher's round robin — belongs to the stage that reads it (INV-DP-07), but the event that resets
 * it is a graph transition the agent observes, not something a stage can see from
 * {@link #decide}. The agent forwards each visited edge to every assembled stage exactly once, after
 * its own counter bookkeeping, and a stage with no episode state inherits the no-op. The alternative
 * — a separate episode-state registry on the run context — would be a second home for information
 * the stage already owns, which is the coupling this change exists to remove.
 */
public interface DecisionStage {

    /** This stage's name in the assembly echo and in step telemetry. Stable: traces join on it. */
    String name();

    /**
     * Decides, passes, or acts, for the step {@code ctx} describes.
     *
     * <p>Total by contract (INV-DP-04): returns exactly one non-null {@link StageResult} and throws
     * nothing. The single exception is the terminal stage, which throws {@code BadStateException}
     * when no rung of the SATA chain yields an action (INV-DP-06) — the pre-change behavior of
     * {@code SataAgent.selectNewActionNonnull}'s last line.
     *
     * @param ctx what this stage may know about the step, read live from the agent and the run
     * @return this stage's answer, never null
     */
    StageResult decide(StepContext ctx);

    /**
     * A graph transition was visited: reset whatever episode state this stage keyed to it.
     *
     * <p>Default no-op — most stages have no episode state, and a stage that must implement an empty
     * method to say so is ceremony.
     *
     * @param edge the transition just visited
     */
    default void onStateTransition(StateTransition edge) {
    }
}

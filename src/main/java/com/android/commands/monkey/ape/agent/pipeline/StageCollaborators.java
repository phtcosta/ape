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

import com.android.commands.monkey.ape.llm.LlmRouter;
import com.android.commands.monkey.ape.model.ModelAction;

/**
 * The agent behaviours assembly binds into stages.
 *
 * <p>A stage needs two different things from the agent and they are deliberately not the same seam.
 * {@link StepContext} is what the step <em>is</em> — the state, the tree, the counters, the seeded
 * stream — read live, once per step, by every stage. This interface is what the agent <em>does</em>:
 * the action producers the ladder called as protected methods, which no per-step data view could
 * carry without turning {@link StepContext} into the god object design D2 exists to prevent.
 *
 * <p>The split has a second consequence that is the reason it is an interface rather than the agent
 * itself: {@link DecisionPipeline#fromSpec} is where INV-DP-01 and INV-DP-03 live — one assembly, and
 * a plan without a feature has no stage for it — and with the agent named concretely those two
 * properties could only be asserted from a device. A fake here makes the roster a plan's roster,
 * assertable in the JVM.
 *
 * <p>Assembly is also where this interface stops: it binds each behaviour into the narrow function
 * object the owning stage takes ({@code Supplier<ModelAction>} for the budget gate, the rung table's
 * supplier pairs for the chain — design D12), so no stage holds this whole surface and a stage test
 * supplies a lambda rather than an implementation.
 */
public interface StageCollaborators {

    /**
     * The navigation action towards an activity with unexplored work, or {@code null} when the
     * current activity already is one and when none is reachable.
     *
     * @return the trivial-activity navigation action, or {@code null}
     */
    ModelAction selectNewActionForTrivialActivity();

    /**
     * The run's LLM router, or {@code null} on a plan with no LLM feature — in which case no stage
     * asks for it, because no LLM stage was assembled.
     *
     * @return the router the LLM stages call
     */
    LlmRouter llmRouter();

    /**
     * Resolves an LLM-synthesized tap against the current state, so the action carries the
     * {@code GUITreeAction} the dispatch path asserts on.
     *
     * <p>Agent-side because resolution needs the throttle the agent computes for the action, which
     * depends on the state's history rather than on anything the step's view carries.
     *
     * @param tap the synthesized off-tree tap the engine returned
     */
    void resolveSynthesizedTap(ModelAction tap);

    /**
     * How many component-trigger targets the arm's census yields, building the pool on first ask.
     *
     * <p>Two methods rather than one because the round-robin walk and the dispatch belong to
     * different owners: the cursor is the stage's episode state (INV-DP-07), while the pool and the
     * per-kind dispatch — intents for receivers and services, {@code content} commands for providers
     * — are the agent's device-facing machinery, which is where design D5 leaves them. The stage
     * therefore asks how many targets there are, walks its own cursor over that range, and names the
     * one to fire.
     *
     * @return the size of the combined (component × filter × action) and (provider × operation)
     *         pool; zero when the census yields no triggerable target
     */
    int mopComponentTargetCount();

    /**
     * Fires the component-trigger target at {@code target}, which the caller took from the range
     * {@link #mopComponentTargetCount} reported.
     *
     * @param target the index of the target to fire
     */
    void triggerMopComponent(int target);

    /**
     * The rungs of the ladder that are still inline on the agent, decided as one block.
     *
     * <p>The extraction walks the ladder from the top, so at any moment the stages that exist stand
     * in front of a remainder that does not yet: the SATA chain. {@link InlineLadderStage} carries that remainder as the roster's
     * terminal stage, which is what lets the pipeline decide every step of a real run — and every
     * step of a golden — from the first extracted stage onwards, instead of running beside the ladder
     * until the last one lands. Task 2.7 replaces it with {@code SataChainStage}, at which point the
     * remainder is empty and this method has no body left to hold.
     *
     * @return the step's decision, always a {@code SELECT}; the block ends in the chain's
     *         {@code BadStateException} rather than in a pass (INV-DP-06)
     */
    StageResult decideInlineLadder();
}

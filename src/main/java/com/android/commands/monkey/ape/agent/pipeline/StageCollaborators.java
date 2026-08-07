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

import com.android.commands.monkey.ape.agent.SataAgent.SataEventType;
import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ModelAction;

import java.util.Random;

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
     * The next action of an already-planned sequence, or {@code null} when the buffer is empty.
     *
     * @return the buffered action, or {@code null}
     */
    ModelAction selectNewActionFromBuffer();

    /**
     * The BACK navigation towards the activity the run set out to return to, or {@code null} when
     * there is none or it is already reached.
     *
     * @return the back-to-activity action, or {@code null}
     */
    ModelAction selectNewActionBackToActivity();

    /**
     * The unvisited-action roulette on the current state, or {@code null} when nothing there is
     * unvisited.
     *
     * @return the early-stage forward action, or {@code null}
     */
    ModelAction selectNewActionEarlyStageForward();

    /**
     * The navigation action towards an activity with unexplored work, or {@code null} when the
     * current activity already is one and when none is reachable.
     *
     * @return the trivial-activity navigation action, or {@code null}
     */
    ModelAction selectNewActionForTrivialActivity();

    /**
     * The backward walk over the graph towards unfinished work, or {@code null} when no such path
     * exists.
     *
     * @return the early-stage backward action, or {@code null}
     */
    ModelAction selectNewActionEarlyStageBackward();

    /**
     * The epsilon-greedy pick: explore at rate epsilon, exploit otherwise. Consumes the seeded
     * stream, which is why its position in the chain is a parity constraint (INV-DP-10).
     *
     * @return the epsilon-greedy action, or {@code null}
     */
    ModelAction selectNewActionEpsilonGreedyRandomly();

    /**
     * The last resort: a validated random pick over the state's actions.
     *
     * <p>It is the one rung that does not decline — it selects, or it throws
     * {@code BadStateException} because the state offers nothing executable.
     *
     * @return the action of last resort, never {@code null}
     */
    ModelAction handleNullAction();

    /**
     * Logs the rung that decided the step, counts it, and writes the action's attribution.
     *
     * <p>Agent-side because the attribution rule is the agent's: every rung but the two that consume
     * priority has its action stamped {@code SATA} here, and the two that do stamp their own source
     * at the pick site. That makes this call the point after which — and only after which — an
     * action's decision source is the one telemetry will print.
     *
     * @param action the action the rung produced
     * @param type the rung's event label
     */
    void logActionSelected(Action action, SataEventType type);

    /**
     * The run's LLM orchestrator, or {@code null} on a plan with no LLM feature — in which case no
     * stage asks for it, because no LLM stage was assembled.
     *
     * @return the engine the LLM stages call
     */
    LlmEngine llmEngine();

    /**
     * The run's single breaker consultation, asked once by whichever LLM hook reached it.
     *
     * <p>A method rather than a handle on the client because consulting the breaker has a side
     * effect — the OPEN&rarr;HALF_OPEN probe and the once-per-open-episode log latch — so a caller
     * holding the client could spend the probe twice on one step. One call site per hook, and no
     * way to ask a second time.
     *
     * @return whether an LLM attempt may be made on this step
     */
    boolean llmBreakerAllows();

    /**
     * The agent's own generator — {@code ape.getRandom()}, Monkey's {@code mRandom}.
     *
     * <p>Named here rather than taken from the step's view because the probabilistic hook holds it
     * for the run, exactly as the predicate it replaced did, and because which of the run's two
     * streams a coin comes from is a parity constraint rather than a convenience (INV-DP-10).
     *
     * @return the agent-side stream
     */
    Random agentRandom();

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
}

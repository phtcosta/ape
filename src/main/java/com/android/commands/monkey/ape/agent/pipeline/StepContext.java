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

import java.util.List;
import java.util.Random;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.utils.MopData;

/**
 * What a stage may know about the step it is deciding.
 *
 * <p>The blocks this pipeline replaces read the agent's fields and protected methods directly, which
 * is why none of them could be tested without an agent, a device and a live model. Naming the read
 * surface as an interface changes that twice over: a stage test supplies a fake and asserts a
 * predicate in pure JVM, and the surface itself becomes reviewable — the enumeration below is the
 * whole list, and adding to it means touching the design that declares it (design D2). The same
 * device is already load-bearing one layer down, in
 * {@link com.android.commands.monkey.ape.agent.scoring.ScoringContext}.
 *
 * <p>The production implementation is a thin view over the agent and the run context: it reads live
 * and copies nothing. A snapshot would be the wrong shape — the stages run in sequence within one
 * step, and a stage that resets the stability counter must be visible to the agent immediately, not
 * at the end of a step.
 *
 * <p><b>One write method, on purpose.</b> {@link #resetGraphStableCounter()} exists because the
 * stagnation stage's accepted escape resets that counter today ({@code SataAgent.java:503}), and the
 * counter is <em>not</em> episode state a stage could own: the forced-restart mechanism reads it too
 * (design D5). So it stays with the agent, and the one legitimate stage-side write is a named method
 * rather than a mutable field handed out. No other stage may write agent state, and there is no
 * second write method to make that ambiguous.
 */
public interface StepContext {

    /** The state the step is being decided on. */
    State newState();

    /** The GUI tree that produced {@link #newState()} — the LLM stages' screenshot subject. */
    GUITree newGUITree();

    /**
     * How many actions are queued in the agent's buffer. Zero is the LLM stages' shared
     * precondition: mid-sequence steps are not the LLM's to redirect.
     */
    int actionBufferSize();

    /**
     * Steps since the graph last grew. Shared exploration state: the stagnation stage reads it and
     * the forced-restart mechanism consumes it independently (design D5).
     */
    int graphStableCounter();

    /** The current step's timestamp, as the action resolution filters use it. */
    int timestamp();

    /**
     * The run's seeded random stream. Every conditional draw the ladder makes comes from here, in the
     * pre-change order (INV-DP-10) — which is what makes a golden reproducible from a seed.
     */
    Random random();

    /** The run's static-analysis data, or {@code null} when the plan carries no MOP feature. */
    MopData mopData();

    /** The exploration graph. */
    Graph graph();

    /** The per-activity budget tracker, or {@code null} when the plan carries no budget feature. */
    ActivityBudgetTracker budgetTracker();

    /** The recent-action window the LLM prompt is built from. */
    List<ApePromptBuilder.ActionHistoryEntry> actionHistory();

    /**
     * Resets the graph-stability counter to zero — the stagnation stage's accepted escape, and the
     * only write a stage may make to agent state.
     */
    void resetGraphStableCounter();
}

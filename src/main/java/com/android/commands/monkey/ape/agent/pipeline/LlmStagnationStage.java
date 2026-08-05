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

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.StateTransition;

/**
 * Ask the model once, halfway to a forced restart, when the graph has stopped growing.
 *
 * <p>The episode is the unit here. Exploration that stops producing new edges is stuck, and the agent
 * restarts it at a threshold; this stage spends one LLM call at the midpoint on the chance the model
 * sees a way out that the chain does not. One call, because a second at the same standstill would ask
 * the same question about the same screen and pay for the same answer.
 *
 * <p><b>The shot is spent by asking, not by succeeding.</b> The flag burns as soon as the trigger
 * fires, before the engine is called, so a null answer is a failed attempt rather than an unused one
 * and the restart at the full threshold is what follows if the standstill persists. This is the one
 * place where "fired" and "helped" are deliberately not the same predicate.
 *
 * <p><b>What re-arms it is an edge, and what resets the counter is not.</b> A new edge means
 * exploration moved, which ends the episode: {@link #onStateTransition} re-arms the flag on exactly
 * the transitions that reset the stability counter. An accepted escape resets that counter too
 * ({@link StepContext#resetGraphStableCounter()}) but does <em>not</em> re-arm — no new edge has been
 * observed yet, so it is still the same episode, and re-arming there would hand a stuck episode an
 * unbounded number of calls.
 *
 * <p>The counter itself stays with the agent rather than moving here (design D5): the forced-restart
 * mechanism reads it independently, so it is shared exploration state, not this stage's episode
 * state. The reset is the single write any stage makes to it.
 *
 * <p><b>The threshold arrives separately from the rest of the LLM parameters, and that is a fact
 * about ownership.</b> {@code ape.graphStableRestartThreshold} is the agent's stagnation budget —
 * exploration scope, not LLM scope — and this stage only ever reads its midpoint. The mode conjunct
 * the inherited predicate also carried is gone: enabling stagnation routing is the condition under
 * which this stage exists (INV-DP-03), so inside it the test is vacuous.
 */
public final class LlmStagnationStage implements DecisionStage {

    private final LlmEngine engine;
    private final BooleanSupplier breakerAllows;
    private final int restartThreshold;
    private final Consumer<ModelAction> resolveSynthesizedTap;

    /** Whether this stagnation episode has already spent its one call. */
    private boolean firedThisEpisode;

    /**
     * @param engine the run's LLM orchestrator; non-null, because this stage exists only on a plan
     *        carrying the stagnation LLM feature and such a plan has one
     * @param breakerAllows the run's single breaker consultation, {@code LlmClient.allows}
     * @param restartThreshold the agent's forced-restart threshold, whose half is this hook's
     *        firing point
     * @param resolveSynthesizedTap the agent's per-state resolution, for the synthesized tap
     */
    public LlmStagnationStage(LlmEngine engine, BooleanSupplier breakerAllows, int restartThreshold,
                              Consumer<ModelAction> resolveSynthesizedTap) {
        this.engine = engine;
        this.breakerAllows = breakerAllows;
        this.restartThreshold = restartThreshold;
        this.resolveSynthesizedTap = resolveSynthesizedTap;
    }

    /**
     * The stagnation trigger predicate (INV-RTR-19): at or past the midpoint, with the episode's
     * flag still armed.
     *
     * <p>The retired condition was exact equality with {@code threshold / 2}. Since the counter
     * resets to 0 on every new edge, that gave each stagnation episode a one-step window: any step
     * where the counter jumped past the midpoint, or was reset just before it, silently cost the
     * episode its only chance, and the hook virtually never fired. {@code >=} closes the window and
     * the flag preserves the original single-shot intent. Pure, so it is unit-testable without a
     * stage, an agent or a plan.
     */
    static boolean stagnationMidpointReached(int graphStableCounter, int threshold,
                                             boolean firedThisEpisode) {
        return !firedThisEpisode && graphStableCounter >= threshold / 2;
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.LLM_STAGNATION.stageName();
    }

    @Override
    public StageResult decide(StepContext ctx) {
        if (!LlmGate.allows(ctx)
                || !stagnationMidpointReached(ctx.graphStableCounter(), restartThreshold,
                        firedThisEpisode)
                || !breakerAllows.getAsBoolean()) {
            return StageResult.continueChain();
        }
        firedThisEpisode = true;
        ModelAction result = engine.selectAction(ctx.newGUITree(), ctx.newState(),
                ctx.newState().getActions(), ctx.mopData(), ctx.actionHistory(), "stagnation");
        if (result == null) {
            return StageResult.continueChain();
        }
        ctx.resetGraphStableCounter();
        ModelAction accepted = LlmGate.accept(result, resolveSynthesizedTap);
        return StageResult.select(accepted, accepted.getDecisionSource().name());
    }

    /**
     * {@inheritDoc}
     *
     * <p>A new edge, in either of the two forms the graph distinguishes, is the end of a stagnation
     * episode and the only thing that re-arms the shot.
     */
    @Override
    public void onStateTransition(StateTransition edge) {
        switch (edge.getType()) {
            case NEW_ACTION:
            case NEW_ACTION_TARGET:
                firedThisEpisode = false;
                break;
            default:
                break;
        }
    }
}

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

/**
 * Ask the model when the agent has just walked into a screen it has never seen.
 *
 * <p>This is the first of the three LLM hooks and therefore the one that preempts the other two, the
 * MOP launcher and the whole SATA chain whenever it answers. Its trigger is two conjuncts in a fixed
 * order: the step entered an unseen state, and then the circuit breaker allows an attempt. The order
 * is behaviour, not style — the breaker's consultation has a side effect (the OPEN→HALF_OPEN probe
 * and the once-per-open-episode log latch) and must not be reached on a step the first conjunct
 * already declined.
 *
 * <p><b>The mode conjunct is gone, and assembly is the whole reason it could go.</b> The predicate
 * this stage inherited also tested that new-state routing was enabled, which is precisely the
 * condition under which this stage exists at all (INV-DP-03). Inside the stage it is necessarily
 * true, so testing it would be asking whether the object running the test was constructed.
 *
 * <p><b>A declining model does not change the step's shape.</b> Precondition unmet, trigger false,
 * breaker open, or an engine that returns null for any of its reasons — screenshot failure, transport
 * failure, unparseable answer, a coordinate matching nothing, a banned dead pair — all take the one
 * path out of here, {@code Continue}, and the rest of the assembled pipeline decides the step
 * (INV-DP-11). There is no retry within a step and no substitute action invented here.
 */
public final class LlmNewStateStage implements DecisionStage {

    private final LlmEngine engine;
    private final BooleanSupplier breakerAllows;
    private final Consumer<ModelAction> resolveSynthesizedTap;

    /**
     * @param engine the run's LLM orchestrator; non-null, because this stage exists only on a plan
     *        carrying the new-state LLM feature and such a plan has one
     * @param breakerAllows the run's single breaker consultation, {@code LlmClient.allows}
     * @param resolveSynthesizedTap the agent's per-state resolution, for the synthesized tap
     */
    public LlmNewStateStage(LlmEngine engine, BooleanSupplier breakerAllows,
                            Consumer<ModelAction> resolveSynthesizedTap) {
        this.engine = engine;
        this.breakerAllows = breakerAllows;
        this.resolveSynthesizedTap = resolveSynthesizedTap;
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.LLM_NEW_STATE.stageName();
    }

    @Override
    public StageResult decide(StepContext ctx) {
        if (!LlmGate.allows(ctx) || !ctx.isNewState() || !breakerAllows.getAsBoolean()) {
            return StageResult.continueChain();
        }
        ModelAction result = engine.selectAction(ctx.newGUITree(), ctx.newState(),
                ctx.newState().getActions(), ctx.mopData(), ctx.actionHistory(), "new-state",
                ctx.timestamp());
        if (result == null) {
            return StageResult.continueChain();
        }
        ModelAction accepted = LlmGate.accept(result, resolveSynthesizedTap);
        return StageResult.select(accepted, accepted.getDecisionSource().name());
    }
}

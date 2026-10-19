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

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.telemetry.RunCounter;

/**
 * Ask the model at the plan's rate {@code p_L}, on steps no LLM trigger called on (llm-routing
 * "Probabilistic LLM Routing", design D-B8, D-B9).
 *
 * <p>The coin is {@code u_L < p_L}, where {@code u_L} was drawn at step open from the LLM coin's
 * own stream ({@link StepRouting#uL()}, INV-DP-13, INV-RTR-24). It is drawn on every step of a plan
 * carrying {@code LLM}, whether or not this stage is reached and whether or not a gate bars, so
 * mounting a gate or changing {@code p_L} moves no other draw. A won coin claims the step for the
 * LLM and counts {@code coinLlm}; a lost coin claims nothing. The won coin then goes through the
 * shared gate chain and makes the step's one call ({@link LlmCall}); when an earlier LLM stage
 * already called, this stage returns {@code Continue} without reading its coin (INV-RTR-26).
 *
 * <p>The rate's other role, whether this stage exists at all, is settled at assembly: a plan with
 * {@code p_L = 0} has no stage here, while {@code u_L} is still drawn every step.
 */
public final class LlmCoinStage implements DecisionStage {

    private final LlmCall llm;
    private final double percentage;

    /**
     * @param llm the gate chain, call budget and acceptance the three LLM stages share
     * @param percentage {@code p_L}, the plan's clamped rate; positive, since a zero rate assembles
     *        no stage
     */
    public LlmCoinStage(LlmCall llm, double percentage) {
        this.llm = llm;
        this.percentage = percentage;
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.LLM_COIN.stageName();
    }

    @Override
    public StageResult decide(StepContext ctx) {
        if (llm.spent() || !(llm.routing().uL() < percentage)) {
            return StageResult.continueChain();
        }
        ctx.sink().count(RunCounter.COIN_LLM);
        if (!llm.admits(ctx, StepRouting.TRIGGER_COIN)) {
            return StageResult.continueChain();
        }
        ModelAction result = llm.call(ctx, StepRouting.TRIGGER_COIN, "random");
        if (result == null) {
            return StageResult.continueChain();
        }
        return llm.select(ctx, result);
    }
}

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

import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.ModelAction;

/**
 * Ask the model at a configured rate, on steps no other hook claimed.
 *
 * <p>The two hooks above this one fire on a condition; this one fires on a coin, so that a run that
 * never enters a new state and never stalls still carries some LLM influence.
 *
 * <p><b>Where the coin is drawn matters more than what it decides.</b> It is drawn after the shared
 * precondition and ahead of the circuit breaker, which is exactly where the predicate this stage
 * inherited drew it (INV-DP-10). A seeded run's whole decision sequence is a function of the order
 * its draws are consumed in, so a coin flipped one line earlier or later would shift every later
 * decision in the run and turn the goldens red for a reason that looks like a logic bug and is not.
 *
 * <p><b>Which stream it comes from, stated because the run has two.</b> The generator is the
 * agent's — {@code ape.getRandom()}, Monkey's {@code mRandom} — injected at assembly, and it is
 * <em>not</em> {@code RunContext.rng()}. The two are seeded from one number but they are different
 * {@code Random} instances, and the agent draws from both; moving the coin between them would shift
 * every later draw of the other stream in an LLM arm. That is a real change in what a device does
 * and one no golden can see, because the parity harness substitutes this coin outright.
 *
 * <p>The rate's other role, deciding whether this stage exists at all, is settled at assembly: a plan
 * with a zero rate has no stage here. That move is draw-neutral, which is the reason it is safe — the
 * rate was already the predicate's first conjunct, so when it was zero the short-circuit meant no coin
 * was drawn anyway.
 */
public final class LlmRandomStage implements DecisionStage {

    private final LlmEngine engine;
    private final BooleanSupplier breakerAllows;
    private final double percentage;
    private final Random random;
    private final Consumer<ModelAction> resolveSynthesizedTap;

    /**
     * @param engine the run's LLM orchestrator; non-null, because this stage exists only on a plan
     *        carrying a positive rate and such a plan has one
     * @param breakerAllows the run's single breaker consultation, {@code LlmClient.allows}
     * @param percentage the plan's rate; positive, since a zero rate assembles no stage
     * @param random the agent's generator, which is the stream the coin must come from
     * @param resolveSynthesizedTap the agent's per-state resolution, for the synthesized tap
     */
    public LlmRandomStage(LlmEngine engine, BooleanSupplier breakerAllows, double percentage,
                          Random random, Consumer<ModelAction> resolveSynthesizedTap) {
        this.engine = engine;
        this.breakerAllows = breakerAllows;
        this.percentage = percentage;
        this.random = random;
        this.resolveSynthesizedTap = resolveSynthesizedTap;
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.LLM_RANDOM.stageName();
    }

    @Override
    public StageResult decide(StepContext ctx) {
        if (!LlmGate.allows(ctx) || random.nextDouble() >= percentage
                || !breakerAllows.getAsBoolean()) {
            return StageResult.continueChain();
        }
        ModelAction result = engine.selectAction(ctx.newGUITree(), ctx.newState(),
                ctx.newState().getActions(), ctx.mopData(), ctx.actionHistory(), "random",
                ctx.timestamp());
        if (result == null) {
            return StageResult.continueChain();
        }
        ModelAction accepted = LlmGate.accept(result, resolveSynthesizedTap);
        return StageResult.select(accepted, accepted.getDecisionSource().name());
    }
}

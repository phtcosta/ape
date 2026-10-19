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

/**
 * Ask the model when the agent has just walked into a screen it has never seen.
 *
 * <p>The first of the three LLM stages, after the {@code Budget} and {@code Restart} guards. Its
 * trigger is the step entering an unseen state, and it consumes no coin (D-A5, D-A17). When the
 * trigger holds, the LLM claims the step and the shared gate chain decides whether the call is made
 * ({@link LlmCall}); the SATA action buffer is no condition, and a selection over a non-empty buffer
 * abandons it without weakening (INV-DP-15).
 *
 * <p><b>A declining model does not change the step's shape.</b> Trigger false, a gate that bars, or
 * an engine that returns null for any of its reasons all take the one path out of here,
 * {@code Continue}, and the rest of the assembled pipeline decides the step (INV-DP-11). A step makes
 * at most one LLM call: when an earlier stage already called, this one returns {@code Continue}
 * without evaluating anything (INV-RTR-26).
 */
public final class LlmNewStateStage implements DecisionStage {

    private final LlmCall llm;

    /**
     * @param llm the gate chain, call budget and acceptance the three LLM stages share
     */
    public LlmNewStateStage(LlmCall llm) {
        this.llm = llm;
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.LLM_NEW_STATE.stageName();
    }

    @Override
    public StageResult decide(StepContext ctx) {
        if (llm.spent() || !ctx.isNewState()
                || !llm.admits(ctx, StepRouting.TRIGGER_NEW_STATE)) {
            return StageResult.continueChain();
        }
        ModelAction result = llm.call(ctx, StepRouting.TRIGGER_NEW_STATE, "new-state");
        if (result == null) {
            return StageResult.continueChain();
        }
        return llm.select(ctx, result);
    }
}

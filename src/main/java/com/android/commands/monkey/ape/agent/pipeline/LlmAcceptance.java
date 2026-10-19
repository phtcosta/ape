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

import java.util.function.Consumer;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;

/**
 * What every LLM stage does with an answer it accepts: stamp the provenance, and resolve the
 * synthesized tap. One place, so that the three stages cannot accept an answer three ways. The
 * preconditions of a call are the gates' ({@link LlmGate}).
 */
public final class LlmAcceptance {

    private LlmAcceptance() {
    }

    /**
     * Stamps an accepted answer with its provenance and resolves it if it needs resolving.
     *
     * @param result the action the engine returned, never null
     * @param resolveSynthesizedTap the agent's per-state resolution, applied only to the synthesized
     *        tap — see {@link #requiresSynthesizedResolution}
     * @return {@code result}, stamped
     */
    public static ModelAction accept(ModelAction result, Consumer<ModelAction> resolveSynthesizedTap) {
        result.setDecisionSource(ModelAction.DecisionSource.LLM);
        result.setPickChannel(ModelAction.PickChannel.LLM);
        if (requiresSynthesizedResolution(result)) {
            resolveSynthesizedTap.accept(result);
        }
        return result;
    }

    /**
     * True when an LLM-routed action must be resolved against the current state before dispatch
     * (llm-coordinate-tap, D7).
     *
     * <p>Only the synthesized off-tree tap ({@link ActionType#MODEL_LLM_TAP}) needs it: the tap is
     * {@code isModelAction()==true} yet absent from {@code State.actions}, so it never passed the
     * per-state resolution pass and its {@code GUITreeAction} is null — which the agent's
     * {@code resolveNewAction()} would {@code assertNotNull} on. A matched widget action returned on
     * the same path is already resolved and MUST NOT be re-resolved, which would re-pick its node, so
     * this returns {@code false} for every widget/targetless type other than the synthesized tap.
     * Pure, so the guard decision is unit-testable without a live State or graph.
     *
     * @param result the action the engine returned
     * @return whether it needs the per-state resolution pass
     */
    public static boolean requiresSynthesizedResolution(ModelAction result) {
        return result.getType() == ActionType.MODEL_LLM_TAP;
    }
}

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
 * What every LLM stage does around its engine call: the precondition before, the acceptance after.
 *
 * <p>Both halves were written out three times in the ladder, once per hook, which is what made them
 * worth a name. The precondition in particular — {@code actionBufferSize() == 0 &&
 * newState.getActions().size() > 2} — was verbatim at three sites, so a change to one of them was a
 * change to the policy at one hook and not the others, silently.
 *
 * <p>The third conjunct of those three copies, a null test on the run's LLM, is not here: an LLM
 * stage exists only on a plan carrying its feature, and such a plan builds the units. The guard
 * dissolved into assembly rather than moving (INV-DP-03).
 */
public final class LlmGate {

    private LlmGate() {
    }

    /**
     * Whether this step is the LLM's to redirect at all.
     *
     * <p>Two conditions, and neither is about the LLM. A non-empty action buffer means the agent is
     * mid-sequence, executing a path it already committed to, and redirecting there would abandon
     * the path rather than choose within it. Three actions or fewer is a screen with nothing to
     * reason about — the model's answer would cost a round trip to pick from a set the chain picks
     * from just as well.
     *
     * @param ctx the step being decided
     * @return whether an LLM stage may consult its trigger predicate
     */
    static boolean allows(StepContext ctx) {
        return ctx.actionBufferSize() == 0 && ctx.newState().getActions().size() > 2;
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

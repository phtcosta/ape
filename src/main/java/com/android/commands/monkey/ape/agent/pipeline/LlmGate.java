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
import java.util.function.Consumer;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;

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
     * <p>A non-empty action buffer closes it unconditionally. The agent is then mid-sequence,
     * executing a path it already committed to, and redirecting there would abandon the path rather
     * than choose within it — which is as true on an opaque screen as on any other.
     *
     * <p>With the buffer empty, a state offering more than two actions opens it. Two actions or fewer
     * is, for the chain, a screen with nothing to reason about: the model's answer would cost a round
     * trip to pick from a set the chain picks from just as well. That premise fails on an
     * {@linkplain #isOpaque opaque} step — a game canvas, a camera preview — where the two actions are
     * {@code MODEL_BACK} and {@code MODEL_MENU}, the chain can only leave or open the menu, and the
     * model, which sees the screenshot and can answer with an off-tree tap, is the one decider with
     * anything to offer. So when the plan turns opaque routing on, an opaque step opens it too.
     *
     * <p>With {@code opaqueEnabled} false the result is the size rule alone, for every input — the
     * property that keeps a plan without opaque routing on the gate, and therefore on the draw
     * sequence, it always had (INV-RTR-22).
     *
     * @param ctx the step being decided
     * @param opaqueEnabled whether the plan turns opaque routing on, injected at assembly
     * @return whether an LLM stage may consult its trigger predicate
     */
    static boolean allows(StepContext ctx, boolean opaqueEnabled) {
        if (ctx.actionBufferSize() != 0) {
            return false;
        }
        State state = ctx.newState();
        return state.getActions().size() > 2 || (opaqueEnabled && isOpaque(state));
    }

    /**
     * Whether {@code state} is opaque: it offers at least one action and none of them needs a
     * target (INV-RTR-21).
     *
     * <p>This is the explorer's own knowledge of the screen, read from the abstract state and from
     * nothing else — no widget class, no static analysis, no screenshot. It does not tell a LibGDX
     * canvas from a camera preview or a loading screen with an empty tree; each is a screen on which
     * the model is the only decider that can do more than leave. A screen whose one widget yields no
     * model action is opaque; a one-button dialog is not, even at two actions.
     *
     * <p>An empty action list is not opaque: {@code CoordinateMapper.map} discards every answer on
     * it before reaching the tap path, so a call made there would be thrown away by construction.
     *
     * <p>The gate and the step record's {@code dec.opaque} both read this method, so the two cannot
     * disagree about which steps were opaque.
     *
     * @param state the state the step is decided on
     * @return whether the state offers only targetless actions
     */
    public static boolean isOpaque(State state) {
        List<ModelAction> actions = state.getActions();
        if (actions.isEmpty()) {
            return false;
        }
        for (ModelAction action : actions) {
            if (action.requireTarget()) {
                return false;
            }
        }
        return true;
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

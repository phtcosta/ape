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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.telemetry.RunCounter;

/**
 * What the three LLM stages share: the gate chain, the step's one call, and the acceptance of an
 * answer (llm-routing, design D-B8, D-B14.14).
 *
 * <p>One instance per run, built by the composition root and handed to every LLM stage, so the
 * gate chain exists in exactly one place and no stage carries its own copy of a gate expression.
 * A stage keeps only its trigger; the order it calls this in is the contract:
 * <ol>
 *   <li>{@link #spent} — an LLM stage of this step already called, so this one returns
 *       {@code Continue} without evaluating its trigger or its gates (INV-RTR-26);
 *   <li>{@link #admits} — the trigger held: the LLM claims the step ({@code dec.own.i}), and the
 *       chain is evaluated with short-circuit; a refusal names its gate and counts it;
 *   <li>{@link #call} — the step's one call;
 *   <li>{@link #select} — the accepted answer, with the buffer abandoned when non-empty.
 * </ol>
 */
public final class LlmCall {

    /** The LLM's owner value, {@code dec.src} and {@code dec.own.i}. */
    public static final String OWNER = "llm";

    private final LlmEngine engine;
    private final List<LlmGate> gates;
    private final StepRouting routing;
    private final Consumer<ModelAction> resolveSynthesizedTap;
    private final Consumer<String> abandonBuffer;
    private final Supplier<String> lastFallback;

    /**
     * @param engine the run's LLM orchestrator
     * @param gates the mounted gate chain, in evaluation order
     * @param routing the pipeline's per-step routing record
     * @param resolveSynthesizedTap the agent's per-state resolution, for the synthesized tap
     * @param abandonBuffer the agent's buffer abandonment without weakening
     * @param lastFallback why the engine's last call returned no action ({@code error},
     *        {@code screenshot}, {@code no_match} or {@code decline}), read right after a null
     *        answer; {@code null} when the engine does not say
     */
    public LlmCall(LlmEngine engine, List<LlmGate> gates, StepRouting routing,
            Consumer<ModelAction> resolveSynthesizedTap, Consumer<String> abandonBuffer,
            Supplier<String> lastFallback) {
        this.engine = engine;
        this.gates = Collections.unmodifiableList(new ArrayList<>(gates));
        this.routing = routing;
        this.resolveSynthesizedTap = resolveSynthesizedTap;
        this.abandonBuffer = abandonBuffer;
        this.lastFallback = lastFallback;
    }

    /** The mounted gate chain, in evaluation order. */
    public List<LlmGate> gates() {
        return gates;
    }

    /** The pipeline's per-step routing record. */
    StepRouting routing() {
        return routing;
    }

    /** Whether this step's one LLM call has been made (INV-RTR-26). */
    boolean spent() {
        return routing.llmCalled();
    }

    /**
     * A trigger held: claims the step for the LLM and evaluates the chain. The first gate that
     * refuses names itself in {@code dec.own.g}, sets {@code fb=gate} and counts once per step.
     *
     * @param ctx the step being decided
     * @param trigger the stage's {@code dec.own.trg}
     * @return whether every mounted gate admits
     */
    boolean admits(StepContext ctx, String trigger) {
        routing.llmTriggered(trigger);
        for (LlmGate gate : gates) {
            if (!gate.admits(ctx)) {
                routing.llmBarred(gate.name(), counterOf(gate), ctx.sink());
                return false;
            }
        }
        return true;
    }

    /**
     * The step's one call. A null answer records its fallback reason; the call is spent either
     * way.
     *
     * @param ctx the step being decided
     * @param trigger the stage's {@code dec.own.trg}
     * @param mode the engine's mode label ({@code new-state}, {@code stagnation}, {@code random})
     * @return the engine's answer, or {@code null}
     */
    ModelAction call(StepContext ctx, String trigger, String mode) {
        routing.llmCalling(trigger);
        ModelAction result = engine.selectAction(ctx.newGUITree(), ctx.newState(),
                ctx.newState().getActions(), ctx.actionHistory(), mode);
        if (result == null) {
            String reason = lastFallback.get();
            if (reason != null) {
                routing.fallback(OWNER, reason);
            }
        }
        return result;
    }

    /**
     * Accepts an answer: stamps the owner and the channel, resolves a synthesized tap, abandons a
     * non-empty buffer without weakening (INV-DP-15), and selects.
     *
     * @param ctx the step being decided
     * @param result the engine's non-null answer
     * @return the stage's {@code Select}
     */
    StageResult select(StepContext ctx, ModelAction result) {
        ModelAction accepted = LlmAcceptance.accept(result, resolveSynthesizedTap);
        if (ctx.actionBufferSize() > 0) {
            abandonBuffer.accept(OWNER);
        }
        return StageResult.select(accepted, OWNER);
    }

    /** The {@code RUN_END} counter of steps {@code gate} lost. */
    private static RunCounter counterOf(LlmGate gate) {
        return BreakerGate.NAME.equals(gate.name()) ? RunCounter.GATE_BREAKER
                : RunCounter.GATE_MIN_ACTIONS;
    }
}

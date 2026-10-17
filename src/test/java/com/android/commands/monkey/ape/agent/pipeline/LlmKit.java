package com.android.commands.monkey.ape.agent.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.ModelAction;

/**
 * The shared LLM unit the stage tests build: the default gate chain ({@code MinActionsGate(3)},
 * then a {@link BreakerGate} over the test's scripted breaker), a fresh routing record opened with
 * the coin the test states, and recorders for the resolved taps and the abandoned buffers.
 */
final class LlmKit {

    final StepRouting routing = new StepRouting(true, false, false);
    final List<ModelAction> resolved = new ArrayList<>();
    final List<String> abandoned = new ArrayList<>();
    final LlmCall call;
    /** The fallback reason the scripted engine reports for its null answers. */
    String fallbackReason;

    LlmKit(LlmEngine engine, BooleanSupplier breaker) {
        this(engine, Arrays.<LlmGate>asList(new MinActionsGate(3), new BreakerGate(breaker)));
    }

    LlmKit(LlmEngine engine, List<LlmGate> gates) {
        this.call = new LlmCall(engine, gates, routing, resolved::add, abandoned::add,
                () -> fallbackReason);
        routing.open(Double.NaN, Double.NaN, Double.NaN);
    }

    /** Opens the next step with {@code uL} as its LLM coin. */
    LlmKit step(double uL) {
        routing.open(uL, Double.NaN, Double.NaN);
        return this;
    }

    /** Opens the next step with no coin read. */
    LlmKit step() {
        return step(Double.NaN);
    }
}

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

/**
 * G3: the LLM is called only while the circuit breaker allows an attempt (llm-routing "Declared
 * LLM Gates", design D-B8).
 *
 * <p>Mounted after {@link MinActionsGate}, and the chain short-circuits, so the breaker is
 * consulted only on a step every earlier gate admitted and a refusal here is unambiguously the
 * breaker's. The consultation is {@code LlmClient.allows()}: one {@code shouldAttempt()}, whose
 * OPEN&rarr;HALF_OPEN probe is the one side effect a gate may have. The composition root does not
 * mount the gate when {@code ape.llmBreakerFailures} is {@code 0}; the breaker is then never
 * consulted.
 */
public final class BreakerGate implements LlmGate {

    /** The gate's wire name, {@code dec.own.g}. */
    public static final String NAME = "breaker";

    private final BooleanSupplier breakerAllows;

    /**
     * @param breakerAllows the run's breaker consultation, {@code LlmClient.allows}
     */
    public BreakerGate(BooleanSupplier breakerAllows) {
        this.breakerAllows = breakerAllows;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean admits(StepContext ctx) {
        return breakerAllows.getAsBoolean();
    }
}

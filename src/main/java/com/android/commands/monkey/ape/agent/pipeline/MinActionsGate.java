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

/**
 * G2: the LLM is called only on a screen offering at least {@code min} actions (llm-routing
 * "Declared LLM Gates", design D-B8).
 *
 * <p>The gate refuses a state with <b>fewer than {@code min}</b> actions, so the default
 * {@code ape.llmGateMinActions=3} refuses two actions or fewer, which is the former {@code > 2}.
 * The composition root does not mount the gate when the key is {@code 0}.
 */
public final class MinActionsGate implements LlmGate {

    /** The gate's wire name, {@code dec.own.g}. */
    public static final String NAME = "min_actions";

    private final int min;

    /**
     * @param min {@code ape.llmGateMinActions}: the fewest actions a state may offer and still be
     *        sent to the LLM; positive, since a zero mounts no gate
     */
    public MinActionsGate(int min) {
        this.min = min;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean admits(StepContext ctx) {
        return ctx.newState().getActions().size() >= min;
    }
}

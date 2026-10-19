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
 * A precondition on an LLM call, shared by the three LLM stages (llm-routing, design D-B8).
 *
 * <p>A Specification: each gate answers one question about the step — enough actions on the
 * screen, a breaker that allows an attempt — and the composition root mounts the gates the plan
 * parameterizes, in a fixed order, so that a step lost to a gate can name the gate that barred it
 * ({@code dec.own.g}). A gate whose parameter is {@code 0} is not mounted at all.
 */
public interface LlmGate {

    /** The gate's name on the wire: {@code min_actions} or {@code breaker}. */
    String name();

    /**
     * Whether this gate lets the step call the LLM. Pure: it reads the step, draws nothing and
     * writes no record. {@link BreakerGate} is the one exception to purity: its single breaker
     * consultation may move the breaker from OPEN to HALF_OPEN.
     *
     * @param ctx the step being decided
     */
    boolean admits(StepContext ctx);
}

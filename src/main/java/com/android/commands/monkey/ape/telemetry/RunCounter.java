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
package com.android.commands.monkey.ape.telemetry;

/**
 * The routing counters of {@code RUN_END.counters} (isolate-guidance-groups, design § Telemetry
 * contract): how often each owner and gate acted over the run. The sink keeps them and writes them
 * in full, zero included, so a cell without a mechanism reads its zero rather than an absence.
 */
public enum RunCounter {
    /** Activities the MOP launcher launched. */
    LAUNCHES("launches"),
    /** Steps on which the minimum-actions gate barred the LLM. */
    GATE_MIN_ACTIONS("gateMinActions"),
    /** Steps on which the circuit breaker barred the LLM. */
    GATE_BREAKER("gateBreaker"),
    /** BACKs the foreign-activity or tree-package guard sent. */
    GUARD_BACKS("guardBacks"),
    /** Non-empty SATA buffers a preempting owner abandoned. */
    BUF_ABANDONED("bufAbandoned"),
    /** Restarts the RESTART guard dispatched. */
    RESTARTS("restarts"),
    /** Steps on which the LLM coin was won. */
    COIN_LLM("coinLlm"),
    /** Steps on which the MOP coin was won. */
    COIN_MOP("coinMop");

    private final String wire;

    RunCounter(String wire) {
        this.wire = wire;
    }

    /** The member name in {@code RUN_END.counters}. */
    public String wire() {
        return wire;
    }
}

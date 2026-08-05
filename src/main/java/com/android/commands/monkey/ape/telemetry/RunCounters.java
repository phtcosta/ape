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
 * What the run's LLM did, totalled — the content of the retired {@code [APE-RV] LLM Summary} line,
 * carried to {@code RUN_END} as a value instead of printed as text.
 *
 * <p><b>These are the LLM's counters and only those.</b> The step and dictionary counts that
 * {@code RUN_END} also carries are not here: the sink maintains them itself and is the only thing
 * that knows them, so routing them out to teardown and back in would be indirection with one
 * subscriber. A plan with no LLM produces no instance at all — the {@code counters} object then has
 * no {@code llm} member, which is a different statement from seventeen zeros and the one a reader
 * needs, since a zeroed block would read as an LLM that was asked nothing rather than an arm that
 * has none.
 *
 * <p><b>Nothing derived is stored.</b> The decision ratio the retired line printed is
 * {@code matched / (matched + llmTap + noMatch + failures())}, and a consumer computes it from
 * these fields; storing it would freeze one definition of the denominator into every trace, and
 * that denominator has already changed once in this study's lifetime.
 *
 * <p>The fields are public and mutable because this is a filled record, not an invariant-bearing
 * object: {@code LlmTelemetry} builds one at teardown and the sink reads it once. A seventeen-
 * parameter constructor would be the alternative, and a positional argument list of seventeen ints
 * is a defect waiting for its first reorder.
 */
public final class RunCounters {

    /** Attempts made, counted before anything about them is known (INV-RTR-07). */
    public int calls;

    public int tokensIn;
    public int tokensOut;
    public long timeMs;

    /** Answers that resolved to a widget action. */
    public int matched;

    /** Synthesized off-tree coordinate taps, a completed outcome inside the decisions total. */
    public int llmTap;

    /** Completed decisions that selected nothing, the dead-pair refusals among them. */
    public int noMatch;

    /** Overlay on {@code noMatch}: the ban's falsification gate, readable without a join. */
    public int deadPair;

    /** Overlay on the three outcomes: decisions whose tool call needed a parser repair. */
    public int repaired;

    // The seven cause counters partition the attempts abandoned before the mapping step
    // (INV-RTR-11): exactly one fires per abandoned attempt, and screenshotFailed is a peer of the
    // other six rather than a subset of any aggregate, so per-app FLAG_SECURE degradation stays
    // countable.
    public int timeout;
    public int httpError;
    public int connError;
    public int parseError;
    public int imageError;
    public int internalError;
    public int screenshotFailed;

    /** How many times the run's circuit breaker opened. */
    public int breakerTrips;

    /**
     * The abandoned attempts, as the partition of INV-RTR-11 defines them.
     *
     * <p>Derived rather than stored, and defined here rather than at each reader: the invariant is
     * that these seven counters partition the abandoned attempts, and a sum written out at three
     * call sites is three places for that partition to fall out of step with the counters.
     *
     * @return the sum of the seven cause counters
     */
    public int failures() {
        return timeout + httpError + connError + parseError + imageError + internalError
                + screenshotFailed;
    }
}

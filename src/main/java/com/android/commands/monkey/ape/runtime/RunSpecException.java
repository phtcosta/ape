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
package com.android.commands.monkey.ape.runtime;

/**
 * The single failure type of plan resolution.
 *
 * <p>Every invalid configuration input raises this and nothing catches it into a degraded run: the
 * bootstrap prints one {@code [APE-RUNSPEC-ABORT]} line and exits nonzero before the first
 * exploration step. That is the whole point of the type — the alternative it replaces is
 * {@code Config}'s empty {@code catch (NumberFormatException)} blocks, where a typo became a
 * default and the run continued reporting numbers nobody could attribute.
 *
 * <p>The three members are what the abort line carries: {@code reason} classifies the failure for
 * a machine, {@code key} names the offending property (null when the failure is about the command
 * line rather than a key), and {@code detail} is the sentence a human reads.
 */
public final class RunSpecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The failure classes of {@code RunSpec.resolve}. The wire name is what the abort line prints,
     * so these strings are part of the diagnostic contract.
     */
    public enum Reason {
        /** An {@code ape.*} key that no owner declares. */
        UNKNOWN_KEY,
        /** A key in a properties file that does not start with {@code ape.}. */
        FOREIGN_KEY,
        /** A key whose mechanism this re-architecture deleted. */
        RETIRED_KEY,
        /** A value that does not parse as its key's declared type. */
        INVALID_TYPE,
        /** An explicitly-stated feature whose declared dependencies are not met. */
        MISSING_DEPENDENCY,
        /** A combination the plan cannot express (currently: replay without a log). */
        INVALID_COMBINATION,
        /** An {@code ape.preset} value outside the four jar-resident presets. */
        UNKNOWN_PRESET,
        /** An {@code --ape} value outside {sata, random, replay}. */
        UNKNOWN_AGENT_TYPE;

        /** The lowercase form printed as {@code reason=} on the abort line. */
        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Reason reason;
    private final String key;
    private final String detail;

    public RunSpecException(Reason reason, String key, String detail) {
        super("reason=" + reason.wireName() + " key=" + key + " detail=" + detail);
        this.reason = reason;
        this.key = key;
        this.detail = detail;
    }

    public Reason getReason() {
        return reason;
    }

    /** The offending property key, or null when the failure is about a command-line value. */
    public String getKey() {
        return key;
    }

    public String getDetail() {
        return detail;
    }

    /** The abort line's body, without the {@code [APE-RUNSPEC-ABORT]} tag the bootstrap adds. */
    public String toDiagnostic() {
        return "reason=" + reason.wireName() + " key=" + key + " detail=" + detail;
    }
}

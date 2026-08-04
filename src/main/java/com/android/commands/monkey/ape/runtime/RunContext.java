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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

import com.android.commands.monkey.ape.utils.RandomHelper;

/**
 * What this run is: its plan, its identity, and its randomness.
 *
 * <p>Three things belong here and nothing else does. The resolved {@link RunSpec}, which is the
 * only authority on what the run was asked to do. The {@code runId}, which is what a trace, a
 * result row and a log line join on. And the seeded random stream — this class performs the single
 * {@link RandomHelper#seed} call of the process, so there is one place that decides where the
 * run's randomness comes from instead of a seeding call sitting in the middle of a bootstrap
 * method.
 *
 * <p><b>This is still a static holder, and it is worth saying so plainly.</b> The gain it delivers
 * is one static holder in place of a hundred-odd static configuration fields and four static file
 * readers, and what makes that safe is not this class: it is that every run gets its own process,
 * so there is never a second run to be confused with. Stage 3 threads the context through
 * constructors and moves the remaining mutable state — the model, the graph, the trackers, the LLM
 * client — into it, at which point the holder stops being the access path. Until then, code that
 * this stage did not touch still reads static {@code Config} finals; what changed is that
 * {@link RunSpec#resolve} decided what those values were allowed to be before anything read them.
 */
public final class RunContext {

    /** Compact UTC stamp for the generated run id: sortable, no separators to strip. */
    private static final String RUN_ID_TIMESTAMP = "yyyyMMdd'T'HHmmss'Z'";

    /** How much of the plan digest the generated run id carries — enough to eyeball an arm. */
    private static final int RUN_ID_DIGEST_PREFIX = 8;

    private static RunContext current;

    private final RunSpec spec;
    private final String runId;

    private RunContext(RunSpec spec, long seed) {
        this.spec = spec;
        // Seeding belongs to constructing the context, not to the caller, so a context cannot
        // exist over an unseeded stream (INV-RUN-08, INV-EXPL-14). The value is the same seed
        // Monkey's own Random was built from, so both streams derive from one number.
        RandomHelper.seed(seed);
        this.runId = resolveRunId(spec, seed);
    }

    /**
     * Establishes the run's context, once.
     *
     * @param spec the resolved plan
     * @param seed the run's seed — the value {@code Monkey}'s own generator was built from
     * @throws IllegalStateException on a second call in one process; a run that re-established its
     *         identity or re-seeded its randomness half way through would produce a trace whose
     *         first line no longer described it
     */
    public static void initialize(RunSpec spec, long seed) {
        if (current != null) {
            throw new IllegalStateException(
                    "the run context is already established as " + current.runId
                            + "; a process is one run");
        }
        current = new RunContext(spec, seed);
    }

    /**
     * The current run's context.
     *
     * @return the context established by {@link #initialize}
     * @throws IllegalStateException if no context has been established, which means a read site
     *         ran before the plan was resolved — an ordering bug, not a missing default
     */
    public static RunContext current() {
        if (current == null) {
            throw new IllegalStateException(
                    "no run context: the plan is resolved before anything reads it");
        }
        return current;
    }

    /**
     * Installs a context for a JVM test, replacing any current one.
     *
     * <p>This is what lets a test state the plan it wants as a {@link RunSpec} value instead of
     * writing property files or mutating static fields — the path the tests of plan-controlled
     * parameters take.
     *
     * @param spec the plan the test wants in effect; its own seed seeds the stream, so an
     *        installed context is a real context and not a weaker variant of one
     */
    public static void installForTest(RunSpec spec) {
        current = new RunContext(spec, spec.seed());
    }

    /**
     * Clears the context so the next {@link #initialize} is a first call.
     *
     * <p>Test-only, and it exists for one reason: the once-only contract is only observable from a
     * known-empty starting point, and JVM tests share a process the way a run never does.
     */
    public static void resetForTest() {
        current = null;
    }

    /** The plan this run resolved, and the only authority on what it was asked to do. */
    public RunSpec spec() {
        return spec;
    }

    /**
     * This run's identity: the harness-supplied {@code ape.runId} when there is one, otherwise
     * generated as {@code <utc>-<seed>-<digest prefix>}.
     */
    public String runId() {
        return runId;
    }

    /**
     * The run's seeded random stream — the one every decision and every generated input draws
     * from (INV-RUN-08).
     */
    public Random rng() {
        return RandomHelper.getRandom();
    }

    /**
     * The run id, generated when the harness supplied none.
     *
     * <p>The three parts each answer a question that has cost this study time: <em>when</em>, so
     * runs of one campaign sort; <em>which seed</em>, so the run is reproducible from its own id;
     * and <em>which arm</em>, since the digest prefix is shared by every run of the same plan and
     * differs the moment the plan does. A blank value is treated as absent — a harness that pushes
     * an empty {@code ape.runId} has supplied nothing, and an identity of {@code ""} would join
     * every such run to every other.
     */
    private static String resolveRunId(RunSpec spec, long seed) {
        String supplied = spec.runId();
        if (supplied != null && !supplied.trim().isEmpty()) {
            return supplied.trim();
        }
        SimpleDateFormat stamp = new SimpleDateFormat(RUN_ID_TIMESTAMP, Locale.ROOT);
        stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        return stamp.format(new Date()) + "-" + seed + "-"
                + spec.digest().substring(0, RUN_ID_DIGEST_PREFIX);
    }
}

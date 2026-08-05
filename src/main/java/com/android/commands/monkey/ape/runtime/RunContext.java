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

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.CoordinateMapper;
import com.android.commands.monkey.ape.llm.LlmClient;
import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.llm.LlmTelemetry;
import com.android.commands.monkey.ape.llm.ScreenshotStep;
import com.android.commands.monkey.ape.llm.ToolCallParser;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.telemetry.NdjsonSink;
import com.android.commands.monkey.ape.utils.RandomHelper;

/**
 * What this run is: its plan, its identity, its randomness, and — when the plan asks for one — its
 * LLM.
 *
 * <p>Four things belong here and nothing else does. The resolved {@link RunSpec}, which is the
 * only authority on what the run was asked to do. The {@code runId}, which is what a trace, a
 * result row and a log line join on. The seeded random stream — this class performs the single
 * {@link RandomHelper#seed} call of the process, so there is one place that decides where the
 * run's randomness comes from instead of a seeding call sitting in the middle of a bootstrap
 * method. And the LLM units, which are run-scoped state with no agent in them: a breaker, a ban
 * record and a counter set all live exactly as long as the process does, and putting them here is
 * what lets a stage hold the one it uses instead of reaching through the agent for it.
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

    /** The run ended on its own terms: the time budget expired, or the source signalled a stop. */
    public static final String REASON_TIMEOUT = "timeout";

    /** A {@code Throwable} escaped the exploration loop; its class name travels as the detail. */
    public static final String REASON_CRASH = "crash";

    /**
     * Nothing said how the run ended.
     *
     * <p>The value a run carries until something decides otherwise, and a real outcome rather than
     * a placeholder: a run that exhausted an event count, or was aborted, ends without any of the
     * two sites below firing, and reporting that as a timeout would be a claim the jar cannot make.
     */
    public static final String REASON_UNKNOWN = "unknown";

    /** Compact UTC stamp for the generated run id: sortable, no separators to strip. */
    private static final String RUN_ID_TIMESTAMP = "yyyyMMdd'T'HHmmss'Z'";

    /** How much of the plan digest the generated run id carries — enough to eyeball an arm. */
    private static final int RUN_ID_DIGEST_PREFIX = 8;

    private static RunContext current;

    private final RunSpec spec;
    private final String runId;

    /**
     * Device epoch milliseconds at the moment this run began.
     *
     * <p>It is the run's single time origin: {@code RUN_START} publishes it as {@code t0} and every
     * step record's {@code t} is measured from it, so the two agree by construction rather than by
     * two reads of the clock landing close enough. A reader recovers an absolute stamp as
     * {@code t0 + t} and needs nothing outside the trace to do it.
     */
    private final long t0;

    /**
     * Where this run's observations go. Always present and identical for every arm — telemetry is
     * an instrument, not a mechanism — and unable to influence a decision by the shape of its own
     * interface (INV-SNK-07).
     */
    private final EventSink sink;

    /**
     * The run's LLM, or four nulls when the plan carries no LLM feature.
     *
     * <p>Absence is the whole mechanism, not a disabled mode: a plan without the feature builds no
     * unit, assembles no LLM stage (INV-DP-03), and emits no LLM trace line. There is nothing to
     * ask whether it is on, because there is nothing there.
     */
    private final LlmClient llmClient;
    private final CoordinateMapper coordinateMapper;
    private final LlmTelemetry llmTelemetry;
    private final LlmEngine llmEngine;

    /**
     * How this run ended, for the {@code RUN_END} record teardown writes.
     *
     * <p>It lives here because the two sites that know are not the site that writes: the time
     * budget expires inside {@code Monkey}'s cycle loop and a {@code StopTestingException} is
     * caught in the event source, while the record is written by the agent's last teardown step.
     * Threading it through would mean a termination-reason parameter on {@code Agent.tearDown} and
     * therefore on every agent, none of which has anything to do with the answer.
     */
    private String terminationReason = REASON_UNKNOWN;

    /** The crash's exception class name, or null on every other ending. */
    private String terminationDetail;

    private RunContext(RunSpec spec, long seed) {
        this.spec = spec;
        // Seeding belongs to constructing the context, not to the caller, so a context cannot
        // exist over an unseeded stream (INV-RUN-08, INV-EXPL-14). The value is the same seed
        // Monkey's own Random was built from, so both streams derive from one number.
        RandomHelper.seed(seed);
        this.runId = resolveRunId(spec, seed);
        this.t0 = System.currentTimeMillis();
        // Built before the LLM units because they record through it, and handed to them rather
        // than looked up: this constructor has not yet returned, so `current` is still null and a
        // unit that reached for it here would find nothing.
        this.sink = new NdjsonSink(System.out);

        if (spec.has(Feature.LLM)) {
            RunSpec.LlmParams llm = spec.llm();
            this.llmClient = new LlmClient(llm, sink);
            // The trip count is asked for rather than mirrored: it belongs to the client, and only
            // a recorded failure can raise it. The dump flag reads on when the plan carries no
            // telemetry scope, which is what its default says anyway.
            this.llmTelemetry = new LlmTelemetry(llmClient::getTripCount, sink,
                    spec.telemetry() == null || spec.telemetry().bool("ape.llmPromptDump"));
            this.coordinateMapper = new CoordinateMapper(llm);
            this.llmEngine = new LlmEngine(new ScreenshotStep(), new ApePromptBuilder(llm.promptVariant()), llmClient,
                    new ToolCallParser(), coordinateMapper, llmTelemetry);
        } else {
            this.llmClient = null;
            this.llmTelemetry = null;
            this.coordinateMapper = null;
            this.llmEngine = null;
        }
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
     * The run's time origin in device epoch milliseconds — the base {@code RUN_START} publishes and
     * every step record's {@code t} is relative to.
     */
    public long t0() {
        return t0;
    }

    /** Milliseconds elapsed since the run began, which is what a step record stamps as {@code t}. */
    public long elapsedMs() {
        return System.currentTimeMillis() - t0;
    }

    /** Where this run's observations go, for every arm alike. */
    public EventSink sink() {
        return sink;
    }

    /**
     * Records how the run ended, for the {@code RUN_END} record.
     *
     * <p>Last writer wins, and the two callers cannot both fire: an orderly stop leaves the cycle
     * loop by a break and a crash leaves it by a throw. The value is write-only inside the jar —
     * nothing reads it but the teardown step that emits the record.
     *
     * @param reason one of {@link #REASON_TIMEOUT}, {@link #REASON_CRASH}, {@link #REASON_UNKNOWN}
     * @param detail the crash's exception class name, or {@code null}
     */
    public void terminated(String reason, String detail) {
        this.terminationReason = reason;
        this.terminationDetail = detail;
    }

    /** How the run ended — {@link #REASON_UNKNOWN} until something says otherwise. */
    public String terminationReason() {
        return terminationReason;
    }

    /** What ended it, when that is a crash: the exception's class name. Otherwise null. */
    public String terminationDetail() {
        return terminationDetail;
    }

    /**
     * The run's seeded random stream — the one every decision and every generated input draws
     * from (INV-RUN-08).
     */
    public Random rng() {
        return RandomHelper.getRandom();
    }

    /** Whether this run has an LLM at all — equivalently, whether the plan carries the feature. */
    public boolean hasLlm() {
        return llmEngine != null;
    }

    /**
     * The orchestrator the three LLM stages call, or null on a plan with no LLM feature — in which
     * case no stage asks, because none was assembled.
     */
    public LlmEngine llmEngine() {
        return llmEngine;
    }

    /** The run's transport and circuit breaker, whose {@code allows()} gates every LLM hook. */
    public LlmClient llmClient() {
        return llmClient;
    }

    /** The mapping and the dead-pair ban record, which the agent feeds executed outcomes to. */
    public CoordinateMapper coordinateMapper() {
        return coordinateMapper;
    }

    /** The run's LLM counters and lines, which print their summary at teardown. */
    public LlmTelemetry llmTelemetry() {
        return llmTelemetry;
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

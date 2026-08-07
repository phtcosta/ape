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

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import android.util.Log;

import com.android.commands.monkey.ape.runtime.Json;
import com.android.commands.monkey.ape.utils.Logger;

/**
 * The sink that writes the trace: one NDJSON record per line, on stdout, and nothing else.
 *
 * <p><b>Where the records go.</b> Straight to the stream, never through {@link Logger} (INV-SNK-11).
 * That is not a style preference: every {@code Logger} line begins with {@code "[APE] "} and every
 * record here begins with <code>{</code>, so a trace line is a record if and only if it starts with
 * a brace. The two streams share one file and stay mechanically separable, which is what lets the
 * free-text diagnostics survive this change untouched instead of having to be converted.
 *
 * <p><b>Why a record is written when it is.</b> A step's outcome does not exist until the next
 * step's graph update — the sink keeps exactly one pending record, and the write happens at
 * closure. There are three ways a record closes, and the trace distinguishes all three because the
 * difference is real: with an {@code out} member when the outcome resolved; <em>without</em> one
 * when it legitimately never did (a restart, a non-model action, a refinement discard — the cases
 * where no {@code [APE-OUTCOME]} line was emitted either); and with {@code out:{"resolved":false}}
 * when teardown flushed a step that was still in flight. Collapsing the last two would report a cut
 * run and an ordinary unattributed step as the same thing.
 *
 * <p><b>Dictionaries.</b> Activity and state strings are the longest, most repeated content in the
 * trace, so each is emitted once as an {@code ACT}/{@code STATE} record and referenced afterwards
 * by a run-local integer. The ordering invariant (INV-SNK-06) is structural rather than checked:
 * interning happens before the record that references the id is written, so a definition can never
 * follow its reference. The static per-activity MOP fact rides the {@code ACT} entry, which is why
 * it stops being repeated on every step and every outcome.
 *
 * <p><b>Failure.</b> No method here can throw into the exploration loop (INV-SNK-12). The first
 * internal failure latches the sink off for the rest of the run and logs one warning — a telemetry
 * defect must cost the run its telemetry, never its result. The serializer's correctness is carried
 * by its permanent tests, not by runtime defensiveness.
 */
public final class NdjsonSink implements EventSink {

    /**
     * The logcat tag of the heartbeat, and a cross-repository constant rather than a local choice.
     *
     * <p>rv-platform does not dump the ring buffer after a run; it streams
     * {@code adb logcat -v <fmt> -s RVSEC:V RVSEC-COV:V …} for the run's duration, a strict tag
     * allowlist. A heartbeat under any other tag is dropped at the device and never reaches the file
     * the analysis join reads — inert while looking healthy. The counterpart repository therefore
     * declares the same literal once, as {@code TAG_APERV_HEARTBEAT = "ApeRvHb"} in
     * {@code rv-android/modules/rv-platform/.../logcat} ({@code LogcatManager.default_tags}, rvsec
     * commit {@code eb058ca4}), and {@code clock_logcat_join.py} matches the line against it.
     * <b>The two literals have to be compared by a human</b>, because a mismatch fails silently on
     * both sides: the device drops the lines, the capture file looks ordinary, and the join quietly
     * falls back to the clock reconstruction the heartbeat exists to retire (INV-SNK-14).
     */
    public static final String HEARTBEAT_TAG = "ApeRvHb";

    private final PrintStream out;

    /**
     * Whether each step writes its logcat heartbeat ({@code ape.telemetryHeartbeat}, default on).
     *
     * <p>The heartbeat exists so that a violation recorded by the instrumented APK and the step that
     * caused it sit in one file on one clock, which is what lets the analysis join them without
     * reconstructing the device's UTC offset. It is write-only in the strongest sense: the jar never
     * reads logcat under any configuration (INV-SNK-10).
     */
    private final boolean heartbeat;

    /**
     * Set when a heartbeat write fails, which stops the heartbeat and nothing else.
     *
     * <p>Deliberately not the sink's own latch. A logcat write failing is a device-side condition
     * with no bearing on the trace, and the trace is the primary artifact — losing it because
     * logcat hiccuped would trade the record for the convenience built on top of it. The failure is
     * reported once rather than per step, because a silently absent heartbeat is exactly the mode
     * INV-SNK-14 exists to prevent.
     */
    private boolean heartbeatFailed;

    /** The record under construction, reused across the run; the accumulator is {@link #pending}. */
    private final Json.Buf recordBuf = new Json.Buf();

    /** A second buffer for dictionary and run-level records, which interleave with a pending step. */
    private final Json.Buf sideBuf = new Json.Buf();

    /**
     * The single open record. One instance for the whole run rather than one per step: it is reset
     * after each write, so the sink allocates nothing per step on the exploration's hot path.
     */
    private final StepRecord pending = new StepRecord();

    /** Activity name to run-local {@code ACT} id, insertion-ordered so ids match emission order. */
    private final Map<String, Integer> activityIds = new LinkedHashMap<String, Integer>();

    /** State key to run-local {@code STATE} id, insertion-ordered so ids match emission order. */
    private final Map<String, Integer> stateIds = new LinkedHashMap<String, Integer>();

    /** Latched by the first internal failure; never cleared, so the run loses its trace once. */
    private boolean disabled;

    /**
     * Step records written so far, which is what {@code RUN_END} reports as {@code steps}. Counted
     * here rather than at teardown because this is the only place that knows a record was actually
     * written — a count taken from the agent's step number would report steps attempted.
     */
    private int stepRecords;

    /**
     * The {@code t} of the first and last step records written, which {@code RUN_END} carries.
     *
     * <p>Their span against the run's budget is what separates a run that explored for its whole
     * budget from one that was alive and idle: the decisive campaign contains a run recorded
     * {@code COMPLETED} with 1,871 s of execution time whose 150 step records span 446 s. Taken at
     * write time rather than at {@code beginStep} so they describe records the trace actually
     * contains.
     */
    private long firstStepT;
    private long lastStepT;

    /** Whether {@code LLM_ACK} has been written, which caps it at one record per run. */
    private boolean acknowledged;

    /**
     * Creates a sink writing to the given stream, with the heartbeat on.
     *
     * @param out the stream the trace is captured from — {@code System.out} in a run, a buffer in a
     *        test. It is a constructor parameter for exactly that reason: the alternative is tests
     *        that reach for the process's stdout, which is shared with everything else the run
     *        prints.
     */
    public NdjsonSink(PrintStream out) {
        this(out, true);
    }

    /**
     * Creates a sink writing to the given stream.
     *
     * @param out the stream the trace is captured from
     * @param heartbeat whether each step writes its logcat line ({@code ape.telemetryHeartbeat})
     */
    public NdjsonSink(PrintStream out, boolean heartbeat) {
        this.out = out;
        this.heartbeat = heartbeat;
    }

    @Override
    public void beginStep(int step, long tRelMs, String activity, boolean activityHasMop,
            String stateKey) {
        if (disabled) {
            return;
        }
        try {
            if (pending.isOpen() && pending.step() == step) {
                // Selection re-entered the same step: the BadStateException retry runs selection
                // again without advancing the agent timestamp. One step is one record whatever
                // selection did internally (INV-SNK-03), so the open record keeps accumulating —
                // its LLM attempts append to the same array and the final decision overwrites the
                // abandoned one, which is what "the action that was finally selected" means.
                return;
            }
            if (pending.isOpen()) {
                writePending();
            }
            int actId = internActivity(activity, activityHasMop);
            pending.open(step, tRelMs, actId, internState(stateKey, actId));
            writeHeartbeat(step, tRelMs);
        } catch (Throwable failure) {
            latch("beginStep", failure);
        }
    }

    /**
     * Writes the step's logcat line, on the same {@code t} the record carries.
     *
     * <p>Sharing the value rather than reading a clock again is the whole mechanism: the join is
     * exact because the two stamps are one number, not two readings a few milliseconds apart.
     *
     * <p>Its own guard, inside the sink's: a heartbeat is a convenience for the analysis side and
     * the trace is the artifact, so a logcat failure costs the heartbeat and never the record.
     */
    private void writeHeartbeat(int step, long tRelMs) {
        if (!heartbeat || heartbeatFailed) {
            return;
        }
        try {
            Log.i(HEARTBEAT_TAG, "s=" + step + " t=" + tRelMs);
        } catch (Throwable failure) {
            heartbeatFailed = true;
            Logger.wformat("heartbeat disabled for the rest of the run: %s", failure);
        }
    }

    @Override
    public void decision(String action, String decisionSource, String pickChannel, int priority,
            int mop, int mopFrontier, int wtg, int coverage, int menu, int form,
            String wtgSource, int patched, int cfChanged, String cfAction) {
        if (disabled || !pending.isOpen()) {
            return;
        }
        try {
            pending.decision(action, decisionSource, pickChannel, priority, mop, mopFrontier, wtg,
                    coverage, menu, form, wtgSource, patched, cfChanged, cfAction);
        } catch (Throwable failure) {
            latch("decision", failure);
        }
    }

    @Override
    public void decisionNonModel(String action, String decisionSource, String pickChannel) {
        if (disabled || !pending.isOpen()) {
            return;
        }
        try {
            pending.decisionNonModel(action, decisionSource, pickChannel);
        } catch (Throwable failure) {
            latch("decisionNonModel", failure);
        }
    }

    @Override
    public void mopExposure(int boosted, int total) {
        if (disabled || !pending.isOpen()) {
            return;
        }
        try {
            pending.mopExposure(boosted, total);
        } catch (Throwable failure) {
            latch("mopExposure", failure);
        }
    }

    @Override
    public void componentLaunch(int result, String error) {
        if (disabled || !pending.isOpen()) {
            return;
        }
        try {
            pending.componentLaunch(result, error);
        } catch (Throwable failure) {
            latch("componentLaunch", failure);
        }
    }

    @Override
    public void llmDump(String system, String user, String response, String toolCalls) {
        if (disabled || !pending.isOpen()) {
            return;
        }
        try {
            pending.stageDump(system, user, response, toolCalls);
        } catch (Throwable failure) {
            latch("llmDump", failure);
        }
    }

    @Override
    public void llmCall(int call, String mode, String tool, int qwenX, int qwenY, int pixelX,
            int pixelY, String result, String reason, String repair, String matchedClass,
            String nearestClass, double nearestDistance, int widgets, int tokensIn, int tokensOut,
            long ms, String text) {
        if (disabled || !pending.isOpen()) {
            return;
        }
        try {
            StepRecord.LlmEvent event = pending.nextLlmEvent();
            event.call = call;
            event.mode = mode;
            event.tool = tool;
            event.qwenX = qwenX;
            event.qwenY = qwenY;
            event.pixelX = pixelX;
            event.pixelY = pixelY;
            event.result = result;
            event.reason = reason;
            event.repair = repair;
            event.matchedClass = matchedClass;
            event.nearestClass = nearestClass;
            event.nearestDistance = nearestDistance;
            event.widgets = widgets;
            event.tokensIn = tokensIn;
            event.tokensOut = tokensOut;
            event.ms = ms;
            event.text = text;
        } catch (Throwable failure) {
            latch("llmCall", failure);
        }
    }

    @Override
    public void llmError(String cause, String detail) {
        if (disabled || !pending.isOpen()) {
            return;
        }
        try {
            StepRecord.LlmEvent event = pending.nextLlmEvent();
            event.result = "error";
            event.cause = cause;
            event.detail = detail;
        } catch (Throwable failure) {
            latch("llmError", failure);
        }
    }

    @Override
    public void llmBreakerOpen(int trips) {
        if (disabled || !pending.isOpen()) {
            return;
        }
        try {
            StepRecord.LlmEvent event = pending.nextLlmEvent();
            event.result = "breaker_open";
            event.trips = trips;
        } catch (Throwable failure) {
            latch("llmBreakerOpen", failure);
        }
    }

    @Override
    public void outcome(boolean newState, String targetStateKey, String targetActivity,
            boolean targetActivityHasMop, boolean activityChanged) {
        if (disabled || !pending.isOpen()) {
            return;
        }
        try {
            // Interning first is what keeps INV-SNK-06 true here: the target state can be seen for
            // the first time at this very moment, and its dictionary entry has to be on an earlier
            // line than the record that references it.
            int targetActId = internActivity(targetActivity, targetActivityHasMop);
            pending.outcome(newState, internState(targetStateKey, targetActId), activityChanged);
            writePending();
        } catch (Throwable failure) {
            latch("outcome", failure);
        }
    }

    @Override
    public void flushPendingStep() {
        if (disabled || !pending.isOpen()) {
            return;
        }
        try {
            pending.unresolved();
            writePending();
        } catch (Throwable failure) {
            latch("flushPendingStep", failure);
        }
    }

    @Override
    public void mopData(String status, String reason, int formatVersion, String sourceDigest,
            String pkg, int windows, int widgets, int flagged, int droppedNoId, int wtgEdges,
            int handlersUnmatched, int syntheticLambda, int recovered, int mopActivities,
            int mopActsAugmented, int components) {
        if (disabled) {
            return;
        }
        try {
            sideBuf.reset().beginObject();
            sideBuf.name("type").value("MOP_DATA");
            sideBuf.name("status").value(status);
            if (reason != null) {
                sideBuf.name("reason").value(reason);
            }
            sideBuf.name("formatVersion").value(formatVersion);
            // Omitted rather than emitted null, the same treatment reason and package get: a
            // digest is a fact about a derived artifact, and a load that never reached one has
            // no digest to report rather than a null one.
            if (sourceDigest != null) {
                sideBuf.name("sourceDigest").value(sourceDigest);
            }
            if (pkg != null) {
                sideBuf.name("package").value(pkg);
            }
            sideBuf.name("windows").value(windows);
            sideBuf.name("widgets").value(widgets);
            sideBuf.name("flagged").value(flagged);
            sideBuf.name("droppedNoId").value(droppedNoId);
            sideBuf.name("wtgEdges").value(wtgEdges);
            sideBuf.name("handlersUnmatched").value(handlersUnmatched);
            sideBuf.name("syntheticLambda").value(syntheticLambda);
            sideBuf.name("recovered").value(recovered);
            sideBuf.name("mopActivities").value(mopActivities);
            sideBuf.name("mopActsAugmented").value(mopActsAugmented);
            sideBuf.name("components").value(components);
            write(sideBuf.endObject().toLine());
        } catch (Throwable failure) {
            latch("mopData", failure);
        }
    }

    @Override
    public void pipeline(List<String> stages, List<String> passes, Map<String, Boolean> candidates) {
        if (disabled) {
            return;
        }
        try {
            sideBuf.reset().beginObject();
            sideBuf.name("type").value("PIPELINE");
            names(sideBuf, "stages", stages);
            names(sideBuf, "passes", passes);
            sideBuf.name("candidates").beginArray();
            if (candidates != null) {
                for (Map.Entry<String, Boolean> candidate : candidates.entrySet()) {
                    sideBuf.beginObject()
                            .name("name").value(candidate.getKey())
                            .name("enabled").value(Boolean.TRUE.equals(candidate.getValue()))
                            .endObject();
                }
            }
            sideBuf.endArray();
            write(sideBuf.endObject().toLine());
        } catch (Throwable failure) {
            latch("pipeline", failure);
        }
    }

    @Override
    public void llmAck(String serverModel) {
        if (disabled || acknowledged) {
            return;
        }
        try {
            acknowledged = true;
            sideBuf.reset().beginObject()
                    .name("type").value("LLM_ACK")
                    .name("server_model").value(serverModel)
                    .endObject();
            write(sideBuf.toLine());
        } catch (Throwable failure) {
            latch("llmAck", failure);
        }
    }

    @Override
    public void runEnd(String reason, String detail, RunCounters counters) {
        if (disabled) {
            return;
        }
        try {
            sideBuf.reset().beginObject();
            sideBuf.name("type").value("RUN_END");
            sideBuf.name("reason").value(reason);
            if (detail != null) {
                sideBuf.name("detail").value(detail);
            }
            sideBuf.name("steps").value(stepRecords);
            if (stepRecords > 0) {
                // Omitted rather than zeroed on a run that wrote no step: a run that never began
                // exploring has no first step, and a pair of zeros would say it began and ended at
                // the origin. Absence is the honest encoding, and INV-SNK-05's rule for it.
                sideBuf.name("t_first_step").value(firstStepT);
                sideBuf.name("t_last_step").value(lastStepT);
            }
            sideBuf.name("counters").beginObject();
            sideBuf.name("acts").value(activityIds.size());
            sideBuf.name("states").value(stateIds.size());
            if (counters != null) {
                writeLlmCounters(counters);
            }
            sideBuf.endObject();
            write(sideBuf.endObject().toLine());
        } catch (Throwable failure) {
            latch("runEnd", failure);
        }
    }

    /**
     * The LLM block of {@code RUN_END.counters} — the seventeen totals of the retired
     * {@code [APE-RV] LLM Summary} line, under the names the reader expects.
     *
     * <p>Written whole, with no default omission: a counter at zero is a measurement here, not an
     * absent field. "The run made no LLM call that timed out" and "this trace does not say" are
     * different claims, and the block is one record per run rather than one per step, so the volume
     * rule that motivates omission elsewhere buys nothing.
     */
    private void writeLlmCounters(RunCounters counters) {
        sideBuf.name("llm").beginObject();
        sideBuf.name("calls").value(counters.calls);
        sideBuf.name("tok_in").value(counters.tokensIn);
        sideBuf.name("tok_out").value(counters.tokensOut);
        sideBuf.name("ms").value(counters.timeMs);
        sideBuf.name("matched").value(counters.matched);
        sideBuf.name("llm_tap").value(counters.llmTap);
        sideBuf.name("no_match").value(counters.noMatch);
        sideBuf.name("dead_pair").value(counters.deadPair);
        sideBuf.name("repaired").value(counters.repaired);
        sideBuf.name("timeout").value(counters.timeout);
        sideBuf.name("http_error").value(counters.httpError);
        sideBuf.name("conn_error").value(counters.connError);
        sideBuf.name("parse_error").value(counters.parseError);
        sideBuf.name("image_error").value(counters.imageError);
        sideBuf.name("internal_error").value(counters.internalError);
        sideBuf.name("screenshot_failed").value(counters.screenshotFailed);
        sideBuf.name("breaker_trips").value(counters.breakerTrips);
        sideBuf.endObject();
    }

    /** Serializes the open record, writes it, and returns the accumulator to its empty state. */
    private void writePending() {
        pending.writeTo(recordBuf);
        write(recordBuf.toLine());
        if (stepRecords == 0) {
            firstStepT = pending.tRelMs();
        }
        lastStepT = pending.tRelMs();
        stepRecords++;
        pending.reset();
    }

    /**
     * Returns the {@code ACT} id for an activity, writing its dictionary entry on first sight.
     *
     * <p>The write happens here, inside the lookup, so that no caller can reference an id whose
     * definition has not already been written (INV-SNK-06). The MOP flag is taken from this first
     * call only; it is a static per-activity fact and later calls cannot revise it.
     *
     * @param activity the activity name
     * @param hasMop the static per-activity MOP fact, recorded on the dictionary entry
     * @return the run-local activity id
     */
    private int internActivity(String activity, boolean hasMop) {
        Integer existing = activityIds.get(activity);
        if (existing != null) {
            return existing.intValue();
        }
        int id = activityIds.size();
        activityIds.put(activity, Integer.valueOf(id));
        sideBuf.reset().beginObject()
                .name("type").value("ACT")
                .name("id").value(id)
                .name("name").value(activity)
                .name("mop").value(hasMop ? 1 : 0)
                .endObject();
        write(sideBuf.toLine());
        return id;
    }

    /**
     * Returns the {@code STATE} id for a state key, writing its dictionary entry on first sight.
     *
     * <p>The entry names the activity it belongs to because that link is the reader's only route
     * from an outcome's target state to the MOP flag: {@code out.target → STATE.act → ACT.mop}.
     * Callers therefore intern the activity first and pass the resulting id here.
     *
     * @param stateKey the abstract state key
     * @param actId the {@code ACT} id of the activity this state belongs to
     * @return the run-local state id
     */
    private int internState(String stateKey, int actId) {
        Integer existing = stateIds.get(stateKey);
        if (existing != null) {
            return existing.intValue();
        }
        int id = stateIds.size();
        stateIds.put(stateKey, Integer.valueOf(id));
        sideBuf.reset().beginObject()
                .name("type").value("STATE")
                .name("id").value(id)
                .name("key").value(stateKey)
                .name("act").value(actId)
                .endObject();
        write(sideBuf.toLine());
        return id;
    }

    private static void names(Json.Buf buf, String field, List<String> values) {
        buf.name(field).beginArray();
        if (values != null) {
            for (String value : values) {
                buf.value(value);
            }
        }
        buf.endArray();
    }

    /**
     * One record, one write, one flush. The concatenation is deliberate: a {@code print} of the line
     * followed by a {@code print} of the terminator is two locks on the stream, and another thread's
     * output between them would split the record — the failure this format exists to make
     * impossible. The flush is what bounds loss at the pending record when the harness kills the
     * process on timeout, rather than at whatever the stream buffer happened to be holding.
     */
    private void write(String line) {
        out.print(line + "\n");
        out.flush();
    }

    /**
     * Disables the sink for the remainder of the run and logs the failure once.
     *
     * <p>Every entry point routes its {@code Throwable} here rather than rethrowing, which is how
     * INV-SNK-12 holds: a telemetry defect costs the run its telemetry and nothing else. The warning
     * goes through {@link Logger}, so it lands on the free-text side of the shared stream and cannot
     * be mistaken for a record.
     *
     * @param where the entry point that failed, named for the warning
     * @param failure the failure that latched the sink off
     */
    private void latch(String where, Throwable failure) {
        disabled = true;
        Logger.wformat("EventSink disabled for the rest of the run: %s failed with %s",
                where, failure);
    }
}

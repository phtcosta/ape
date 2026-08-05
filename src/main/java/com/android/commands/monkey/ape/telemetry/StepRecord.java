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

import java.util.ArrayList;
import java.util.List;

import com.android.commands.monkey.ape.runtime.Json;

/**
 * One step's record while it is still open, and the one place its schema is written down.
 *
 * <p>A step cannot be serialized as it happens: its outcome does not exist until the next step's
 * graph update, which is the fact the retired {@code [APE-STEP]}/{@code [APE-OUTCOME]} pair encoded
 * as two lines and a join key. So the fields accumulate here and the line is written once, at
 * closure — which is also what makes the join disappear rather than move.
 *
 * <p><b>The instance is reused, not reallocated.</b> The sink holds exactly one, and
 * {@link #reset()} returns it to empty for the next step; the LLM sub-events are pooled the same
 * way, since a run makes tens of thousands of them and none outlives its step. What is deliberately
 * not held is any reference into the model — no {@code State}, no {@code ModelAction}, no
 * {@code GUITree}. Strings and integers are copied in and that is all, so the sink cannot pin a
 * tree the memory work of stage 6 is trying to release, and cannot expose model state to a
 * decision path (INV-SNK-07).
 *
 * <p>{@link #writeTo(Json.Buf)} is where the volume rules live (INV-SNK-04/05): the envelope once,
 * a field omitted whenever it equals its documented default, and the two exemptions —
 * {@code patched} and {@code cf} — emitted whenever they are defined at all, because for those the
 * absence is the information.
 */
final class StepRecord {

    /** The record was closed with a resolved outcome, or with none, or by the teardown flush. */
    private static final int OUT_NONE = 0;
    private static final int OUT_RESOLVED = 1;
    private static final int OUT_UNRESOLVED = 2;

    private boolean open;

    // Envelope.
    private int step;
    private long tRelMs;
    private int actId;
    private int stateId;

    // Decision.
    private boolean hasDecision;
    private boolean modelAction;
    private String action;
    private String decisionSource;
    private String pickChannel;
    private int priority;
    private int mop;
    private int mopFrontier;
    private int wtg;
    private int coverage;
    private int menu;
    private int form;
    private String wtgSource;
    private int patched = EventSink.ABSENT;
    private int cfChanged = EventSink.ABSENT;
    private String cfAction;
    private int mopExposureBoosted = EventSink.ABSENT;
    private int mopExposureTotal = EventSink.ABSENT;
    private boolean hasComponentLaunch;
    private int componentResult;
    private String componentError;

    // LLM sub-events, and the dumps staged for the next one.
    private final List<LlmEvent> llm = new ArrayList<LlmEvent>();
    private int llmCount;
    private String stagedSystem;
    private String stagedUser;
    private String stagedResponse;
    private String stagedToolCalls;

    // Outcome.
    private int outKind = OUT_NONE;
    private boolean newState;
    private int targetStateId;
    private boolean activityChanged;

    boolean isOpen() {
        return open;
    }

    int step() {
        return step;
    }

    /** This record's {@code t}, which the sink keeps as the run's first and last step stamp. */
    long tRelMs() {
        return tRelMs;
    }

    void open(int step, long tRelMs, int actId, int stateId) {
        reset();
        this.open = true;
        this.step = step;
        this.tRelMs = tRelMs;
        this.actId = actId;
        this.stateId = stateId;
    }

    void decision(String action, String decisionSource, String pickChannel, int priority,
            int mop, int mopFrontier, int wtg, int coverage, int menu, int form,
            String wtgSource, int patched, int cfChanged, String cfAction) {
        this.hasDecision = true;
        this.modelAction = true;
        this.action = action;
        this.decisionSource = decisionSource;
        this.pickChannel = pickChannel;
        this.priority = priority;
        this.mop = mop;
        this.mopFrontier = mopFrontier;
        this.wtg = wtg;
        this.coverage = coverage;
        this.menu = menu;
        this.form = form;
        this.wtgSource = wtgSource;
        this.patched = patched;
        this.cfChanged = cfChanged;
        this.cfAction = cfAction;
    }

    void decisionNonModel(String action, String decisionSource, String pickChannel) {
        this.hasDecision = true;
        this.modelAction = false;
        this.action = action;
        this.decisionSource = decisionSource;
        this.pickChannel = pickChannel;
    }

    void mopExposure(int boosted, int total) {
        this.mopExposureBoosted = boosted;
        this.mopExposureTotal = total;
    }

    void componentLaunch(int result, String error) {
        this.hasComponentLaunch = true;
        this.componentResult = result;
        this.componentError = error;
    }

    void stageDump(String system, String user, String response, String toolCalls) {
        this.stagedSystem = system;
        this.stagedUser = user;
        this.stagedResponse = response;
        this.stagedToolCalls = toolCalls;
    }

    /**
     * Returns the next sub-event, recycled from the pool when this step has already used fewer than
     * the list holds, and consumes whatever dumps were staged for it.
     */
    LlmEvent nextLlmEvent() {
        LlmEvent event;
        if (llmCount < llm.size()) {
            event = llm.get(llmCount);
            event.reset();
        } else {
            event = new LlmEvent();
            llm.add(event);
        }
        llmCount++;
        event.system = stagedSystem;
        event.user = stagedUser;
        event.response = stagedResponse;
        event.toolCalls = stagedToolCalls;
        stagedSystem = null;
        stagedUser = null;
        stagedResponse = null;
        stagedToolCalls = null;
        return event;
    }

    void outcome(boolean newState, int targetStateId, boolean activityChanged) {
        this.outKind = OUT_RESOLVED;
        this.newState = newState;
        this.targetStateId = targetStateId;
        this.activityChanged = activityChanged;
    }

    /** Marks the record as cut mid-step: written by the teardown flush, never by a normal close. */
    void unresolved() {
        this.outKind = OUT_UNRESOLVED;
    }

    /**
     * Renders the record into the buffer, applying the volume rules. The buffer is reset first, so
     * the caller gets one complete line and the previous record cannot leak into this one.
     */
    void writeTo(Json.Buf buf) {
        buf.reset().beginObject();
        buf.name("s").value(step);
        buf.name("t").value(tRelMs);
        buf.name("act").value(actId);
        buf.name("st").value(stateId);

        if (hasDecision) {
            buf.name("dec").beginObject();
            buf.name("a").value(action);
            buf.name("src").value(decisionSource);
            buf.name("ch").value(pickChannel);
            if (modelAction) {
                buf.name("pri").value(priority);
                boost(buf, "mop", mop);
                boost(buf, "mopf", mopFrontier);
                boost(buf, "wtg", wtg);
                if (wtg != 0 && wtgSource != null) {
                    // Exactly when the boost is non-zero: with both producers configured at the
                    // same weight the value alone cannot say which of them wrote it.
                    buf.name("wtgsrc").value(wtgSource);
                }
                boost(buf, "cov", coverage);
                boost(buf, "menu", menu);
                boost(buf, "form", form);
                if (mopExposureTotal != EventSink.ABSENT) {
                    buf.name("mopx").beginArray()
                            .value(mopExposureBoosted).value(mopExposureTotal).endArray();
                }
                if (patched != EventSink.ABSENT) {
                    // Exempt from the defaults-omitted rule: `0` says the action had a resolved
                    // target that was not patch-promoted, absence says it had no resolved target,
                    // and collapsing the two would lose the distinction the field exists for.
                    buf.name("patched").value(patched);
                }
                if (cfChanged != EventSink.ABSENT) {
                    // Exempt for the same reason: present exactly on the four MOP-sensitive
                    // channels, so absence identifies the channel class rather than a default.
                    buf.name("cf").beginObject().name("changed").value(cfChanged);
                    if (cfAction != null) {
                        buf.name("a").value(cfAction);
                    }
                    buf.endObject();
                }
            }
            if (hasComponentLaunch) {
                buf.name("comp").beginObject().name("r").value(componentResult);
                if (componentError != null) {
                    buf.name("e").value(componentError);
                }
                buf.endObject();
            }
            buf.endObject();
        }

        if (llmCount > 0) {
            buf.name("llm").beginArray();
            for (int i = 0; i < llmCount; i++) {
                llm.get(i).writeTo(buf);
            }
            buf.endArray();
        }

        if (outKind == OUT_RESOLVED) {
            buf.name("out").beginObject();
            if (newState) {
                buf.name("new_state").value(true);
            }
            buf.name("target").value(targetStateId);
            if (activityChanged) {
                buf.name("act_changed").value(true);
            }
            buf.endObject();
        } else if (outKind == OUT_UNRESOLVED) {
            buf.name("out").beginObject().name("resolved").value(false).endObject();
        }

        buf.endObject();
    }

    /**
     * Returns the accumulator to empty. The sub-event pool survives — the objects are reused, their
     * contents are not: {@link #llmCount} is what the writer reads, so a stale entry beyond it can
     * never reach a record.
     */
    void reset() {
        open = false;
        step = 0;
        tRelMs = 0;
        actId = 0;
        stateId = 0;
        hasDecision = false;
        modelAction = false;
        action = null;
        decisionSource = null;
        pickChannel = null;
        priority = 0;
        mop = 0;
        mopFrontier = 0;
        wtg = 0;
        coverage = 0;
        menu = 0;
        form = 0;
        wtgSource = null;
        patched = EventSink.ABSENT;
        cfChanged = EventSink.ABSENT;
        cfAction = null;
        mopExposureBoosted = EventSink.ABSENT;
        mopExposureTotal = EventSink.ABSENT;
        hasComponentLaunch = false;
        componentResult = 0;
        componentError = null;
        llmCount = 0;
        stagedSystem = null;
        stagedUser = null;
        stagedResponse = null;
        stagedToolCalls = null;
        outKind = OUT_NONE;
        newState = false;
        targetStateId = 0;
        activityChanged = false;
    }

    private static void boost(Json.Buf buf, String name, int value) {
        if (value != 0) {
            buf.name(name).value(value);
        }
    }

    /**
     * One LLM routing attempt. Completed calls, attempts abandoned before they mapped, and the
     * once-per-episode breaker decline are all the same kind of thing here — an attempt with an
     * outcome — which is why {@code result} discriminates them instead of three record types.
     */
    static final class LlmEvent {

        int call;
        String mode;
        String tool;
        int qwenX = EventSink.ABSENT;
        int qwenY = EventSink.ABSENT;
        int pixelX = EventSink.ABSENT;
        int pixelY = EventSink.ABSENT;
        String result;
        String reason;
        String repair;
        String matchedClass;
        String nearestClass;
        double nearestDistance;
        int widgets;
        int tokensIn;
        int tokensOut;
        long ms;
        String text;
        String cause;
        String detail;
        int trips = EventSink.ABSENT;
        String system;
        String user;
        String response;
        String toolCalls;

        void writeTo(Json.Buf buf) {
            buf.beginObject();
            if (call > 0) {
                buf.name("call").value(call);
            }
            optional(buf, "mode", mode);
            optional(buf, "tool", tool);
            if (qwenX != EventSink.ABSENT || qwenY != EventSink.ABSENT) {
                buf.name("qwen").beginArray().value(qwenX).value(qwenY).endArray();
            }
            if (pixelX != EventSink.ABSENT || pixelY != EventSink.ABSENT) {
                buf.name("px").beginArray().value(pixelX).value(pixelY).endArray();
            }
            optional(buf, "result", result);
            optional(buf, "reason", reason);
            optional(buf, "repair", repair);
            optional(buf, "cause", cause);
            optional(buf, "detail", detail);
            if (trips != EventSink.ABSENT) {
                buf.name("trips").value(trips);
            }
            optional(buf, "mcls", matchedClass);
            optional(buf, "ncls", nearestClass);
            if (nearestDistance != 0.0d) {
                buf.name("ndist").value(nearestDistance);
            }
            if (widgets != 0) {
                buf.name("widgets").value(widgets);
            }
            if (tokensIn != 0 || tokensOut != 0) {
                buf.name("tok").beginArray().value(tokensIn).value(tokensOut).endArray();
            }
            if (ms != 0) {
                buf.name("ms").value(ms);
            }
            optional(buf, "text", text);
            optional(buf, "sys", system);
            optional(buf, "user", user);
            optional(buf, "resp", response);
            optional(buf, "tool_calls", toolCalls);
            buf.endObject();
        }

        void reset() {
            call = 0;
            mode = null;
            tool = null;
            qwenX = EventSink.ABSENT;
            qwenY = EventSink.ABSENT;
            pixelX = EventSink.ABSENT;
            pixelY = EventSink.ABSENT;
            result = null;
            reason = null;
            repair = null;
            matchedClass = null;
            nearestClass = null;
            nearestDistance = 0.0d;
            widgets = 0;
            tokensIn = 0;
            tokensOut = 0;
            ms = 0;
            text = null;
            cause = null;
            detail = null;
            trips = EventSink.ABSENT;
            system = null;
            user = null;
            response = null;
            toolCalls = null;
        }

        private static void optional(Json.Buf buf, String name, String value) {
            if (value != null) {
                buf.name(name).value(value);
            }
        }
    }
}

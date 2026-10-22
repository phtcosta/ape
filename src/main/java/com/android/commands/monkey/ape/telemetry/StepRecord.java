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
import java.util.Arrays;
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
 * a field omitted whenever it equals its documented default, and the exemptions — {@code patched}
 * and {@code mopd}, emitted whenever they are defined, because for those the absence is the
 * information, and {@code own} and {@code rot}, written on every record that has a {@code dec}.
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
    private int navFrontier;
    private int coverage;
    private int menu;
    private int form;
    private int patched = EventSink.ABSENT;
    private int mopExposureWeighted = EventSink.ABSENT;
    private int mopExposureTotal = EventSink.ABSENT;
    private boolean hasComponentLaunch;
    private int componentResult;
    private String componentError;
    private int mopDistance = EventSink.ABSENT;
    private String mopSource;
    /** {@code dec.hnd}: the stamped handler behind the MOP weight; null unless {@code mops} is s. */
    private String handler;
    /** {@code dec.ret}: ascending, each index once; null when the step retired nothing. */
    private int[] retired;

    // The routing and selection members of isolate-guidance-groups (design § Telemetry contract).
    private String ownIntended;
    private String ownTrigger;
    private double ownUL = Double.NaN;
    private double ownUM = Double.NaN;
    private String ownGate;
    private String ownFallback;
    /** {@code dec.cfc}, in arrival order; parallel lists rather than a map, as the pool reuses them. */
    private final List<String> cfcScorers = new ArrayList<String>();
    private final List<Boolean> cfcChanged = new ArrayList<Boolean>();
    private int drawIndex = EventSink.ABSENT;
    private int drawTotal = EventSink.ABSENT;
    private String navPoint;
    private String navVariant;
    private boolean navAlt;
    private boolean navDrew;
    private String inputOrigin;
    private String restartKind;
    private int backMenuCapped;
    private String strategy;
    private boolean hasRotation;
    private int rotation;
    private String bufferOwner;
    private String triggerKind;
    private String triggerClass;

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
            int mop, int mopFrontier, int wtg, int navFrontier, int coverage, int menu, int form,
            int patched, int mopDistance, String mopSource, String handler) {
        this.hasDecision = true;
        this.modelAction = true;
        this.action = action;
        this.decisionSource = decisionSource;
        this.pickChannel = pickChannel;
        this.priority = priority;
        this.mop = mop;
        this.mopFrontier = mopFrontier;
        this.wtg = wtg;
        this.navFrontier = navFrontier;
        this.coverage = coverage;
        this.menu = menu;
        this.form = form;
        this.patched = patched;
        this.mopDistance = mopDistance;
        this.mopSource = mopSource;
        this.handler = handler;
    }

    void decisionNonModel(String action, String decisionSource, String pickChannel) {
        this.hasDecision = true;
        this.modelAction = false;
        this.action = action;
        this.decisionSource = decisionSource;
        this.pickChannel = pickChannel;
    }

    /**
     * Replaces the step's decision with a non-model one: a post-selection restart replaced the
     * selected action. The replaced decision's priority, scorer and MOP members, its selection
     * members ({@code cfc}, {@code draw}, {@code nav}, {@code strat}, {@code bmc}, {@code buf}) and
     * its routing claim ({@code own.trg}, {@code g}, {@code fb}) are dropped, and {@code own.i}
     * becomes this decision's owner. The step-open coin draws, the LLM attempts, the component
     * trigger and {@code dec.restart} stay.
     */
    void decisionReplaced(String action, String decisionSource, String pickChannel) {
        dropReplacedDecision(decisionSource);
        decisionNonModel(action, decisionSource, pickChannel);
    }

    private void dropReplacedDecision(String owner) {
        priority = 0;
        mop = 0;
        mopFrontier = 0;
        wtg = 0;
        navFrontier = 0;
        coverage = 0;
        menu = 0;
        form = 0;
        patched = EventSink.ABSENT;
        mopExposureWeighted = EventSink.ABSENT;
        mopExposureTotal = EventSink.ABSENT;
        mopDistance = EventSink.ABSENT;
        mopSource = null;
        handler = null;
        retired = null;
        ownIntended = owner;
        ownTrigger = null;
        ownGate = null;
        ownFallback = null;
        cfcScorers.clear();
        cfcChanged.clear();
        drawIndex = EventSink.ABSENT;
        drawTotal = EventSink.ABSENT;
        navPoint = null;
        navVariant = null;
        navAlt = false;
        navDrew = false;
        backMenuCapped = 0;
        strategy = null;
        bufferOwner = null;
    }

    void mopExposure(int weighted, int total) {
        this.mopExposureWeighted = weighted;
        this.mopExposureTotal = total;
    }

    /**
     * Adds the targets the step's action retired. A selection retry that runs {@code moveForward}
     * again within the step adds to the indices already held rather than replacing them: the first
     * retirement happened, and a target retires once, so the union is what the step retired. The
     * caller's array is copied, never kept.
     */
    void mopRetired(int[] indices) {
        if (indices == null || indices.length == 0) {
            return;
        }
        int[] merged;
        if (retired == null) {
            merged = indices.clone();
        } else {
            merged = Arrays.copyOf(retired, retired.length + indices.length);
            System.arraycopy(indices, 0, merged, retired.length, indices.length);
        }
        Arrays.sort(merged);
        int distinct = 0;
        for (int i = 0; i < merged.length; i++) {
            if (distinct == 0 || merged[i] != merged[distinct - 1]) {
                merged[distinct++] = merged[i];
            }
        }
        retired = Arrays.copyOf(merged, distinct);
    }

    /**
     * Whether this record's chosen action had its MOP lookup answered by a handler stamp — what
     * {@code RUN_END.counters.stampHits} counts, once per written record and so at most once per
     * step, whatever selection retried in between.
     */
    boolean stampHit() {
        return hasDecision && modelAction && EventSink.MOP_SOURCE_STAMP.equals(mopSource);
    }

    /** Merges into {@code dec.own}: a null string or a {@code NaN} draw leaves its member as is. */
    void owner(String intended, String trigger, double uL, double uM, String gate,
            String fallback) {
        if (intended != null) {
            ownIntended = intended;
        }
        if (trigger != null) {
            ownTrigger = trigger;
        }
        if (!Double.isNaN(uL)) {
            ownUL = uL;
        }
        if (!Double.isNaN(uM)) {
            ownUM = uM;
        }
        if (gate != null) {
            ownGate = gate;
        }
        if (fallback != null) {
            ownFallback = fallback;
        }
    }

    /**
     * Adds a {@code dec.cfc} key. A selection retry that reaches the same pick site again replaces
     * the key's value rather than repeating the key, so the record holds each scorer once.
     */
    void cfc(String scorer, boolean changed) {
        int at = cfcScorers.indexOf(scorer);
        if (at >= 0) {
            cfcChanged.set(at, changed);
        } else {
            cfcScorers.add(scorer);
            cfcChanged.add(changed);
        }
    }

    void draw(int index, int total) {
        this.drawIndex = index;
        this.drawTotal = total;
    }

    void nav(String point, String variant, boolean alt, boolean drew) {
        this.navPoint = point;
        this.navVariant = variant;
        this.navAlt = alt;
        this.navDrew = drew;
    }

    void input(String origin) {
        this.inputOrigin = origin;
    }

    void restart(String kind) {
        this.restartKind = kind;
    }

    void backMenuCapped(int removed) {
        this.backMenuCapped = removed;
    }

    void strategy(String path) {
        this.strategy = path;
    }

    void rotation(int rotation) {
        this.hasRotation = true;
        this.rotation = rotation;
    }

    void bufferAbandoned(String owner) {
        this.bufferOwner = owner;
    }

    void componentTrigger(String kind, String className) {
        this.triggerKind = kind;
        this.triggerClass = className;
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

        if (hasDecision || hasRotation) {
            buf.name("dec").beginObject();
            if (hasDecision) {
                buf.name("a").value(action);
                buf.name("src").value(decisionSource);
                buf.name("ch").value(pickChannel);
            }
            writeOwner(buf);
            if (hasRotation) {
                // Exempt from the defaults-omitted rule: `0` is the portrait reading the fraction
                // of landscape steps is measured against.
                buf.name("rot").value(rotation);
            }
            if (hasDecision && modelAction) {
                buf.name("pri").value(priority);
                boost(buf, "mop", mop);
                boost(buf, "mopf", mopFrontier);
                boost(buf, "wtg", wtg);
                boost(buf, "navf", navFrontier);
                boost(buf, "cov", coverage);
                boost(buf, "menu", menu);
                boost(buf, "form", form);
                if (mopExposureTotal != EventSink.ABSENT) {
                    buf.name("mopx").beginArray()
                            .value(mopExposureWeighted).value(mopExposureTotal).endArray();
                }
                if (mopDistance != EventSink.ABSENT) {
                    // Exempt from the defaults-omitted rule (INV-SNK-05 as amended): `0` says the
                    // action's handler is itself a target, absence says the action has no
                    // distance-based weight.
                    buf.name("mopd").value(mopDistance);
                }
                if (mopSource != null) {
                    buf.name("mops").value(mopSource);
                }
                if (handler != null && EventSink.MOP_SOURCE_STAMP.equals(mopSource)) {
                    buf.name("hnd").value(handler);
                }
                if (retired != null) {
                    // Written at close like the rest of `dec`, so it survives a record closed
                    // without an `out` member.
                    buf.name("ret").beginArray();
                    for (int index : retired) {
                        buf.value(index);
                    }
                    buf.endArray();
                }
                if (patched != EventSink.ABSENT) {
                    // Exempt from the defaults-omitted rule: `0` says the action had a resolved
                    // target that was not patch-promoted, absence says it had no resolved target,
                    // and collapsing the two would lose the distinction the field exists for.
                    buf.name("patched").value(patched);
                }
            }
            writeSelection(buf);
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

    /** {@code dec.own}, written whenever {@code dec} is: an empty object when nothing claimed. */
    private void writeOwner(Json.Buf buf) {
        buf.name("own").beginObject();
        optionalName(buf, "i", ownIntended);
        optionalName(buf, "trg", ownTrigger);
        if (!Double.isNaN(ownUL)) {
            buf.name("uL").value(ownUL);
        }
        if (!Double.isNaN(ownUM)) {
            buf.name("uM").value(ownUM);
        }
        optionalName(buf, "g", ownGate);
        optionalName(buf, "fb", ownFallback);
        buf.endObject();
    }

    /** The conditional members of isolate-guidance-groups, each absent when its condition fails. */
    private void writeSelection(Json.Buf buf) {
        if (!cfcScorers.isEmpty()) {
            buf.name("cfc").beginObject();
            for (int i = 0; i < cfcScorers.size(); i++) {
                buf.name(cfcScorers.get(i)).value(cfcChanged.get(i).booleanValue() ? 1 : 0);
            }
            buf.endObject();
        }
        if (drawTotal != EventSink.ABSENT) {
            buf.name("draw").beginArray().value(drawIndex).value(drawTotal).endArray();
        }
        if (navPoint != null) {
            buf.name("nav").beginObject()
                    .name("x").value(navPoint)
                    .name("c").value(navVariant)
                    .name("alt").value(navAlt ? 1 : 0)
                    .name("drew").value(navDrew ? 1 : 0)
                    .endObject();
        }
        optionalName(buf, "inp", inputOrigin);
        optionalName(buf, "restart", restartKind);
        boost(buf, "bmc", backMenuCapped);
        optionalName(buf, "strat", strategy);
        optionalName(buf, "buf", bufferOwner);
        if (triggerKind != null) {
            buf.name("trig").beginObject()
                    .name("k").value(triggerKind)
                    .name("c").value(triggerClass)
                    .endObject();
        }
    }

    private static void optionalName(Json.Buf buf, String name, String value) {
        if (value != null) {
            buf.name(name).value(value);
        }
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
        navFrontier = 0;
        coverage = 0;
        menu = 0;
        form = 0;
        patched = EventSink.ABSENT;
        mopExposureWeighted = EventSink.ABSENT;
        mopExposureTotal = EventSink.ABSENT;
        hasComponentLaunch = false;
        componentResult = 0;
        componentError = null;
        mopDistance = EventSink.ABSENT;
        mopSource = null;
        handler = null;
        retired = null;
        ownIntended = null;
        ownTrigger = null;
        ownUL = Double.NaN;
        ownUM = Double.NaN;
        ownGate = null;
        ownFallback = null;
        cfcScorers.clear();
        cfcChanged.clear();
        drawIndex = EventSink.ABSENT;
        drawTotal = EventSink.ABSENT;
        navPoint = null;
        navVariant = null;
        navAlt = false;
        navDrew = false;
        inputOrigin = null;
        restartKind = null;
        backMenuCapped = 0;
        strategy = null;
        hasRotation = false;
        rotation = 0;
        bufferOwner = null;
        triggerKind = null;
        triggerClass = null;
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

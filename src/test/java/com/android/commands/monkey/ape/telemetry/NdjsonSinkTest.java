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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.android.commands.monkey.ape.utils.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the sink has to get right for a trace to be worth reading.
 *
 * <p>The tests are written against the emitted lines rather than against the sink's fields, because
 * the lines are the entire product: nothing in the jar reads a record back, so a property that
 * holds in memory and not on the stream is not a property at all. Each one names the invariant it
 * stands for — these are the permanent gates of the event-sink capability, not scaffolding for the
 * implementation underneath them.
 */
public class NdjsonSinkTest {

    private ByteArrayOutputStream captured;
    private NdjsonSink sink;

    @Before
    public void setUp() throws Exception {
        captured = new ByteArrayOutputStream();
        sink = new NdjsonSink(new PrintStream(captured, true, "UTF-8"));
        Log.reset();
    }

    @After
    public void leaveNoHeartbeatsBehind() {
        // The stub is static, as android.util.Log is; a test that wrote to it would otherwise be
        // counted by the next one.
        Log.reset();
    }

    // --- lifecycle (INV-SNK-03, INV-SNK-08) -----------------------------------------------------

    @Test
    public void theOutcomeJoinsItsStepAtTheNextUpdate() throws Exception {
        sink.beginStep(10, 1_000L, "com.foo/.Main", true, "S1");
        sink.decision("model=CLICK@x", "MOP", "roulette_greedy", 9,
                500, 0, 0, 0, 0, 0, null, 1, EventSink.ABSENT, null);
        // Step 11's graph update is where step 10's outcome first exists.
        sink.outcome(true, "S2", "com.foo/.Detail", false, true);

        JSONObject record = onlyStepRecord();
        assertEquals(10, record.getInt("s"));
        JSONObject out = record.getJSONObject("out");
        assertTrue(out.getBoolean("new_state"));
        assertTrue(out.getBoolean("act_changed"));
        assertEquals(stateId("S2"), out.getInt("target"));
    }

    @Test
    public void aStepWhoseOutcomeNeverResolvesClosesWithoutAnOutMember() throws Exception {
        // The legitimate absence of INV-ARCH-09: a restart, a non-model action or a refinement
        // discard produced no transition, exactly the cases that emitted no [APE-OUTCOME] line.
        sink.beginStep(20, 1L, "com.foo/.Main", false, "S1");
        sink.decisionNonModel("EVENT_TRIGGER_ACTIVITY", "Component", "launcher");
        sink.beginStep(21, 2L, "com.foo/.Main", false, "S1");

        JSONObject record = stepRecords().get(0);
        assertEquals(20, record.getInt("s"));
        assertFalse("a record with no resolved outcome must not carry an out member",
                record.has("out"));
    }

    @Test
    public void theTeardownFlushIsADistinctEncodingFromTheAbsentOutcome() throws Exception {
        sink.beginStep(199, 5L, "com.foo/.Main", false, "S1");
        sink.decision("model=CLICK@x", "SATA", "sata_other", 1,
                0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        sink.flushPendingStep();

        JSONObject out = onlyStepRecord().getJSONObject("out");
        assertFalse("the run was cut here, which is not the same as an outcome that never came",
                out.getBoolean("resolved"));
    }

    @Test
    public void aSelectionRetryWithinAStepStillProducesOneRecord() throws Exception {
        // The BadStateException retry re-runs selection without advancing the agent timestamp.
        sink.beginStep(7, 1L, "com.foo/.Main", false, "S1");
        sink.llmError("timeout", "read timed out");
        sink.beginStep(7, 1L, "com.foo/.Main", false, "S1");
        sink.decision("model=CLICK@y", "SATA", "sata_other", 3,
                0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        sink.outcome(false, "S1", "com.foo/.Main", false, false);

        assertEquals("one step is one record, whatever selection did internally",
                1, stepRecords().size());
        JSONObject record = onlyStepRecord();
        assertEquals("model=CLICK@y", record.getJSONObject("dec").getString("a"));
        assertEquals("the attempt made before the retry belongs to the same step",
                1, record.getJSONArray("llm").length());
    }

    @Test
    public void everyStepThatOpensIsWrittenExactlyOnce() throws Exception {
        for (int step = 1; step <= 5; step++) {
            sink.beginStep(step, step * 10L, "com.foo/.Main", false, "S" + step);
            sink.decision("model=CLICK@" + step, "SATA", "sata_other", 1,
                    0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
            sink.outcome(false, "S" + step, "com.foo/.Main", false, false);
        }

        List<JSONObject> records = stepRecords();
        assertEquals(5, records.size());
        for (int i = 0; i < records.size(); i++) {
            assertEquals("s values are unique and strictly increasing within a run",
                    i + 1, records.get(i).getInt("s"));
        }
    }

    // --- dictionaries (INV-SNK-06) ---------------------------------------------------------------

    @Test
    public void aDictionaryEntryIsAlwaysOnAnEarlierLineThanItsFirstReference() throws Exception {
        sink.beginStep(5, 1L, "com.foo/.Settings", true, "S9");
        sink.decision("model=CLICK@x", "SATA", "sata_other", 1,
                0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        sink.outcome(false, "S9", "com.foo/.Settings", true, false);

        List<JSONObject> records = records();
        assertEquals("ACT", records.get(0).getString("type"));
        assertEquals("com.foo/.Settings", records.get(0).getString("name"));
        assertEquals("STATE", records.get(1).getString("type"));
        assertEquals("S9", records.get(1).getString("key"));
        assertEquals("the STATE entry names the activity the outcome-side MOP flag is derived from",
                records.get(0).getInt("id"), records.get(1).getInt("act"));
        JSONObject step = records.get(2);
        assertEquals(records.get(0).getInt("id"), step.getInt("act"));
        assertEquals(records.get(1).getInt("id"), step.getInt("st"));
    }

    @Test
    public void aStateFirstSeenAsAnOutcomeIsDefinedBeforeTheRecordThatReferencesIt() throws Exception {
        sink.beginStep(1, 1L, "com.foo/.Main", false, "S1");
        sink.decision("model=CLICK@x", "SATA", "sata_other", 1,
                0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        sink.outcome(true, "S2", "com.foo/.Detail", true, true);

        List<JSONObject> records = records();
        JSONObject step = records.get(records.size() - 1);
        assertFalse("the step record is the last line, after every definition it uses",
                step.has("type"));
        int targetDefinedAt = indexOfDictionary("STATE", "key", "S2");
        assertTrue(targetDefinedAt >= 0 && targetDefinedAt < records.size() - 1);
    }

    @Test
    public void theStaticMopFactIsRecordedOnceOnTheActivityRatherThanOnEveryStep() throws Exception {
        for (int step = 1; step <= 3; step++) {
            sink.beginStep(step, step, "com.foo/.Main", true, "S1");
            sink.decision("model=CLICK@x", "SATA", "sata_other", 1,
                    0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
            sink.outcome(false, "S1", "com.foo/.Main", true, false);
        }

        int actEntries = 0;
        for (JSONObject record : records()) {
            if ("ACT".equals(record.optString("type", null))) {
                actEntries++;
                assertEquals(1, record.getInt("mop"));
            }
        }
        assertEquals("an activity visited 200 times still defines itself once", 1, actEntries);
        for (JSONObject step : stepRecords()) {
            assertFalse(step.has("activity_has_mop"));
            assertFalse(step.has("mop_screen"));
        }
    }

    // --- volume rules (INV-SNK-04, INV-SNK-05) ---------------------------------------------------

    @Test
    public void aFieldAtItsDefaultIsAbsentRatherThanZero() throws Exception {
        sink.beginStep(1, 1L, "com.foo/.Main", false, "S1");
        sink.decision("model=CLICK@x", "SATA", "sata_other", 2,
                0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        sink.outcome(false, "S1", "com.foo/.Main", false, false);

        JSONObject record = onlyStepRecord();
        JSONObject dec = record.getJSONObject("dec");
        for (String boost : new String[] {"mop", "mopf", "wtg", "cov", "menu", "form"}) {
            assertFalse("a zero boost costs bytes on every step and says nothing", dec.has(boost));
        }
        JSONObject out = record.getJSONObject("out");
        assertFalse(out.has("new_state"));
        assertFalse(out.has("act_changed"));
        assertFalse("an empty attempt list is the absence of attempts", record.has("llm"));
    }

    @Test
    public void theEnvelopeAppearsOncePerRecordAndNeverPerSubEvent() throws Exception {
        sink.beginStep(42, 8_123L, "com.foo/.Main", false, "S1");
        for (int call = 1; call <= 3; call++) {
            sink.llmCall(call, "new_state", "click", 500, 861, 512, 884, "matched", null, null,
                    "android.widget.Button", "android.widget.Button", 4.2d, 23, 1841, 25, 973L,
                    null);
        }
        sink.decision("model=CLICK@x", "LLM", "llm", 5,
                0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        sink.outcome(false, "S1", "com.foo/.Main", false, false);

        JSONObject record = onlyStepRecord();
        assertEquals(42, record.getInt("s"));
        assertEquals(8_123L, record.getLong("t"));
        JSONArray llm = record.getJSONArray("llm");
        assertEquals(3, llm.length());
        for (int i = 0; i < llm.length(); i++) {
            JSONObject event = llm.getJSONObject(i);
            assertEquals("attempts keep their order", i + 1, event.getInt("call"));
            assertFalse("the step is the parent record's, not the sub-event's", event.has("s"));
            assertFalse(event.has("step"));
            assertFalse(event.has("activity"));
            assertFalse("the prompt variant is run-constant and lives in RUN_START",
                    event.has("variant"));
        }
    }

    @Test
    public void theTriStatePatchedKeepsItsThirdState() throws Exception {
        // Absence is information here: no resolved target at all, which a 0 would have claimed was
        // a resolved target that happened not to be patch-promoted.
        record(1, 1, EventSink.ABSENT, null);
        record(2, 0, EventSink.ABSENT, null);
        record(3, EventSink.ABSENT, EventSink.ABSENT, null);

        List<JSONObject> records = stepRecords();
        assertEquals(1, records.get(0).getJSONObject("dec").getInt("patched"));
        assertEquals(0, records.get(1).getJSONObject("dec").getInt("patched"));
        assertFalse(records.get(2).getJSONObject("dec").has("patched"));
    }

    @Test
    public void theCounterfactualIsEmittedWheneverItIsDefinedAndNotOtherwise() throws Exception {
        record(1, EventSink.ABSENT, 0, null);
        record(2, EventSink.ABSENT, 1, "model=CLICK@other");
        record(3, EventSink.ABSENT, EventSink.ABSENT, null);

        List<JSONObject> records = stepRecords();
        JSONObject unchanged = records.get(0).getJSONObject("dec").getJSONObject("cf");
        assertEquals(0, unchanged.getInt("changed"));
        assertFalse("an unchanged counterfactual is the factual action, not a second copy of it",
                unchanged.has("a"));
        JSONObject changed = records.get(1).getJSONObject("dec").getJSONObject("cf");
        assertEquals(1, changed.getInt("changed"));
        assertEquals("model=CLICK@other", changed.getString("a"));
        assertFalse("MOP boosts do not participate in the other channels' picks",
                records.get(2).getJSONObject("dec").has("cf"));
    }

    @Test
    public void theWtgStampRidesTheBoostItDeAliases() throws Exception {
        sink.beginStep(1, 1L, "com.foo/.Main", false, "S1");
        sink.decision("model=CLICK@x", "WTG", "roulette_greedy", 9,
                0, 0, 400, 0, 0, 0, "both", EventSink.ABSENT, EventSink.ABSENT, null);
        sink.outcome(false, "S1", "com.foo/.Main", false, false);
        sink.beginStep(2, 2L, "com.foo/.Main", false, "S1");
        sink.decision("model=CLICK@y", "SATA", "sata_other", 1,
                0, 0, 0, 0, 0, 0, "wtg", EventSink.ABSENT, EventSink.ABSENT, null);
        sink.outcome(false, "S1", "com.foo/.Main", false, false);

        List<JSONObject> records = stepRecords();
        assertEquals("both", records.get(0).getJSONObject("dec").getString("wtgsrc"));
        assertFalse("with no boost there is nothing to attribute",
                records.get(1).getJSONObject("dec").has("wtgsrc"));
    }

    @Test
    public void theExposurePairAndTheLaunchResultRideTheirOwnStep() throws Exception {
        sink.beginStep(1, 1L, "com.foo/.Main", true, "S1");
        sink.mopExposure(2, 17);
        sink.decision("model=CLICK@x", "MOP", "roulette_greedy", 9,
                500, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        sink.outcome(false, "S1", "com.foo/.Main", true, false);

        sink.beginStep(2, 2L, "com.foo/.Main", true, "S1");
        sink.decisionNonModel("EVENT_TRIGGER_ACTIVITY", "Component", "launcher");
        sink.componentLaunch(-2, "START_CLASS_NOT_FOUND");
        sink.beginStep(3, 3L, "com.foo/.Main", true, "S1");

        List<JSONObject> records = stepRecords();
        JSONArray exposure = records.get(0).getJSONObject("dec").getJSONArray("mopx");
        assertEquals(2, exposure.getInt(0));
        assertEquals(17, exposure.getInt(1));

        JSONObject launch = records.get(1).getJSONObject("dec").getJSONObject("comp");
        assertEquals("a refused launch and an accepted one differ only here", -2, launch.getInt("r"));
        assertEquals("START_CLASS_NOT_FOUND", launch.getString("e"));
        assertFalse("a non-model record carries no priority", records.get(1)
                .getJSONObject("dec").has("pri"));
    }

    // --- LLM sub-events ---------------------------------------------------------------------------

    @Test
    public void everyKindOfAttemptLandsOnTheStepThatMadeIt() throws Exception {
        sink.beginStep(55, 1L, "com.foo/.Main", false, "S1");
        sink.llmError("timeout", "read timed out after 15000 ms");
        sink.llmBreakerOpen(2);
        sink.llmCall(4, "stagnation", "click", 500, 499, 512, 511, "no_match", "dead_pair", null,
                null, null, 0.0d, 12, 900, 20, 640L, null);
        sink.decision("model=CLICK@x", "SATA", "sata_other", 1,
                0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        sink.outcome(false, "S1", "com.foo/.Main", false, false);

        JSONArray llm = onlyStepRecord().getJSONArray("llm");
        assertEquals(3, llm.length());
        assertEquals("error", llm.getJSONObject(0).getString("result"));
        assertEquals("timeout", llm.getJSONObject(0).getString("cause"));
        assertFalse("attribution is by construction — the entry is a member of its step's record",
                llm.getJSONObject(0).has("step"));
        assertEquals("breaker_open", llm.getJSONObject(1).getString("result"));
        assertEquals(2, llm.getJSONObject(1).getInt("trips"));
        JSONObject banned = llm.getJSONObject(2);
        assertEquals("no_match", banned.getString("result"));
        assertEquals("dead_pair", banned.getString("reason"));
        assertEquals(500, banned.getJSONArray("qwen").getInt(0));
        assertEquals(511, banned.getJSONArray("px").getInt(1));
        assertEquals(900, banned.getJSONArray("tok").getInt(0));
    }

    @Test
    public void aDumpBelongsToTheAttemptItWasWrittenFor() throws Exception {
        // The prompt exists before the response is parsed and the mapping decided, which is why it
        // is staged: an attempt abandoned before it maps still keeps the prompt that produced it.
        sink.beginStep(1, 1L, "com.foo/.Main", false, "S1");
        sink.llmDump("you are a tester", "Screen \"Main\":\n[0] BACK", null, null);
        sink.llmError("parse", "no tool call in response");
        sink.llmCall(2, "new_state", "click", 1, 2, 3, 4, "matched", null, null, null, null,
                0.0d, 1, 10, 2, 5L, null);
        sink.beginStep(2, 2L, "com.foo/.Main", false, "S1");

        JSONArray llm = stepRecords().get(0).getJSONArray("llm");
        assertEquals("you are a tester", llm.getJSONObject(0).getString("sys"));
        assertEquals("Screen \"Main\":\n[0] BACK", llm.getJSONObject(0).getString("user"));
        assertFalse("a staged dump is consumed once, by the attempt it belongs to",
                llm.getJSONObject(1).has("sys"));
    }

    // --- the stream contract (INV-SNK-01, INV-SNK-02, INV-SNK-11) --------------------------------

    @Test
    public void everyRecordLineBeginsWithABrace() throws Exception {
        sink.mopData("loaded", null, 1, "sha256:ab", "com.foo", 12, 340, 8, 2, 7, 1, 0, 3, 4, 5, 9);
        sink.beginStep(1, 1L, "com.foo/.Main", true, "S1");
        sink.decision("model=CLICK@x", "SATA", "sata_other", 1,
                0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        sink.outcome(false, "S1", "com.foo/.Main", true, false);

        List<String> lines = lines();
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            assertTrue("a trace line is a record if and only if it starts with a brace: " + line,
                    line.startsWith("{"));
            assertFalse("the sink never writes through Logger", line.startsWith(Logger.TAG));
        }
    }

    @Test
    public void hostileWidgetTextCannotSplitARecord() throws Exception {
        // The failure this replaces is in the corpus, not hypothetical: 74 [APE-STEP] lines across
        // 18 runs of the decisive campaign are physically split because a widget's text carried a
        // raw newline, and what those steps silently lost was decision_source.
        String hostile = "Salvar\n\"opção\" \\fim\u0000";
        sink.beginStep(1, 1L, "com.foo/.Main\n", false, "S1\"S2");
        sink.decision(hostile, "SATA", "sata_other", 1,
                0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        sink.outcome(false, "S1\"S2", "com.foo/.Main\n", false, false);

        assertEquals("one record is one line whatever the device put in it", 3, lines().size());
        assertEquals(hostile, onlyStepRecord().getJSONObject("dec").getString("a"));
    }

    // --- run-level records ------------------------------------------------------------------------

    @Test
    public void theLoadCensusCarriesTheNumberTheFrontierPassesGateOn() throws Exception {
        sink.mopData("loaded", null, 1, "sha256:ab", "com.foo", 12, 340, 8, 2, 0, 1, 0, 3, 4, 5, 9);

        JSONObject census = records().get(0);
        assertEquals("MOP_DATA", census.getString("type"));
        assertEquals(12, census.getInt("windows"));
        assertEquals(340, census.getInt("widgets"));
        assertEquals(0, census.getInt("wtgEdges"));
        assertFalse("restoring transitions would restore the misreading it caused",
                census.has("transitions"));
        assertFalse("hasWtgData is wtgEdges > 0 by construction", census.has("has_wtg_data"));
    }

    @Test
    public void theLoadCensusNamesTheContractAndTheDocumentItWasDerivedFrom() throws Exception {
        sink.mopData("loaded", null, 1, "sha256:beef", "com.foo", 5, 30, 3, 0, 16, 5, 1, 1, 3, 2, 5);

        JSONObject census = records().get(0);
        assertEquals("a trace that cannot name its wire contract cannot be read by a later jar",
                1, census.getInt("formatVersion"));
        assertEquals("the digest is what joins a run to the exact static analysis that steered it",
                "sha256:beef", census.getString("sourceDigest"));
        assertEquals(5, census.getInt("components"));
        // The stage-4 census survives whole underneath the three new fields: this record gains
        // fields across the window and loses none.
        assertEquals(3, census.getInt("mopActivities"));
        assertEquals(2, census.getInt("mopActsAugmented"));
        assertEquals(1, census.getInt("recovered"));
    }

    @Test
    public void aRejectedLoadReportsItsReasonAndClaimsNoProvenance() throws Exception {
        sink.mopData("rejected", "version-mismatch", 0, null, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        JSONObject census = records().get(0);
        assertEquals("rejected", census.getString("status"));
        assertEquals("version-mismatch", census.getString("reason"));
        assertFalse("a load that reached no artifact has no digest to report, not a null one",
                census.has("sourceDigest"));
        assertFalse(census.has("package"));
        assertEquals("a version this jar rejected is not a version it can vouch for",
                0, census.getInt("formatVersion"));
    }

    @Test
    public void thePipelineCensusSaysWhatWasNotAssembled() throws Exception {
        Map<String, Boolean> candidates = new LinkedHashMap<String, Boolean>();
        candidates.put("MopWidgetPass", Boolean.TRUE);
        candidates.put("WtgPass", Boolean.FALSE);
        candidates.put("FrontierPass", Boolean.FALSE);
        sink.pipeline(Arrays.asList("LlmStage", "SataStage"), Arrays.asList("MopWidgetPass"),
                candidates);

        JSONObject record = records().get(0);
        assertEquals("PIPELINE", record.getString("type"));
        assertEquals(2, record.getJSONArray("stages").length());
        assertEquals(1, record.getJSONArray("passes").length());
        JSONArray census = record.getJSONArray("candidates");
        assertEquals(3, census.length());
        assertTrue(census.getJSONObject(0).getBoolean("enabled"));
        assertEquals("WtgPass", census.getJSONObject(1).getString("name"));
        assertFalse("'the arm turned it off' and 'the data could not support it' are the two "
                + "readings this census exists to separate", census.getJSONObject(1)
                .getBoolean("enabled"));
        assertFalse("a reason would encode the order the constructor evaluates its conjuncts",
                census.getJSONObject(1).has("reason"));
    }

    @Test
    public void theServerModelIsAcknowledgedOnce() throws Exception {
        sink.llmAck("qwen3-vl-8b");
        sink.llmAck("qwen3-vl-8b");

        assertEquals(1, records().size());
        assertEquals("qwen3-vl-8b", records().get(0).getString("server_model"));
    }

    // --- failure containment (INV-SNK-12) ---------------------------------------------------------

    @Test
    public void aSinkFailureCostsTheRunItsTelemetryAndNothingElse() throws Exception {
        final List<String> written = new ArrayList<String>();
        PrintStream failing = new PrintStream(new ByteArrayOutputStream()) {
            @Override
            public void print(String line) {
                written.add(line);
                if (written.size() >= 2) {
                    throw new IllegalStateException("the stream died mid-run");
                }
            }
        };
        NdjsonSink latching = new NdjsonSink(failing);

        // None of these may throw: a telemetry defect must not alter or kill an experimental run.
        latching.beginStep(1, 1L, "com.foo/.Main", false, "S1");
        latching.decision("model=CLICK@x", "SATA", "sata_other", 1,
                0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        latching.outcome(false, "S1", "com.foo/.Main", false, false);
        latching.beginStep(2, 2L, "com.foo/.Other", false, "S2");
        latching.flushPendingStep();
        latching.mopData("loaded", null, 1, "sha256:ab", "com.foo", 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        latching.llmAck("qwen3-vl-8b");
        latching.runEnd("timeout", null, null);

        assertEquals("after the first failure the sink stops emitting for the rest of the run",
                2, written.size());
    }

    @Test
    public void callsArrivingWithNoOpenStepAreIgnoredRatherThanInvented() throws Exception {
        // A record with no envelope would violate INV-SNK-04 more thoroughly than a missing record
        // violates anything: it would be a step's worth of data attributed to no step.
        sink.decision("model=CLICK@x", "SATA", "sata_other", 1,
                0, 0, 0, 0, 0, 0, null, EventSink.ABSENT, EventSink.ABSENT, null);
        sink.llmError("timeout", "detail");
        sink.outcome(true, "S1", "com.foo/.Main", false, false);
        sink.flushPendingStep();

        assertTrue(lines().isEmpty());
    }

    // --- RUN_END (INV-SNK-09) ---------------------------------------------------------------------

    @Test
    public void runEndCarriesTheReasonTheStepSpanAndTheCounters() throws Exception {
        sink.beginStep(1, 1_500L, "com.foo/.Main", true, "S1");
        sink.outcome(false, "S1", "com.foo/.Main", true, false);
        sink.beginStep(2, 9_800L, "com.foo/.Detail", false, "S2");
        sink.outcome(false, "S2", "com.foo/.Detail", false, false);

        RunCounters counters = new RunCounters();
        counters.calls = 5;
        counters.matched = 3;
        counters.timeout = 1;
        sink.runEnd("timeout", null, counters);

        JSONObject record = runEnd();
        assertEquals("timeout", record.getString("reason"));
        assertFalse("an orderly ending has nothing to name", record.has("detail"));
        assertEquals("steps is the count of records written, not of steps attempted",
                2, record.getInt("steps"));
        // The span, not the budget: a run alive and idle for most of its budget is exactly what
        // these two separate from one that explored for all of it.
        assertEquals(1_500L, record.getLong("t_first_step"));
        assertEquals(9_800L, record.getLong("t_last_step"));

        JSONObject countersJson = record.getJSONObject("counters");
        assertEquals("two activities were seen", 2, countersJson.getInt("acts"));
        assertEquals("and two states", 2, countersJson.getInt("states"));
        assertEquals(5, countersJson.getJSONObject("llm").getInt("calls"));
        assertEquals(3, countersJson.getJSONObject("llm").getInt("matched"));
        assertEquals(1, countersJson.getJSONObject("llm").getInt("timeout"));
    }

    @Test
    public void aRunThatWroteNoStepRecordCarriesNoStepSpan() throws Exception {
        // A run that died before its first selection. Zeros here would say it began and ended at
        // the time origin, which is a claim; absence says only that there is nothing to report.
        sink.runEnd("crash", "java.lang.IllegalStateException", null);

        JSONObject record = runEnd();
        assertEquals(0, record.getInt("steps"));
        assertFalse(record.has("t_first_step"));
        assertFalse(record.has("t_last_step"));
        assertEquals("a crash names the exception that ended the run",
                "java.lang.IllegalStateException", record.getString("detail"));
    }

    @Test
    public void aPlanWithNoLlmCarriesNoLlmCounterBlock() throws Exception {
        // Not a zeroed block: seventeen zeros would read as an LLM that was asked nothing, and the
        // control arm's whole point is that it has none to ask.
        sink.beginStep(1, 1L, "com.foo/.Main", false, "S1");
        sink.flushPendingStep();
        sink.runEnd("timeout", null, null);

        JSONObject counters = runEnd().getJSONObject("counters");
        assertFalse("an arm with no LLM has no LLM counters", counters.has("llm"));
        assertTrue("the sink's own counts are there either way", counters.has("acts"));
        assertTrue(counters.has("states"));
    }

    // --- the logcat heartbeat (INV-SNK-10, INV-SNK-14) --------------------------------------------

    @Test
    public void oneHeartbeatLinePerStepOnTheStepsOwnClock() throws Exception {
        sink.beginStep(1, 1_500L, "com.foo/.Main", false, "S1");
        sink.outcome(false, "S1", "com.foo/.Main", false, false);
        sink.beginStep(2, 9_800L, "com.foo/.Main", false, "S1");
        sink.outcome(false, "S1", "com.foo/.Main", false, false);

        List<Log.Entry> beats = Log.entries();
        assertEquals("one line per step, no more", 2, beats.size());
        // The tag is what the capture-side allowlist admits and what clock_logcat_join.py matches;
        // a different literal is dropped at the device and the mechanism is inert while looking fine.
        assertEquals("ApeRvHb", beats.get(0).tag);
        assertEquals(NdjsonSink.HEARTBEAT_TAG, beats.get(0).tag);
        // The payload is the record's own s and t — one number shared, not a second clock reading,
        // which is what makes the trace↔logcat mapping exact rather than approximate.
        assertEquals("s=1 t=1500", beats.get(0).message);
        assertEquals("s=2 t=9800", beats.get(1).message);
    }

    @Test
    public void aSelectionRetryDoesNotBeatTwice() throws Exception {
        // The retry re-enters the same step; one step is one record (INV-SNK-03) and one heartbeat,
        // or the logcat line count stops being a step count.
        sink.beginStep(7, 1L, "com.foo/.Main", false, "S1");
        sink.beginStep(7, 1L, "com.foo/.Main", false, "S1");

        assertEquals(1, Log.entries().size());
    }

    @Test
    public void theTraceIsByteIdenticalWithTheHeartbeatOff() throws Exception {
        ByteArrayOutputStream withoutBuffer = new ByteArrayOutputStream();
        scriptedRun(new NdjsonSink(new PrintStream(withoutBuffer, true, "UTF-8"), false));
        assertTrue("a heartbeat-off run writes no logcat line at all", Log.entries().isEmpty());

        scriptedRun(sink);

        assertEquals("and a heartbeat-on run writes one per step", 2, Log.entries().size());
        assertEquals("but the records are byte-identical: the flag moves nothing between the two"
                + " destinations", new String(withoutBuffer.toByteArray(), StandardCharsets.UTF_8),
                new String(captured.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void aFailingHeartbeatCostsTheHeartbeatAndNotTheTrace() throws Exception {
        // A logcat write failing is a device-side condition with no bearing on the record. The sink
        // latches off for its own failures (INV-SNK-12); it must not latch off for this one.
        Log.failWith(new IllegalStateException("logcat is gone"));

        String warnings = onStdout(() -> {
            sink.beginStep(1, 1L, "com.foo/.Main", false, "S1");
            sink.outcome(false, "S1", "com.foo/.Main", false, false);
            sink.beginStep(2, 2L, "com.foo/.Main", false, "S1");
            sink.outcome(false, "S1", "com.foo/.Main", false, false);
        });

        assertEquals("both steps are still on the record", 2, stepRecords().size());
        assertEquals("and the failure is said once, not once per step — a silently absent heartbeat"
                + " is the mode INV-SNK-14 exists to prevent",
                1, countOccurrences(warnings, "heartbeat disabled for the rest of the run"));
    }

    // --- neutrality by shape (INV-SNK-07) ---------------------------------------------------------

    @Test
    public void noSinkMethodCanHandAnythingBackToADecisionPath() throws Exception {
        // The neutrality test proper (R7) replays a preset under one seed with each implementation.
        // This is the structural half of the same argument, and it is the half that a future
        // convenience accessor would break silently: a sink that returns a value is a sink a
        // decision can read.
        for (Method method : EventSink.class.getDeclaredMethods()) {
            assertEquals("EventSink." + method.getName() + " must return void",
                    Void.TYPE, method.getReturnType());
        }
    }

    @Test
    public void theNoopSinkAcceptsEveryCallAndWritesNothing() throws Exception {
        EventSink noop = new NoopSink();
        noop.beginStep(1, 1L, "com.foo/.Main", true, "S1");
        noop.decision("model=CLICK@x", "MOP", "roulette_greedy", 9,
                500, 0, 0, 0, 0, 0, null, 1, 0, null);
        noop.decisionNonModel("EVENT_TRIGGER_ACTIVITY", "Component", "launcher");
        noop.mopExposure(1, 2);
        noop.componentLaunch(0, null);
        noop.llmDump("s", "u", "r", "t");
        noop.llmCall(1, "new_state", "click", 1, 2, 3, 4, "matched", null, null, null, null,
                0.0d, 1, 1, 1, 1L, null);
        noop.llmError("timeout", "detail");
        noop.llmBreakerOpen(1);
        noop.outcome(true, "S2", "com.foo/.Main", true, true);
        noop.flushPendingStep();
        noop.mopData("loaded", null, 1, "sha256:ab", "com.foo", 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        noop.pipeline(Arrays.asList("a"), Arrays.asList("b"), new LinkedHashMap<String, Boolean>());
        noop.llmAck("qwen3-vl-8b");
        noop.runEnd("timeout", null, new RunCounters());

        assertTrue(lines().isEmpty());
    }

    // --- helpers ----------------------------------------------------------------------------------

    private void record(int step, int patched, int cfChanged, String cfAction) throws Exception {
        sink.beginStep(step, step, "com.foo/.Main", false, "S1");
        sink.decision("model=CLICK@" + step, "MOP", "roulette_greedy", 9,
                500, 0, 0, 0, 0, 0, null, patched, cfChanged, cfAction);
        sink.outcome(false, "S1", "com.foo/.Main", false, false);
    }

    private List<String> lines() throws Exception {
        String text = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<String>();
        if (!text.isEmpty()) {
            for (String line : text.split("\n")) {
                lines.add(line);
            }
        }
        return lines;
    }

    private List<JSONObject> records() throws Exception {
        List<JSONObject> records = new ArrayList<JSONObject>();
        for (String line : lines()) {
            records.add(new JSONObject(line));
        }
        return records;
    }

    private List<JSONObject> stepRecords() throws Exception {
        List<JSONObject> steps = new ArrayList<JSONObject>();
        for (JSONObject record : records()) {
            if (!record.has("type")) {
                steps.add(record);
            }
        }
        return steps;
    }

    /** Two ordinary resolved steps, the same script through whichever sink is handed in. */
    private static void scriptedRun(NdjsonSink target) {
        target.beginStep(1, 1_500L, "com.foo/.Main", true, "S1");
        target.decision("model=CLICK@x", "MOP", "roulette_greedy", 9,
                500, 0, 0, 0, 0, 0, null, 1, EventSink.ABSENT, null);
        target.outcome(true, "S2", "com.foo/.Detail", false, true);
        target.beginStep(2, 9_800L, "com.foo/.Detail", false, "S2");
        target.decisionNonModel("EVENT_TRIGGER_ACTIVITY", "Component", "launcher");
        target.outcome(false, "S2", "com.foo/.Detail", false, false);
    }

    /** Runs {@code body} with stdout captured, and returns what the free-text side received. */
    private static String onStdout(Runnable body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out, true));
            body.run();
        } finally {
            System.setOut(original);
        }
        return out.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private JSONObject runEnd() throws Exception {
        for (JSONObject record : records()) {
            if ("RUN_END".equals(record.optString("type", null))) {
                return record;
            }
        }
        throw new AssertionError("no RUN_END record was written");
    }

    private JSONObject onlyStepRecord() throws Exception {
        List<JSONObject> steps = stepRecords();
        assertEquals("expected exactly one step record", 1, steps.size());
        return steps.get(0);
    }

    private int stateId(String key) throws Exception {
        int index = indexOfDictionary("STATE", "key", key);
        assertTrue("no STATE entry for " + key, index >= 0);
        return records().get(index).getInt("id");
    }

    private int indexOfDictionary(String type, String field, String value) throws Exception {
        List<JSONObject> records = records();
        for (int i = 0; i < records.size(); i++) {
            JSONObject record = records.get(i);
            if (type.equals(record.optString("type", null)) && value.equals(
                    record.optString(field, null))) {
                return i;
            }
        }
        return -1;
    }
}

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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What {@code RUN_START} has to be worth: the line alone reconstructs the arm.
 *
 * <p>The reconstruction tests are the load-bearing ones, and they are written as an actual
 * reconstruction rather than as a field-by-field checklist. A checklist asserts that the fields
 * someone thought of are present; re-resolving a plan from the emitted parameters and comparing
 * digests asserts the property the line exists for — that nothing which distinguishes this arm
 * from another was left out. A checklist would have passed on a line missing a weight nobody
 * remembered to list.
 */
public class RunSpecEchoTest {

    private static final String MOP_PATH = "/data/local/tmp/static_analysis.json";
    private static final String LLM_URL = "http://10.0.2.2:30000/v1";

    @Before
    public void clearTheHolder() {
        RunContext.resetForTest();
    }

    @After
    public void leaveNoContextBehind() {
        RunContext.resetForTest();
    }

    private static Map<String, String> entries(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static RunSpec resolve(Map<String, String> entries) {
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", 12345L, null));
    }

    /** Emits the plan and returns the raw stream content, newline and all. */
    private static String emit(RunSpec spec) {
        RunContext.resetForTest();
        RunContext.installForTest(spec);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            PrintStream out = new PrintStream(buffer, true, "UTF-8");
            RunSpecEcho.emit(RunContext.current(), out);
            return buffer.toString("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable", e);
        }
    }

    private static JSONObject record(RunSpec spec) throws Exception {
        String written = emit(spec);
        assertTrue("the record must be newline-terminated", written.endsWith("\n"));
        assertEquals("the record must be exactly one line",
                written.length() - 1, written.indexOf('\n'));
        return new JSONObject(written);
    }

    // -----------------------------------------------------------------------------------------
    // The completeness bar: the line alone reconstructs the arm
    // -----------------------------------------------------------------------------------------

    /**
     * A preset plus the two keys a preset deliberately leaves out.
     *
     * <p>{@code Presets} carries no {@code ape.mopDataPath} and no {@code ape.llmUrl}: those say
     * where <em>this</em> deployment put its static-analysis artifact and its server, which is not
     * part of what the arm is. Since they are also the activation keys of the MOP and LLM
     * families, a preset naming either family does not resolve without them.
     */
    private static Map<String, String> presetPlan(String preset) {
        Map<String, String> plan = entries("ape.preset", preset);
        if (Presets.MOP.equals(preset) || Presets.LLM_MOP.equals(preset)) {
            plan.put("ape.mopDataPath", MOP_PATH);
        }
        if (Presets.LLM.equals(preset) || Presets.LLM_MOP.equals(preset)) {
            plan.put("ape.llmUrl", LLM_URL);
        }
        return plan;
    }

    @Test
    public void eachPresetPlanIsReconstructibleFromItsOwnRecord() throws Exception {
        for (String preset : Presets.names()) {
            RunSpec original = resolve(presetPlan(preset));
            JSONObject json = record(original);

            RunSpec rebuilt = RunSpec.resolve(paramsAsEntries(json),
                    RunSpec.CliValues.of(json.getString("agent"), json.getLong("seed"), null));

            // The digest is over the effective plan, so equal digests mean the reconstruction has
            // the same agent, the same features and the same value for every parameter of them.
            assertEquals(preset + ": the record does not reconstruct the plan",
                    original.digest(), rebuilt.digest());
            assertEquals(preset + ": feature set differs", original.features(), rebuilt.features());
            assertEquals(preset + ": plan values differ",
                    original.effectiveValues(), rebuilt.effectiveValues());
        }
    }

    @Test
    public void aFullyLoadedPlanIsReconstructibleWithoutConsultingTheHarness() throws Exception {
        // The heaviest arm of the decisive campaign: MOP substrate, the menu gateway, the LLM and
        // one non-default sampling parameter. Nothing here may need tool.py to be readable.
        RunSpec original = resolve(entries(
                "ape.mopDataPath", MOP_PATH,
                "ape.mopWeightDirect", "500",
                "ape.modelMenuEnabled", "true",
                "ape.mopWeightOpenMenu", "250",
                "ape.llmUrl", LLM_URL,
                "ape.llmOnNewState", "true",
                "ape.llmTemperature", "0.7"));
        JSONObject json = record(original);

        RunSpec rebuilt = RunSpec.resolve(paramsAsEntries(json),
                RunSpec.CliValues.of(json.getString("agent"), json.getLong("seed"), null));
        assertEquals(original.digest(), rebuilt.digest());
        assertEquals(original.features(), rebuilt.features());
    }

    @Test
    public void parametersCarryTheirDeclaredJsonType() throws Exception {
        // A reader parses the record; it does not re-parse property text out of it. Each value
        // here differs from its jar default, which is what puts it in the record at all.
        JSONObject params = record(resolve(entries(
                "ape.mopDataPath", MOP_PATH,
                "ape.mopWeightDirect", "777",
                "ape.mopStrictPackageMatch", "true",
                "ape.defaultEpsilon", "0.25"))).getJSONObject("params");

        assertEquals(777, params.getInt("ape.mopWeightDirect"));
        assertTrue(params.getBoolean("ape.mopStrictPackageMatch"));
        assertEquals(0.25d, params.getDouble("ape.defaultEpsilon"), 0.0d);
        assertEquals(MOP_PATH, params.getString("ape.mopDataPath"));
    }

    @Test
    public void defaultsAreOmittedButActiveActivationKeysAreNot() throws Exception {
        RunSpec spec = resolve(entries("ape.mopDataPath", MOP_PATH));
        JSONObject params = record(spec).getJSONObject("params");

        // ape.mopWeightDirect's jar default is already the campaign's 500, so a plan that states
        // it has chosen nothing and the record omits it — recoverable from the jar build.sha
        // names. That the arm still reconstructs from such a record is what the digest tests show.
        assertEquals("500", KeyOwnership.defaultOf("ape.mopWeightDirect"));
        assertFalse("a jar default is recoverable from the jar the record names",
                params.has("ape.mopWeightDirect"));
        // ape.doFuzzing defaults to true, so FUZZING is active without anyone stating it. Reading
        // the record must not require knowing which defaults happen to be on.
        assertTrue(spec.has(Feature.FUZZING));
        assertTrue("an active feature's activation key is always emitted",
                params.getBoolean("ape.doFuzzing"));
    }

    // -----------------------------------------------------------------------------------------
    // The retired [APE-LLM-CONFIG] manifest is carried here now
    // -----------------------------------------------------------------------------------------

    /**
     * The retired manifest field by field: its name, the plan key whose value it printed, and a
     * value to state the run at.
     *
     * <p>The stated values are chosen so the key reaches {@code params} for one of the two reasons
     * that put a key there, because a test that stated jar defaults would assert absence and prove
     * nothing about carriage. Eight of them differ from their jar default. {@code ape.llmUrl} has
     * no default, so any value it holds was chosen. The remaining three are activation keys of
     * active features, which the record emits whatever their value — and two of them are stated at
     * their default deliberately: the non-default value of a {@code Rule.TRUE} activation key is
     * {@code false}, which switches the feature off and moves the key to {@code inert}.
     */
    private static final String[][] RETIRED_LLM_MANIFEST = {
            {"model", "ape.llmModel", "Qwen/Qwen3-VL-8B-Instruct"},
            {"temperature", "ape.llmTemperature", "0.7"},
            {"top_p", "ape.llmTopP", "0.9"},
            {"top_k", "ape.llmTopK", "20"},
            {"max_tokens", "ape.llmMaxTokens", "2048"},
            {"timeout_ms", "ape.llmTimeoutMs", "20000"},
            {"prompt_variant", "ape.llmPromptVariant", "ape_v2"},
            {"llm_percentage", "ape.llmPercentage", "0.7"},
            {"on_new_state", "ape.llmOnNewState", "true"},
            {"on_stagnation", "ape.llmOnStagnation", "true"},
            {"stagnation_threshold", "ape.graphStableRestartThreshold", "50"},
            {"url", "ape.llmUrl", LLM_URL},
    };

    /**
     * The manifest emitter was deleted, not replaced, so what has to hold is that every field it
     * printed is in this record. The check is per-field rather than by digest because the claim
     * being retired is field-level: a reader of a stage-4 trace looks for {@code temperature} and
     * finds it under a plan key, and a digest equal by luck would not tell them that.
     *
     * <p>{@code stagnation_threshold} is the one field whose key is not LLM-scoped — the
     * exploration restart threshold that the router only read the midpoint of. It reached the
     * manifest through a constructor parameter that existed for no other purpose, which is why
     * that parameter died with the emitter.
     */
    @Test
    public void runStartCarriesEveryFieldOfTheRetiredLlmConfigManifest() throws Exception {
        Map<String, String> plan = entries();
        for (String[] field : RETIRED_LLM_MANIFEST) {
            plan.put(field[1], field[2]);
        }
        JSONObject json = record(resolve(plan));
        JSONObject params = json.getJSONObject("params");

        for (String[] field : RETIRED_LLM_MANIFEST) {
            String manifestField = field[0];
            String key = field[1];
            assertTrue("the manifest's " + manifestField + " has no carrier in the record",
                    params.has(key));
            assertTrue(manifestField + " reached the record with a different value",
                    KeyOwnership.typeOf(key)
                            .sameValue(key, String.valueOf(params.get(key)), field[2]));
        }
        // The manifest said what the run would send; t0 says when it started sending. A trace
        // needs both, and stage 4's step records are measured from this one.
        assertTrue("the time base rides the same record", json.has("t0"));
    }

    /**
     * The same fields on a run that states none of them — the case the record answers by omission.
     *
     * <p>This is the honest half of the previous test. A minimal LLM arm states only the server
     * URL, and eight manifest fields then sit at their jar default and are absent from
     * {@code params} by the volume rule. They are recoverable rather than lost, and what makes
     * them recoverable is that the jar holding the default is named by {@code build.sha} in the
     * same record — so the assertion here is that each such key <em>has</em> a jar default to be
     * recovered from. A key with no default and no value stated would be genuinely unreadable.
     */
    @Test
    public void aManifestFieldLeftAtItsDefaultStaysRecoverableFromTheJarTheRecordNames()
            throws Exception {
        JSONObject json = record(resolve(entries("ape.llmUrl", LLM_URL)));
        JSONObject params = json.getJSONObject("params");

        assertFalse("the build stamp is what the omitted defaults are recovered from",
                json.getJSONObject("build").getString("sha").isEmpty());
        for (String[] field : RETIRED_LLM_MANIFEST) {
            String key = field[1];
            if (params.has(key)) {
                // Either chosen (ape.llmUrl, which has no default) or the activation key of an
                // active feature, which the record emits so that reading it never requires
                // knowing which defaults happen to be on.
                continue;
            }
            assertTrue("the manifest's " + field[0] + " is omitted with nothing to recover it from",
                    KeyOwnership.defaultOf(key) != null);
        }
    }

    // -----------------------------------------------------------------------------------------
    // inert, corpus_basis, build
    // -----------------------------------------------------------------------------------------

    @Test
    public void inertListsTheKeysAcceptedForAMechanismThePlanDoesNotHave() throws Exception {
        // Every baseline arm pushes this key on a run with no LLM at all. It is recorded as
        // accepted-and-doing-nothing, and it is not a plan value, so it is not in params.
        JSONObject json = record(resolve(entries("ape.llmPercentageNoSubstrate", "-1")));

        JSONArray inert = json.getJSONArray("inert");
        assertEquals(1, inert.length());
        assertEquals("ape.llmPercentageNoSubstrate", inert.getString(0));
        assertFalse("an inert key parameterizes nothing, so it is not a parameter",
                json.getJSONObject("params").has("ape.llmPercentageNoSubstrate"));
        // The feature it belongs to is not in the plan — which is the whole reason it is inert.
        // (The plan is not featureless: a bare run carries every feature whose activation key
        // defaults on, which is why this asserts an absence rather than an empty list.)
        assertFalse(names(json.getJSONArray("features")).contains(Feature.LLM_RANDOM.name()));
        assertFalse(names(json.getJSONArray("features")).contains(Feature.LLM.name()));
    }

    @Test
    public void corpusBasisIsEchoedWhenSuppliedAndOmittedWhenNot() throws Exception {
        String basis = "subset40:9f2c8b1e4d6a0c3f5e7b9d1a2c4e6f80";
        assertEquals(basis, record(resolve(entries("ape.corpusBasis", basis)))
                .getString("corpus_basis"));
        // Omitted rather than defaulted: an invented basis would be a claim about which
        // application list the run was drawn from.
        assertFalse(record(resolve(entries())).has("corpus_basis"));
    }

    @Test
    public void theRecordNamesTheJarItRanFrom() throws Exception {
        JSONObject build = record(resolve(entries())).getJSONObject("build");
        assertEquals(BuildInfo.GIT_SHA, build.getString("sha"));
        assertEquals(BuildInfo.JAR_BUILT, build.getString("time"));
        assertFalse("an unfiltered template placeholder would make the stamp worthless",
                build.getString("sha").contains("${"));
    }

    @Test
    public void theRecordCarriesItsIdentityVersionAndTimeBase() throws Exception {
        RunSpec spec = resolve(entries());
        long before = System.currentTimeMillis();
        JSONObject json = record(spec);

        assertEquals("RUN_START", json.getString("type"));
        assertEquals(1, json.getInt("v"));
        assertEquals(RunContext.current().runId(), json.getString("run_id"));
        assertEquals(12345L, json.getLong("seed"));
        assertEquals("sata", json.getString("agent"));
        assertEquals(RunSpec.PRESET_EXPLICIT, json.getString("preset"));
        assertEquals(spec.digest(), json.getString("digest"));
        assertEquals(spec.propertiesDigest(), json.getString("props_digest"));
        // t0 is the base stage 4's relative step offsets resolve against.
        assertTrue("t0 must be the emission instant", json.getLong("t0") >= before);
        assertTrue(json.getLong("t0") <= System.currentTimeMillis());
    }

    @Test
    public void thePresetNameIsRecordedEvenThoughTheDigestIgnoresIt() throws Exception {
        // How the plan was written is provenance; what the plan is, is the digest. Both are in
        // the record, and only one of them joins runs of an arm.
        RunSpec byPreset = resolve(presetPlan(Presets.MOP));
        RunSpec byHand = RunSpec.resolve(paramsAsEntries(record(byPreset)),
                RunSpec.CliValues.of("sata", 12345L, null));

        assertEquals(Presets.MOP, record(byPreset).getString("preset"));
        assertEquals(RunSpec.PRESET_EXPLICIT, record(byHand).getString("preset"));
        assertEquals(byPreset.digest(), byHand.digest());
    }

    // -----------------------------------------------------------------------------------------
    // Hostile values survive the whole path, not just the serializer
    // -----------------------------------------------------------------------------------------

    @Test
    public void aHostileParameterValueKeepsTheRecordOnOneLine() throws Exception {
        // ape.llmPromptVariant is an operator-supplied string that reaches the record unmodified.
        // A newline in it would split the record, and a quote would end it early.
        String hostile = "ape_current\"v2\\draft\nsecond line\ttabbed";
        RunSpec spec = resolve(entries(
                "ape.llmUrl", LLM_URL,
                "ape.llmPromptVariant", hostile));

        String written = emit(spec);
        assertEquals("a hostile value must not split the record",
                written.length() - 1, written.indexOf('\n'));
        assertEquals(hostile, new JSONObject(written).getJSONObject("params")
                .getString("ape.llmPromptVariant"));
    }

    // -----------------------------------------------------------------------------------------
    // Position: the record precedes agent construction
    // -----------------------------------------------------------------------------------------

    /**
     * Guards the emission site's position in {@code Monkey.run} by reading the source.
     *
     * <p>Stated honestly: this is a source-order guard, not a runtime-order proof. The runtime
     * order cannot be exercised from the JVM suite — the statements it orders are
     * {@code AndroidDevice} initialisation and the {@code MonkeySourceApe} constructor, both of
     * which need a device — so a runtime assertion here would have to restate the ordering rather
     * than execute it. What this catches is the realistic regression: someone moving the echo
     * below the source construction while refactoring the bootstrap. The device-side check that
     * the record precedes every {@code [APE-*]} line is task 8.2, and it is owner-executed.
     */
    @Test
    public void theEchoIsWiredAheadOfAgentConstructionInTheBootstrap() throws Exception {
        Path monkey = Paths.get("src", "main", "java", "com", "android", "commands", "monkey",
                "Monkey.java");
        assertTrue("bootstrap source not found at " + monkey.toAbsolutePath(),
                Files.isRegularFile(monkey));
        String body = new String(Files.readAllBytes(monkey), StandardCharsets.UTF_8);

        int resolved = body.indexOf("resolveRunSpec()");
        int initialized = body.indexOf("RunContext.initialize(");
        int emitted = body.indexOf("RunSpecEcho.emit(");
        int constructed = body.indexOf("new MonkeySourceApe(");

        // A guard that silently matches nothing is worse than no guard.
        assertTrue("resolveRunSpec call site not found", resolved >= 0);
        assertTrue("RunContext.initialize call site not found", initialized >= 0);
        assertTrue("RunSpecEcho.emit call site not found", emitted >= 0);
        assertTrue("MonkeySourceApe construction not found", constructed >= 0);

        assertTrue("the context is established after the plan resolves", resolved < initialized);
        assertTrue("the record is emitted after the context exists", initialized < emitted);
        assertTrue("the record must precede agent and source construction", emitted < constructed);
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Turns a record's {@code params} object back into resolver input — the reconstruction under
     * test. Values go back through their JSON type to text, which is what a reader rebuilding an
     * arm from a trace would do.
     */
    private static Set<String> names(JSONArray array) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            out.add(array.getString(i));
        }
        return out;
    }

    private static Map<String, String> paramsAsEntries(JSONObject json) throws Exception {
        JSONObject params = json.getJSONObject("params");
        Map<String, String> entries = new LinkedHashMap<>();
        for (Iterator<String> keys = params.keys(); keys.hasNext();) {
            String key = keys.next();
            entries.put(key, String.valueOf(params.get(key)));
        }
        // The inert list is the record saying "these keys were stated and parameterized nothing",
        // and a key is inert at exactly one value — its neutral one — so the name plus the jar
        // gives the value back. Restoring them is not optional bookkeeping: an arm that turns a
        // MOP-family mechanism off (ape.frontierBoostWeight=0, ape.activityTriggerEnabled=false)
        // states a key whose *jar default* is on, and a reconstruction that dropped it would
        // switch the mechanism back on. This uses the same jar the record names by build.sha that
        // the omitted defaults already require.
        JSONArray inert = json.getJSONArray("inert");
        for (int i = 0; i < inert.length(); i++) {
            String key = inert.getString(i);
            entries.put(key, KeyOwnership.spec(key).feature().neutralValue(key));
        }
        return entries;
    }
}

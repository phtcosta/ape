package com.android.commands.monkey.ape.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Resolution: the inert rule, preset merging, the clamps, and digest determinism.
 *
 * <p>The digest tests are the load-bearing ones. A digest that changed with how the plan was
 * <em>written</em> rather than with what the plan <em>is</em> would be useless for the job it
 * exists to do — joining runs of the same arm across a campaign — so key order, the split across
 * two properties files, and preset-versus-explicit expression must all be invisible to it, while
 * any real difference in the effective plan must not be.
 */
public class RunSpecResolveTest {

    private static final String MOP_PATH = "/data/local/tmp/static_analysis.json";
    private static final String LLM_URL = "http://10.0.2.2:30000/v1";

    private static RunSpec resolve(Map<String, String> entries) {
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", 42L, null));
    }

    private static Map<String, String> entries(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    // -----------------------------------------------------------------------------------------
    // The inert rule — the substitute for the retired exemption registry
    // -----------------------------------------------------------------------------------------

    @Test
    public void theNoSubstrateSentinelIsInertOnAPlanWithNoLlm() {
        // Every current baseline arm pushes this key on runs that have no LLM at all. Accepting it
        // at its sentinel — and only at its sentinel — is what lets the harness keep doing that.
        RunSpec spec = resolve(entries("ape.llmPercentageNoSubstrate", "-1"));
        assertFalse(spec.has(Feature.LLM));
        assertNull(spec.llm());
        assertTrue(spec.inertKeys().contains("ape.llmPercentageNoSubstrate"));
        assertFalse("an inert key parameterizes nothing, so it is not a plan value",
                spec.effectiveValues().containsKey("ape.llmPercentageNoSubstrate"));
    }

    @Test
    public void aNonNeutralValueForAnAbsentFeatureAborts() {
        // You may state OFF for a mechanism that does not exist; you may not state ON.
        RunSpecException failure = abort(entries("ape.llmPercentageNoSubstrate", "0.5"));
        assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, failure.getReason());
        assertEquals("ape.llmPercentageNoSubstrate", failure.getKey());
    }


    @Test
    public void aSubParameterOfAnActiveFeatureIsNotInert() {
        Map<String, String> arm = entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true", "ape.mopWeightDirect", "0");
        RunSpec spec = resolve(arm);
        assertTrue(spec.has(Feature.MOP));
        assertTrue(spec.inertKeys().isEmpty());
        // A zero weight on an active substrate is a configuration, not an absent mechanism.
        assertEquals("0", spec.effectiveValues().get("ape.mopWeightDirect"));
    }

    // -----------------------------------------------------------------------------------------
    // The MOP scoring mode (llm-coordinate-single-base Part B, D18–D20, INV-RUN-09)
    // -----------------------------------------------------------------------------------------

    @Test
    public void theSixMopScoringKeysResolveToTheirDefaults() {
        RunSpec spec = resolve(entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true"));
        Map<String, String> mop = spec.mop().values();
        assertEquals("flag", mop.get("ape.mopScoring"));
        assertEquals("500", mop.get("ape.mopWeightD1"));
        assertEquals("400", mop.get("ape.mopWeightD2"));
        assertEquals("300", mop.get("ape.mopWeightD3"));
        assertEquals("3", mop.get("ape.mopRetireAfter"));
        assertEquals("6", mop.get("ape.mopLauncherDmax"));
        assertTrue("the plan states none of them, so none is inert", spec.inertKeys().isEmpty());
    }

    @Test
    public void mopParamsReadsTheSixKeysThroughTypedAccessors() {
        RunSpec.MopParams defaults = resolve(entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true")).mop();
        assertEquals(MopScoring.FLAG, defaults.scoring());
        assertEquals(500, defaults.weightD1());
        assertEquals(400, defaults.weightD2());
        assertEquals(300, defaults.weightD3());
        assertEquals(3, defaults.retireAfter());
        assertEquals(6, defaults.launcherDmax());

        RunSpec.MopParams stated = resolve(entries(
                "ape.staticDataPath", MOP_PATH, "ape.mop", "true",
                "ape.mopScoring", "distance",
                "ape.mopWeightD1", "600",
                "ape.mopWeightD2", "450",
                "ape.mopWeightD3", "350",
                "ape.mopRetireAfter", "5",
                "ape.mopLauncherDmax", "4")).mop();
        assertEquals(MopScoring.DISTANCE, stated.scoring());
        assertEquals(600, stated.weightD1());
        assertEquals(450, stated.weightD2());
        assertEquals(350, stated.weightD3());
        assertEquals(5, stated.retireAfter());
        assertEquals(4, stated.launcherDmax());
    }

    @Test
    public void theTwoModesReadDisjointKeySets() {
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList(
                        "ape.mopWeightDirect", "ape.mopWeightTransitive")),
                MopScoring.DISTANCE.unreadKeys());
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList(
                        "ape.mopWeightD1", "ape.mopWeightD2", "ape.mopWeightD3",
                        "ape.mopRetireAfter", "ape.mopLauncherDmax")),
                MopScoring.FLAG.unreadKeys());
        for (MopScoring mode : MopScoring.values()) {
            assertFalse(mode.unreadKeys().contains(MopScoring.KEY));
            for (String key : mode.modeKeys()) {
                assertEquals(key + " is a MOP key", Feature.MOP, KeyOwnership.spec(key).feature());
            }
        }
    }

    @Test
    public void theDistanceCellMarksTheFlagWeightsInert() {
        RunSpec spec = resolve(entries(
                "ape.preset", Presets.AS_MOP,
                "ape.staticDataPath", MOP_PATH,
                "ape.mopWeightDirect", "500", "ape.mopWeightTransitive", "300"));
        assertTrue(spec.has(Feature.MOP));
        assertEquals("distance", spec.mop().values().get("ape.mopScoring"));
        assertTrue(spec.inertKeys().contains("ape.mopWeightDirect"));
        assertTrue(spec.inertKeys().contains("ape.mopWeightTransitive"));
        assertFalse("the mode key itself is never inert while MOP is in the plan",
                spec.inertKeys().contains("ape.mopScoring"));
        // Inert by mode is not inert by absence: the keys keep their place in the plan.
        assertEquals("500", spec.effectiveValues().get("ape.mopWeightDirect"));
        assertEquals("300", spec.effectiveValues().get("ape.mopWeightTransitive"));
    }

    @Test
    public void theFlagModeOnAMopCellHasNoModeInertKey() {
        RunSpec spec = resolve(entries("ape.preset", Presets.AS_MOP, "ape.staticDataPath", MOP_PATH,
                "ape.mopScoring", "flag"));
        assertFalse(spec.inertKeys().contains("ape.mopWeightDirect"));
        assertFalse(spec.inertKeys().contains("ape.mopWeightTransitive"));
    }

    @Test
    public void distanceKeysStatedUnderFlagAreInertNotRejected() {
        RunSpec spec = resolve(entries(
                "ape.staticDataPath", MOP_PATH, "ape.mop", "true",
                "ape.mopScoring", "flag",
                "ape.mopWeightD2", "450",
                "ape.mopRetireAfter", "5"));
        assertEquals(Arrays.asList("ape.mopRetireAfter", "ape.mopWeightD2"), spec.inertKeys());
        assertEquals("450", spec.effectiveValues().get("ape.mopWeightD2"));
    }

    @Test
    public void everyDistanceKeyStatedUnderFlagIsListedAndNoOtherMopKey() {
        RunSpec spec = resolve(entries(
                "ape.staticDataPath", MOP_PATH, "ape.mop", "true",
                "ape.mopWeightDirect", "500",
                "ape.mopWeightTransitive", "300",
                "ape.mopWeightD1", "500",
                "ape.mopWeightD2", "400",
                "ape.mopWeightD3", "300",
                "ape.mopRetireAfter", "3",
                "ape.mopLauncherDmax", "6"));
        assertEquals(Arrays.asList("ape.mopLauncherDmax", "ape.mopRetireAfter",
                "ape.mopWeightD1", "ape.mopWeightD2", "ape.mopWeightD3"), spec.inertKeys());
    }

    @Test
    public void aNonNeutralModeInertValueIsAcceptedAndStillEntersTheDigest() {
        // Unlike a key of an absent feature, a key the mode does not read is accepted at any value
        // of its type (INV-RUN-09): the plan may state both modes' weights.
        Map<String, String> arm = entries(
                "ape.staticDataPath", MOP_PATH, "ape.mop", "true",
                "ape.mopScoring", "distance",
                "ape.mopWeightDirect", "777");
        RunSpec spec = resolve(arm);
        assertEquals(Arrays.asList("ape.mopWeightDirect"), spec.inertKeys());
        assertEquals("777", spec.effectiveValues().get("ape.mopWeightDirect"));

        Map<String, String> other = new LinkedHashMap<>(arm);
        other.put("ape.mopWeightDirect", "500");
        assertNotEquals("the key keeps its value in the plan, so it enters the digest",
                spec.digest(), resolve(other).digest());
    }

    @Test
    public void theModeSplitsTheFiveDistanceKeysFromTheTwoFlagWeights() {
        Map<String, String> all = entries(
                "ape.staticDataPath", MOP_PATH, "ape.mop", "true",
                "ape.mopWeightDirect", "500",
                "ape.mopWeightTransitive", "300",
                "ape.mopWeightD1", "500",
                "ape.mopWeightD2", "400",
                "ape.mopWeightD3", "300",
                "ape.mopRetireAfter", "3",
                "ape.mopLauncherDmax", "6");
        all.put("ape.mopScoring", "distance");
        assertEquals(Arrays.asList("ape.mopWeightDirect", "ape.mopWeightTransitive"),
                resolve(all).inertKeys());
    }

    @Test
    public void theModeKeyOfAnAbsentMopIsInertAtFlag() {
        RunSpec spec = resolve(entries("ape.mopScoring", "flag"));
        assertNull(spec.mop());
        assertEquals(Arrays.asList("ape.mopScoring"), spec.inertKeys());
        assertFalse(spec.effectiveValues().containsKey("ape.mopScoring"));
    }

    @Test
    public void theDistanceModeOfAnAbsentMopAborts() {
        RunSpecException failure = abort(entries("ape.mopScoring", "distance"));
        assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, failure.getReason());
        assertEquals("ape.mopScoring", failure.getKey());
    }

    // -----------------------------------------------------------------------------------------
    // Presets
    // -----------------------------------------------------------------------------------------

    @Test
    public void anExplicitKeyOverridesThePreset() {
        Map<String, String> input = entries(
                "ape.preset", Presets.AS_MOP,
                "ape.staticDataPath", MOP_PATH,
                "ape.mopWeightWtg", "150");
        RunSpec spec = resolve(input);
        assertEquals(Presets.AS_MOP, spec.presetName());
        assertEquals("150", spec.effectiveValues().get("ape.mopWeightWtg"));
        assertEquals("the preset's other weights survive the override",
                "200", spec.effectiveValues().get("ape.mopFrontierWeight"));
    }

    @Test
    public void aPlanWithNoPresetReportsItselfAsExplicit() {
        assertEquals(RunSpec.PRESET_EXPLICIT, resolve(entries()).presetName());
    }

    // -----------------------------------------------------------------------------------------
    // Clamps: documented value semantics, not typos, so they clamp rather than abort
    // -----------------------------------------------------------------------------------------

    @Test
    public void theLoadTimeClampsArePreservedAsClamps() {
        Map<String, String> arm = entries(
                "ape.staticDataPath", MOP_PATH, "ape.mop", "true",
                "ape.activityTriggerEnabled", "true",
                "ape.activityTriggerStagnationStep", "0",
                "ape.activityTriggerMaxPerRun", "-5");
        RunSpec spec = resolve(arm);
        assertEquals("50", spec.effectiveValues().get("ape.activityTriggerStagnationStep"));
        assertEquals("0", spec.effectiveValues().get("ape.activityTriggerMaxPerRun"));

        RunSpec coin = resolve(entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true",
                "ape.mopCoinLedgerMaxInteractions", "0"));
        assertEquals("1", coin.effectiveValues().get("ape.mopCoinLedgerMaxInteractions"));
        assertEquals(1, coin.mop().coinLedgerMaxInteractions());
        assertEquals(3, resolve(entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true",
                "ape.mopCoinLedgerMaxInteractions", "3")).mop().coinLedgerMaxInteractions());

        RunSpec llm = resolve(entries("ape.llm", "true", "ape.llmUrl", LLM_URL, "ape.llmPercentage", "1.5"));
        assertEquals(1.0d,
                Double.parseDouble(llm.effectiveValues().get("ape.llmPercentage")), 1e-9);
    }

    // -----------------------------------------------------------------------------------------
    // Digest determinism
    // -----------------------------------------------------------------------------------------

    @Test
    public void keyOrderDoesNotChangeTheDigest() {
        Map<String, String> forward = entries(
                "ape.staticDataPath", MOP_PATH, "ape.mop", "true", "ape.mopWeightDirect", "400",
                "ape.mopWeightTransitive", "200");
        Map<String, String> reversed = entries(
                "ape.mopWeightTransitive", "200", "ape.mopWeightDirect", "400",
                "ape.staticDataPath", MOP_PATH, "ape.mop", "true");
        assertEquals(resolve(forward).digest(), resolve(reversed).digest());
    }

    @Test
    public void theSplitAcrossTwoPropertiesFilesDoesNotChangeTheDigest() {
        // The resolver sees the union in load order, later file winning. Whether a key arrived in
        // /data/local/tmp or /sdcard is a fact about the deployment, not about the plan — and it is
        // propertiesDigest, not digest, that records it.
        Map<String, String> single = entries(
                "ape.staticDataPath", MOP_PATH, "ape.mop", "true", "ape.mopWeightDirect", "400");
        Map<String, String> merged = new LinkedHashMap<>();
        merged.putAll(entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true", "ape.mopWeightDirect", "999"));
        merged.putAll(entries("ape.mopWeightDirect", "400"));
        assertEquals(resolve(single).digest(), resolve(merged).digest());
    }

    @Test
    public void aPresetAndTheSamePlanWrittenOutHaveTheSameDigest() {
        // INV-RUN-04's hardest clause: the digest identifies the arm as interpreted, so how the
        // arm was expressed must be invisible to it. This is why the preset name is not hashed.
        Map<String, String> viaPreset = entries("ape.preset", Presets.AS_MOP,
                "ape.staticDataPath", MOP_PATH);
        Map<String, String> written = new LinkedHashMap<>(Presets.resolve(Presets.AS_MOP));
        written.put("ape.staticDataPath", MOP_PATH);

        RunSpec fromPreset = resolve(viaPreset);
        RunSpec fromKeys = resolve(written);
        assertEquals(fromPreset.digest(), fromKeys.digest());
        assertNotEquals("the preset name is still reported, just not hashed",
                fromPreset.presetName(), fromKeys.presetName());
    }

    @Test
    public void statingADefaultExplicitlyDoesNotChangeTheDigest() {
        assertEquals(resolve(entries()).digest(),
                resolve(entries("ape.maxThrottle", "5000")).digest());
    }

    @Test
    public void equivalentNumericSpellingsDoNotChangeTheDigest() {
        assertEquals(resolve(entries("ape.componentPercentage", "0")).digest(),
                resolve(entries("ape.componentPercentage", "0.0")).digest());
    }

    @Test
    public void theSeedAndRunIdAreExcludedFromTheDigest() {
        Map<String, String> arm = entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true");
        RunSpec first = RunSpec.resolve(arm, RunSpec.CliValues.of("sata", 1L, null));
        RunSpec second = RunSpec.resolve(arm, RunSpec.CliValues.of("sata", 999L, null));
        assertEquals(first.digest(), second.digest());

        Map<String, String> withRunId = entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true",
                "ape.runId", "20260803-000000-abcdef01");
        assertEquals(first.digest(), resolve(withRunId).digest());
        assertEquals("20260803-000000-abcdef01", resolve(withRunId).runId());
    }

    @Test
    public void theCorpusBasisIsEchoedButNotHashed() {
        Map<String, String> arm = entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true");
        Map<String, String> withBasis = entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true",
                "ape.corpusBasis", "subset40:2b9d4c1f");
        assertEquals(resolve(arm).digest(), resolve(withBasis).digest());
        assertEquals("subset40:2b9d4c1f", resolve(withBasis).corpusBasis());
        assertNull("absent means absent, never a default", resolve(arm).corpusBasis());
    }

    @Test
    public void aRealDifferenceInThePlanDoesChangeTheDigest() {
        assertNotEquals(resolve(entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true")).digest(),
                resolve(entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true", "ape.mopWeightDirect", "400"))
                        .digest());
        assertNotEquals("the agent type is part of the plan",
                RunSpec.resolve(entries(), RunSpec.CliValues.of("sata", 42L, null)).digest(),
                RunSpec.resolve(entries(), RunSpec.CliValues.of("random", 42L, null)).digest());
    }

    // -----------------------------------------------------------------------------------------
    // The properties digest: which file the run saw
    // -----------------------------------------------------------------------------------------

    @Test
    public void thePropertiesDigestFollowsTheRawBytes() {
        byte[] one = "ape.maxThrottle=5000\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withComment =
                "# tuned 2026-08\nape.maxThrottle=5000\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(RunSpec.digestProperties(one, null), RunSpec.digestProperties(one, null));
        assertNotEquals("a comment changes the file, so it must change the file's digest",
                RunSpec.digestProperties(one, null), RunSpec.digestProperties(withComment, null));
        assertNotEquals("an absent second file is not the same as an empty one",
                RunSpec.digestProperties(one, null), RunSpec.digestProperties(one, new byte[0]));
        assertNotEquals("the two files are not interchangeable",
                RunSpec.digestProperties(one, null), RunSpec.digestProperties(null, one));
    }

    @Test
    public void aResolveWithNoFilesReportsTheAbsentSentinel() {
        assertEquals(RunSpec.PROPERTIES_DIGEST_NONE, resolve(entries()).propertiesDigest());
        assertEquals(RunSpec.digestProperties(null, null), RunSpec.PROPERTIES_DIGEST_NONE);
    }

    // -----------------------------------------------------------------------------------------
    // Immutability
    // -----------------------------------------------------------------------------------------

    @Test
    public void theResolvedPlanCannotBeMutatedThroughItsAccessors() {
        RunSpec spec = resolve(entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true"));
        assertThrowsUnsupported(() -> spec.features().clear());
        assertThrowsUnsupported(() -> spec.effectiveValues().put("ape.maxThrottle", "1"));
        assertThrowsUnsupported(() -> spec.inertKeys().add("ape.maxThrottle"));
        assertThrowsUnsupported(() -> spec.mop().values().clear());
    }

    @Test
    public void mutatingTheInputMapAfterResolutionDoesNotChangeThePlan() {
        Map<String, String> input = entries("ape.staticDataPath", MOP_PATH, "ape.mop", "true");
        RunSpec spec = resolve(input);
        String before = spec.digest();
        input.put("ape.mopWeightDirect", "1");
        assertEquals(before, spec.digest());
        assertEquals("500", spec.effectiveValues().get("ape.mopWeightDirect"));
    }

    private static void assertThrowsUnsupported(Runnable mutation) {
        try {
            mutation.run();
            org.junit.Assert.fail("expected the view to be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // the plan is immutable after resolve returns (INV-RUN-01)
        }
    }

    private static RunSpecException abort(Map<String, String> entries) {
        try {
            resolve(entries);
        } catch (RunSpecException e) {
            return e;
        }
        throw new AssertionError("expected resolution to abort");
    }
}

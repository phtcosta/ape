package com.android.commands.monkey.ape.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The four jar-resident presets, pinned against the harness arm dictionaries they were translated
 * from.
 *
 * <p>The vectors were read at rvsec commit {@code 6dc8a0af} from
 * {@code modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py} (sha256
 * {@code 660f151709b12dc311e6ddaa221d4af968b2630d235149cd22f94bada7736612}) — from the code rather
 * than from the design document, because a preset vector asserted from prose is a guess with a
 * citation.
 *
 * <p>The other half of the pin is the group of tests at the bottom of this class: each preset,
 * plus the deployment-specific keys it deliberately omits, resolves to the same digest as the
 * harness's own generated properties file for the corresponding arm. The vector assertions above
 * check that the jar says what we think it says; those check that what it says still matches what
 * the harness sends. A vector can be internally consistent and still have drifted.
 *
 * <p>Until stage 5 makes {@code preset + overrides} the harness contract, these vectors are the
 * only thing keeping the jar's idea of an arm and the harness's idea of an arm in agreement.
 */
public class PresetsTest {

    /** {@code _BASELINE_ARM_FLAGS} minus {@code ape_pure_mode}, plus {@code throttle_ms}. */
    private static final Map<String, String> APERV_VECTOR = vector(
            "ape.backMenuPickCap", "3",
            "ape.foreignActivityGuard", "true",
            "ape.treePackageGuard", "true",
            "ape.dynamicEpsilon", "true",
            "ape.heuristicInput", "true",
            "ape.fuzzInputTyped", "true",
            "ape.formCompletionEnabled", "true",
            "ape.modelMenuEnabled", "true",
            "ape.leastVisitedPriorityTiebreak", "true",
            "ape.treeEnhancementsEnabled", "true",
            "ape.activityBudgetEnabled", "true",
            "ape.llmPercentageNoSubstrate", "-1",
            "ape.frontierBoostWeight", "0",
            "ape.activityTriggerEnabled", "false",
            "ape.mopActivitySourceComponents", "false",
            "ape.mopFrontierWeight", "0",
            "ape.defaultGUIThrottle", "200");

    /** {@code _MOP_SUBSTRATE} minus {@code mop_data}, which is deployment-specific. */
    private static final Map<String, String> MOP_WEIGHTS = vector(
            "ape.mopWeightDirect", "500",
            "ape.mopWeightTransitive", "300",
            "ape.mopWeightOpenMenu", "250",
            "ape.mopWeightWtg", "200");

    /** {@code _LLM_FLAGS} minus {@code llm_url}, which is deployment-specific. */
    private static final Map<String, String> LLM_BLOCK = vector(
            "ape.llmOnNewState", "true",
            "ape.llmOnStagnation", "true",
            "ape.llmModel", "default",
            "ape.llmTemperature", "0.3",
            "ape.llmTopP", "0.6",
            "ape.llmTopK", "50",
            "ape.llmTimeoutMs", "15000");

    private static Map<String, String> vector(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    public void apervIsTheSataArm() {
        assertEquals(APERV_VECTOR, Presets.resolve(Presets.APERV));
    }

    @Test
    public void mopIsApervPlusTheWeightSubstrate() {
        Map<String, String> expected = vector();
        expected.putAll(APERV_VECTOR);
        expected.putAll(MOP_WEIGHTS);
        assertEquals(expected, Presets.resolve(Presets.MOP));
    }

    @Test
    public void llmIsApervPlusTheSamplingBlock() {
        Map<String, String> expected = vector();
        expected.putAll(APERV_VECTOR);
        expected.putAll(LLM_BLOCK);
        assertEquals(expected, Presets.resolve(Presets.LLM));
    }

    @Test
    public void llmMopIsMopPlusLlm() {
        Map<String, String> expected = vector();
        expected.putAll(APERV_VECTOR);
        expected.putAll(MOP_WEIGHTS);
        expected.putAll(LLM_BLOCK);
        assertEquals(expected, Presets.resolve(Presets.LLM_MOP));
    }

    @Test
    public void theSixteenSurvivingArmDefiningFlagsAreAllPresent() {
        // The harness's ARM_DEFINING_KEYS had eighteen members; ape_pure_mode left with the
        // stage-2 edit and ape.stepTelemetryEnabled leaves here, because telemetry stopped being a
        // mechanism an arm may define (event-sink INV-SNK-07). The other sixteen must all be stated
        // by the baseline preset or the arm stops being fully explicit — the property the harness's
        // own guard tests enforce.
        Set<String> armDefining = new LinkedHashSet<>(Arrays.asList(
                "ape.frontierBoostWeight", "ape.activityTriggerEnabled", "ape.backMenuPickCap",
                "ape.foreignActivityGuard", "ape.treePackageGuard", "ape.dynamicEpsilon",
                "ape.heuristicInput", "ape.fuzzInputTyped", "ape.formCompletionEnabled",
                "ape.modelMenuEnabled",
                "ape.leastVisitedPriorityTiebreak", "ape.treeEnhancementsEnabled",
                "ape.activityBudgetEnabled", "ape.mopActivitySourceComponents",
                "ape.mopFrontierWeight", "ape.llmPercentageNoSubstrate"));
        assertEquals(16, armDefining.size());
        assertTrue(Presets.resolve(Presets.APERV).keySet().containsAll(armDefining));
    }

    @Test
    public void noPresetCarriesADeploymentSpecificValue() {
        // A path and a URL belong to the machine, not to the arm. Baking either in would make the
        // preset unusable anywhere else, and would activate a feature the operator did not ask for.
        for (String name : Presets.names()) {
            Set<String> keys = Presets.resolve(name).keySet();
            assertFalse(name + " must not carry ape.mopDataPath", keys.contains("ape.mopDataPath"));
            assertFalse(name + " must not carry ape.llmUrl", keys.contains("ape.llmUrl"));
            assertFalse(name + " must not carry an agent type",
                    keys.contains("ape.agentType"));
        }
    }

    @Test
    public void everyPresetKeyIsOwned() {
        for (String name : Presets.names()) {
            for (Map.Entry<String, String> entry : Presets.resolve(name).entrySet()) {
                assertTrue(name + " sets the unowned key " + entry.getKey(),
                        KeyOwnership.isKnown(entry.getKey()));
                // A preset value must parse as its key's declared type, or the preset would abort
                // the moment anyone selected it.
                KeyOwnership.typeOf(entry.getKey()).parse(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    public void resolveReturnsAFreshMapTheCallerMayOverlay() {
        Map<String, String> first = Presets.resolve(Presets.MOP);
        Map<String, String> second = Presets.resolve(Presets.MOP);
        assertNotSame(first, second);
        first.put("ape.mopWeightDirect", "0");
        assertEquals("500", second.get("ape.mopWeightDirect"));
    }

    @Test
    public void theFourNamesAreTheWholeSet() {
        assertEquals(Arrays.asList(Presets.APERV, Presets.MOP, Presets.LLM, Presets.LLM_MOP),
                Presets.names());
    }

    // --- The other half of the pin: preset ≡ what the harness actually pushes (task 6.3). --------

    /**
     * Resolves a plan stated as a preset name plus the deployment-specific keys the preset
     * deliberately omits — the form stage 5 will make the harness contract.
     */
    private static RunSpec fromPreset(String preset, String... deploymentKeys) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(KeyOwnership.KEY_PRESET, preset);
        for (int i = 0; i < deploymentKeys.length; i += 2) {
            entries.put(deploymentKeys[i], deploymentKeys[i + 1]);
        }
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", 42L, null));
    }

    /**
     * The equivalence this class exists to protect: naming a preset and naming every key by hand
     * must produce <em>the same plan</em>, not merely a similar one.
     *
     * <p>The digest is the assertion because it is the strongest available — it covers the resolved
     * values and the feature set together, so a preset that drifted by one flag fails here even
     * though both plans still resolve. The two forms are comparable at all only because the plan
     * digest excludes {@code presetName} by design (INV-RUN-04's invariance clause): they are
     * genuinely the same run stated two ways, and the digest says so.
     *
     * <p>The deployment keys are added back explicitly, and that is the point rather than a
     * workaround — a path and a URL belong to the machine, so the preset cannot carry them while
     * the harness's file necessarily does. When one of these fails, suspect a missing deployment
     * key before suspecting the vector.
     */
    @Test
    public void apervResolvesToTheSataArmTheHarnessPushes() {
        RunSpec preset = fromPreset(Presets.APERV);
        RunSpec pushed = CompatFixtures.resolve("sata.properties");

        assertEquals(pushed.features(), preset.features());
        assertEquals(pushed.digest(), preset.digest());
    }

    @Test
    public void mopResolvesToTheSataMopWidgetArmTheHarnessPushes() {
        RunSpec preset = fromPreset(Presets.MOP,
                "ape.mopDataPath", CompatFixtures.MOP_DATA_PATH);
        RunSpec pushed = CompatFixtures.resolve("sata_mop_widget.properties");

        assertEquals(pushed.features(), preset.features());
        assertEquals(pushed.digest(), preset.digest());
    }

    @Test
    public void llmResolvesToTheSataLlmArmTheHarnessPushes() {
        RunSpec preset = fromPreset(Presets.LLM, "ape.llmUrl", CompatFixtures.LLM_URL);
        RunSpec pushed = CompatFixtures.resolve("sata_llm.properties");

        assertEquals(pushed.features(), preset.features());
        assertEquals(pushed.digest(), preset.digest());
    }

    @Test
    public void llmMopResolvesToTheSataMopLlmArmTheHarnessPushes() {
        RunSpec preset = fromPreset(Presets.LLM_MOP,
                "ape.mopDataPath", CompatFixtures.MOP_DATA_PATH,
                "ape.llmUrl", CompatFixtures.LLM_URL);
        RunSpec pushed = CompatFixtures.resolve("sata_mop_llm.properties");

        assertEquals(pushed.features(), preset.features());
        assertEquals(pushed.digest(), preset.digest());
    }

    @Test
    public void thePresetFormIsStillDistinguishableFromTheExplicitOne() {
        // The digests match, but the two plans are not indistinguishable: presetName records which
        // form was used and RUN_START echoes it. That is exactly why presetName is excluded from
        // the digest — a run stated as `ape.preset=mop` and the same run stated key by key are the
        // same experiment and must compare equal, while still being told apart in the trace.
        RunSpec preset = fromPreset(Presets.MOP, "ape.mopDataPath", CompatFixtures.MOP_DATA_PATH);
        RunSpec explicit = CompatFixtures.resolve("sata_mop_widget.properties");

        assertEquals(Presets.MOP, preset.presetName());
        assertEquals(RunSpec.PRESET_EXPLICIT, explicit.presetName());
        assertEquals(explicit.digest(), preset.digest());
    }

    @Test
    public void theLlmPresetAbortsWithoutTheDeploymentUrl() {
        // Stated in D-3 as a fail-fast rather than a fallback: the preset turns the routing gates
        // on, so resolving it without a server is a plan whose mechanism is absent. Quietly
        // resolving to "LLM off" would be the silent degradation this change exists to end.
        try {
            fromPreset(Presets.LLM);
            fail("ape.preset=llm without ape.llmUrl must abort");
        } catch (RunSpecException e) {
            assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, e.getReason());
        }
    }
}

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

/**
 * The four jar-resident presets, pinned against the harness arm dictionaries they were translated
 * from.
 *
 * <p>The vectors were read at rvsec commit {@code 6dc8a0af} from
 * {@code modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py} (sha256
 * {@code 660f151709b12dc311e6ddaa221d4af968b2630d235149cd22f94bada7736612}) — from the code rather
 * than from the design document, because a preset vector asserted from prose is a guess with a
 * citation. Group 6 of this stage adds the other half of the pin: that each preset plus the
 * deployment-specific keys resolves to the same digest as the harness's own generated properties
 * file for the corresponding arm.
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
            "ape.stepTelemetryEnabled", "true",
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
    public void theSeventeenSurvivingArmDefiningFlagsAreAllPresent() {
        // The harness's ARM_DEFINING_KEYS has eighteen members; ape_pure_mode leaves with the
        // stage-2 edit, and the other seventeen must all be stated by the baseline preset or the
        // arm stops being fully explicit — the property the harness's own guard tests enforce.
        Set<String> armDefining = new LinkedHashSet<>(Arrays.asList(
                "ape.frontierBoostWeight", "ape.activityTriggerEnabled", "ape.backMenuPickCap",
                "ape.foreignActivityGuard", "ape.treePackageGuard", "ape.dynamicEpsilon",
                "ape.heuristicInput", "ape.fuzzInputTyped", "ape.formCompletionEnabled",
                "ape.stepTelemetryEnabled", "ape.modelMenuEnabled",
                "ape.leastVisitedPriorityTiebreak", "ape.treeEnhancementsEnabled",
                "ape.activityBudgetEnabled", "ape.mopActivitySourceComponents",
                "ape.mopFrontierWeight", "ape.llmPercentageNoSubstrate"));
        assertEquals(17, armDefining.size());
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
}

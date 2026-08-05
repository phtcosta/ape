package com.android.commands.monkey.ape.runtime;

import java.util.Arrays;
import java.util.EnumSet;
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
 * The four jar-resident presets: what each one declares, and what each one resolves to.
 *
 * <p>The jar is the sole authority on what a preset means. A variant in the experimental matrix is
 * a preset name plus a dict of override deltas, so everything asserted here is asserted against the
 * preset definitions themselves — never against a captured copy of what the harness writes. A test
 * that pinned this class to generated properties would be pinning the jar to one deployment of one
 * repository, and would freeze that deployment's shape the moment it changed.
 *
 * <p>The class has two halves and they check different things. The vectors above state what each
 * preset declares, key by key, so a silent edit to a preset fails loudly. The contract tests below
 * state what a preset <em>does</em>: it resolves to a feature set, an explicit key beside it wins
 * over its base vector, and the merged result passes exactly the validation an explicit plan
 * passes. A vector can be internally consistent and still resolve to the wrong plan.
 *
 * <p>Which variants exist, and which preset each one names, is not asserted here and is not this
 * repository's to assert — that roster lives in rv-android's {@code aperv} capability.
 */
public class PresetsTest {

    /** The baseline exploration vector: every RV feature flag the preset states, plus the throttle. */
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

    /** The MOP scoring weights. The artifact path is deployment-specific and is not a preset value. */
    private static final Map<String, String> MOP_WEIGHTS = vector(
            "ape.mopWeightDirect", "500",
            "ape.mopWeightTransitive", "300",
            "ape.mopWeightOpenMenu", "250",
            "ape.mopWeightWtg", "200");

    /** The LLM gates and sampling block. The endpoint is deployment-specific and is not a preset value. */
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
        // These sixteen are the flags that decide what an arm *is*, so the baseline preset must
        // state every one of them: a flag it leaves unstated would be inherited from a jar default
        // rather than chosen, and the arm would stop being fully explicit. Two keys that once
        // belonged here are deliberately absent — ape.apePureMode, retired because purity is
        // structural, and ape.stepTelemetryEnabled, because telemetry stopped being a mechanism an
        // arm may define at all (event-sink INV-SNK-07).
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

    // --- The contract: a preset resolves, overrides win, and the result validates like any plan. --

    /**
     * A device path standing in for the MOP artifact, whose only role here is to activate
     * {@code MOP}. The tests round-trip this value and never dereference it, so it is deliberately
     * not a claim about which path the harness pushes to — that is rv-android's to state.
     */
    private static final String MOP_DATA_PATH = "/data/local/tmp/static_analysis.json";

    /** An endpoint standing in for the SGLang server; its only role here is to activate {@code LLM}. */
    private static final String LLM_URL = "http://10.0.2.2:30000/v1";

    /**
     * The exploration features the baseline preset turns on by stating them, plus the two it
     * inherits from positive jar defaults ({@code ape.coverageBoostWeight} at 100 and
     * {@code ape.doFuzzing} at true), which no preset states and none can turn off.
     */
    private static final Set<Feature> BASELINE = EnumSet.of(
            Feature.MODEL_MENU,
            Feature.FORM_COMPLETION,
            Feature.LEAST_VISITED_TIEBREAK,
            Feature.TREE_ENHANCEMENTS,
            Feature.ACTIVITY_BUDGET,
            Feature.DYNAMIC_EPSILON,
            Feature.HEURISTIC_INPUT,
            Feature.TYPED_FUZZ,
            Feature.FOREIGN_ACTIVITY_GUARD,
            Feature.TREE_PACKAGE_GUARD,
            Feature.COVERAGE_BOOST,
            Feature.FUZZING);

    /**
     * Resolves a plan stated the way the harness states one: a preset name plus the
     * deployment-specific keys the preset deliberately omits, and nothing else.
     */
    private static RunSpec fromPreset(String preset, String... deploymentKeys) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(KeyOwnership.KEY_PRESET, preset);
        for (int i = 0; i < deploymentKeys.length; i += 2) {
            entries.put(deploymentKeys[i], deploymentKeys[i + 1]);
        }
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", 42L, null));
    }

    @Test
    public void apervResolvesToTheBaselineExplorationPlan() {
        RunSpec spec = fromPreset(Presets.APERV);

        assertEquals(BASELINE, spec.features());
        assertEquals(200L, spec.exploration().lng("ape.defaultGUIThrottle"));
        // Neither root feature is reachable: the preset carries no substrate, by design.
        assertFalse(spec.has(Feature.MOP));
        assertFalse(spec.has(Feature.LLM));
    }

    @Test
    public void mopAddsTheWeightActivatedSubstrate() {
        RunSpec spec = fromPreset(Presets.MOP, "ape.mopDataPath", MOP_DATA_PATH);

        Set<Feature> expected = EnumSet.copyOf(BASELINE);
        expected.add(Feature.MOP);
        // The two weight-activated members of the MOP family this preset turns on; the frontier and
        // activity-trigger members are stated at zero/false and so stay out.
        expected.add(Feature.WTG);
        expected.add(Feature.MENU_GATEWAY);
        assertEquals(expected, spec.features());

        assertEquals(MOP_DATA_PATH, spec.mop().dataPath());
        assertEquals(250, spec.mop().weightOpenMenu());
        assertFalse(spec.has(Feature.FRONTIER));
        assertFalse(spec.has(Feature.MOP_FRONTIER));
        assertFalse(spec.has(Feature.ACTIVITY_TRIGGER));
        assertFalse(spec.has(Feature.MOP_ACTIVITY_SOURCE));
    }

    @Test
    public void llmAddsTheRoutingGates() {
        RunSpec spec = fromPreset(Presets.LLM, "ape.llmUrl", LLM_URL);

        Set<Feature> expected = EnumSet.copyOf(BASELINE);
        expected.add(Feature.LLM);
        expected.add(Feature.LLM_NEW_STATE);
        expected.add(Feature.LLM_STAGNATION);
        // Inherited, not stated — see randomRoutingIsInheritedRatherThanStated.
        expected.add(Feature.LLM_RANDOM);
        assertEquals(expected, spec.features());

        assertEquals(LLM_URL, spec.llm().url());
        assertEquals("default", spec.llm().model());
        assertFalse(spec.has(Feature.MOP));
    }

    @Test
    public void llmMopIsTheUnionOfBothSubstrates() {
        RunSpec spec = fromPreset(Presets.LLM_MOP,
                "ape.mopDataPath", MOP_DATA_PATH,
                "ape.llmUrl", LLM_URL);

        Set<Feature> expected = EnumSet.copyOf(BASELINE);
        expected.add(Feature.MOP);
        expected.add(Feature.WTG);
        expected.add(Feature.MENU_GATEWAY);
        expected.add(Feature.LLM);
        expected.add(Feature.LLM_NEW_STATE);
        expected.add(Feature.LLM_STAGNATION);
        expected.add(Feature.LLM_RANDOM);
        assertEquals(expected, spec.features());

        assertEquals(MOP_DATA_PATH, spec.mop().dataPath());
        assertEquals(LLM_URL, spec.llm().url());
    }

    @Test
    public void randomRoutingIsInheritedRatherThanStated() {
        // Worth pinning because it is invisible from the preset vector: no preset states
        // ape.llmPercentage, so both LLM presets inherit the jar's default of 0.02 and route to the
        // LLM at a 2% random rate nothing in the plan text mentions. Nothing here is wrong; the
        // point is that the resolved plan says so out loud, where Config left it implicit.
        for (String name : new String[] {Presets.LLM, Presets.LLM_MOP}) {
            assertFalse(name + " is expected NOT to state the rate",
                    Presets.resolve(name).containsKey("ape.llmPercentage"));
        }
        assertTrue(fromPreset(Presets.LLM, "ape.llmUrl", LLM_URL).has(Feature.LLM_RANDOM));
        // And it is genuinely conditional on the LLM being present, not unconditional.
        assertFalse(fromPreset(Presets.APERV).has(Feature.LLM_RANDOM));
    }

    @Test
    public void theNoSubstrateSentinelIsInertOnThePresetsWithoutAnLlm() {
        // ape.llmPercentageNoSubstrate=-1 rides on every preset, including the two with no LLM at
        // all. It parameterizes LLM_RANDOM, which those plans do not carry, so it survives only
        // because -1 is that key's declared neutral value — "you may state OFF for an absent
        // mechanism, not ON". The presets write -1 while the neutral is declared -1.0, so this also
        // pins that the comparison is by declared type and not by string.
        RunSpec aperv = fromPreset(Presets.APERV);
        RunSpec mop = fromPreset(Presets.MOP, "ape.mopDataPath", MOP_DATA_PATH);
        for (RunSpec spec : new RunSpec[] {aperv, mop}) {
            assertTrue("the sentinel must resolve as inert",
                    spec.inertKeys().contains("ape.llmPercentageNoSubstrate"));
            assertFalse(spec.has(Feature.LLM_RANDOM));
        }
    }

    @Test
    public void anExplicitKeyOverridesThePresetBaseVector() {
        // The half of the contract the preset vectors alone cannot show: a variant is a preset name
        // plus deltas, so a key stated beside the preset must win over the preset's own value. If
        // this ever reversed, every override in the experimental matrix would silently do nothing.
        RunSpec spec = fromPreset(Presets.MOP,
                "ape.mopDataPath", MOP_DATA_PATH,
                "ape.mopWeightDirect", "900");

        assertEquals("500", Presets.resolve(Presets.MOP).get("ape.mopWeightDirect"));
        assertEquals(900, spec.mop().weightDirect());
    }

    @Test
    public void aPresetPlusOverridesResolvesLikeTheSamePlanStatedKeyByKey() {
        // The equivalence that makes `preset + overrides` a restatement rather than a second
        // resolver: naming a preset and naming every one of its keys by hand must produce the same
        // plan, not merely a similar one. The digest is the assertion because it is the strongest
        // available — it covers the resolved values and the feature set together, so a preset that
        // drifted by one flag fails here even though both forms still resolve.
        //
        // The explicit side is built from the preset definition itself, not from a captured copy of
        // any harness output: what is under test is the jar's own two statements of one plan.
        Map<String, String> explicitEntries = new LinkedHashMap<>(Presets.resolve(Presets.MOP));
        explicitEntries.put("ape.mopDataPath", MOP_DATA_PATH);
        RunSpec explicit =
                RunSpec.resolve(explicitEntries, RunSpec.CliValues.of("sata", 42L, null));
        RunSpec preset = fromPreset(Presets.MOP, "ape.mopDataPath", MOP_DATA_PATH);

        assertEquals(explicit.features(), preset.features());
        assertEquals(explicit.digest(), preset.digest());

        // The digests match, but the two plans are not indistinguishable: presetName records which
        // form was used and RUN_START echoes it. That is exactly why presetName is excluded from
        // the digest — the two are the same experiment and must compare equal, while still being
        // told apart in the trace.
        assertEquals(Presets.MOP, preset.presetName());
        assertEquals(RunSpec.PRESET_EXPLICIT, explicit.presetName());
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

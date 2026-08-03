package com.android.commands.monkey.ape.runtime;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Feature derivation and the dependency matrix.
 *
 * <p>The property under test that is easiest to get wrong is the asymmetry between explicit input
 * and a jar default. {@code ape.activityTriggerEnabled} defaults to {@code true}, so a naive
 * derivation would conjure a launcher on every run that has no MOP data at all — and then either
 * abort the run or, worse, construct a mechanism with nothing to launch. The rule is that a
 * default never activates a feature whose dependencies are unmet, while an explicit statement of
 * the same thing aborts. Stating something impossible is an error; inheriting it is not.
 */
public class FeatureDerivationTest {

    /** The features a run gets from the jar defaults alone, with no properties file at all. */
    private static final Set<Feature> BARE_RUN = EnumSet.of(
            Feature.MODEL_MENU, Feature.FORM_COMPLETION, Feature.STEP_TELEMETRY,
            Feature.LEAST_VISITED_TIEBREAK, Feature.TREE_ENHANCEMENTS, Feature.ACTIVITY_BUDGET,
            Feature.DYNAMIC_EPSILON, Feature.HEURISTIC_INPUT, Feature.TYPED_FUZZ,
            Feature.FOREIGN_ACTIVITY_GUARD, Feature.TREE_PACKAGE_GUARD, Feature.COVERAGE_BOOST,
            Feature.FUZZING);

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
    // The declared matrix
    // -----------------------------------------------------------------------------------------

    @Test
    public void theMopFamilyDependsOnMop() {
        for (Feature feature : new Feature[] {Feature.WTG, Feature.MENU_GATEWAY, Feature.FRONTIER,
                Feature.MOP_FRONTIER, Feature.ACTIVITY_TRIGGER, Feature.COMPONENT_TRIGGER,
                Feature.MOP_ACTIVITY_SOURCE}) {
            assertTrue(feature + " must require MOP",
                    feature.dependencies().contains(Feature.MOP));
        }
    }

    @Test
    public void theLlmModesDependOnLlm() {
        for (Feature feature : new Feature[] {Feature.LLM_NEW_STATE, Feature.LLM_STAGNATION,
                Feature.LLM_RANDOM}) {
            assertEquals(EnumSet.of(Feature.LLM), feature.dependencies());
        }
    }

    @Test
    public void theMenuGatewayNeedsBothTheSubstrateAndTheMenuAction() {
        assertEquals(EnumSet.of(Feature.MODEL_MENU, Feature.MOP),
                Feature.MENU_GATEWAY.dependencies());
    }

    @Test
    public void theRootFeaturesDependOnNothing() {
        assertTrue(Feature.MOP.dependencies().isEmpty());
        assertTrue(Feature.LLM.dependencies().isEmpty());
        assertTrue(Feature.MODEL_MENU.dependencies().isEmpty());
    }

    @Test
    public void noDependencyIsCircular() {
        for (Feature feature : Feature.values()) {
            assertFalse(feature + " depends on itself",
                    feature.dependencies().contains(feature));
            for (Feature dependency : feature.dependencies()) {
                assertFalse(feature + " and " + dependency + " depend on each other",
                        dependency.dependencies().contains(feature));
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Derivation
    // -----------------------------------------------------------------------------------------

    @Test
    public void aBareRunCarriesOnlyTheDefaultedStandaloneFeatures() {
        RunSpec spec = resolve(entries());
        assertEquals(BARE_RUN, spec.features());
        assertNull("no MOP data means no MOP params", spec.mop());
        assertNull("no LLM url means no LLM params", spec.llm());
        assertNotNull("step telemetry defaults on", spec.telemetry());
    }

    @Test
    public void defaultsDoNotActivateAnImpossibleFeature() {
        // ape.activityTriggerEnabled defaults to true and ape.mopWeightWtg to 200, so both the
        // launcher and the WTG boost "want" to be on — but neither has a substrate.
        RunSpec spec = resolve(entries());
        assertFalse(spec.has(Feature.ACTIVITY_TRIGGER));
        assertFalse(spec.has(Feature.WTG));
        assertFalse(spec.has(Feature.MENU_GATEWAY));
        assertFalse(spec.has(Feature.FRONTIER));
        assertFalse(spec.has(Feature.LLM_NEW_STATE));
        assertFalse(spec.has(Feature.LLM_RANDOM));
    }

    @Test
    public void aMopPathActivatesTheFamilyItsDefaultsAskFor() {
        RunSpec spec = resolve(entries("ape.mopDataPath", "/data/local/tmp/static_analysis.json"));
        assertTrue(spec.has(Feature.MOP));
        // The same defaults that were inert without a substrate now take effect: this is the
        // "same defaults, same clamps" promise of explicit-key resolution.
        assertTrue(spec.has(Feature.WTG));
        assertTrue(spec.has(Feature.MENU_GATEWAY));
        assertTrue(spec.has(Feature.FRONTIER));
        assertTrue(spec.has(Feature.ACTIVITY_TRIGGER));
        assertFalse("mopFrontierWeight defaults to 0", spec.has(Feature.MOP_FRONTIER));
        assertFalse("componentPercentage defaults to 0", spec.has(Feature.COMPONENT_TRIGGER));
        assertNotNull(spec.mop());
        assertEquals("/data/local/tmp/static_analysis.json", spec.mop().dataPath());
    }

    @Test
    public void anLlmUrlActivatesTheRoutingModesItsDefaultsAskFor() {
        RunSpec spec = resolve(entries("ape.llmUrl", "http://10.0.2.2:30000/v1"));
        assertTrue(spec.has(Feature.LLM));
        assertTrue(spec.has(Feature.LLM_NEW_STATE));
        assertTrue(spec.has(Feature.LLM_STAGNATION));
        assertTrue("llmPercentage defaults to 0.02", spec.has(Feature.LLM_RANDOM));
        assertNotNull(spec.llm());
        assertEquals("http://10.0.2.2:30000/v1", spec.llm().url());
        assertEquals("default", spec.llm().model());
    }

    @Test
    public void theCampaignBaselineArmResolvesToTheBareFeatureSet() {
        // The sata arm states the jar defaults explicitly, so it must derive exactly what a run
        // with no properties file derives. If these two ever diverge, "RV exploration at the jar
        // defaults, made explicit" has stopped being true of the arm.
        RunSpec spec = resolve(new LinkedHashMap<>(Presets.resolve(Presets.APERV)));
        assertEquals(BARE_RUN, spec.features());
    }

    @Test
    public void theCampaignMopArmAddsExactlyTheSubstrateFeatures() {
        Map<String, String> arm = new LinkedHashMap<>(Presets.resolve(Presets.MOP));
        arm.put("ape.mopDataPath", "/data/local/tmp/static_analysis.json");
        RunSpec spec = resolve(arm);

        Set<Feature> expected = EnumSet.copyOf(BARE_RUN);
        expected.add(Feature.MOP);
        expected.add(Feature.WTG);
        expected.add(Feature.MENU_GATEWAY);
        assertEquals(expected, spec.features());
        // The arm explicitly turns the frontier family off, so it must stay off even with a
        // substrate present — that is the fair-test obligation the arm dictionary encodes.
        assertFalse(spec.has(Feature.FRONTIER));
        assertFalse(spec.has(Feature.MOP_FRONTIER));
        assertFalse(spec.has(Feature.ACTIVITY_TRIGGER));
    }

    @Test
    public void paramsAreNullExactlyWhenTheirFeatureIsAbsent() {
        Map<String, String> both = new LinkedHashMap<>(Presets.resolve(Presets.LLM_MOP));
        both.put("ape.mopDataPath", "/data/local/tmp/static_analysis.json");
        both.put("ape.llmUrl", "http://10.0.2.2:30000/v1");
        RunSpec spec = resolve(both);
        assertNotNull(spec.mop());
        assertNotNull(spec.llm());

        RunSpec noTelemetry = resolve(entries("ape.stepTelemetryEnabled", "false"));
        assertFalse(noTelemetry.has(Feature.STEP_TELEMETRY));
        assertNull(noTelemetry.telemetry());
    }

    @Test
    public void theFiveFormerlyMutableConfigValuesAreReadableFromThePlan() {
        // D-11: these five were non-final in Config purely so tests could toggle them. The plan is
        // where they live now, so a test that needs one builds a plan instead of mutating a global.
        Map<String, String> arm = new LinkedHashMap<>(Presets.resolve(Presets.MOP));
        arm.put("ape.mopDataPath", "/data/local/tmp/static_analysis.json");
        arm.put("ape.mopStrictPackageMatch", "true");
        RunSpec spec = resolve(arm);

        assertEquals(250, spec.mop().weightOpenMenu());
        assertEquals(0, spec.mop().frontierWeight());
        assertFalse(spec.mop().activityTriggerEnabled());
        assertTrue(spec.mop().strictPackageMatch());
        assertTrue(spec.exploration().fuzzInputTyped());
    }
}

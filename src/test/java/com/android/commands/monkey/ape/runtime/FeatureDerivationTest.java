package com.android.commands.monkey.ape.runtime;

import java.util.Collections;
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
 * derivation would conjure a launcher on every run without the MOP group — and then either abort
 * the run or, worse, construct a mechanism with nothing to launch. The rule is that a default
 * never activates a feature whose dependencies are unmet, while an explicit statement of the same
 * thing aborts. Stating something impossible is an error; inheriting it is not. Since
 * isolate-guidance-groups that same rule is what mounts the groups: every member depends on its
 * group, so a group that is off leaves its members out.
 */
public class FeatureDerivationTest {

    private static final String PATH = "/data/local/tmp/static_analysis.json";

    /** The features a run gets from the jar defaults alone: the base cell, BASE's fuzzing. */
    private static final Set<Feature> BARE_RUN = EnumSet.of(Feature.FUZZING);

    /** The ADDONS group and the members its jar defaults turn on. */
    private static final Set<Feature> ADDONS = EnumSet.of(Feature.ADDONS,
            Feature.MODEL_MENU, Feature.FORM_COMPLETION, Feature.TREE_ENHANCEMENTS,
            Feature.ACTIVITY_BUDGET, Feature.DYNAMIC_EPSILON, Feature.HEURISTIC_INPUT,
            Feature.FOREIGN_ACTIVITY_GUARD, Feature.TREE_PACKAGE_GUARD, Feature.COVERAGE_BOOST,
            Feature.BACK_MENU_CAP, Feature.ACTIVITY_STABLE_RESTART, Feature.APE_FIXES);

    private static RunSpec resolve(Map<String, String> entries) {
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", 42L, null));
    }

    private static RunSpecException abort(Map<String, String> entries) {
        try {
            resolve(entries);
        } catch (RunSpecException e) {
            return e;
        }
        throw new AssertionError("expected resolution to abort");
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
    public void everyMemberDependsOnItsGroup() {
        Feature[][] groups = {
                {Feature.ADDONS, Feature.MODEL_MENU, Feature.FORM_COMPLETION,
                        Feature.TREE_ENHANCEMENTS, Feature.ACTIVITY_BUDGET,
                        Feature.DYNAMIC_EPSILON, Feature.HEURISTIC_INPUT,
                        Feature.FOREIGN_ACTIVITY_GUARD, Feature.TREE_PACKAGE_GUARD,
                        Feature.COVERAGE_BOOST, Feature.BACK_MENU_CAP,
                        Feature.ACTIVITY_STABLE_RESTART, Feature.APE_FIXES},
                {Feature.STATIC, Feature.NAV_FRONTIER, Feature.TYPED_FUZZ,
                        Feature.COMPONENT_TRIGGER},
                {Feature.MOP, Feature.WTG, Feature.MENU_GATEWAY, Feature.MOP_FRONTIER,
                        Feature.MOP_LAUNCHER, Feature.MOP_ACTIVITY_SOURCE, Feature.MOP_COIN},
                {Feature.LLM, Feature.LLM_NEW_STATE, Feature.LLM_STAGNATION, Feature.LLM_COIN},
        };
        for (Feature[] group : groups) {
            for (int i = 1; i < group.length; i++) {
                assertTrue(group[i] + " must require " + group[0],
                        group[i].dependencies().contains(group[0]));
            }
        }
    }

    @Test
    public void theMenuGatewayNeedsBothTheMopGroupAndTheMenuAction() {
        assertEquals(EnumSet.of(Feature.MODEL_MENU, Feature.MOP),
                Feature.MENU_GATEWAY.dependencies());
    }

    @Test
    public void theRootsAreTheDataRootAddonsLlmAndFuzzing() {
        Set<Feature> roots = EnumSet.noneOf(Feature.class);
        for (Feature feature : Feature.values()) {
            if (feature.dependencies().isEmpty()) {
                roots.add(feature);
            }
        }
        assertEquals(EnumSet.of(Feature.STATIC_DATA, Feature.ADDONS, Feature.LLM,
                Feature.FUZZING), roots);
        assertEquals(EnumSet.of(Feature.STATIC_DATA), Feature.STATIC.dependencies());
        assertEquals(EnumSet.of(Feature.STATIC_DATA), Feature.MOP.dependencies());
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
    public void aBareRunIsTheBaseCell() {
        RunSpec spec = resolve(entries());
        assertEquals(BARE_RUN, spec.features());
        assertNull("no artifact means no static params", spec.staticData());
        assertNull("no MOP group means no MOP params", spec.mop());
        assertNull("no LLM group means no LLM params", spec.llm());
        assertNotNull("telemetry is always present", spec.telemetry());
    }

    @Test
    public void defaultsDoNotActivateAnImpossibleFeature() {
        // ape.activityTriggerEnabled defaults to true, ape.mopWeightWtg to 200 and every addon gate
        // to true, so all of them "want" to be on — but none has its group.
        RunSpec spec = resolve(entries());
        assertFalse(spec.has(Feature.MOP_LAUNCHER));
        assertFalse(spec.has(Feature.WTG));
        assertFalse(spec.has(Feature.MENU_GATEWAY));
        assertFalse(spec.has(Feature.NAV_FRONTIER));
        assertFalse(spec.has(Feature.MODEL_MENU));
        assertFalse(spec.has(Feature.BACK_MENU_CAP));
        assertFalse(spec.has(Feature.LLM_NEW_STATE));
        assertFalse(spec.has(Feature.LLM_COIN));
    }

    @Test
    public void aGroupOffLeavesItsMembersOutSilently() {
        // guidance-groups, "a group off leaves its members out silently".
        RunSpec spec = resolve(entries("ape.staticDataPath", PATH));
        assertEquals(EnumSet.of(Feature.FUZZING, Feature.STATIC_DATA), spec.features());
        assertNotNull(spec.staticData());
        assertEquals(Collections.singletonList("ape.staticDataPath"), spec.inertKeys());
    }

    @Test
    public void anExplicitMemberKeyWithoutItsGroupAborts() {
        RunSpecException failure = abort(entries("ape.modelMenuEnabled", "true"));
        assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, failure.getReason());
        assertEquals("ape.modelMenuEnabled", failure.getKey());
        assertTrue(failure.getMessage(), failure.getMessage().contains("ADDONS"));

        failure = abort(entries("ape.staticDataPath", PATH, "ape.frontierBoostWeight", "200"));
        assertEquals("ape.frontierBoostWeight", failure.getKey());
        assertTrue(failure.getMessage(), failure.getMessage().contains("STATIC"));
    }

    @Test
    public void aGroupWithoutItsDataAborts() {
        RunSpecException failure = abort(entries("ape.mop", "true"));
        assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, failure.getReason());
        assertEquals("ape.mop", failure.getKey());
        assertTrue(failure.getMessage(), failure.getMessage().contains("STATIC_DATA"));

        failure = abort(entries("ape.llm", "true"));
        assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, failure.getReason());
        assertTrue(failure.getMessage(), failure.getMessage().contains("ape.llmUrl"));
    }

    @Test
    public void staticAndMopSwitchIndependentlyOverTheSameArtifact() {
        RunSpec spec = resolve(entries("ape.staticDataPath", PATH, "ape.static", "true",
                "ape.mop", "false"));
        assertTrue(spec.has(Feature.STATIC_DATA));
        assertTrue(spec.has(Feature.STATIC));
        assertTrue(spec.has(Feature.NAV_FRONTIER));
        assertTrue(spec.has(Feature.TYPED_FUZZ));
        assertFalse(spec.has(Feature.MOP));
        assertFalse(spec.has(Feature.MOP_LAUNCHER));
        assertNull(spec.mop());
        assertEquals(PATH, spec.staticData().dataPath());
        assertFalse("the path is read by STATIC, so it is not inert",
                spec.inertKeys().contains("ape.staticDataPath"));
    }

    @Test
    public void theMopGroupActivatesTheMembersItsDefaultsAskFor() {
        RunSpec spec = resolve(entries("ape.staticDataPath", PATH, "ape.mop", "true"));
        assertTrue(spec.has(Feature.MOP));
        // The same defaults that were inert without the group now take effect: this is the
        // "same defaults, same clamps" promise of explicit-key resolution.
        assertTrue(spec.has(Feature.WTG));
        assertTrue(spec.has(Feature.MOP_LAUNCHER));
        assertTrue("p_M defaults to 1.0", spec.has(Feature.MOP_COIN));
        assertFalse("the gateway needs the addon menu action", spec.has(Feature.MENU_GATEWAY));
        assertFalse("the frontier is STATIC, not MOP", spec.has(Feature.NAV_FRONTIER));
        assertFalse("mopFrontierWeight defaults to 0", spec.has(Feature.MOP_FRONTIER));
        assertNotNull(spec.mop());
        assertEquals(1.0, spec.mop().coinPercentage(), 0.0);
        assertNotNull("STATIC is off, but the artifact root is mounted", spec.staticData());
    }

    @Test
    public void theLlmGroupActivatesTheRoutingModesItsDefaultsAskFor() {
        RunSpec spec = resolve(entries("ape.llm", "true", "ape.llmUrl", "http://10.0.2.2:30000/v1"));
        assertTrue(spec.has(Feature.LLM));
        assertTrue(spec.has(Feature.LLM_NEW_STATE));
        assertTrue(spec.has(Feature.LLM_STAGNATION));
        assertTrue("llmPercentage defaults to 0.02", spec.has(Feature.LLM_COIN));
        assertNotNull(spec.llm());
        assertEquals("http://10.0.2.2:30000/v1", spec.llm().url());
        assertEquals("default", spec.llm().model());
        assertEquals(3, spec.llm().gateMinActions());
        assertEquals(3, spec.llm().breakerFailures());
        assertEquals(60000L, spec.llm().breakerOpenMs());
    }

    @Test
    public void anLlmUrlWithoutTheGroupAborts() {
        RunSpecException failure = abort(entries("ape.llmUrl", "http://10.0.2.2:30000/v1"));
        assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, failure.getReason());
        assertEquals("ape.llmUrl", failure.getKey());
        assertTrue(failure.getMessage(), failure.getMessage().contains("LLM"));
    }

    @Test
    public void theAddonsGroupActivatesItsMembers() {
        RunSpec spec = resolve(entries("ape.addons", "true"));
        Set<Feature> expected = EnumSet.copyOf(BARE_RUN);
        expected.addAll(ADDONS);
        assertEquals(expected, spec.features());
        assertEquals(3, spec.exploration().backMenuPickCap());
        assertEquals(200, spec.exploration().activityStableRestartThreshold());
    }

    @Test
    public void anAbsentMemberReadsAsItsNeutralValue() {
        RunSpec spec = resolve(entries());
        assertEquals("no BACK/MENU cap", 0, spec.exploration().backMenuPickCap());
        assertEquals("upstream: never restart on activity stability", Integer.MAX_VALUE,
                spec.exploration().activityStableRestartThreshold());
    }

    @Test
    public void paramsAreNullExactlyWhenTheirFeatureIsAbsent() {
        Map<String, String> both = new LinkedHashMap<>(Presets.resolve(Presets.AS_MOP_LLM));
        both.put("ape.staticDataPath", PATH);
        both.put("ape.llm", "true");
        both.put("ape.llmUrl", "http://10.0.2.2:30000/v1");
        RunSpec spec = resolve(both);
        assertNotNull(spec.staticData());
        assertNotNull(spec.mop());
        assertNotNull(spec.llm());

        // Telemetry is the exception this test is named against, and deliberately so: it is not
        // a feature, so its scope is never null and no plan can make it so.
        assertNotNull(spec.telemetry());
        assertNotNull(resolve(entries()).telemetry());
    }

    @Test
    public void telemetryCannotBeTurnedOffByAnyPlan() {
        // The gate this change removed. `ape.stepTelemetryEnabled=false` was how the baseline arm
        // was made blind under the old INV-ARCH-01; the key is gone, so stating it is a typo now
        // and aborts before step 1 rather than silently producing an untelemetered arm
        // (event-sink INV-SNK-07's Telemetry Neutrality clause).
        RunSpecException failure = abort(entries("ape.stepTelemetryEnabled", "false"));
        assertEquals(RunSpecException.Reason.UNKNOWN_KEY, failure.getReason());
        assertEquals("ape.stepTelemetryEnabled", failure.getKey());

        // Stating it ON is refused for the same reason: there is no key, in either direction.
        assertEquals(RunSpecException.Reason.UNKNOWN_KEY,
                abort(entries("ape.stepTelemetryEnabled", "true")).getReason());
    }

    /**
     * The neutral values of the six MOP scoring keys (run-spec delta, "The MOP scoring keys"):
     * zero for a weight, as for {@code ape.mopWeightDirect}, and the default for the mode, the
     * retirement count and the launcher bound.
     */
    @Test
    public void theMopFeatureOwnsTheSixScoringKeysWithTheirNeutralValues() {
        String[][] neutral = {
                {"ape.mopScoring", "flag"},
                {"ape.mopWeightD1", "0"},
                {"ape.mopWeightD2", "0"},
                {"ape.mopWeightD3", "0"},
                {"ape.mopRetireAfter", "3"},
                {"ape.mopLauncherDmax", "6"},
        };
        for (String[] row : neutral) {
            assertTrue("MOP must own " + row[0], Feature.MOP.ownedKeys().contains(row[0]));
            assertEquals(row[0] + "'s neutral value", row[1], Feature.MOP.neutralValue(row[0]));
        }
    }

    @Test
    public void theSixScoringKeysAddNoFeature() {
        // The mode is a parameter of MOP, not a mechanism of its own: selecting distance changes
        // what MOP reads, not which features the plan carries.
        Map<String, String> flag = entries("ape.staticDataPath", PATH, "ape.mop", "true");
        Map<String, String> distance = new LinkedHashMap<>(flag);
        distance.put("ape.mopScoring", "distance");
        assertEquals(resolve(flag).features(), resolve(distance).features());
    }

    @Test
    public void staticParamsCarryTheDataRootAndTheStaticMembers() {
        RunSpec spec = resolve(entries("ape.staticDataPath", PATH, "ape.static", "true",
                "ape.mopStrictPackageMatch", "true", "ape.componentPercentage", "0.03"));
        assertEquals(PATH, spec.staticData().dataPath());
        assertTrue(spec.staticData().strictPackageMatch());
        assertEquals(200, spec.staticData().frontierBoostWeight());
        assertTrue(spec.staticData().fuzzInputTyped());
        assertEquals(0.03, spec.staticData().componentPercentage(), 0.0);
        assertTrue(spec.has(Feature.COMPONENT_TRIGGER));

        // A member that is absent reads as its neutral value.
        RunSpec mopOnly = resolve(entries("ape.staticDataPath", PATH, "ape.mop", "true"));
        assertEquals(0, mopOnly.staticData().frontierBoostWeight());
        assertFalse(mopOnly.staticData().fuzzInputTyped());
        assertEquals(0.0, mopOnly.staticData().componentPercentage(), 0.0);
    }
}

package com.android.commands.monkey.ape.runtime;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What each of the six cells resolves to (guidance-groups, "Cell Presets as Unions of Group
 * Blocks"): its feature set, with the harness keys of its row and nothing else.
 */
public class CellPresetTest {

    private static final String PATH = "/data/local/tmp/mop-artifact.json";
    private static final String LLM_URL = "http://10.0.2.2:30000/v1";

    private static final Set<Feature> BASE = EnumSet.of(Feature.FUZZING);

    private static final Set<Feature> MOP = EnumSet.of(Feature.FUZZING, Feature.STATIC_DATA,
            Feature.MOP, Feature.WTG, Feature.MOP_FRONTIER, Feature.MOP_LAUNCHER,
            Feature.MOP_ACTIVITY_SOURCE, Feature.MOP_COIN);

    private static final Set<Feature> AS = EnumSet.of(Feature.FUZZING, Feature.ADDONS,
            Feature.MODEL_MENU, Feature.FORM_COMPLETION, Feature.TREE_ENHANCEMENTS,
            Feature.ACTIVITY_BUDGET, Feature.DYNAMIC_EPSILON, Feature.HEURISTIC_INPUT,
            Feature.FOREIGN_ACTIVITY_GUARD, Feature.TREE_PACKAGE_GUARD, Feature.COVERAGE_BOOST,
            Feature.BACK_MENU_CAP, Feature.ACTIVITY_STABLE_RESTART, Feature.STATIC_DATA,
            Feature.STATIC, Feature.NAV_FRONTIER, Feature.TYPED_FUZZ, Feature.COMPONENT_TRIGGER);

    private static final Set<Feature> LLM = EnumSet.of(Feature.LLM, Feature.LLM_NEW_STATE,
            Feature.LLM_STAGNATION, Feature.LLM_COIN);

    private static RunSpec cell(String preset, String... harnessKeys) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(KeyOwnership.KEY_PRESET, preset);
        entries.put(KeyOwnership.KEY_VARIANT, preset);
        for (int i = 0; i < harnessKeys.length; i += 2) {
            entries.put(harnessKeys[i], harnessKeys[i + 1]);
        }
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", 42L, null));
    }

    @SafeVarargs
    private static Set<Feature> union(Set<Feature>... sets) {
        Set<Feature> out = EnumSet.noneOf(Feature.class);
        for (Set<Feature> set : sets) {
            out.addAll(set);
        }
        return out;
    }

    @Test
    public void baseIsTheJarDefaults() {
        RunSpec spec = cell(Presets.BASE);
        assertEquals(BASE, spec.features());
        assertNull(spec.staticData());
        assertNull(spec.mop());
        assertNull(spec.llm());
    }

    @Test
    public void mopHasNoMenuGatewayAndDoesNotAbort() {
        RunSpec spec = cell(Presets.MOP, "ape.staticDataPath", PATH);
        assertEquals(MOP, spec.features());
        // ADDONS is off, so there is no menu action and no gateway (D-B14.1), and no abort.
        assertFalse(spec.has(Feature.MODEL_MENU));
        assertFalse(spec.has(Feature.MENU_GATEWAY));
    }

    @Test
    public void asIsTheFullToolWithoutSpecifications() {
        RunSpec spec = cell(Presets.AS, "ape.staticDataPath", PATH);
        assertEquals(AS, spec.features());
        assertNull(spec.mop());
        assertEquals(0.03, spec.staticData().componentPercentage(), 0.0);
    }

    @Test
    public void asMopAddsTheMopMembersAndTheGateway() {
        RunSpec spec = cell(Presets.AS_MOP, "ape.staticDataPath", PATH);
        assertEquals(union(AS, MOP, EnumSet.of(Feature.MENU_GATEWAY)), spec.features());
    }

    @Test
    public void asLlmAddsTheLlmMembers() {
        RunSpec spec = cell(Presets.AS_LLM, "ape.staticDataPath", PATH, "ape.llm", "true", "ape.llmUrl", LLM_URL);
        assertEquals(union(AS, LLM), spec.features());
        assertNull(spec.mop());
    }

    @Test
    public void asMopLlmResolvesAllFourGroups() {
        RunSpec spec = cell(Presets.AS_MOP_LLM, "ape.staticDataPath", PATH,
                "ape.llm", "true", "ape.llmUrl", LLM_URL);
        assertEquals(union(AS, MOP, EnumSet.of(Feature.MENU_GATEWAY), LLM), spec.features());
        assertEquals(0.9, spec.llm().dbl("ape.llmPercentage"), 0.0);
        assertEquals(1.0, spec.mop().coinPercentage(), 0.0);
        assertEquals(Presets.AS_MOP_LLM, spec.variant());
    }

    @Test
    public void everyCellResolvesWithTheHarnessKeysOfItsRow() {
        assertTrue(cell(Presets.BASE).inertKeys().isEmpty());
        for (String preset : new String[] {Presets.MOP, Presets.AS, Presets.AS_MOP}) {
            assertTrue(preset, cell(preset, "ape.staticDataPath", PATH).has(Feature.STATIC_DATA));
        }
        for (String preset : new String[] {Presets.AS_LLM, Presets.AS_MOP_LLM}) {
            assertTrue(preset, cell(preset, "ape.staticDataPath", PATH, "ape.llm", "true", "ape.llmUrl", LLM_URL)
                    .has(Feature.LLM));
        }
    }
}

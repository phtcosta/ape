package com.android.commands.monkey.ape.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The jar-resident cell presets: what each one declares, and the contract every preset obeys.
 *
 * <p>The jar is the sole authority on what a preset means. A variant in the experimental matrix is
 * a cell name plus a dict of override deltas, so everything asserted here is asserted against the
 * preset definitions themselves — never against a captured copy of what the harness writes.
 *
 * <p>The vectors below are the group blocks of guidance-groups, "Cell Presets as Unions of Group
 * Blocks", key by key, and each cell is asserted to be exactly the union of its blocks
 * (INV-GRP-04). What a cell resolves to — its feature set — is {@code CellPresetTest}'s.
 */
public class PresetsTest {

    private static final Map<String, String> ADDONS_BLOCK = vector(
            "ape.addons", "true",
            "ape.backMenuPickCap", "3",
            "ape.foreignActivityGuard", "true",
            "ape.treePackageGuard", "true",
            "ape.dynamicEpsilon", "true",
            "ape.heuristicInput", "true",
            "ape.formCompletionEnabled", "true",
            "ape.modelMenuEnabled", "true",
            "ape.treeEnhancementsEnabled", "true",
            "ape.activityBudgetEnabled", "true",
            "ape.activityStableRestartThreshold", "200",
            "ape.coverageBoostWeight", "100");

    private static final Map<String, String> STATIC_BLOCK = vector(
            "ape.static", "true",
            "ape.frontierBoostWeight", "200",
            "ape.fuzzInputTyped", "true",
            "ape.componentPercentage", "0.03");

    private static final Map<String, String> MOP_BLOCK = vector(
            "ape.mop", "true",
            "ape.mopScoring", "distance",
            "ape.mopWeightWtg", "200",
            "ape.mopFrontierWeight", "200",
            "ape.activityTriggerEnabled", "true",
            "ape.mopActivitySourceComponents", "true",
            "ape.mopCoinPercentage", "1.0");

    private static final Map<String, String> LLM_BLOCK = vector(
            "ape.llm", "true",
            "ape.llmOnNewState", "true",
            "ape.llmOnStagnation", "true",
            "ape.llmPercentage", "0.9",
            "ape.llmPromptVariant", "v13",
            "ape.llmTemperature", "0.0",
            "ape.llmTopP", "1.0",
            "ape.llmTopK", "-1",
            "ape.llmSnapTolerancePx", "150",
            "ape.llmModel", "Qwen/Qwen3-VL-4B-Instruct-FP8",
            "ape.llmTimeoutMs", "15000");

    private static final String PATH = "/data/local/tmp/mop-artifact.json";
    private static final String LLM_URL = "http://10.0.2.2:30000/v1";

    private static Map<String, String> vector(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @SafeVarargs
    private static Map<String, String> union(Map<String, String>... blocks) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, String> block : blocks) {
            out.putAll(block);
        }
        return out;
    }

    /** Resolves a cell the way the harness states one: its name plus the deployment keys. */
    private static RunSpec fromPreset(String preset, String... deploymentKeys) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(KeyOwnership.KEY_PRESET, preset);
        for (int i = 0; i < deploymentKeys.length; i += 2) {
            entries.put(deploymentKeys[i], deploymentKeys[i + 1]);
        }
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", 42L, null));
    }

    // --- The vectors: each cell is exactly the union of its group blocks (INV-GRP-04). ----------

    @Test
    public void baseStatesNoBlock() {
        assertEquals(vector(), Presets.resolve(Presets.BASE));
    }

    @Test
    public void mopIsTheMopBlock() {
        assertEquals(MOP_BLOCK, Presets.resolve(Presets.MOP));
    }

    @Test
    public void asIsAddonsPlusStatic() {
        assertEquals(union(ADDONS_BLOCK, STATIC_BLOCK), Presets.resolve(Presets.AS));
    }

    @Test
    public void asMopAddsTheMopBlock() {
        assertEquals(union(ADDONS_BLOCK, STATIC_BLOCK, MOP_BLOCK),
                Presets.resolve(Presets.AS_MOP));
    }

    @Test
    public void asLlmAddsTheLlmBlock() {
        assertEquals(union(ADDONS_BLOCK, STATIC_BLOCK, LLM_BLOCK),
                Presets.resolve(Presets.AS_LLM));
    }

    @Test
    public void asMopLlmIsAllFourBlocks() {
        assertEquals(union(ADDONS_BLOCK, STATIC_BLOCK, MOP_BLOCK, LLM_BLOCK),
                Presets.resolve(Presets.AS_MOP_LLM));
    }

    @Test
    public void theMopBlockDoesNotStateTheMenuGatewayWeight() {
        // D-B14.1: a preset key is explicit, so stating it would abort the mop cell, which has no
        // MODEL_MENU. Its jar default mounts the gateway wherever the menu action exists.
        for (String cell : new String[] {Presets.MOP, Presets.AS_MOP, Presets.AS_MOP_LLM}) {
            assertFalse(cell, Presets.resolve(cell).containsKey("ape.mopWeightOpenMenu"));
        }
    }

    // --- The contract. --------------------------------------------------------------------------

    @Test
    public void noPresetCarriesADeploymentSpecificValue() {
        // A path, a URL and a harness label belong to the machine, not to the cell (INV-GRP-04).
        for (String name : Presets.names()) {
            Set<String> keys = Presets.resolve(name).keySet();
            for (String key : new String[] {"ape.staticDataPath", "ape.llmUrl", "ape.variant",
                    "ape.runId", "ape.agentType"}) {
                assertFalse(name + " must not carry " + key, keys.contains(key));
            }
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
        Map<String, String> first = Presets.resolve(Presets.AS_MOP);
        Map<String, String> second = Presets.resolve(Presets.AS_MOP);
        assertNotSame(first, second);
        first.put("ape.mopCoinPercentage", "0");
        assertEquals("1.0", second.get("ape.mopCoinPercentage"));
    }

    @Test
    public void theCellsLeadTheNames() {
        assertEquals(Arrays.asList(Presets.BASE, Presets.MOP, Presets.AS, Presets.AS_MOP,
                Presets.AS_LLM, Presets.AS_MOP_LLM), Presets.names().subList(0, 6));
    }

    @Test
    public void anExplicitKeyOverridesTheCellVector() {
        // A dose overridden per variant (guidance-groups): the stated key wins, every other key
        // keeps the cell's value.
        RunSpec spec = fromPreset(Presets.AS_MOP, "ape.staticDataPath", PATH,
                "ape.mopCoinPercentage", "0.5");
        assertEquals(0.5, spec.mop().coinPercentage(), 0.0);
        assertEquals(200, spec.staticData().frontierBoostWeight());
        assertEquals("1.0", Presets.resolve(Presets.AS_MOP).get("ape.mopCoinPercentage"));
    }

    @Test
    public void aCellPlusOverridesResolvesLikeTheSamePlanStatedKeyByKey() {
        // Naming a cell and naming every one of its keys by hand must produce the same plan. The
        // digest covers the resolved values and the feature set together; presetName, which is
        // outside it, still tells the two forms apart in the trace.
        Map<String, String> explicitEntries = new LinkedHashMap<>(Presets.resolve(Presets.AS_MOP));
        explicitEntries.put("ape.staticDataPath", PATH);
        RunSpec explicit =
                RunSpec.resolve(explicitEntries, RunSpec.CliValues.of("sata", 42L, null));
        RunSpec preset = fromPreset(Presets.AS_MOP, "ape.staticDataPath", PATH);

        assertEquals(explicit.features(), preset.features());
        assertEquals(explicit.digest(), preset.digest());
        assertEquals(Presets.AS_MOP, preset.presetName());
        assertEquals(RunSpec.PRESET_EXPLICIT, explicit.presetName());
    }

    @Test
    public void aCellWithoutItsDeploymentKeysAborts() {
        // A preset that resolved to "STATIC off" or "LLM off" would be the silent degradation this
        // capability exists to end.
        expectMissingDependency(Presets.AS);
        expectMissingDependency(Presets.MOP);
        expectMissingDependency(Presets.AS_LLM, "ape.staticDataPath", PATH);
    }

    @Test
    public void anUnknownPresetAbortsNamingTheCells() {
        try {
            fromPreset("sata_mop");
            fail("an unknown preset must abort");
        } catch (RunSpecException e) {
            assertEquals(RunSpecException.Reason.UNKNOWN_PRESET, e.getReason());
            for (String cell : new String[] {"base", "mop", "as", "as_mop", "as_llm",
                    "as_mop_llm"}) {
                assertTrue(e.getMessage(), e.getMessage().contains(cell));
            }
        }
    }

    @Test
    public void theLlmCellsCarryTheirUrlFromTheHarness() {
        RunSpec spec = fromPreset(Presets.AS_LLM, "ape.staticDataPath", PATH,
                "ape.llm", "true", "ape.llmUrl", LLM_URL);
        assertEquals(LLM_URL, spec.llm().url());
        assertEquals(0.9, spec.llm().dbl("ape.llmPercentage"), 0.0);
    }

    private static void expectMissingDependency(String preset, String... deploymentKeys) {
        try {
            fromPreset(preset, deploymentKeys);
            fail(preset + " without its deployment keys must abort");
        } catch (RunSpecException e) {
            assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, e.getReason());
        }
    }
}

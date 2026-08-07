package com.android.commands.monkey.ape.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The abort matrix: every input class that must end the process before step 1.
 *
 * <p>Each case here is a behavior the old loader had and this one does not. A typo in a key name
 * was ignored outright — there was no unknown-key detection of any kind. A non-numeric value for a
 * numeric key hit an empty {@code catch (NumberFormatException)} and became the default. A boolean
 * had no error path at all, so {@code ape.doFuzzing=ture} read as {@code false} and the run went on
 * to report numbers for an arm nobody had configured. And {@code --ape bfs} fell through to
 * {@code SataAgent}, which is how a variant that never existed produced results for months.
 *
 * <p>The point is not that these inputs are now rejected. It is that they are rejected
 * <em>before</em> the run, where the cost is a failed task instead of a campaign of data that
 * silently measured something else.
 */
public class RunSpecAbortTest {

    private static final String MOP_PATH = "/data/local/tmp/static_analysis.json";

    private static Map<String, String> entries(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static RunSpecException abort(Map<String, String> entries) {
        return abort(entries, RunSpec.CliValues.of("sata", 42L, null));
    }

    private static RunSpecException abort(Map<String, String> entries, RunSpec.CliValues cli) {
        try {
            RunSpec.resolve(entries, cli);
        } catch (RunSpecException e) {
            return e;
        }
        throw new AssertionError("expected resolution to abort");
    }

    // -----------------------------------------------------------------------------------------
    // Keys
    // -----------------------------------------------------------------------------------------

    @Test
    public void anUnknownKeyAborts() {
        // The typo from the spec: one transposed character in a threshold nobody would notice.
        RunSpecException failure = abort(entries("ape.graphStableRestartThreshld", "200"));
        assertEquals(RunSpecException.Reason.UNKNOWN_KEY, failure.getReason());
        assertEquals("ape.graphStableRestartThreshld", failure.getKey());
        assertTrue(failure.toDiagnostic().startsWith("reason=unknown_key "));
    }

    @Test
    public void aForeignKeyAborts() {
        RunSpecException failure = abort(entries("java.version", "11"));
        assertEquals(RunSpecException.Reason.FOREIGN_KEY, failure.getReason());
        assertEquals("java.version", failure.getKey());
    }

    @Test
    public void everyRetiredKeyAbortsWithItsOwnReason() {
        for (String key : KeyOwnership.retiredKeys()) {
            RunSpecException failure = abort(entries(key, "true"));
            assertEquals(key + " must abort as retired",
                    RunSpecException.Reason.RETIRED_KEY, failure.getReason());
            assertEquals(key, failure.getKey());
            assertEquals("the abort must carry the key's own reason, not a generic one",
                    KeyOwnership.retiredReason(key), failure.getDetail());
        }
    }

    @Test
    public void theKillSwitchAbortsAtEitherValue() {
        // After the harness edit no arm pushes the key at all. A hand-written file might, and
        // false is the value that would look harmless — so it must abort too, naming the decision.
        for (String value : new String[] {"true", "false"}) {
            RunSpecException failure = abort(entries("ape.apePureMode", value));
            assertEquals(RunSpecException.Reason.RETIRED_KEY, failure.getReason());
            assertTrue("the reason must explain that purity is structural",
                    failure.getDetail().contains("structural"));
        }
    }

    /**
     * A complete arm whose every flag is stated at its off value — the shape a purity control has,
     * and the shape a harness that still wrote {@code ape.apePureMode} would push it in.
     */
    private static Map<String, String> pureShapedArm() {
        return entries(
                "ape.defaultGUIThrottle", "200",
                "ape.backMenuPickCap", "0",
                "ape.foreignActivityGuard", "false",
                "ape.treePackageGuard", "false",
                "ape.dynamicEpsilon", "false",
                "ape.heuristicInput", "false",
                "ape.fuzzInputTyped", "false",
                "ape.formCompletionEnabled", "false",
                "ape.modelMenuEnabled", "false",
                "ape.leastVisitedPriorityTiebreak", "false",
                "ape.treeEnhancementsEnabled", "false",
                "ape.activityBudgetEnabled", "false",
                "ape.mopActivitySourceComponents", "false",
                "ape.mopFrontierWeight", "0",
                "ape.frontierBoostWeight", "0",
                "ape.activityTriggerEnabled", "false",
                "ape.llmPercentageNoSubstrate", "-1");
    }

    /** A complete MOP arm: substrate, the four live weights, and the baseline flags stated on. */
    private static Map<String, String> mopShapedArm() {
        return entries(
                "ape.mopDataPath", MOP_PATH,
                "ape.defaultGUIThrottle", "200",
                "ape.mopWeightDirect", "500",
                "ape.mopWeightTransitive", "300",
                "ape.mopWeightOpenMenu", "250",
                "ape.mopWeightWtg", "200",
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
                "ape.mopActivitySourceComponents", "false",
                "ape.mopFrontierWeight", "0",
                "ape.frontierBoostWeight", "0",
                "ape.activityTriggerEnabled", "false",
                "ape.llmPercentageNoSubstrate", "-1");
    }

    @Test
    public void aRetiredKeyAbortsEvenInsideAnOtherwiseResolvableArm() {
        // What the two hand-written negative fixtures carried, that the cases above do not, is
        // surroundings. Every other abort test in this class states the offending key alone, which
        // leaves open the reading that classification wins only because there is nothing else to
        // look at. These state a complete arm and hide the retired key among twenty valid ones.
        //
        // The first is the deployment ordering failure itself: what a harness that still writes
        // ape.apePureMode pushes, meeting a jar that has retired it. The abort is what makes the
        // ordering a precondition rather than a preference.
        Map<String, String> pure = pureShapedArm();
        pure.put("ape.apePureMode", "true");
        RunSpecException purity = abort(pure);
        assertEquals(RunSpecException.Reason.RETIRED_KEY, purity.getReason());
        assertEquals("ape.apePureMode", purity.getKey());

        // The second is a MOP arm carrying the dead scoring weight, and it matters that this aborts
        // rather than being ignored: an ignored weight reads as configured and fires zero times,
        // which is the gh71 failure mode — found only in post-hoc analysis of a 2,028-task campaign.
        Map<String, String> mop = mopShapedArm();
        mop.put("ape.mopWeightActivity", "250");
        RunSpecException weight = abort(mop);
        assertEquals(RunSpecException.Reason.RETIRED_KEY, weight.getReason());
        assertEquals("ape.mopWeightActivity", weight.getKey());
    }

    @Test
    public void theArmsAroundThoseRetiredKeysDoResolve() {
        // The other half of the case above, and the reason it proves anything: without the retired
        // key each arm resolves. A negative test whose surroundings were themselves unresolvable
        // would pass for the wrong reason, and would keep passing if classification order broke.
        RunSpec pure = RunSpec.resolve(pureShapedArm(), RunSpec.CliValues.of("sata", 42L, null));
        assertEquals(200L, pure.exploration().lng("ape.defaultGUIThrottle"));

        RunSpec mop = RunSpec.resolve(mopShapedArm(), RunSpec.CliValues.of("sata", 42L, null));
        assertTrue("the substrate the weights hang off must be active", mop.has(Feature.MOP));
        assertEquals(MOP_PATH, mop.mop().dataPath());
    }

    @Test
    public void aFileBorneAgentTypeAborts() {
        // The /sdcard agent-swap hole: a stray properties file used to be able to change which
        // agent a scientific run used, with nothing in the trace to say so.
        RunSpecException failure = abort(entries("ape.agentType", "random"));
        assertEquals(RunSpecException.Reason.RETIRED_KEY, failure.getReason());
        assertTrue(failure.getDetail().contains("--ape"));
    }

    @Test
    public void aRetiredKeyIsCaughtBeforeItsValueIsJudged() {
        // Classification runs ahead of value semantics, so a retired key with an unparsable value
        // still reports what actually matters: that the key is gone.
        assertEquals(RunSpecException.Reason.RETIRED_KEY,
                abort(entries("ape.saveObjModel", "not-a-boolean")).getReason());
    }

    // -----------------------------------------------------------------------------------------
    // Types
    // -----------------------------------------------------------------------------------------

    @Test
    public void aNonNumericValueForANumericKeyAborts() {
        RunSpecException failure = abort(entries("ape.maxThrottle", "fast"));
        assertEquals(RunSpecException.Reason.INVALID_TYPE, failure.getReason());
        assertEquals("ape.maxThrottle", failure.getKey());
    }

    @Test
    public void aMistypedBooleanAbortsInsteadOfReadingFalse() {
        RunSpecException failure = abort(entries("ape.doFuzzing", "ture"));
        assertEquals(RunSpecException.Reason.INVALID_TYPE, failure.getReason());
        assertTrue(failure.getDetail().contains("true or false"));
    }

    @Test
    public void aCorrectlySpelledBooleanIsAcceptedInAnyCase() {
        assertEquals(Boolean.FALSE, ValueType.BOOLEAN.parse("ape.doFuzzing", "FALSE"));
        assertEquals(Boolean.TRUE, ValueType.BOOLEAN.parse("ape.doFuzzing", " True "));
    }

    // -----------------------------------------------------------------------------------------
    // Dependencies and combinations
    // -----------------------------------------------------------------------------------------

    @Test
    public void anLlmModeWithoutAnLlmUrlAborts() {
        RunSpecException failure = abort(entries("ape.llmOnNewState", "true"));
        assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, failure.getReason());
        assertEquals("ape.llmOnNewState", failure.getKey());
        assertTrue(failure.getDetail().contains("LLM"));
    }

    @Test
    public void theLauncherWithoutAMopSubstrateAborts() {
        RunSpecException failure = abort(entries("ape.activityTriggerEnabled", "true"));
        assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, failure.getReason());
        assertTrue(failure.getDetail().contains("ACTIVITY_TRIGGER"));
        assertTrue(failure.getDetail().contains("MOP"));
    }

    @Test
    public void theMenuGatewayWithoutTheMenuActionAborts() {
        // Both dependencies matter, and this case isolates the one that is easy to forget: the
        // substrate is present, the boost is configured, and there is no menu action to steer.
        RunSpecException failure = abort(entries(
                "ape.mopDataPath", MOP_PATH,
                "ape.mopWeightOpenMenu", "250",
                "ape.modelMenuEnabled", "false"));
        assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, failure.getReason());
        assertEquals("ape.mopWeightOpenMenu", failure.getKey());
        assertTrue(failure.getDetail().contains("MENU_GATEWAY"));
        assertTrue(failure.getDetail().contains("MODEL_MENU"));
    }

    // -----------------------------------------------------------------------------------------
    // Command line
    // -----------------------------------------------------------------------------------------

    @Test
    public void anUnknownAgentTypeAbortsNamingTheValidSet() {
        // bfs was never an agent type: createAgent accepted sata, random and replay, and fell
        // through to SataAgent for everything else. The variant ran, and it ran as sata.
        for (String value : new String[] {"bfs", "dfs", "ape", "sat a"}) {
            RunSpecException failure =
                    abort(entries(), RunSpec.CliValues.of(value, 42L, null));
            assertEquals(RunSpecException.Reason.UNKNOWN_AGENT_TYPE, failure.getReason());
            assertTrue("the diagnostic must name the valid set",
                    failure.getDetail().contains("sata")
                            && failure.getDetail().contains("random")
                            && failure.getDetail().contains("replay"));
        }
    }

    @Test
    public void replayWithoutALogAborts() {
        RunSpecException failure = abort(entries(), RunSpec.CliValues.of("replay", 42L, null));
        assertEquals(RunSpecException.Reason.INVALID_COMBINATION, failure.getReason());
        assertEquals("--ape-replay", failure.getKey());
    }

    @Test
    public void replayWithALogResolves() {
        RunSpec spec = RunSpec.resolve(entries(),
                RunSpec.CliValues.of("replay", 42L, "/data/local/tmp/replay.log"));
        assertEquals("replay", spec.agentType());
        assertEquals("/data/local/tmp/replay.log", spec.replayLog());
    }

    @Test
    public void anAbsentAgentFlagDefaultsToSata() {
        assertEquals("sata",
                RunSpec.resolve(entries(), RunSpec.CliValues.of(null, 42L, null)).agentType());
    }

    // -----------------------------------------------------------------------------------------
    // Presets
    // -----------------------------------------------------------------------------------------

    @Test
    public void anUnknownPresetAbortsListingTheValidNames() {
        RunSpecException failure = abort(entries("ape.preset", "mop_v2"));
        assertEquals(RunSpecException.Reason.UNKNOWN_PRESET, failure.getReason());
        for (String name : Presets.names()) {
            assertTrue("the diagnostic must list " + name, failure.getDetail().contains(name));
        }
    }

    @Test
    public void theLlmPresetWithoutADeploymentUrlAborts() {
        // Deliberate and worth pinning: the preset states the routing gates ON, and the server
        // they route to is deployment-specific. A preset that quietly resolved to "LLM off" would
        // be the silent-degradation failure this whole change exists to end.
        RunSpecException failure = abort(entries("ape.preset", Presets.LLM));
        assertEquals(RunSpecException.Reason.MISSING_DEPENDENCY, failure.getReason());
    }

    @Test
    public void noPresetCarriesARetiredKey() {
        // A preset feeds the same validator as an explicit plan, so a retired key baked into one
        // would make the preset abort on itself.
        for (String name : Presets.names()) {
            for (String key : Presets.resolve(name).keySet()) {
                if (KeyOwnership.isRetired(key)) {
                    fail("preset " + name + " carries the retired key " + key);
                }
            }
        }
    }
}

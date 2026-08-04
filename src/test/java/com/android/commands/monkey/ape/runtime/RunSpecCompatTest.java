package com.android.commands.monkey.ape.runtime;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The Python contract: what the harness actually pushes, resolved by the jar that will receive it.
 *
 * <p>Every other test in this package states a plan the way a Java author would. This one states
 * plans the way the deployment does — by loading the byte-for-byte output of
 * {@code ApeRVTool._push_properties} for each campaign arm, captured into
 * {@code src/test/resources/compat/} and pinned to a named {@code tool.py} (see that directory's
 * README). The distinction matters because the two are not the same function: the harness filters
 * to keys present in both the arm dictionary and its property mapping, lowercases Python booleans,
 * and prepends {@code ape.mopDataPath} only when it actually pushed the MOP artifact. A fixture
 * transcribed by eye from the arm dictionaries would differ in exactly those three ways.
 *
 * <p><b>What failure means here.</b> These tests do not guard a jar-internal invariant — they
 * guard an agreement between two repositories that have no build-time coupling. Nothing crashes
 * when they drift; the fixtures simply stop representing what the deployment sends, and the first
 * evidence would otherwise be a campaign arm aborting on a device. When one goes red, check
 * {@code sha256sum tool.py} against the README's pin before suspecting the resolver: a mismatch
 * means the fixtures are stale, not that the jar regressed.
 */
public class RunSpecCompatTest {

    /**
     * Features active on <em>every</em> plan here, including {@code ape_pure}, because their
     * activation keys carry positive jar defaults and no arm dictionary states them:
     * {@code ape.coverageBoostWeight} defaults to {@code 100} and {@code ape.doFuzzing} to
     * {@code true}.
     *
     * <p>They are arm-neutral by construction — neither key is a member of the harness's
     * {@code ARM_DEFINING_KEYS}, so no arm can turn one on or off relative to another, and they
     * cancel in any paired comparison. Naming them as a set rather than folding them into the
     * baseline keeps that distinction visible: everything below this line is stated by an arm,
     * everything in it is inherited from the jar.
     */
    private static final Set<Feature> ARM_NEUTRAL_DEFAULTS = EnumSet.of(
            Feature.COVERAGE_BOOST,
            Feature.FUZZING);

    /**
     * The eleven RV exploration features the campaign arms turn on by stating them, plus the two
     * inherited defaults. {@code _BASELINE_ARM_FLAGS} states all eleven explicitly, and no arm
     * below turns any of them off — the MOP and LLM arms are this set plus their own substrate.
     */
    private static final Set<Feature> BASELINE = EnumSet.of(
            Feature.MODEL_MENU,
            Feature.FORM_COMPLETION,
            Feature.STEP_TELEMETRY,
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

    private static Set<String> statedKeys(String name) {
        return CompatFixtures.statedKeys(name);
    }

    private static RunSpec resolveFixture(String name) {
        return CompatFixtures.resolve(name);
    }

    private static String rawText(String name) {
        return CompatFixtures.rawText(name);
    }

    private static RunSpecException abortOf(String fixture) {
        try {
            resolveFixture(fixture);
        } catch (RunSpecException e) {
            return e;
        }
        fail("expected " + fixture + " to abort, but it resolved");
        return null;
    }

    // --- The four campaign arms. ----------------------------------------------------------------

    @Test
    public void sataResolvesAsTheBaselineArm() {
        RunSpec spec = resolveFixture("sata.properties");

        assertEquals(BASELINE, spec.features());
        assertEquals(200L, spec.exploration().lng("ape.defaultGUIThrottle"));
        // No substrate was pushed, so neither root feature is reachable from this file.
        assertFalse(spec.has(Feature.MOP));
        assertFalse(spec.has(Feature.LLM));
    }

    @Test
    public void sataMopWidgetAddsTheMopSubstrate() {
        RunSpec spec = resolveFixture("sata_mop_widget.properties");

        Set<Feature> expected = EnumSet.copyOf(BASELINE);
        expected.add(Feature.MOP);
        // The two weight-activated members of the MOP family the widget arm turns on; the frontier
        // and activity-trigger members stay at zero/false and so stay out.
        expected.add(Feature.WTG);
        expected.add(Feature.MENU_GATEWAY);
        assertEquals(expected, spec.features());

        assertEquals("/data/local/tmp/static_analysis.json", spec.mop().dataPath());
        assertEquals(250, spec.mop().weightOpenMenu());
        assertFalse(spec.has(Feature.FRONTIER));
        assertFalse(spec.has(Feature.MOP_FRONTIER));
        assertFalse(spec.has(Feature.ACTIVITY_TRIGGER));
        assertFalse(spec.has(Feature.MOP_ACTIVITY_SOURCE));
    }

    @Test
    public void sataLlmAddsTheRoutingGates() {
        RunSpec spec = resolveFixture("sata_llm.properties");

        Set<Feature> expected = EnumSet.copyOf(BASELINE);
        expected.add(Feature.LLM);
        expected.add(Feature.LLM_NEW_STATE);
        expected.add(Feature.LLM_STAGNATION);
        // Inherited, not stated — see randomLlmRoutingIsInheritedFromTheJarDefault.
        expected.add(Feature.LLM_RANDOM);
        assertEquals(expected, spec.features());

        assertEquals("http://10.0.2.2:30000/v1", spec.llm().url());
        assertEquals("default", spec.llm().model());
        assertFalse(spec.has(Feature.MOP));
    }

    @Test
    public void randomLlmRoutingIsInheritedFromTheJarDefault() {
        // Worth pinning because it is invisible from the harness side. `_LLM_FLAGS` states eight
        // keys and ape.llmPercentage is not among them, nor is it arm-defining — so both LLM
        // campaign arms inherit the jar's default of 0.02 and route to the LLM at a 2% random rate
        // that no arm dictionary mentions. Nothing here is wrong; the point is that the plan now
        // says so out loud, where the pre-change configuration left it implicit in Config.
        for (String fixture : new String[] {"sata_llm.properties", "sata_mop_llm.properties"}) {
            assertFalse(fixture + " is expected NOT to state the rate",
                    statedKeys(fixture).contains("ape.llmPercentage"));
            assertTrue(fixture + " should still route randomly, from the default",
                    resolveFixture(fixture).has(Feature.LLM_RANDOM));
        }
        // And it is genuinely conditional on the LLM being present, not unconditional.
        assertFalse(resolveFixture("sata.properties").has(Feature.LLM_RANDOM));
    }

    @Test
    public void sataMopLlmIsTheUnionOfBothSubstrates() {
        RunSpec spec = resolveFixture("sata_mop_llm.properties");

        Set<Feature> expected = EnumSet.copyOf(BASELINE);
        expected.add(Feature.MOP);
        expected.add(Feature.WTG);
        expected.add(Feature.MENU_GATEWAY);
        expected.add(Feature.LLM);
        expected.add(Feature.LLM_NEW_STATE);
        expected.add(Feature.LLM_STAGNATION);
        expected.add(Feature.LLM_RANDOM);
        assertEquals(expected, spec.features());

        assertEquals("/data/local/tmp/static_analysis.json", spec.mop().dataPath());
        assertEquals("http://10.0.2.2:30000/v1", spec.llm().url());
    }

    // --- The fifth fixture: ape_pure. -----------------------------------------------------------

    @Test
    public void apePureResolvesAsAStructurallyPurePlan() {
        RunSpec spec = resolveFixture("ape_pure.properties");

        // The whole point of the arm: purity is a property of the seventeen explicitly-off flags in
        // the file, not of a jar-side switch that forces RV off. With ape.apePureMode retired, the
        // arm still resolves to the original-APE configuration, so the baseline stays auditable
        // from ape.properties alone.
        //
        // "Pure" is precisely: nothing this arm states turns anything on. What survives is exactly
        // the arm-neutral set every arm inherits from the jar — no RV exploration feature, no MOP
        // family member, no LLM. Asserting equality with that set rather than with the empty set is
        // the honest form: an empty-set assertion would be false, and weakening to "contains no
        // MOP" would stop catching a flag that silently flipped on.
        assertEquals(ARM_NEUTRAL_DEFAULTS, spec.features());
        assertEquals(200L, spec.exploration().lng("ape.defaultGUIThrottle"));
    }

    @Test
    public void apePureTurnsOffEveryFeatureTheBaselineArmTurnsOn() {
        // The contrast that makes ape_pure a control: same file shape, opposite values. Every
        // feature `sata` gains by stating a flag is absent here, and the difference between the two
        // plans is exactly the eleven RV exploration features.
        Set<Feature> sata = resolveFixture("sata.properties").features();
        Set<Feature> pure = resolveFixture("ape_pure.properties").features();

        Set<Feature> difference = EnumSet.copyOf(sata);
        difference.removeAll(pure);
        assertEquals(11, difference.size());
        assertTrue("ape_pure must not carry any feature sata lacks", pure.containsAll(
                EnumSet.copyOf(ARM_NEUTRAL_DEFAULTS)) && sata.containsAll(pure));
    }

    @Test
    public void apePureAndSataStateTheSameKeysAndDifferOnlyInValues() {
        // Both arms state the same eighteen keys; what separates them is which values those keys
        // carry. That is what makes ape_pure a control rather than a differently-shaped arm, and
        // it is the property that would break if the harness ever let a flag fall back to a jar
        // default instead of stating it.
        //
        // Asserted on the keys the *files* state, not on the resolved plans' effectiveValues():
        // that map is base keys plus the keys of active features, so the two arms differ there by
        // construction — precisely because ape_pure turns those features off.
        assertEquals(statedKeys("sata.properties"), statedKeys("ape_pure.properties"));
        assertEquals(18, statedKeys("sata.properties").size());
    }

    // --- The retired key is gone from every fixture. --------------------------------------------

    @Test
    public void noCampaignFixtureCarriesTheRetiredPurityKey() {
        // The post-gh93 property of the whole fixture set, asserted on the file text rather than on
        // the resolved plan: a fixture carrying the key would abort before producing a plan to
        // inspect, so this failure names the real problem instead of a resolution error.
        for (String fixture : new String[] {"sata.properties", "sata_mop_widget.properties",
                "sata_llm.properties", "sata_mop_llm.properties", "ape_pure.properties"}) {
            assertFalse(fixture + " still carries the retired ape.apePureMode",
                    rawText(fixture).contains("ape.apePureMode"));
        }
    }

    // --- The two hand-written negatives. ---------------------------------------------------------

    @Test
    public void aPreEditApePureModeFileAbortsAsARetiredKey() {
        // This fixture is what a pre-gh93 harness pushed for ape_pure. It is the ordering failure
        // design D-4 forbids, reproduced as a test so the abort stays proven rather than assumed.
        RunSpecException abort = abortOf("negative/ape_pure_mode.properties");

        assertEquals(RunSpecException.Reason.RETIRED_KEY, abort.getReason());
        assertEquals("ape.apePureMode", abort.getKey());
        assertTrue("the abort must say why the key is retired",
                abort.getDetail().equals(KeyOwnership.retiredReason("ape.apePureMode")));
    }

    @Test
    public void aRetiredMopWeightAbortsRatherThanBeingIgnored() {
        RunSpecException abort = abortOf("negative/mop_weight_activity.properties");

        assertEquals(RunSpecException.Reason.RETIRED_KEY, abort.getReason());
        assertEquals("ape.mopWeightActivity", abort.getKey());
    }

    @Test
    public void apePureModeAbortsAtFalseToo() {
        // The case that actually forced the ordered Python edit: 23 of the 29 arms pushed
        // ape.apePureMode=false, not true. `false` reads like an inert value, but the inert rule
        // admits only sub-parameters of a *feature* judged against that feature's neutral value,
        // and this key owns no feature — the mechanism it named is deleted. Retired-key
        // classification is evaluated before any value semantics, so both values abort. If this
        // ever stopped aborting, the retirement would be nominal and the ordering constraint
        // pointless.
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("ape.apePureMode", "false");
        try {
            RunSpec.resolve(entries, RunSpec.CliValues.of("sata", 42L, null));
            fail("ape.apePureMode=false must abort as a retired key");
        } catch (RunSpecException e) {
            assertEquals(RunSpecException.Reason.RETIRED_KEY, e.getReason());
            assertEquals("ape.apePureMode", e.getKey());
        }
    }

    // --- The sentinel every baseline arm pushes. ------------------------------------------------

    @Test
    public void theNoSubstrateSentinelIsInertOnTheArmsWithoutAnLlm() {
        // ape.llmPercentageNoSubstrate=-1 rides on all five fixtures, including the three with no
        // LLM at all. It parameterizes LLM_RANDOM, which those plans do not carry, so it survives
        // only because -1 is that key's declared neutral value — "you may state OFF for an absent
        // mechanism, not ON". The harness writes -1 while the neutral is declared -1.0, so this
        // also pins that the comparison is by declared type and not by string.
        for (String fixture : new String[] {"sata.properties", "ape_pure.properties",
                "sata_mop_widget.properties"}) {
            RunSpec spec = resolveFixture(fixture);
            assertTrue(fixture + ": the sentinel must resolve as inert",
                    spec.inertKeys().contains("ape.llmPercentageNoSubstrate"));
            assertFalse(spec.has(Feature.LLM_RANDOM));
        }
    }
}

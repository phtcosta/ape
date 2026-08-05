package com.android.commands.monkey.ape.oracle;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * rearch-01 task 6.2 — the {@code mop} preset's baseline golden: MOP data loaded from the
 * committed static-analysis fixture through the production {@code MopData.load} path, no LLM.
 *
 * <h2>The two MOP mechanisms this golden pins</h2>
 * <ol>
 *   <li><b>The cadence launcher.</b> {@code MopLauncherStage.shouldFire} is an exact equality against
 *       {@code Config.activityTriggerStagnationStep} (50), and the counter's increment is the
 *       block's first statement, so a run starting at 0 fires on its 50th pass. The scenario
 *       declares the counter seeded at 48 (design D2), which puts the fire on step 1 — where a
 *       reviewer can see it, next to the step before it and the step after. The candidate comes
 *       from the fixture's census (`MessageDigestActivity`, `CipherActivity`,
 *       `generated.CryptographyActivity`), and the screens deliberately run on the fixture's
 *       <b>main</b> activity: the launcher skips any activity already in the graph, so a scenario
 *       borrowing a census name would starve its own candidate.</li>
 *   <li><b>The unvisited-MOP short-circuit</b> in the epsilon-greedy rung, which is where the boost
 *       actually converts into a decision. The scenario declares the boost (finding 6.1-a) because
 *       nothing below the oracle's entry point assigns one, and it declares the carrying action
 *       unvisited-and-saturated — the stand-in, documented in the scaffold's ledger, for the
 *       refined-sibling state that produces that pairing in production.</li>
 * </ol>
 *
 * <p>The launcher's record is the format's one non-{@code ModelAction} shape: an
 * {@code EVENT_TRIGGER_ACTIVITY} whose {@code target} is a class name, with no
 * {@code decisionSource} and no {@code pickChannel} — it has none to read. Their absence is the
 * launcher's signature in a golden, never a "not set yet".
 *
 * <p>Every record is free of the {@code llm} field: this preset has no LLM, so no consultation
 * was possible, which is a different claim from {@code not_routed} and is what keeps a MOP diff
 * readable.
 */
public class ParityOracleMopTest {

    private static final long SEED = 5150L;

    private static final String PRESET = "mop";
    private static final String SCENARIO = "baseline";

    /** The fixture's own main activity: outside the census, so it never eats a candidate. */
    private static final String HOST_ACTIVITY = "br.unb.cic.cryptoapp.MainActivity";

    /** The first census activity the round-robin reaches from index 0. */
    private static final String EXPECTED_TRIGGER =
            "br.unb.cic.cryptoapp.messagedigest.MessageDigestActivity";

    /**
     * Two below the cadence: the block's increment is its first statement, so step 0 lands on 49
     * and step 1 lands on the fire. Seeding one below would fire on step 0 and cost the golden the
     * step that shows the launcher <i>not</i> firing.
     */
    private static final int SEEDED_CADENCE = 48;

    private static final int BOOSTED_PRIORITY = 20;
    private static final int PLAIN_PRIORITY = 12;
    private static final int NAV_PRIORITY = 5;
    private static final int DECLARED_BOOST = 7;

    private static ScenarioScript baseline() {
        return ScenarioScript.named(SCENARIO, SEED)
                .screens(
                        // A genuinely fresh screen: nothing visited, nothing saturated, BACK and
                        // MENU included. They are unsaturated too — isSaturated() is visit-based
                        // for targetless actions — so the EARLY_STAGE priority roulette offers
                        // them alongside the widgets. The low navigation priority is what keeps
                        // the walk on the widgets, and it is a declared input like every other.
                        ScenarioScript.screen("home", HOST_ACTIVITY, NAV_PRIORITY, false,
                                ScenarioScript.widget("//*[@resource-id='h0']", BOOSTED_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h1']", BOOSTED_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h2']", BOOSTED_PRIORITY, false, 0.0F)),
                        // Saturated everywhere, so the chain falls to the epsilon-greedy rung; the
                        // boosted widget is unvisited there, which is what arms the short-circuit.
                        ScenarioScript.screen("mopish", HOST_ACTIVITY, NAV_PRIORITY, true,
                                ScenarioScript.widget("//*[@resource-id='m_boost']",
                                        BOOSTED_PRIORITY, false, 1.0F, DECLARED_BOOST),
                                ScenarioScript.widget("//*[@resource-id='m1']", PLAIN_PRIORITY, true, 1.0F),
                                ScenarioScript.widget("//*[@resource-id='m2']", PLAIN_PRIORITY, true, 1.0F)))
                .transition("home", "//*[@resource-id='h0']", "mopish")
                .transition("home", "//*[@resource-id='h1']", "mopish")
                .transition("home", "//*[@resource-id='h2']", "mopish")
                .stepsSinceLauncherFiring(SEEDED_CADENCE)
                // `mopish` carries pre-visited actions, so it is not a first visit and its steps say
                // so: `_isNewState` is the script's stand-in for what `updateStateInternal` computed
                // before the visit mark erased it, and declaring it true on a screen whose actions
                // are already visited would be a claim the state itself contradicts.
                .steps(
                        ScenarioScript.step(true, 0),
                        ScenarioScript.step(false, 0),
                        ScenarioScript.step(false, 1),
                        ScenarioScript.step(false, 2))
                .build();
    }

    @Test
    public void ladderConfigDefaultsAreUnchanged() {
        LadderConfigGuard.assertSharedDefaults();
        LadderConfigGuard.assertMopDefaults();
    }

    @Test
    public void baselineGoldenIsReproduced() throws Exception {
        ScenarioScript script = baseline();
        OracleSataAgent agent = OracleScaffold.newAgent(OracleScaffold.Preset.MOP, script, null);

        List<DecisionRecord> records = OracleDriver.run(agent, script);

        assertEquals("one record per scripted step", script.getSteps().size(), records.size());

        DecisionRecord launcher = records.get(1);
        assertEquals("the seeded cadence puts the launcher fire on step 1",
                "EVENT_TRIGGER_ACTIVITY", launcher.getActionType());
        assertEquals("the launcher's target is the census candidate's class",
                EXPECTED_TRIGGER, launcher.getTarget());
        assertNull("a launcher return is not a ModelAction, so it carries no decision source",
                launcher.getDecisionSource());
        assertNull("nor a pick channel", launcher.getPickChannel());

        DecisionRecord mopPick = records.get(2);
        assertEquals("the declared boost converts into a MOP-attributed decision",
                "MOP", mopPick.getDecisionSource());
        assertEquals("through the unvisited-MOP short-circuit",
                "short_circuit_unvisited", mopPick.getPickChannel());

        for (DecisionRecord record : records) {
            assertNull("a preset without an LLM has no llm axis at all", record.getLlm());
        }

        GoldenFile.captureOrCompare(new GoldenFile.Header(
                PRESET, SCENARIO, SEED, OracleScaffold.MOP_FIXTURE, GoldenFile.capturedAt()),
                records);
    }
}

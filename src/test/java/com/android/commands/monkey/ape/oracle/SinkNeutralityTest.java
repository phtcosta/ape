package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.telemetry.NdjsonSink;
import com.android.commands.monkey.ape.telemetry.NoopSink;

import android.util.Log;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The neutrality gate (R7, INV-SNK-07): the sink observes and never decides.
 *
 * <p>This is the recorded substitute for INV-ARCH-01, and it is the reason dissolving that
 * invariant was safe. The baseline arm used to be protected from telemetry by being blind to it;
 * it is now protected by the sink being unable to speak — every {@code EventSink} method returns
 * {@code void}, none touches the seeded stream, and none hands a decision path anything. That is an
 * argument from shape, which {@code NdjsonSinkTest} pins reflectively. This test is the
 * <em>behavioural</em> half: the rearch-01 parity harness replays a preset under one seed with the
 * real sink and then with the no-op, and the decisions must be identical.
 *
 * <p>All four presets are replayed, since "all arms carry the same telemetry" is the claim being
 * defended. The comparison is over {@code DecisionRecord}s, not over the goldens on disk: a golden
 * pins what the ladder decides, and this pins that <em>nothing about the sink</em> moves it. The
 * two are complementary, and this one would still fail if both sinks moved the same decision the
 * same way.
 *
 * <p><b>What this replay cannot yet reach, stated because a gate that overclaims is worse than a
 * missing one.</b> The harness's entry point is {@code agent.ladder()}, and every sink call the
 * step path makes sits above it: {@code beginStep}/{@code decision}/{@code outcome} are in
 * {@code resolveNewAction} and {@code updateGraph}, {@code mopExposure} is in the scoring pass that
 * runs inside {@code adjustActionsByGUITree()}, and the LLM sub-events are emitted by units the
 * scaffold substitutes with {@link ScriptedLlm}. So the real sink receives <em>nothing</em> during
 * a replay — {@link #theHarnessEntersBelowEverySinkCallSite()} pins exactly that — and what these
 * four tests establish is that installing a different sink implementation does not perturb the
 * ladder, not that a sink under load leaves it alone. The load-bearing half of R7 for now is the
 * interface's shape ({@code NdjsonSinkTest.noSinkMethodCanHandAnythingBackToADecisionPath}) plus
 * the fact that no sink method returns a value a decision could read. Closing the gap needs the
 * harness to drive a step's sink calls around each {@code ladder()} call — that is a change to
 * {@code OracleDriver}, and it is why task 6.2 stays open.
 */
public class SinkNeutralityTest {

    private static final long SEED = 1234L;

    private static final String HOST_ACTIVITY = "br.unb.cic.cryptoapp.MainActivity";

    private static final int BOOSTED_PRIORITY = 20;
    private static final int PLAIN_PRIORITY = 12;
    private static final int NAV_PRIORITY = 5;
    private static final int DECLARED_BOOST = 7;

    /** Two below the cadence, so the launcher fires inside a four-step scenario (design D2). */
    private static final int SEEDED_CADENCE = 48;

    @After
    public void leaveNoHeartbeatsBehind() {
        Log.reset();
    }

    /**
     * A four-step script that reaches every mechanism the presets carry: an accepted LLM answer, a
     * declined one, a step nothing is asked about, and a timeout.
     *
     * <p>Built once per replay rather than shared between them, because a scenario carries the
     * mutable state the driver advances; two replays over one instance would compare a run against
     * a run that started where the first ended.
     */
    private static ScenarioScript script(boolean withLlm) {
        ScenarioScript.Builder builder = ScenarioScript.named("neutrality", SEED)
                .screens(
                        ScenarioScript.screen("home", HOST_ACTIVITY, NAV_PRIORITY, false,
                                ScenarioScript.widget("//*[@resource-id='h0']", BOOSTED_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h1']", BOOSTED_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h2']", BOOSTED_PRIORITY, false, 0.0F)),
                        ScenarioScript.screen("mopish", HOST_ACTIVITY, NAV_PRIORITY, true,
                                ScenarioScript.widget("//*[@resource-id='m_boost']",
                                        BOOSTED_PRIORITY, false, 1.0F, DECLARED_BOOST),
                                ScenarioScript.widget("//*[@resource-id='m1']", PLAIN_PRIORITY, true, 1.0F),
                                ScenarioScript.widget("//*[@resource-id='m2']", PLAIN_PRIORITY, true, 1.0F)))
                .transition("home", "//*[@resource-id='h0']", "mopish")
                .transition("home", "//*[@resource-id='h1']", "mopish")
                .transition("home", "//*[@resource-id='h2']", "mopish")
                .stepsSinceLauncherFiring(SEEDED_CADENCE);
        if (withLlm) {
            return builder.steps(
                    ScenarioScript.step(true, 0, ScenarioScript.accept(
                            true, false, false, ScriptedLlm.FIRST_UNVISITED_TARGETED)),
                    ScenarioScript.step(false, 5, ScenarioScript.decline(false, true, false)),
                    ScenarioScript.step(false, 6),
                    ScenarioScript.step(false, 7, ScenarioScript.timeout(false, false, true)))
                    .build();
        }
        return builder.steps(
                ScenarioScript.step(true, 0),
                ScenarioScript.step(false, 5),
                ScenarioScript.step(false, 6),
                ScenarioScript.step(false, 7))
                .build();
    }

    /** One replay of the preset under {@code sink}, returning what the ladder decided. */
    private static List<DecisionRecord> replay(OracleScaffold.Preset preset, EventSink sink)
            throws Exception {
        ScenarioScript script = script(preset.hasLlm());
        ScriptedLlm llm = preset.hasLlm() ? new ScriptedLlm(script) : null;
        OracleSataAgent agent = OracleScaffold.newAgent(preset, script, llm, sink);
        return preset.hasLlm()
                ? OracleDriver.run(agent, script, llm)
                : OracleDriver.run(agent, script);
    }

    /**
     * Replays a preset with each sink and asserts the decisions did not move.
     *
     * <p>The real sink writes to a buffer rather than to {@code System.out}, so the assertion is
     * about the decisions and not about which stream a test happened to hold.
     */
    private void assertNeutralOn(OracleScaffold.Preset preset) throws Exception {
        ByteArrayOutputStream trace = new ByteArrayOutputStream();
        Log.reset();
        List<DecisionRecord> withSink = replay(preset,
                new NdjsonSink(new PrintStream(trace, true, "UTF-8")));
        List<DecisionRecord> withoutSink = replay(preset, new NoopSink());

        assertEquals(preset + ": the two replays must decide the same number of steps",
                withSink.size(), withoutSink.size());
        for (int i = 0; i < withSink.size(); i++) {
            assertEquals(preset + ": step " + i + " moved when the sink was switched",
                    render(withSink.get(i)), render(withoutSink.get(i)));
        }
    }

    /** A record as one comparable string — every field the harness observes, in a fixed order. */
    private static String render(DecisionRecord record) {
        return record.getStep() + "|" + record.getActionType() + "|" + record.getTarget() + "|"
                + record.getDecisionSource() + "|" + record.getPickChannel() + "|" + record.getLlm();
    }

    @Test
    public void theLlmMopArmDecidesTheSameWithTheSinkOnOrOff() throws Exception {
        assertNeutralOn(OracleScaffold.Preset.LLM_MOP);
    }

    @Test
    public void theLlmArmDecidesTheSameWithTheSinkOnOrOff() throws Exception {
        assertNeutralOn(OracleScaffold.Preset.LLM);
    }

    @Test
    public void theMopArmDecidesTheSameWithTheSinkOnOrOff() throws Exception {
        assertNeutralOn(OracleScaffold.Preset.MOP);
    }

    @Test
    public void theControlArmDecidesTheSameWithTheSinkOnOrOff() throws Exception {
        // The arm that used to be blind by construction. It carries the same telemetry as every
        // other now, and this is what says that costs it nothing.
        assertNeutralOn(OracleScaffold.Preset.APERV);
    }

    /**
     * The finding that bounds the four tests above, pinned so it cannot rot silently.
     *
     * <p>A replay drives the ladder directly, and every sink call site is above that entry point.
     * If this ever fails, the harness has grown reach over a sink call — and the neutrality gate
     * can then be strengthened from "a different implementation does not perturb the ladder" to
     * "a sink under load does not", which is what task 6.2 asks for.
     */
    @Test
    public void theHarnessEntersBelowEverySinkCallSite() throws Exception {
        ByteArrayOutputStream trace = new ByteArrayOutputStream();
        replay(OracleScaffold.Preset.LLM_MOP, new NdjsonSink(new PrintStream(trace, true, "UTF-8")));

        assertEquals("the replay reaches no sink call, so the gate above is bounded by that",
                0, trace.size());
    }

    @Test
    public void aRunCannotAskForTheNoOpSink() throws Exception {
        // The other half of neutrality, and the one a replay cannot show: there is no plan key for
        // the sink, so an arm cannot turn telemetry off the way ape_pure once did. Plan validation
        // rejects the key an operator would reach for.
        try {
            com.android.commands.monkey.ape.runtime.TestRunSpecs.spec("ape.telemetrySink", "noop");
            org.junit.Assert.fail("a sink key would be an arm-level flag, which INV-SNK-07 forbids");
        } catch (RuntimeException expected) {
            assertTrue("the plan must reject it as unknown, got: " + expected.getMessage(),
                    String.valueOf(expected.getMessage()).contains("ape.telemetrySink"));
        }
    }
}

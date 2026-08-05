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
 * <p><b>The sink is under load while the decisions are compared, and that is the whole of the
 * gate.</b> Every production sink call sits above the harness's entry point — {@code beginStep} and
 * {@code decision} in {@code resolveNewAction}, {@code outcome} in {@code updateGraph} — so a plain
 * replay leaves the real sink holding nothing and can only say that <em>installing</em> a different
 * implementation is harmless. {@link OracleDriver#runUnderSinkLoad} is what closes that: it drives
 * the step's calls around each {@code ladder()} call, so the "on" replay interns dictionary
 * entries, escapes and writes a record per step, and beats a heartbeat, while the "off" replay does
 * none of it — and the decisions still have to match. That reaches every channel a sink could
 * perturb a decision through, because all of them are global rather than positional: the shared
 * {@code RandomHelper} statics, the agent's pinned stream, and the model objects whose strings the
 * sink is handed.
 *
 * <p><b>What the load still does not include</b>, stated because a gate that overclaims is worse
 * than a missing one: {@code mopExposure} is emitted inside {@code adjustActionsByGUITree()} and
 * the {@code llm[]} sub-events by the units the scaffold replaces with {@link ScriptedLlm}, so
 * neither reaches the sink here. {@link #theHarnessEntersBelowEverySinkCallSite()} keeps that bound
 * visible from the undriven path: it is the reason the driver has to place the calls at all, and
 * the day the harness grows reach over a production sink call it fails and says so.
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

    /**
     * One replay of the preset under {@code sink}, returning what the ladder decided.
     *
     * @param underLoad whether the driver drives the step's sink calls around each selection. The
     *        four neutrality tests say true, which is what puts the sink to work; the bound test
     *        below says false, which is what shows the ladder itself reaches none of them.
     */
    private static List<DecisionRecord> replay(OracleScaffold.Preset preset, EventSink sink,
            boolean underLoad) throws Exception {
        ScenarioScript script = script(preset.hasLlm());
        ScriptedLlm llm = preset.hasLlm() ? new ScriptedLlm(script) : null;
        OracleSataAgent agent = OracleScaffold.newAgent(preset, script, llm, sink);
        if (underLoad) {
            return OracleDriver.runUnderSinkLoad(agent, script, llm);
        }
        return preset.hasLlm()
                ? OracleDriver.run(agent, script, llm)
                : OracleDriver.run(agent, script);
    }

    /**
     * Replays a preset with each sink under load and asserts the decisions did not move.
     *
     * <p>The real sink writes to a buffer rather than to {@code System.out}, so the assertion is
     * about the decisions and not about which stream a test happened to hold.
     *
     * <p>It asserts the load happened before it asserts the decisions match, and that order is the
     * point: identical sequences prove nothing if the "on" replay quietly wrote nothing, which is
     * exactly the state this test was in before task 6.2. One record per step and one heartbeat per
     * step is what says the sink did the work the run would have made it do.
     */
    private void assertNeutralOn(OracleScaffold.Preset preset) throws Exception {
        ByteArrayOutputStream trace = new ByteArrayOutputStream();
        Log.reset();
        List<DecisionRecord> withSink = replay(preset,
                new NdjsonSink(new PrintStream(trace, true, "UTF-8")), true);
        int heartbeats = Log.entries().size();
        List<DecisionRecord> withoutSink = replay(preset, new NoopSink(), true);

        assertEquals(preset + ": the sink must have written a record per step",
                withSink.size(), stepRecords(trace.toString("UTF-8")));
        assertEquals(preset + ": the heartbeat must have beaten once per step",
                withSink.size(), heartbeats);
        assertEquals(preset + ": the no-op replay must not have written anything",
                heartbeats, Log.entries().size());

        assertEquals(preset + ": the two replays must decide the same number of steps",
                withSink.size(), withoutSink.size());
        for (int i = 0; i < withSink.size(); i++) {
            assertEquals(preset + ": step " + i + " moved when the sink was switched",
                    render(withSink.get(i)), render(withoutSink.get(i)));
        }
    }

    /** Step records in a trace: the lines carrying an {@code s} member, dictionaries excluded. */
    private static int stepRecords(String trace) {
        int count = 0;
        for (String line : trace.split("\n")) {
            if (line.startsWith("{\"s\":")) {
                count++;
            }
        }
        return count;
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
     * The finding that makes the driver's load necessary, pinned so it cannot rot silently.
     *
     * <p>A replay drives the ladder directly, and every production sink call site is above that
     * entry point — which is why the four tests above have the driver place the calls instead. If
     * this ever fails, the harness has grown reach over a production sink call, and that much of
     * the load can stop being synthetic.
     */
    @Test
    public void theHarnessEntersBelowEverySinkCallSite() throws Exception {
        ByteArrayOutputStream trace = new ByteArrayOutputStream();
        replay(OracleScaffold.Preset.LLM_MOP,
                new NdjsonSink(new PrintStream(trace, true, "UTF-8")), false);

        assertEquals("the ladder itself reaches no sink call, so the load has to be driven",
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

package com.android.commands.monkey.ape.oracle;

import android.util.Log;

import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.telemetry.NdjsonSink;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The clean-control gate (event-sink INV-SNK-18, isolate-guidance-groups 11.5): a run of a cell
 * without {@code MOP} — {@code base}, {@code as}, {@code as_llm} — carries no MOP signal in its
 * trace, so a contrast against it measures the MOP group and nothing that leaked from it.
 *
 * <p>Each cell is replayed under the real sink with the driver's load ({@link
 * OracleDriver#runUnderSinkLoad}), the sink wired as {@code RunContext} wires it for the plan, and
 * the run is closed with its {@code RUN_END}. The scenario declares a MOP weight on a widget, as a
 * MOP scenario would: without the group nothing reads it, and the trace must say so. The UICOV
 * line's {@code mopReach} is the agent's predicate, which is null in these cells because
 * {@code MopData} is; its omission from the line is {@code UICoverageTrackerTest}'s.
 */
public class CleanControlTest {

    private static final long SEED = 2468L;

    private static final String HOST_ACTIVITY = "br.unb.cic.cryptoapp.MainActivity";

    private static final int WIDGET_PRIORITY = 20;
    private static final int NAV_PRIORITY = 5;
    private static final int DECLARED_WEIGHT = 7;

    /** The step-record {@code dec} members only the MOP group writes. */
    private static final List<String> MOP_DEC_MEMBERS =
            Arrays.asList("mop", "mopf", "wtg", "menu", "mopd", "mops", "ret", "mopx");

    /** The {@code dec.cfc} keys of the MOP scorers. */
    private static final List<String> MOP_CFC_KEYS = Arrays.asList("mop", "mopf", "wtg", "menu");

    @After
    public void leaveNoHeartbeatsBehind() {
        Log.reset();
    }

    private static ScenarioScript script(boolean withLlm) {
        ScenarioScript.Builder builder = ScenarioScript.named("clean-control", SEED)
                .screens(
                        ScenarioScript.screen("home", HOST_ACTIVITY, NAV_PRIORITY, false,
                                ScenarioScript.widget("//*[@resource-id='h0']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h1']", WIDGET_PRIORITY, false, 0.0F),
                                ScenarioScript.widget("//*[@resource-id='h2']", WIDGET_PRIORITY, false, 0.0F)),
                        ScenarioScript.screen("weighted", HOST_ACTIVITY, NAV_PRIORITY, true,
                                ScenarioScript.widget("//*[@resource-id='w_mop']",
                                        WIDGET_PRIORITY, false, 1.0F, DECLARED_WEIGHT),
                                ScenarioScript.widget("//*[@resource-id='w1']", WIDGET_PRIORITY, true, 1.0F),
                                ScenarioScript.widget("//*[@resource-id='w2']", WIDGET_PRIORITY, true, 1.0F)))
                .transition("home", "//*[@resource-id='h0']", "weighted")
                .transition("home", "//*[@resource-id='h1']", "weighted")
                .transition("home", "//*[@resource-id='h2']", "weighted")
                .stepsSinceLauncherFiring(48);
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
                ScenarioScript.step(false, 0),
                ScenarioScript.step(false, 1),
                ScenarioScript.step(false, 2))
                .build();
    }

    /** The trace of one replay of {@code cell}, closed with its {@code RUN_END}, as JSON lines. */
    private static List<JSONObject> trace(OracleScaffold.Cell cell) throws Exception {
        ScenarioScript script = script(cell.hasLlm());
        RunSpec plan = OracleScaffold.planOf(cell, script.getSeed());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NdjsonSink sink = new NdjsonSink(new PrintStream(out, true, "UTF-8"), true,
                plan.has(Feature.MOP));
        ScriptedLlm llm = cell.hasLlm() ? new ScriptedLlm(script) : null;
        OracleSataAgent agent = OracleScaffold.newAgent(cell, script, llm, sink);
        OracleDriver.runUnderSinkLoad(agent, script, llm);
        sink.runEnd("clean-control", null, 0, 0, null);

        List<JSONObject> lines = new ArrayList<>();
        for (String line : out.toString("UTF-8").split("\n")) {
            if (line.startsWith("{")) {
                lines.add(new JSONObject(line));
            }
        }
        return lines;
    }

    private static void assertClean(OracleScaffold.Cell cell) throws Exception {
        assertFalse(cell + " is a cell without MOP",
                OracleScaffold.planOf(cell, SEED).has(Feature.MOP));
        List<JSONObject> lines = trace(cell);
        int steps = 0;
        boolean runEnd = false;
        for (JSONObject line : lines) {
            if (line.has("s")) {
                steps++;
                assertStepClean(cell, line);
            } else if ("ACT".equals(line.optString("type"))) {
                assertFalse(cell + ": ACT carries no mop bit", line.has("mop"));
            } else if ("RUN_END".equals(line.optString("type"))) {
                runEnd = true;
                JSONObject counters = line.getJSONObject("counters");
                for (String counter : Arrays.asList("coinMop", "launches", "retired",
                        "nullRelaunches")) {
                    assertEquals(cell + ": RUN_END.counters." + counter, 0, counters.getInt(counter));
                }
            }
        }
        assertTrue(cell + ": the load wrote step records", steps > 0);
        assertTrue(cell + ": the run closed with RUN_END", runEnd);
    }

    private static void assertStepClean(OracleScaffold.Cell cell, JSONObject step) throws Exception {
        String at = cell + " step " + step.get("s");
        JSONObject dec = step.optJSONObject("dec");
        if (dec == null) {
            return;
        }
        for (String member : MOP_DEC_MEMBERS) {
            assertFalse(at + ": dec." + member, dec.has(member));
        }
        assertFalse(at + ": src=mop", "mop".equals(dec.optString("src")));
        assertFalse(at + ": src=launcher", "launcher".equals(dec.optString("src")));
        assertFalse(at + ": ch=mop_coin", "mop_coin".equals(dec.optString("ch")));
        JSONObject own = dec.optJSONObject("own");
        if (own != null) {
            assertFalse(at + ": own.uM", own.has("uM"));
            assertFalse(at + ": own.i=mop", "mop".equals(own.optString("i")));
        }
        JSONObject cfc = dec.optJSONObject("cfc");
        if (cfc != null) {
            for (String key : MOP_CFC_KEYS) {
                assertFalse(at + ": cfc." + key, cfc.has(key));
            }
        }
        JSONObject nav = dec.optJSONObject("nav");
        if (nav != null) {
            assertFalse(at + ": nav.c=mop", "mop".equals(nav.optString("c")));
        }
    }

    @Test
    public void theBaseCellCarriesNoMopSignal() throws Exception {
        assertClean(OracleScaffold.Cell.BASE);
    }

    @Test
    public void theAsCellCarriesNoMopSignal() throws Exception {
        // The real control of the C2 contrast.
        assertClean(OracleScaffold.Cell.AS);
    }

    @Test
    public void theAsLlmCellCarriesNoMopSignal() throws Exception {
        assertClean(OracleScaffold.Cell.AS_LLM);
    }

    /**
     * The gate is not vacuous: the same replay in a MOP cell writes the signals the clean cells
     * must not, so the assertions above are reading the fields that would carry a leak.
     */
    @Test
    public void theSameReplayInAMopCellCarriesTheSignals() throws Exception {
        boolean coin = false;
        boolean actBit = false;
        for (JSONObject line : trace(OracleScaffold.Cell.AS_MOP_LLM)) {
            JSONObject dec = line.optJSONObject("dec");
            coin = coin || (dec != null && "mop_coin".equals(dec.optString("ch")));
            actBit = actBit || ("ACT".equals(line.optString("type")) && line.has("mop"));
        }
        assertTrue("the MOP coin's channel appears with MOP mounted", coin);
        assertTrue("the ACT mop bit appears with MOP mounted", actBit);
    }
}

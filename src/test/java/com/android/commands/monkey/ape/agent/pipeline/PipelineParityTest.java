package com.android.commands.monkey.ape.agent.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.runtime.Presets;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The roster of each cell preset (task 7.9.6; decision-pipeline "Pipeline Assembly from the
 * RunSpec", scenario "the four shipped presets assemble the rosters their keys state").
 *
 * <p>Each cell is resolved through {@code RunSpec} the way a device receives it — the preset name
 * plus the deployment keys the tool passes ({@code ape.staticDataPath}, {@code ape.llmUrl},
 * {@code ape.variant}) — and both the assembled candidates and the {@code [APE-ARCH]} echo are
 * asserted, in the fixed order {@code Budget, Restart, LlmNewState, LlmStagnation, LlmCoin,
 * MopLauncher, ComponentTrigger, MopCoin, SataChain}.
 */
public class PipelineParityTest {

    /** The cell's plan, with the deployment keys the tool passes for it. */
    private static RunSpec cell(String preset) {
        List<String> keys = new ArrayList<>(Arrays.asList("ape.preset", preset,
                "ape.variant", preset));
        if (!preset.equals(Presets.BASE)) {
            keys.addAll(Arrays.asList("ape.staticDataPath", TestRunSpecs.MOP_PATH));
        }
        if (preset.endsWith("_llm")) {
            keys.addAll(Arrays.asList("ape.llmUrl", PipelineFixture.LLM_URL));
        }
        return TestRunSpecs.spec(keys.toArray(new String[0]));
    }

    /** Assembles the cell and asserts the candidates, the roster and the echoed line. */
    private static void assertCell(String preset, String... roster) {
        RunSpec spec = cell(preset);
        List<String> candidates = new ArrayList<>();
        for (DecisionPipeline.Candidate candidate : DecisionPipeline.assembledCandidates(spec)) {
            candidates.add(candidate.stageName());
        }
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        DecisionPipeline pipeline;
        try {
            System.setOut(new PrintStream(captured, true));
            pipeline = DecisionPipeline.fromSpec(spec, new PipelineFixture.FakeAgent(
                    new PipelineFixture.StubLlm(true, null),
                    new ModelAction(null, ActionType.MODEL_CLICK)));
        } finally {
            System.setOut(originalOut);
        }

        List<String> expected = Arrays.asList(roster);
        assertEquals(preset + ": mounted candidates", expected, candidates);
        assertEquals(preset + ": assembled stages", expected, pipeline.stageNames());
        assertTrue(preset + " echoed: " + captured,
                captured.toString().contains(
                        "[APE-ARCH] stages=[" + String.join(", ", expected) + "]"));
    }

    @Test
    public void base() {
        assertCell(Presets.BASE, "Restart", "SataChain");
    }

    @Test
    public void mop() {
        // No Budget: ACTIVITY_BUDGET is an ADDONS member. No ComponentTrigger: it is STATIC's.
        assertCell(Presets.MOP, "Restart", "MopLauncher", "MopCoin", "SataChain");
    }

    @Test
    public void as() {
        assertCell(Presets.AS, "Budget", "Restart", "ComponentTrigger", "SataChain");
    }

    @Test
    public void asMop() {
        assertCell(Presets.AS_MOP, "Budget", "Restart", "MopLauncher", "ComponentTrigger",
                "MopCoin", "SataChain");
    }

    @Test
    public void asLlm() {
        assertCell(Presets.AS_LLM, "Budget", "Restart", "LlmNewState", "LlmStagnation",
                "LlmCoin", "ComponentTrigger", "SataChain");
    }

    @Test
    public void asMopLlm() {
        assertCell(Presets.AS_MOP_LLM, "Budget", "Restart", "LlmNewState", "LlmStagnation",
                "LlmCoin", "MopLauncher", "ComponentTrigger", "MopCoin", "SataChain");
    }

    @Test
    public void theCoinsAreDrawnOnlyForTheMountedGroups() throws Exception {
        // INV-DP-13: base draws no u_L and no u_M, so dec.own carries neither.
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(cell(Presets.BASE),
                new PipelineFixture.FakeAgent(new PipelineFixture.StubLlm(true, null),
                        new ModelAction(null, ActionType.MODEL_CLICK)));
        FakeStepContext ctx = PipelineFixture.routableStep();

        pipeline.decide(ctx);

        RecordingSink.Own own = ctx.recorder.lastOwner();
        assertEquals("sata", own.i);
        assertTrue(Double.isNaN(own.uL));
        assertTrue(Double.isNaN(own.uM));
    }
}

package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.agent.pipeline.DecisionPipeline;
import com.android.commands.monkey.ape.runtime.Presets;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * event-sink / decision-pipeline: {@code PIPELINE.stageCandidates} lists every candidate decision
 * stage in the fixed order, each marked by whether the plan assembles it (design D-B23).
 */
public class StageCensusTest {

    @Test
    public void theBaseCellListsEveryCandidateAndAssemblesOnlyRestartAndSata() {
        RunSpec spec = TestRunSpecs.spec("ape.preset", Presets.BASE);
        Map<String, Boolean> census = StatefulAgent.stageCensus(spec);

        List<String> order = new ArrayList<>();
        for (DecisionPipeline.Candidate candidate : DecisionPipeline.Candidate.values()) {
            order.add(candidate.stageName());
        }
        assertEquals("every candidate, in the fixed order", order, new ArrayList<>(census.keySet()));
        assertEquals(9, census.size());

        List<String> assembled = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : census.entrySet()) {
            if (entry.getValue()) {
                assembled.add(entry.getKey());
            }
        }
        assertEquals("enabled agrees with the assembled stages", Arrays.asList(
                DecisionPipeline.Candidate.RESTART.stageName(),
                DecisionPipeline.Candidate.SATA_CHAIN.stageName()), assembled);
    }
}

package com.android.commands.monkey.ape.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * A-5 (INV-SEL-05) — the {@code pick_channel} enum is the wire contract of the `[APE-STEP]` line,
 * so its labels are locked here: a typo in one of them would only surface as an unparseable field
 * in the decisive run's telemetry. The non-model derivation is covered next to its sibling in
 * {@code ActivityFrontierTest}, where the type-to-provenance mapping already lives.
 */
public class PickChannelTest {

    @Test
    public void labelsAreTheFixedEnumTheAnalysisExpects() {
        Set<String> labels = new HashSet<>();
        for (ModelAction.PickChannel channel : ModelAction.PickChannel.values()) {
            labels.add(channel.getLabel());
        }
        assertEquals(new HashSet<>(Arrays.asList(
                "short_circuit_unvisited", "short_circuit_0step", "roulette_greedy",
                "roulette_early", "launcher", "llm", "buffer", "sata_other")), labels);
    }

    @Test
    public void everyActionStartsOnTheTotalityValue() {
        // Nothing is ever emitted without a channel: an action nobody stamped reports sata_other.
        assertEquals(ModelAction.PickChannel.SATA_OTHER,
                new ModelAction(null, ActionType.MODEL_CLICK).getPickChannel());
    }

}

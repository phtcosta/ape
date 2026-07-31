package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A3 (INV-SEL-08), emission half: the counterfactual fields appear on exactly the four
 * MOP-sensitive channels and on no others, and a failed recomputation is inert rather than absent.
 */
public class CounterfactualFieldTest {

    private static ModelAction action(ModelAction.PickChannel channel, ModelAction counterfactual) {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setPickChannel(channel);
        a.setCounterfactualPick(counterfactual);
        return a;
    }

    @Test
    public void aDivergentPickReportsTheCounterfactualAction() {
        ModelAction alternative = new ModelAction(null, ActionType.MODEL_CLICK);
        ModelAction picked = action(ModelAction.PickChannel.SHORT_CIRCUIT_UNVISITED, alternative);

        String fields = StatefulAgent.counterfactualFields(picked);

        assertTrue(fields.contains("cf_action=" + alternative));
        assertTrue(fields.endsWith("cf_changed=1"));
    }

    @Test
    public void aPickTheBoostDidNotChangeReportsZero() {
        ModelAction picked = new ModelAction(null, ActionType.MODEL_CLICK);
        picked.setPickChannel(ModelAction.PickChannel.ROULETTE_GREEDY);
        picked.setCounterfactualPick(picked);

        assertTrue(StatefulAgent.counterfactualFields(picked).endsWith("cf_changed=0"));
    }

    @Test
    public void allFourMopSensitiveChannelsCarryTheFields() {
        ModelAction.PickChannel[] channels = {
                ModelAction.PickChannel.SHORT_CIRCUIT_UNVISITED,
                ModelAction.PickChannel.SHORT_CIRCUIT_0STEP,
                ModelAction.PickChannel.ROULETTE_GREEDY,
                ModelAction.PickChannel.ROULETTE_EARLY
        };
        for (ModelAction.PickChannel channel : channels) {
            ModelAction picked = new ModelAction(null, ActionType.MODEL_CLICK);
            picked.setPickChannel(channel);
            picked.setCounterfactualPick(picked);
            assertTrue(channel + " must carry the counterfactual",
                    StatefulAgent.counterfactualFields(picked).contains("cf_changed="));
        }
    }

    @Test
    public void otherChannelsCarryNoCounterfactualAtAll() {
        // MOP boosts do not participate in these picks, so a counterfactual on them would be a
        // contrast between two things that were never weighted.
        ModelAction.PickChannel[] channels = {
                ModelAction.PickChannel.LLM,
                ModelAction.PickChannel.LAUNCHER,
                ModelAction.PickChannel.BUFFER,
                ModelAction.PickChannel.SATA_OTHER
        };
        for (ModelAction.PickChannel channel : channels) {
            assertEquals(channel + " must carry no counterfactual fields",
                    "", StatefulAgent.counterfactualFields(action(channel, null)));
        }
    }

    @Test
    public void aFailedRecomputationIsInertRatherThanAbsent() {
        // Failure containment: the factual pick already happened, so the line keeps its shape and
        // reports no divergence.
        ModelAction picked = action(ModelAction.PickChannel.ROULETTE_EARLY, null);

        String fields = StatefulAgent.counterfactualFields(picked);

        assertTrue(fields.contains("cf_action=" + picked));
        assertTrue(fields.endsWith("cf_changed=0"));
    }
}

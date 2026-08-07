package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.telemetry.EventSink;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A3 (INV-SEL-08), emission half: {@code dec.cf} is defined on exactly the four MOP-sensitive
 * channels and absent on every other, and a failed recomputation is inert rather than absent.
 *
 * <p>The counterfactual action itself is carried only when it diverges. That is not an omission:
 * an unchanged counterfactual <em>is</em> the factual pick, which the record already carries as
 * {@code dec.a}, and repeating the longest string in the record on every MOP-sensitive step would
 * buy nothing a reader cannot get by looking one field up.
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

        assertEquals(1, StatefulAgent.counterfactualChanged(picked));
        assertEquals(alternative.toString(), StatefulAgent.counterfactualAction(picked));
    }

    @Test
    public void aPickTheBoostDidNotChangeReportsZeroAndNoAction() {
        ModelAction picked = new ModelAction(null, ActionType.MODEL_CLICK);
        picked.setPickChannel(ModelAction.PickChannel.ROULETTE_GREEDY);
        picked.setCounterfactualPick(picked);

        assertEquals(0, StatefulAgent.counterfactualChanged(picked));
        assertNull("the unchanged counterfactual is dec.a, and is not written twice",
                StatefulAgent.counterfactualAction(picked));
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
            assertNotEquals(channel + " must carry the counterfactual",
                    EventSink.ABSENT, StatefulAgent.counterfactualChanged(picked));
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
            assertEquals(channel + " must carry no counterfactual at all",
                    EventSink.ABSENT,
                    StatefulAgent.counterfactualChanged(action(channel, null)));
            assertNull(StatefulAgent.counterfactualAction(action(channel, null)));
        }
    }

    @Test
    public void aFailedRecomputationIsInertRatherThanAbsent() {
        // Failure containment: the factual pick already happened, so the record keeps its shape and
        // reports no divergence.
        ModelAction picked = action(ModelAction.PickChannel.ROULETTE_EARLY, null);

        assertEquals(0, StatefulAgent.counterfactualChanged(picked));
        assertNull(StatefulAgent.counterfactualAction(picked));
    }
}

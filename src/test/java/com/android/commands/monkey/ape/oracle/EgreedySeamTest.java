package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.State;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * rearch-01 task 1.6 — the guard on the one production line this change edits (INV-ORA-01
 * carve-out): {@code SataAgent.egreedy()} draws through the overridable {@code getRandom()}
 * (`ApeAgent.java:322-323`) rather than reading {@code ape.getRandom()} directly.
 *
 * <p>The distinction is invisible in production — {@code getRandom()} is defined as
 * {@code return ape.getRandom()}, same object, same stream — and decisive off device, where the
 * harness has no {@code MonkeySourceApe} to put in {@code ape} and the direct read therefore
 * NPEs. Both legs of the epsilon-greedy rung sit behind that coin flip, including
 * {@code State.greedyPickLeastVisited}. If someone ever re-inlines the field read, this test
 * fails instead of the rung silently dropping out of every golden.
 *
 * <p>Wiring comes from {@code OracleSpikeTest} while group 2 is pending; it moves to
 * {@code OracleScaffold} with the rest of the harness (task 2.1).
 */
public class EgreedySeamTest {

    /** A state whose actions are all saturated, so the ladder descends past EARLY_STAGE. */
    private static State saturatedState() throws Exception {
        return OracleSpikeTest.syntheticState("com.example.MainActivity", 3, true);
    }

    @Test
    public void egreedyDrawsFromTheOverriddenStream() throws Exception {
        OracleSpikeTest.SpikeSataAgent agent = OracleSpikeTest.apervAgent(saturatedState(), 42L);
        // egreedyOverride stays null: the real egreedy() runs, and its draw must be intercepted.

        Action selected = agent.ladder();

        assertNotNull("the ladder completed with a null ape field", selected);
        assertEquals("egreedy() consulted the production coin flip exactly once",
                1, agent.egreedyCalls);
        assertTrue("the coin flip drew from the overridden getRandom()", agent.getRandomCalls >= 1);
    }

    /**
     * The seam is only worth anything if it is the *same* stream: a pinned seed must produce a
     * repeatable flip, which is what makes the epsilon-greedy leg deterministic in a golden.
     */
    @Test
    public void theFlipIsRepeatableUnderTheSameSeed() throws Exception {
        Random first = new Random(7L);
        Random second = new Random(7L);

        OracleSpikeTest.SpikeSataAgent a = OracleSpikeTest.apervAgent(saturatedState(), 42L);
        a.pinned = first;
        Action firstPick = a.ladder();

        OracleSpikeTest.SpikeSataAgent b = OracleSpikeTest.apervAgent(saturatedState(), 42L);
        b.pinned = second;
        Action secondPick = b.ladder();

        assertEquals("same seed, same epsilon-greedy outcome",
                String.valueOf(firstPick), String.valueOf(secondPick));
        assertEquals(a.getRandomCalls, b.getRandomCalls);
    }
}

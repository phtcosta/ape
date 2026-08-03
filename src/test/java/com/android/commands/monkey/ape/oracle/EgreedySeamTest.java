package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.model.Action;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * rearch-01 task 1.6 — the guard on the one production line this change edits (the INV-ORA-01
 * carve-out): {@code SataAgent.egreedy()} draws through the overridable {@code getRandom()}
 * ({@code ApeAgent.java:322-323}) rather than reading {@code ape.getRandom()} directly.
 *
 * <p>The distinction is invisible in production — {@code getRandom()} is defined as
 * {@code return ape.getRandom()}, same object, same stream — and decisive off device, where the
 * harness has no {@code MonkeySourceApe} to put in {@code ape} and the direct read therefore
 * NPEs. Both legs of the epsilon-greedy rung sit behind that coin flip, including
 * {@code State.greedyPickLeastVisited}. If someone ever re-inlines the field read, the ladder
 * dies here with a {@code NullPointerException} instead of the rung silently dropping out of
 * every golden.
 *
 * <p>The scenario is a state whose actions are all saturated <i>and</i> whose BACK and MENU are
 * visited: saturation is what makes the chain fall past the EARLY_STAGE rungs, and the visited
 * navigation is what keeps the epsilon-greedy rung from short-circuiting on an unvisited BACK
 * before it reaches the coin flip ({@code SataAgent.java:610-630}).
 */
public class EgreedySeamTest {

    private static ScenarioScript saturatedScript(long seed) {
        return ScenarioScript.named("egreedy-seam", seed)
                .screens(ScenarioScript.screen("main", "com.example.MainActivity",
                        ScenarioScript.DEFAULT_PRIORITY, true,
                        ScenarioScript.widget("//*[@resource-id='w0']",
                                ScenarioScript.DEFAULT_PRIORITY, true, 1.0F),
                        ScenarioScript.widget("//*[@resource-id='w1']",
                                ScenarioScript.DEFAULT_PRIORITY, true, 1.0F),
                        ScenarioScript.widget("//*[@resource-id='w2']",
                                ScenarioScript.DEFAULT_PRIORITY, true, 1.0F)))
                .steps(ScenarioScript.step(false, 0))
                .build();
    }

    @Test
    public void egreedyDrawsFromTheOverriddenStream() throws Exception {
        OracleSataAgent agent = OracleScaffold.newAgent(
                OracleScaffold.Preset.APERV, saturatedScript(42L), null);

        Action selected = agent.ladder();

        assertNotNull("the ladder completed with a null ape field", selected);
        assertTrue("the epsilon-greedy rung drew from the overridden getRandom()",
                agent.getRandomCalls >= 1);
    }

    /**
     * The seam is only worth anything if it is the <i>same</i> stream: a pinned seed must produce a
     * repeatable flip, which is what makes the epsilon-greedy leg deterministic in a golden.
     */
    @Test
    public void theFlipIsRepeatableUnderTheSameSeed() throws Exception {
        OracleSataAgent first = OracleScaffold.newAgent(
                OracleScaffold.Preset.APERV, saturatedScript(7L), null);
        Action firstPick = first.ladder();

        OracleSataAgent second = OracleScaffold.newAgent(
                OracleScaffold.Preset.APERV, saturatedScript(7L), null);
        Action secondPick = second.ladder();

        assertEquals("same seed, same epsilon-greedy outcome",
                String.valueOf(firstPick), String.valueOf(secondPick));
        assertEquals(first.getRandomCalls, second.getRandomCalls);
    }
}

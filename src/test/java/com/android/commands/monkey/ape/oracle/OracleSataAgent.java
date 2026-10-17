package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;

import java.util.Random;

/**
 * rearch-01 task 2.2 — the agent under oracle. It does two things and no more: it pins the
 * agent-side RNG stream, and it is the entry point into the selection ladder (and into the
 * decision record the driver places around it when it loads the sink).
 *
 * <p><b>The RNG seam (design D5).</b> APE draws from two streams seeded from one seed
 * ({@code Monkey.java:697,731}): the {@code RandomHelper} statics, seeded by
 * {@link OracleScaffold}, and {@code ape.getRandom()}, reached through the overridable
 * {@code ApeAgent.getRandom()} ({@code ApeAgent.java:322-323}). Overriding the latter with a
 * per-run {@code new Random(seed)} pins the second stream without a {@code MonkeySourceApe} —
 * which the JVM cannot supply — so the field {@code ape} stays null for the whole run. What the
 * override reaches, verified in the tree rather than assumed: the epsilon-greedy coin flip
 * ({@code SataAgent.java:1330}, after the task-1.6 seam), the epsilon-greedy roulette
 * ({@code :681}), and {@code handleNullAction} ({@code StatefulAgent.java:1695,1706,1711}).
 *
 * <p>No coin is among them: the LLM, MOP and component coins and the fuzz toss are drawn at step
 * open from the four {@code StepRng} streams (design D-B9), seeded by the installed context, so
 * mounting a stage moves no draw of this stream (INV-DP-13).
 *
 * <p><b>Why {@link #ladder()} exists (finding 1.1-a).</b> The ladder cannot be entered
 * reflectively. {@code Class.getDeclaredMethod} runs {@code getDeclaredMethods0}, which resolves
 * every declared signature of the class, and {@code SataAgent.onActivityBlocked(ComponentName)}
 * makes that die with {@code NoClassDefFoundError: android/content/ComponentName}. A compiled
 * call resolves only the invoked signature, so this accessor — not reflection — is how the
 * harness reaches {@code selectNewActionNonnull()}.
 *
 * <p>This class is allocated through {@code Unsafe} like the production agent it extends
 * ({@link OracleScaffold#newAgent}); its constructor never runs, and its fields are injected.
 * Besides the stream it overrides only {@link #triggerComponent}, whose dispatch leaves the JVM:
 * every production decision the ladder makes must be the production one, or the golden records a
 * harness artifact rather than the system under test.
 */
public class OracleSataAgent extends SataAgent {

    /** The pinned agent-side stream; injected by {@link OracleScaffold#newAgent}. */
    Random pinned;

    /** How many times the ladder drew from the agent-side stream — harness observability only. */
    int getRandomCalls;

    /** Never invoked: the harness allocates this class through {@code Unsafe}. */
    public OracleSataAgent() {
        super(null, null);
    }

    @Override
    protected Random getRandom() {
        getRandomCalls++;
        return pinned;
    }

    /** The {@code kind:class} of each target the component trigger fired, in order; null until the first. */
    java.util.List<String> triggered;

    /**
     * The component trigger as a recorded no-op (design D-B14.5): the dispatch needs
     * {@code android.content.Intent}, so nothing is sent, and the step carries the {@code dec.trig}
     * production records for the target the round-robin cursor selected.
     */
    @Override
    public void triggerComponent(int target) {
        if (triggered == null) {
            triggered = new java.util.ArrayList<>();
        }
        String[] kindAndClass = componentTarget(target);
        triggered.add(kindAndClass[0] + ":" + kindAndClass[1]);
        recordComponentTrigger(target);
    }

    /** The compiled entry point into the protected selection ladder. */
    Action ladder() {
        return selectNewActionNonnull();
    }

    /**
     * The compiled entry point into the production decision record, which the driver places around
     * the ladder when it loads the sink ({@code OracleDriver.runUnderSinkLoad}).
     */
    void recordDecision(State state, ModelAction action) {
        recordModelDecision(state, action);
    }
}

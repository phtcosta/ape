package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.model.Action;

import java.util.Random;

/**
 * rearch-01 task 2.2 — the agent under oracle. It does two things and no more: it pins the
 * agent-side RNG stream, and it is the entry point into the selection ladder.
 *
 * <p><b>The RNG seam (design D5).</b> APE draws from two streams seeded from one seed
 * ({@code Monkey.java:697,731}): the {@code RandomHelper} statics, seeded by
 * {@link OracleScaffold}, and {@code ape.getRandom()}, reached through the overridable
 * {@code ApeAgent.getRandom()} ({@code ApeAgent.java:322-323}). Overriding the latter with a
 * per-run {@code new Random(seed)} pins the second stream without a {@code MonkeySourceApe} —
 * which the JVM cannot supply — so the field {@code ape} stays null for the whole run. What the
 * override reaches: the epsilon-greedy coin flip ({@code SataAgent.java:1330}, after the task-1.6
 * seam), the epsilon-greedy roulette ({@code :681}), the component-trigger draw ({@code :548})
 * and {@code handleNullAction} ({@code StatefulAgent.java:1695,1706,1711}).
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
 * It deliberately overrides nothing else: every production decision the ladder makes must be the
 * production one, or the golden records a harness artifact rather than the system under test.
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

    /** The compiled entry point into the protected selection ladder. */
    Action ladder() {
        return selectNewActionNonnull();
    }
}

package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

/**
 * {@link MopContext}s stated as values, for the tests whose fakes used to hand out a bare
 * {@link MopData}.
 *
 * <p>{@link #defaults} goes through {@link MopContext#fromSpec} over the jar-default MOP arm, so a
 * fake built here carries exactly the mode and weights a run without overrides would — the same
 * route {@code StatefulAgent} takes, rather than a second copy of the defaults.
 */
public final class TestMopContexts {

    private TestMopContexts() {
    }

    /** The jar-default context ({@code flag}, 500/300) over {@code data}; null when data is null. */
    public static MopContext defaults(MopData data) {
        return MopContext.fromSpec(data, TestRunSpecs.mopSpec().mop());
    }

    /**
     * A {@code flag} context with the given flag weights (the distance keys at their defaults);
     * null when data is null.
     */
    public static MopContext flag(MopData data, int weightDirect, int weightTransitive) {
        if (data == null) {
            return null;
        }
        return new MopContext(data, MopScoring.FLAG, weightDirect, weightTransitive,
                500, 400, 300, 3, 6, null);
    }
}

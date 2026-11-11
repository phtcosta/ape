package com.android.commands.monkey.ape.utils;

import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

/**
 * {@link MopContext}s stated as values, for the tests whose fakes used to hand out a bare
 * {@link MopData}.
 *
 * <p>{@link #defaults} goes through {@link MopContext#fromSpec} over the jar-default MOP arm, so a
 * fake built here carries exactly the mode and weights a run without overrides would — the same
 * route {@code StatefulAgent} takes, rather than a second copy of the defaults. A context built
 * without a static model carries {@link StaticGuiModel#EMPTY}.
 */
public final class TestMopContexts {

    private TestMopContexts() {
    }

    /** The jar-default context ({@code flag}, 500/300) over {@code data}; null when data is null. */
    public static MopContext defaults(MopData data) {
        return defaults(data, StaticGuiModel.EMPTY);
    }

    /** {@link #defaults(MopData)} over both providers of one loaded artifact. */
    public static MopContext defaults(MopData data, StaticGuiModel staticModel) {
        return MopContext.fromSpec(data, staticModel, TestRunSpecs.mopSpec().mop());
    }

    /** {@link #defaults(MopData, StaticGuiModel)} over a loaded artifact. */
    public static MopContext defaults(StaticArtifactLoader.Loaded loaded) {
        return defaults(loaded.mopData, loaded.staticModel);
    }

    /**
     * A {@code flag} context with the given flag weights; null when data is null. The distance
     * keys, which {@code flag} does not read, hold fixed values rather than the plan's: the E7
     * ladder {@code 500,400,300} and {@code ape.mopRetireAfter} 3 (the defaults are calibration
     * v6's F1, {@code 194,146,97} and 10), and {@code ape.mopLauncherDmax} 6.
     */
    public static MopContext flag(MopData data, int weightDirect, int weightTransitive) {
        if (data == null) {
            return null;
        }
        return new MopContext(data, StaticGuiModel.EMPTY, MopScoring.FLAG, weightDirect,
                weightTransitive, new int[] {500, 400, 300}, 3, 6, null);
    }
}

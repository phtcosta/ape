package com.android.commands.monkey.ape.utils;

import java.util.Objects;

import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.runtime.RunSpec;

/**
 * Everything a MOP reader needs about the run, as one immutable value (design D17 "Plumbing", API
 * Design "Part B: {@code MopContext}"): the loaded {@link MopData} for the targets, the
 * {@link StaticGuiModel} split from the same artifact for the WTG edges, the widget metadata and
 * the components (plan P-1: MOP reads both providers), the scoring mode ({@code ape.mopScoring}),
 * the weights of both modes, and the run's {@link MopRetirement}.
 *
 * <p>Built once by {@code StatefulAgent}, right after the artifact loads, and null when MOP is not
 * mounted — so a null context and a MOP-off cell are the same statement (design D-B14.21). It is
 * passed wherever {@code MopData} was: in {@code ScoringContext} for {@code MopWidgetPass}, and in
 * the decision {@code StepContext}, whose {@code mopData()} is derived from it.
 *
 * <p>Both modes' weights are held whatever the mode; a reader honors the mode, and the keys the
 * selected mode does not read are inert (INV-RUN-09, {@link MopScoring#modeKeys()}).
 */
public final class MopContext {

    private final MopData data;
    private final StaticGuiModel staticModel;
    private final MopScoring scoring;
    private final int weightDirect;
    private final int weightTransitive;
    private final int weightD1;
    private final int weightD2;
    private final int weightD3;
    private final int retireAfter;
    private final int launcherDmax;
    private final MopRetirement retirement;

    /**
     * @param staticModel the static model of the same artifact ({@link StaticGuiModel#EMPTY} in a
     *                    test that reads none of it)
     * @param retirement the run's retirement; null under {@code flag} (INV-MOP-37). Under
     *                   {@code distance} {@link #fromSpec} always builds one; a null one there
     *                   (tests) retires nothing
     * @throws IllegalArgumentException when a retirement is given under {@code flag}
     */
    public MopContext(MopData data, StaticGuiModel staticModel, MopScoring scoring,
            int weightDirect, int weightTransitive, int weightD1, int weightD2, int weightD3,
            int retireAfter, int launcherDmax, MopRetirement retirement) {
        this.data = Objects.requireNonNull(data, "data");
        this.staticModel = Objects.requireNonNull(staticModel, "staticModel");
        this.scoring = Objects.requireNonNull(scoring, "scoring");
        if (scoring == MopScoring.FLAG && retirement != null) {
            throw new IllegalArgumentException("no retirement exists under flag (INV-MOP-37)");
        }
        this.weightDirect = weightDirect;
        this.weightTransitive = weightTransitive;
        this.weightD1 = weightD1;
        this.weightD2 = weightD2;
        this.weightD3 = weightD3;
        this.retireAfter = retireAfter;
        this.launcherDmax = launcherDmax;
        this.retirement = retirement;
    }

    /**
     * The context of a run whose artifact loaded as {@code data} and {@code staticModel}, under the
     * plan's MOP parameters; null when {@code data} is null (MOP not mounted). A non-null
     * {@code data} implies the plan carries the MOP feature, so {@code params} is then non-null.
     *
     * <p>Under {@code distance} it builds the run's {@link MopRetirement}, sized by the artifact's
     * target count and retiring at {@code ape.mopRetireAfter} (design D19); under {@code flag} the
     * retirement is null (INV-MOP-37). {@code StatefulAgent} calls this once per run, so the run has
     * exactly one retirement.
     */
    public static MopContext fromSpec(MopData data, StaticGuiModel staticModel,
            RunSpec.MopParams params) {
        if (data == null) {
            return null;
        }
        Objects.requireNonNull(params, "a loaded artifact needs the plan's MOP parameters");
        MopRetirement retirement = params.scoring() == MopScoring.DISTANCE
                ? new MopRetirement(data.targetCount(), params.retireAfter())
                : null;
        return new MopContext(data, staticModel, params.scoring(), params.weightDirect(),
                params.weightTransitive(), params.weightD1(), params.weightD2(),
                params.weightD3(), params.retireAfter(), params.launcherDmax(), retirement);
    }

    /** The loaded artifact's targets; never null. */
    public MopData data() {
        return data;
    }

    /** The static model split from the same artifact: WTG, widget metadata, components. */
    public StaticGuiModel staticModel() {
        return staticModel;
    }

    /** {@code ape.mopScoring}. */
    public MopScoring scoring() {
        return scoring;
    }

    /** {@code ape.mopWeightDirect}: the {@code flag} weight of a direct flag. */
    public int weightDirect() {
        return weightDirect;
    }

    /** {@code ape.mopWeightTransitive}: the {@code flag} weight of a transitive flag. */
    public int weightTransitive() {
        return weightTransitive;
    }

    /** {@code ape.mopWeightD1}: the {@code distance} weight for {@code d <= 1}. */
    public int weightD1() {
        return weightD1;
    }

    /** {@code ape.mopWeightD2}: the {@code distance} weight for {@code d = 2}. */
    public int weightD2() {
        return weightD2;
    }

    /** {@code ape.mopWeightD3}: the {@code distance} weight for {@code d = 3}. */
    public int weightD3() {
        return weightD3;
    }

    /** {@code ape.mopRetireAfter}: first interactions after which a target retires. */
    public int retireAfter() {
        return retireAfter;
    }

    /** {@code ape.mopLauncherDmax}: the largest distance the launcher orders activities by. */
    public int launcherDmax() {
        return launcherDmax;
    }

    /** The run's retirement, or null (always under {@code flag}). */
    public MopRetirement retirement() {
        return retirement;
    }
}

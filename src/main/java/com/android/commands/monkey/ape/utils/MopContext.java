package com.android.commands.monkey.ape.utils;

import java.util.Objects;

import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.runtime.RunSpec;

/**
 * Everything a MOP reader needs about the run, as one immutable value (design D17 "Plumbing", API
 * Design "Part B: {@code MopContext}"): the loaded {@link MopData} for the targets, the
 * {@link StaticGuiModel} split from the same artifact for the WTG edges, the widget metadata and
 * the components (plan P-1: MOP reads both providers), the scoring mode ({@code ape.mopScoring}),
 * the weights of both modes ({@code flag}'s direct and transitive weights, {@code distance}'s
 * ladder {@code ape.mopWeights}), and the run's {@link MopRetirement}.
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
    private final int[] weights;
    private final int retireAfter;
    private final int launcherDmax;
    private final MopRetirement retirement;

    /**
     * @param staticModel the static model of the same artifact ({@link StaticGuiModel#EMPTY} in a
     *                    test that reads none of it)
     * @param weights the {@code distance} ladder, element {@code l - 1} the weight of level
     *                {@code l}; copied, so the caller keeps no handle on it
     * @param retirement the run's retirement; null under {@code flag} (INV-MOP-37). Under
     *                   {@code distance} {@link #fromSpec} always builds one; a null one there
     *                   (tests) retires nothing
     * @throws IllegalArgumentException when a retirement is given under {@code flag}
     */
    public MopContext(MopData data, StaticGuiModel staticModel, MopScoring scoring,
            int weightDirect, int weightTransitive, int[] weights,
            int retireAfter, int launcherDmax, MopRetirement retirement) {
        this.data = Objects.requireNonNull(data, "data");
        this.staticModel = Objects.requireNonNull(staticModel, "staticModel");
        this.scoring = Objects.requireNonNull(scoring, "scoring");
        if (scoring == MopScoring.FLAG && retirement != null) {
            throw new IllegalArgumentException("no retirement exists under flag (INV-MOP-37)");
        }
        this.weightDirect = weightDirect;
        this.weightTransitive = weightTransitive;
        this.weights = Objects.requireNonNull(weights, "weights").clone();
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
                params.weightTransitive(), params.weights(), params.retireAfter(),
                params.launcherDmax(), retirement);
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

    /** {@code ape.mopWeights}: the {@code distance} ladder, as a fresh copy. */
    public int[] weights() {
        return weights.clone();
    }

    /** The cut c: the ladder's length, the deepest level with a weight. */
    public int cut() {
        return weights.length;
    }

    /**
     * The ladder's weight of {@code level} (counting from 1): element {@code level - 1} when
     * {@code level <= cut()}, and 0 beyond the cut.
     *
     * @param level a level, {@code >= 1}
     */
    public int levelWeight(int level) {
        return level <= weights.length ? weights[level - 1] : 0;
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

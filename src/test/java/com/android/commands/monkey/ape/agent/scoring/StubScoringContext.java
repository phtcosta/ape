package com.android.commands.monkey.ape.agent.scoring;

import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.StaticGuiModel;
import com.android.commands.monkey.ape.utils.TestMopContexts;
import com.android.commands.monkey.ape.utils.UICoverageTracker;

/**
 * A mutable {@link ScoringContext} stub for pass/pipeline unit tests — the seam that lets a pass be
 * tested without an agent (INV-ARCH-02). All collaborators default to inert values; set only what a
 * test needs.
 *
 * <p>The two providers are set apart, as the loader splits them: {@link #staticModel} for the WTG
 * and {@link #mopData} for the targets. A test that only cares about the MOP targets sets
 * {@link #mopData}, and the stub hands out the jar-default context over it and the static model; a
 * test that needs a mode or weights of its own sets {@link #mopContext}, which then wins.
 */
final class StubScoringContext implements ScoringContext {

    StaticGuiModel staticModel = null;
    MopData mopData = null;
    MopContext mopContext = null;
    UICoverageTracker coverageTracker = new UICoverageTracker();
    Graph graph = null;
    int timestamp = 1;

    @Override
    public StaticGuiModel getStaticModel() {
        return staticModel;
    }

    @Override
    public MopContext getMopContext() {
        return mopContext != null ? mopContext : TestMopContexts.defaults(mopData,
                staticModel != null ? staticModel : StaticGuiModel.EMPTY);
    }

    @Override
    public UICoverageTracker getCoverageTracker() {
        return coverageTracker;
    }

    @Override
    public Graph getGraph() {
        return graph;
    }

    @Override
    public int getTimestamp() {
        return timestamp;
    }
}

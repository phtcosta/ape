/*
 * Copyright 2020 Advanced Software Technologies Lab at ETH Zurich, Switzerland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.commands.monkey.ape.agent.pipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.android.commands.monkey.ape.model.ActivityNode;
import com.android.commands.monkey.ape.model.ActivityTriggerAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.utils.ComponentInfo;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.MopRetirement;

/**
 * Launch the next unvisited activity of the arm's MOP census, on a fixed cadence.
 *
 * <p>Exploration that only ever clicks what is on screen can leave a monitored activity unreached for
 * a whole run because nothing on any visited screen leads to it. This stage launches one directly,
 * every {@code cadence} passes, walking the census round-robin so that a census the run keeps failing
 * to reach is not retried from its first entry forever.
 *
 * <p><b>Two counters, two lifecycles, and it is easy to conflate them.</b> The cadence counter resets
 * at every firing point regardless of what the candidate scan finds — that is what keeps firing
 * periodic and stops a run rescanning an exhausted census on every step. The launch budget, in
 * contrast, is consumed only when an activity is actually launched (INV-CT-12), because a firing that
 * finds nothing has spent no budget.
 *
 * <p><b>The cadence counter advances at entry, which is a decision.</b> A step decided by an earlier
 * stage never reaches this one, so it never advances the cadence — an LLM-preempted step does not
 * bring the next launch closer (finding 3.3-1, INV-DP-08). That is preserved behaviour, and it is
 * preserved <em>by construction</em> here rather than by a comment: hard preemption means the
 * increment is simply not executed. The counter is defensible on its own terms too, since it counts
 * SATA-eligible selection passes, and a step the LLM decided is not one.
 *
 * <p>The block used to increment before checking whether the launcher was enabled at all, so a run
 * with no MOP data advanced a counter that could never fire, was never logged, and died with the
 * process. That counter is gone rather than relocated: this stage exists only where a launcher does
 * (INV-DP-03).
 *
 * <p><b>Which eligible activity, by scoring mode</b> (component-triggering "Cadence-Based MOP
 * Activity Launch", design D20, INV-CT-15). Under {@code flag} the walk is the manifest round-robin
 * of {@link #selectTriggerCandidate}. Under {@code distance} it is {@link #selectByDistance}: a
 * rotation over the eligible activities ordered by their nearest unretired {@code activityDist}
 * target within {@code ape.mopLauncherDmax}, then manifest order. The mode and the bound are
 * injected at assembly; the artifact and the run's retirement, which the stage reads and never
 * writes, arrive through {@link StepContext#mopContext()} (INV-DP-12). Eligibility is the same in
 * both modes, and neither reads the launch result (INV-CT-14).
 */
public final class MopLauncherStage implements DecisionStage {

    /**
     * Framework/tooling class-name prefixes that are never genuine app screens (Compose preview,
     * abstract framework activities, leakcanary, test scaffolds). Debug-build manifest merging pulls
     * these into the app package and over-approximated reachability can pull them into the MOP
     * census, so they otherwise pass the eligibility conjunction; the class namespace is the
     * discriminator (app code is never authored here). A fixed correctness filter, not a tunable —
     * no configuration key (INV-CT-10). cmpft4: 76% of 114 launches hit these.
     */
    private static final String[] FRAMEWORK_ACTIVITY_PREFIXES = {
            "android.", "androidx.", "com.google.android.", "kotlin.", "kotlinx.",
            "junit.", "org.junit.", "leakcanary.",
    };

    /** {@link #selectByDistance}'s answer when no activity is eligible, and the cursor's start. */
    public static final long NO_SELECTION = -1L;

    private final int cadence;
    private final int maxPerRun;
    private final MopScoring scoring;
    private final int launcherDmax;

    /** Selection passes since the last firing point; reset there, whatever the scan found. */
    private int stepsSinceFiring;

    /**
     * The round-robin cursor, persisted across firings (INV-CT-06, INV-DP-07); its content changes
     * kind with the mode. Under {@code flag}, where the next census walk starts: advanced by one at
     * every firing, candidate or not. Under {@code distance}, the sort position
     * ({@link #sortPosition}) of the last selection, {@link #NO_SELECTION} before the first: moved
     * at every selection, whatever the launch result (design D20).
     */
    private long roundRobinIndex;

    /** Launches actually performed this run; the budget the cap bounds (INV-CT-12). */
    private int launchCount;

    /**
     * @param cadence selection passes between firings
     * @param maxPerRun per-run launch budget; zero means unlimited
     * @param scoring {@code ape.mopScoring}: which eligible activity is chosen (design D20)
     * @param launcherDmax {@code ape.mopLauncherDmax}: the largest distance the {@code distance}
     *                     order counts; inert under {@code flag}
     */
    public MopLauncherStage(int cadence, int maxPerRun, MopScoring scoring, int launcherDmax) {
        this.cadence = cadence;
        this.maxPerRun = maxPerRun;
        this.scoring = scoring;
        this.launcherDmax = launcherDmax;
        this.roundRobinIndex = scoring == MopScoring.DISTANCE ? NO_SELECTION : 0;
    }

    @Override
    public String name() {
        return DecisionPipeline.Candidate.MOP_LAUNCHER.stageName();
    }

    @Override
    public StageResult decide(StepContext ctx) {
        stepsSinceFiring++;
        if (!shouldFire(stepsSinceFiring, cadence, launchCount, maxPerRun)) {
            return StageResult.continueChain();
        }
        stepsSinceFiring = 0;
        Set<String> visited = new HashSet<>();
        for (ActivityNode an : ctx.graph().getActivityNodes()) {
            visited.add(an.activity);
        }
        MopData data = ctx.mopData();
        ComponentInfo candidate;
        if (scoring == MopScoring.DISTANCE) {
            long position = selectByDistance(data.getActivities(), visited,
                    data.getMainActivity(), data.getMopActivities(), data,
                    ctx.mopContext().retirement(), launcherDmax, roundRobinIndex);
            if (position == NO_SELECTION) {
                candidate = null;
            } else {
                roundRobinIndex = position; // the last selection, whatever the launch result
                candidate = data.getActivities().get(manifestIndex(position));
            }
        } else {
            candidate = selectTriggerCandidate(data.getActivities(), visited,
                    data.getMainActivity(), (int) roundRobinIndex, data.getMopActivities());
            roundRobinIndex++;
        }
        if (candidate == null) {
            return StageResult.continueChain();
        }
        launchCount++; // budget consumed only on an actual launch (INV-CT-12)
        Logger.iformat("[APE-RV] Triggering activity: %s", candidate.className);
        // Package captured from MopData (never from the class name — INV-CT-04).
        ActivityTriggerAction trigger = new ActivityTriggerAction(ctx.mopData().getPackageName(),
                candidate.className, candidate.deepLinkUri);
        return StageResult.select(trigger, ModelAction.DecisionSource.Component.name());
    }

    /**
     * The cadence gate (pure, INV-CT-05/12).
     *
     * <p>Equality against the cadence rather than {@code >=}: the counter is reset to zero at every
     * firing point, so while firing is active it never overshoots. The budget clause reads
     * {@code maxPerRun == 0} as unlimited.
     *
     * @param stepsSinceFiring passes since the last firing point, already incremented for this step
     * @param cadence passes between firings
     * @param launchesSoFar launches performed this run
     * @param maxPerRun per-run budget, zero for unlimited
     * @return whether this pass is a firing point with budget left
     */
    public static boolean shouldFire(int stepsSinceFiring, int cadence, int launchesSoFar, int maxPerRun) {
        return stepsSinceFiring == cadence && (maxPerRun == 0 || launchesSoFar < maxPerRun);
    }

    /**
     * The candidate walk (pure, INV-CT-06/10). Round-robin over {@code activities} from
     * {@code rrIndex} (wrapping once), returning the first that satisfies, at call time, ALL of: a
     * member of the arm's MOP census ({@code className} in {@code mopActivities}), not
     * framework/tooling-namespaced, no permission gate ({@code permission == null}), not the main
     * activity (by {@code isMain} flag or by name equal to {@code mainActivity}), and currently
     * unvisited ({@code className} not in {@code visitedActivities}).
     *
     * <p>Exported status is not consulted, and cannot be: the artifact carries no such field,
     * because the dispatch path ({@code IActivityManager.startActivity} from uid 2000) launches
     * non-exported activities and an export test would silently shrink the frontier. What used to
     * be a rule this walk had to remember is now a property of the data it reads
     * (INV-CT-10). There is no non-census fallback: a null/empty
     * census, or no eligible census member, yields null. The census is the reachability-augmented
     * {@code MopData.activityHasMop} truth (INV-MOP-27), NOT the component-level
     * {@code ComponentInfo.reachesTarget} field, which false-negatives lambda-triggered activities.
     * The caller owns the index increment.
     *
     * @param activities the arm's declared activities
     * @param visitedActivities activity names the run has already reached
     * @param mainActivity the app's launcher activity, never a candidate
     * @param rrIndex where this walk starts
     * @param mopActivities the arm's MOP census
     * @return the activity to launch, or {@code null} when none is eligible
     */
    public static ComponentInfo selectTriggerCandidate(List<? extends ComponentInfo> activities,
            Set<String> visitedActivities, String mainActivity, int rrIndex,
            Set<String> mopActivities) {
        if (activities == null || activities.isEmpty() || mopActivities == null) {
            return null;
        }
        int n = activities.size();
        for (int i = 0; i < n; i++) {
            int idx = (((rrIndex + i) % n) + n) % n; // safe modulo for any rrIndex
            ComponentInfo c = activities.get(idx);
            if (isEligible(c, visitedActivities, mainActivity, mopActivities)) {
                return c;
            }
        }
        return null;
    }

    /**
     * The {@code distance} walk (pure, design D20, INV-CT-15): the rotation over the eligible
     * activities in distance order. An activity's key is
     * {@link MopRetirement#nearest(MopRetirement, int[][], int)} over its {@code activityDist}
     * pairs with {@code dmax}: the smallest unretired distance within the bound, or
     * {@link Integer#MAX_VALUE} when it has none, so keyless activities sort after every keyed one.
     * The order is ascending {@link #sortPosition}, key then manifest index, recomputed at each
     * call. The candidate is the first eligible activity whose position comes after
     * {@code lastPosition}, wrapping to the head of the order when none does. Eligibility is
     * exactly {@link #selectTriggerCandidate}'s; the launch result is never an input.
     *
     * @param activities the arm's declared activities, in manifest order
     * @param visitedActivities activity names the run has already reached
     * @param mainActivity the app's launcher activity, never a candidate
     * @param mopActivities the arm's MOP census
     * @param data the artifact whose {@code activityDist} keys the order
     * @param retirement the run's retirement, read only; null retires nothing
     * @param dmax {@code ape.mopLauncherDmax}
     * @param lastPosition the sort position of the last selection, or {@link #NO_SELECTION}
     * @return the selected activity's sort position ({@link #manifestIndex} recovers it), or
     *         {@link #NO_SELECTION} when none is eligible
     */
    public static long selectByDistance(List<? extends ComponentInfo> activities,
            Set<String> visitedActivities, String mainActivity, Set<String> mopActivities,
            MopData data, MopRetirement retirement, int dmax, long lastPosition) {
        if (activities == null || mopActivities == null) {
            return NO_SELECTION;
        }
        long head = Long.MAX_VALUE;
        long after = Long.MAX_VALUE;
        for (int idx = 0; idx < activities.size(); idx++) {
            ComponentInfo c = activities.get(idx);
            if (!isEligible(c, visitedActivities, mainActivity, mopActivities)) {
                continue;
            }
            long position = sortPosition(
                    MopRetirement.nearest(retirement, data.activityDist(c.className), dmax), idx);
            head = Math.min(head, position);
            if (position > lastPosition) {
                after = Math.min(after, position);
            }
        }
        if (after != Long.MAX_VALUE) {
            return after;
        }
        return head == Long.MAX_VALUE ? NO_SELECTION : head;
    }

    /**
     * The sort position {@code (key, manifest index)} of the {@code distance} order as one
     * non-negative {@code long} whose natural order is the lexicographic one: the key (a distance,
     * or {@link Integer#MAX_VALUE} for none) in the high half, the manifest index in the low half.
     */
    static long sortPosition(int key, int manifestIndex) {
        return ((long) key << 32) | manifestIndex;
    }

    /** The manifest index a {@link #sortPosition} holds. */
    public static int manifestIndex(long position) {
        return (int) position;
    }

    /**
     * The eligibility conjunction both walks share (INV-CT-06/10): a member of the arm's MOP census
     * (no fallback outside it), not framework/tooling-namespaced, no permission gate, not the main
     * activity (by {@code isMain} flag or by name), and currently unvisited.
     */
    private static boolean isEligible(ComponentInfo c, Set<String> visitedActivities,
            String mainActivity, Set<String> mopActivities) {
        if (!mopActivities.contains(c.className)) {
            return false; // census-only: never launch outside the arm's MOP census (no fallback)
        }
        if (isFrameworkActivity(c.className)) {
            return false;
        }
        return c.permission == null && !c.isMain
                && !c.className.equals(mainActivity)
                && !visitedActivities.contains(c.className);
    }

    /**
     * Whether {@code className} starts with any {@link #FRAMEWORK_ACTIVITY_PREFIXES} entry (prefix
     * match, never substring).
     */
    private static boolean isFrameworkActivity(String className) {
        if (className == null) {
            return false;
        }
        for (String prefix : FRAMEWORK_ACTIVITY_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

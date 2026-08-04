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
package com.android.commands.monkey.ape.agent;

import static com.android.commands.monkey.ape.utils.Config.checkRestart;
import static com.android.commands.monkey.ape.utils.Config.inputRate;
import static com.android.commands.monkey.ape.utils.Config.restartThresholdMax;
import static com.android.commands.monkey.ape.utils.Config.restartThresholdMin;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.android.commands.monkey.MonkeySourceApe;
import com.android.commands.monkey.MonkeyUtils;
import com.android.commands.monkey.ape.Agent;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.BadStateException;
import com.android.commands.monkey.ape.StopTestingException;
import com.android.commands.monkey.ape.events.ApeEvent;
import com.android.commands.monkey.ape.events.ApeFuzzer;
import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Crash;
import com.android.commands.monkey.ape.model.CrashAction;
import com.android.commands.monkey.ape.model.FuzzAction;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.MopScorer;
import com.android.commands.monkey.ape.utils.TypedInputGenerator;
import com.android.commands.monkey.ape.utils.InputValueGenerator;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.RandomHelper;
import com.android.commands.monkey.ape.utils.StringCache;
import com.android.commands.monkey.ape.utils.Utils;

import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

public abstract class ApeAgent implements Agent {

    /**
     * Build the agent the resolved plan asks for.
     *
     * <p>The agent type and the replay log arrive already validated, so this method has no default
     * branch and no error path: an unrecognized type aborted the run at resolution, and a replay
     * run without a log did too. What it replaced was a chain that fell through to
     * {@code SataAgent} for a null or unknown type — the shape that let {@code --ape bfs} run a
     * full campaign as SATA while the operator believed otherwise — and a {@code System.exit(1)}
     * inside a constructor path for the missing replay log.
     *
     * <p>The graph always starts empty. There is no model file to load from.
     */
    public static ApeAgent createAgent(MonkeySourceApe ape, RunSpec spec) {
        Graph graph = new Graph();
        String type = spec.agentType();
        if (type.equals("sata")) {
            return new SataAgent(ape, graph);
        }
        if (type.equals("random")) {
            return new RandomAgent(ape, graph);
        }
        if (type.equals("replay")) {
            return new ReplayAgent(ape, graph, spec.replayLog());
        }
        // Unreachable through RunSpec, and it fails loudly rather than defaulting: a type that
        // escaped validation must not quietly become the agent whose behavior everyone assumes.
        throw new IllegalStateException("Unvalidated agent type: " + type);
    }


    protected MonkeySourceApe ape;
    protected int timestamp;
    private int lastBadStateCount;
    private int badStateCounter;
    private int totalBadStates;

    private Set<String> activityNames = new HashSet<>();
    private Map<String, Set<String>> activityTransitions = new HashMap<>();

    private final InputValueGenerator inputValueGenerator = new InputValueGenerator();

    protected boolean disableFuzzing;
    private boolean restart;
    private int nextRestartThreshold;
    private int lastRestartStep;
    boolean newActivityStarting;
    private boolean disableRestart;
    boolean start;

    private final long beginMillis;
    public ApeAgent(MonkeySourceApe ape) {
        this.ape = ape;
        updateRestartThreshold();
        beginMillis = System.currentTimeMillis();
    }

    public final boolean canFuzzing() {
        return !disableFuzzing;
    }

    @Override
    public boolean activityResuming(String pkg) {
        return false;
    }

    @Override
    public boolean activityStarting(Intent intent, String pkg) {
        boolean allow = MonkeyUtils.getPackageFilter().checkEnteringPackage(pkg);
        if (allow) {
            String newActivity = intent.getComponent().getClassName();
            if (activityNames.add(newActivity)) {
                newActivityStarting = true;
            }
            String currentActivity = ape.getTopActivityClassName();
            Utils.addToMapSet(activityTransitions, currentActivity, newActivity);
        }
        return allow;
    }

    static String processNameToPackageName(String processName) {
        int index = processName.indexOf(":");
        if (index == -1) {
            return processName;
        }
        return processName.substring(0, index);
    }

    public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg, long timeMillis,
            String stackTrace) {
        Crash crash = new Crash(processName, pid, shortMsg, longMsg, timeMillis, stackTrace);
        CrashAction action = new CrashAction(crash);
        Logger.iformat("Appending crash [%s] to action history [%s]", crash, Thread.currentThread());
        appendToActionHistory(timeMillis, action);
        return false;
    }

    @Override
    public int appEarlyNotResponding(String arg0, int arg1, String arg2) {
        return 0;
    }

    @Override
    public int appNotResponding(String arg0, int arg1, String arg2) {
        return 0;
    }

    @Override
    public int systemNotResponding(String arg0) {
        return 0;
    }


    public Action generateFuzzingAction() {
        List<ApeEvent> events = ApeFuzzer.generateFuzzingEvents();
        Action fuzzAction = new FuzzAction(events);
        return fuzzAction;
    }

    protected Action checkInput(Action action) {
        if (action.requireTarget()) {
            GUITreeNode node = ((ModelAction) action).getResolvedNode();
            boolean llmTextEntry = isLlmInputDecision(action, node);
            if ((node.isEditText() || llmTextEntry) && node.getInputText() == null) {
                // In a form-completion context fill deterministically so every field of a form
                // gets text; otherwise keep the legacy probabilistic gate for non-form screens
                // (INV-FORM-03 / INV-INP-04). The toss is short-circuited when in context.
                // An LLM decision on an input-capable widget also fills deterministically: it is
                // the *what* half of fixTextEdit (B6(iv)), where CoordinateMapper already decided
                // the decision is a text entry rather than a bare press. The generator is the
                // same one a SATA-selected input action uses — the LLM is never asked for the text.
                if (llmTextEntry || inFormCompletionContext() || RandomHelper.toss(inputRate)) {
                    node.setInputText(generateInputText(node));
                }
            }
        }
        return action;
    }

    /**
     * True when the selected action is an LLM decision landing on an input-capable widget — the
     * fixTextEdit case. Widens the fill beyond {@code isEditText()} to the input-capable set the
     * prompt and the dead-pair ban share (it adds both {@code SearchView} classes), and only for
     * LLM-originated decisions: the algorithmic arms keep their existing input behavior exactly.
     * Pure static so the guard is unit-testable without a live agent.
     */
    static boolean isLlmInputDecision(Action action, GUITreeNode node) {
        if (node == null || !(action instanceof ModelAction)) return false;
        return ((ModelAction) action).getDecisionSource() == ModelAction.DecisionSource.LLM
                && ApePromptBuilder.isInputClass(node);
    }

    /**
     * Overridden by {@link StatefulAgent}: true when the current state carries at least one
     * unfilled {@code EditText} (the form-completion context). The base agent has no state model,
     * so it returns false and {@link #checkInput} keeps the legacy {@code inputRate} toss.
     */
    protected boolean inFormCompletionContext() {
        return false;
    }

    /**
     * Generate input text for an EditText node. gh13 T1.3: when static-analysis MOP data is
     * available and carries a non-empty inputType/hint for the widget, use the type-aware
     * generator; otherwise fall back to the legacy heuristic / random generator (no regression
     * on non-instrumented apps). Bypassed entirely when the plan's fuzzInputTyped is false.
     */
    protected String generateInputText(GUITreeNode node) {
        if (RunContext.current().spec().exploration().fuzzInputTyped()) {
            MopData md = getMopData();
            if (md != null) {
                String activity = ape.getTopActivityClassName();
                // INV-MOP-23: resolve the static widget via the same ±2-level containment
                // policy as the MOP scoring pass (design D3), not an exact-id-only lookup.
                // The node's own short id is tried first (list order guarantees this).
                for (String shortId : MopScorer.containmentShortIds(node)) {
                    MopData.Widget w = md.getWidget(activity, shortId);
                    if (w != null && (notEmpty(w.inputType) || notEmpty(w.hint))) {
                        return TypedInputGenerator.generateForType(w.inputType, w.hint, RandomHelper.getRandom());
                    }
                }
            }
        }
        return Config.heuristicInput ? inputValueGenerator.generateForNode(node) : StringCache.nextString();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /** Overridden by StatefulAgent to expose loaded MOP data; null disables T1.3. */
    protected MopData getMopData() {
        return null;
    }

    protected Action checkFuzzing(Action origin) {
        return origin; 
    }

    protected Action getStartAction(ActionType actionType) {
        return Action.getStartAction(actionType, ape.randomlyPickMainApp());
    }

    protected ActionType nextRestartAction() {
        if (RandomHelper.toss(0.2D)) {
            return ActionType.EVENT_CLEAN_RESTART;
        }
        return ActionType.EVENT_RESTART;
    }

    protected Action checkRestart(Action origin) {
        if (!checkRestart) {
            return origin;
        }
        if (restart) {
            restart = false;
            lastRestartStep = getTimestamp();
            this.startNewEpisode();
            return getStartAction(nextRestartAction());
        }
        if (disableRestart) {
            return origin;
        }
        int contSteps = getTimestamp() - lastRestartStep;
        if (contSteps > nextRestartThreshold) {
            lastRestartStep = getTimestamp();
            updateRestartThreshold();
            this.startNewEpisode();
            return getStartAction(nextRestartAction());
        }
        return origin;
    }

    protected void updateRestartThreshold() {
        if (restartThresholdMax <= restartThresholdMin) {
            nextRestartThreshold = restartThresholdMin;
        } else {
            nextRestartThreshold = RandomHelper.nextBetween(restartThresholdMin, restartThresholdMax);
        }
    }

    public void disableRestart() {
        Logger.iprintln("Requesting disabling restart.");
        this.disableRestart = true;
    }

    public void requestStart() {
        this.start = true;
    }

    public void requestRestart() {
        this.restart = true;
    }

    protected Random getRandom() {
        return ape.getRandom();
    }

    protected boolean toss(double probability) {
        double v = ape.getRandom().nextDouble();
        return v < probability;
    }

    public int getTimestamp() {
        return this.timestamp;
    }

    public boolean onLostFocused(int counter) {
        return true;
    }

    public final Action updateState(ComponentName topComp, AccessibilityNodeInfo info) {
        Action action = updateStateWrapper(topComp, info);
        return action;
    }

    public String getElapsedTestingTime() {
        long duration = System.currentTimeMillis() - beginMillis;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(duration) % 60;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(duration) % 60;
        long hours = TimeUnit.MILLISECONDS.toHours(duration) % 24;
        long days = TimeUnit.MILLISECONDS.toDays(duration);
        return String.format("%04d %02d:%02d:%02d", days, hours, minutes, seconds);
    }

    private Action updateStateWrapper(ComponentName topComp, AccessibilityNodeInfo info) {
        try {
            Logger.format(">>>>>>>> %s begin step [%d][Elapsed: %s]", getLoggerName(), ++timestamp,
                    getElapsedTestingTime());
            printExploredActivity();
            printMemoryUsage();
            try {
                disableRestart = false;
                disableFuzzing = false;
                return checkInput(checkFuzzing(checkRestart(updateStateInternal(topComp, info))));
            } catch (BadStateException e) {
                Logger.wprintln("Bad state, retrieve the Window node.");
                info = ape.getRootInActiveWindowSlow();
                if (info == null) {
                    Logger.wprintln("Fail to retrieve the Window node.");
                    throw e;
                }
                return updateStateInternal(topComp, info);
            }
        } catch (BadStateException e) {
            Logger.wprintln("Handle bad state.");
            totalBadStates++;
            if (lastBadStateCount == (timestamp - 1)) {
                badStateCounter++;
            } else {
                badStateCounter = 0;
            }
            lastBadStateCount = timestamp;
            onBadState(lastBadStateCount, badStateCounter);
            if (badStateCounter > 10) {
                ape.stopTopActivity();
            }
            if (totalBadStates > 100) {
                throw new StopTestingException("Too many bad states.");
            }
            return handleBadState();
        } catch (Exception e) {
            throw e;
        } finally {
            Logger.format(">>>>>>>> %s end step [%d]", getLoggerName(), timestamp);
        }

    }

    public abstract void onBadState(int lastBadStateCount, int badStateCounter);

    public abstract String getLoggerName();

    protected Action handleBadState() {
        return Action.ACTIVATE;
    }

    protected Action handleTrivialState() {
        return Action.NOP;
    }

    protected abstract Action updateStateInternal(ComponentName topComp, AccessibilityNodeInfo info);

    public int nextInt(int bound) {
        return ape.getRandom().nextInt(bound);
    }

    static Comparator<Entry<String, Object>> comparator = new Comparator<Entry<String, Object>>() {

        @Override
        public int compare(Entry<String, Object> o1, Entry<String, Object> o2) {
            return o1.getKey().compareTo(o2.getKey());
        }

    };

    public void tearDown() {
        printActivities();
        Config.printConfigurations();
    }


    protected File checkOutputDir() {
        File dir = ape.getOutputDirectory();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }



    private void printExploredActivity() {
        if (timestamp % 50 == 0) {
            printActivities();
        } else {
            Logger.iformat("Explored %d app activities.", activityNames.size());
        }
    }

    private void printMemoryUsage() {
        final Runtime runtime = Runtime.getRuntime();
        final long usedMemInMB=(runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
        final long maxHeapSizeInMB=runtime.maxMemory() / 1048576L;
        final long availHeapSizeInMB = maxHeapSizeInMB - usedMemInMB;
        Logger.iformat("Used: %d MB, Max: %d MB, Available: %d MB", usedMemInMB, maxHeapSizeInMB, availHeapSizeInMB);
    }

    private void printActivities() {
        String[] names = this.activityNames.toArray(new String[0]);
        Arrays.sort(names);
        Logger.println("Explored app activities:");
        for (int i = 0; i < names.length; i++) {
            Logger.format("%4d %s", i + 1, names[i]);
        }
    }

}

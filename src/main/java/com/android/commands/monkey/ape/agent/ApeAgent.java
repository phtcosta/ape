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
import com.android.commands.monkey.ape.runtime.Feature;
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
import com.android.commands.monkey.ape.telemetry.RunCounter;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.MopScorer;
import com.android.commands.monkey.ape.utils.StaticGuiModel;
import com.android.commands.monkey.ape.utils.TypedInputGenerator;
import com.android.commands.monkey.ape.utils.InputValueGenerator;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.RandomHelper;
import com.android.commands.monkey.ape.utils.StringCache;
import com.android.commands.monkey.ape.utils.Utils;

import android.content.ComponentName;
import android.content.Intent;
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

    /** The restart kinds {@code dec.restart} names (decision-pipeline "Restart Guard Stage"). */
    public static final String RESTART_GRAPH = "graph";
    public static final String RESTART_STATE = "state";
    public static final String RESTART_ACTIVITY = "activity";
    public static final String RESTART_PERIODIC = "periodic";

    protected boolean disableFuzzing;
    private boolean restart;
    /** The stability check that raised {@link #restart}: {@code graph}, {@code state} or {@code activity}. */
    private String restartKind;
    /** The kind {@link #restartDue} found due on this step, for {@link #startRestart}; null when none. */
    private String dueRestartKind;
    private int nextRestartThreshold;
    private int lastRestartStep;
    boolean newActivityStarting;
    private boolean disableRestart;
    /**
     * Whether this step's {@code updateStateInternal} is the {@code BadStateException} retry, which
     * {@link #checkRestart} never restarts; cleared at step start.
     */
    private boolean badStateRetry;
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
                boolean formFill = !llmTextEntry && inFormCompletionContext();
                if (llmTextEntry || formFill || RandomHelper.toss(inputRate)) {
                    InputText input = generateInputText(node);
                    node.setInputText(input.text);
                    // dec.inp (heuristic-input INV-INP-10): llm > form > the generator's origin.
                    RunContext.current().sink().input(llmTextEntry ? InputText.LLM
                            : formFill ? InputText.FORM : input.origin);
                }
            }
        }
        return action;
    }

    /**
     * A generated input text and the generator that produced it, the origin {@code dec.inp}
     * records when no higher-precedence origin ({@code llm}, {@code form}) applies.
     */
    static final class InputText {
        static final String TYPED = "typed";
        static final String HEURISTIC = "heuristic";
        static final String RANDOM = "random";
        static final String FORM = "form";
        static final String LLM = "llm";

        final String text;
        final String origin;

        InputText(String text, String origin) {
            this.text = text;
            this.origin = origin;
        }
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
     * Overridden by {@link StatefulAgent}: true when the plan mounts {@code FORM_COMPLETION} and the
     * current state carries at least one unfilled {@code EditText} (the form-completion context).
     * The base agent has no state model, so it returns false and {@link #checkInput} keeps the
     * legacy {@code inputRate} toss.
     */
    protected boolean inFormCompletionContext() {
        return false;
    }

    /**
     * The text for an EditText node, from the first generator the plan admits (heuristic-input
     * "ApeAgent.checkInput() Integration"):
     * <ol>
     * <li>{@code typed}: with {@code TYPED_FUZZ} mounted, the static model's {@code inputType} or
     * {@code hint} for the node or one of its containment nodes (node first, INV-MOP-23), read from
     * {@link StaticGuiModel} and never from {@code MopData} (INV-INP-09);</li>
     * <li>{@code heuristic}: with {@code HEURISTIC_INPUT} mounted, {@link InputValueGenerator};</li>
     * <li>{@code random}: {@link StringCache#nextString()}.</li>
     * </ol>
     */
    protected InputText generateInputText(GUITreeNode node) {
        RunSpec spec = RunContext.current().spec();
        StaticGuiModel staticModel = staticModel();
        if (spec.has(Feature.TYPED_FUZZ) && staticModel != null) {
            String activity = ape.getTopActivityClassName();
            // The containment walk is a tree-only helper: it reads no MOP data.
            for (GUITreeNode candidate : MopScorer.containmentNodes(node)) {
                StaticGuiModel.WidgetMeta w = staticModel.widget(activity,
                        MopData.extractShortId(candidate.getResourceID()));
                if (w != null && (notEmpty(w.inputType) || notEmpty(w.hint))) {
                    return new InputText(TypedInputGenerator.generateForType(w.inputType, w.hint,
                            RandomHelper.getRandom()), InputText.TYPED);
                }
            }
        }
        if (spec.has(Feature.HEURISTIC_INPUT)) {
            return new InputText(inputValueGenerator.generateForNode(node), InputText.HEURISTIC);
        }
        return new InputText(StringCache.nextString(), InputText.RANDOM);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * The run's static GUI model, or null when no group reads the artifact. Overridden by
     * {@link StatefulAgent}, which holds the model; the base agent has none.
     */
    public StaticGuiModel staticModel() {
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

    /**
     * Whether this agent's decision pipeline assembled the RESTART stage, which then takes the
     * restart before selection. The base agent has no pipeline.
     */
    protected boolean restartStageMounted() {
        return false;
    }

    /**
     * The post-selection restart, APE's: called once per step after selection, it replaces the
     * selected action with a start action when {@link #restartDue} holds. It returns {@code origin}
     * unchanged when the RESTART stage is mounted, which takes the restart before selection
     * instead, when {@code ape.checkRestart} is off, or on the {@code BadStateException} retry of
     * the step, which APE runs without a restart check.
     *
     * @param origin the action this step selected
     * @return {@code origin}, or the start action of {@link #startRestart}
     */
    protected Action checkRestart(Action origin) {
        if (restartStageMounted() || badStateRetry
                || !RunContext.current().spec().exploration().checkRestart()) {
            return origin;
        }
        if (restartDue()) {
            return startRestart();
        }
        return origin;
    }

    /**
     * Whether a restart is due on this step: the stability flag of step 2 is up, or the steps since
     * the last restart exceed the periodic threshold and the {@code disableRestart} latch is not
     * set. This call reads the latch and clears it. With the RESTART stage mounted it runs before
     * selection, so the latch it reads is the one the previous step's selection left, and a step
     * that an earlier guard (Budget) takes leaves the latch for the next step. Without the stage it
     * runs after selection, from {@link #checkRestart}, and the latch was cleared at step start, so
     * it reads the one this step's selection set.
     *
     * @return whether a restart is due
     */
    public boolean restartDue() {
        boolean latched = disableRestart;
        disableRestart = false;
        if (restart) {
            dueRestartKind = restartKind;
        } else if (!latched && getTimestamp() - lastRestartStep > nextRestartThreshold) {
            dueRestartKind = RESTART_PERIODIC;
        } else {
            dueRestartKind = null;
        }
        return dueRestartKind != null;
    }

    /**
     * The start action of the restart {@link #restartDue} found due: lowers the stability flag,
     * sets {@code lastRestartStep}, redraws the periodic threshold for a periodic restart, starts a
     * new episode, and records the kind as {@code dec.restart} with one {@code restarts} count.
     * The start action keeps APE's kind toss ({@code EVENT_CLEAN_RESTART} with probability 0.2);
     * the threshold and the toss stay on the helper stream, in APE's order.
     *
     * @return the start action, never null
     * @throws IllegalStateException when no restart is due on this step
     */
    public Action startRestart() {
        String kind = dueRestartKind;
        if (kind == null) {
            throw new IllegalStateException("startRestart without a due restart");
        }
        dueRestartKind = null;
        restart = false;
        restartKind = null;
        lastRestartStep = getTimestamp();
        if (RESTART_PERIODIC.equals(kind)) {
            updateRestartThreshold();
        }
        this.startNewEpisode();
        RunContext.current().sink().restart(kind);
        RunContext.current().sink().count(RunCounter.RESTARTS);
        return getStartAction(nextRestartAction());
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

    /**
     * Raises the restart flag.
     *
     * @param kind the stability check that raised it: {@link #RESTART_GRAPH},
     *        {@link #RESTART_STATE} or {@link #RESTART_ACTIVITY}
     */
    public void requestRestart(String kind) {
        this.restart = true;
        this.restartKind = kind;
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
                disableFuzzing = false;
                badStateRetry = false;
                if (!restartStageMounted()) {
                    // The post-selection restart reads the latch this step's selection sets; the
                    // RESTART stage reads the one the previous step left.
                    disableRestart = false;
                }
                return checkInput(checkFuzzing(updateStateInternal(topComp, info)));
            } catch (BadStateException e) {
                Logger.wprintln("Bad state, retrieve the Window node.");
                info = retrieveWindowNode(e);
                badStateRetry = true;
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

    /**
     * The active window's root for the {@code BadStateException} retry, fetched after waiting for
     * the device to be idle.
     *
     * @param e the bad state the step's first attempt raised, rethrown when there is no root
     * @return the root, never null
     */
    protected AccessibilityNodeInfo retrieveWindowNode(BadStateException e) {
        AccessibilityNodeInfo info = ape.getRootInActiveWindowSlow();
        if (info == null) {
            Logger.wprintln("Fail to retrieve the Window node.");
            throw e;
        }
        return info;
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

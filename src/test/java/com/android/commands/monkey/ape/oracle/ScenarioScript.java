package com.android.commands.monkey.ape.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * rearch-01 task 2.3 — the declarative description of one oracle scenario: a synthetic app
 * (screens plus a transition table) and an ordered list of steps carrying the agent-side state
 * and LLM verdicts the driver replays.
 *
 * <p>A script is a value: it is hand-written in a capture test, built once, and never mutated.
 * Everything a golden depends on is declared here — including the two properties the oracle's
 * entry point leaves unassigned (see {@link OracleScaffold} finding 1.2-b: action validity and
 * priority) and the two the production loop would compute off device ({@code _isNewState} and
 * {@code graphStableCounter}, design D7). Declaring them is what makes the goldens independent
 * of every scoring weight: the scenario states the inputs the ladder reads, so no golden record
 * can depend on how a score was computed above the entry point.
 *
 * <p><b>Screens and transitions.</b> The screen list is the app; its first element is the entry
 * screen. A transition maps {@code (screen, selected action's xpath)} to the next screen; an
 * unmapped selection leaves the agent on the same screen, which is the common case and keeps
 * scenarios short. The driver (task 5.1) is what consults the table — this class only holds it.
 *
 * <p><b>LLM entries.</b> A step's entry declares which of the three hooks the script wants routed
 * and what comes back when one does. It is consumed by {@code ScriptedLlm} (task 3.1), which owns
 * the verdict and nothing more: each stage evaluates its own condition before consulting the script,
 * so a scripted {@code routeStagnation} still yields to the episode's single-shot flag and a
 * scripted {@code routeNewState} still yields to {@code _isNewState} — but it is the stage that
 * yields, not this table, and such a hook never reaches the script at all. A step with no entry
 * declares that no consultation is expected; {@code ScriptedLlm} fails loudly if a hook reaches its
 * engine anyway.
 */
public final class ScenarioScript {

    /**
     * The priority every scripted action carries unless the scenario says otherwise. Any positive
     * value works — {@code State.countActionPriority} only requires {@code > 0} — and the value is
     * deliberately not derived from any {@code Config} scoring weight, since the oracle enters
     * below the pipeline that would have computed one.
     */
    public static final int DEFAULT_PRIORITY = 8;

    /** What the scripted LLM answers when a hook routes. */
    public enum LlmVerdict {
        /** Return a {@code ModelAction} chosen from the offered list by the entry's selector. */
        ACCEPT,
        /** Return null — the ladder falls through to the mechanisms below the LLM blocks. */
        DECLINE,
        /** Return null as well; distinguished from DECLINE only as provenance in the golden. */
        TIMEOUT
    }

    /** One targeted widget of a screen, materialized as a {@code MODEL_CLICK} action. */
    public static final class Widget {
        private final String xpath;
        private final int priority;
        private final boolean visited;
        private final float saturation;
        private final int mopBoost;

        Widget(String xpath, int priority, boolean visited, float saturation, int mopBoost) {
            this.xpath = xpath;
            this.priority = priority;
            this.visited = visited;
            this.saturation = saturation;
            this.mopBoost = mopBoost;
        }

        public String getXPath() { return xpath; }
        public int getPriority() { return priority; }
        public boolean isVisited() { return visited; }

        /**
         * The MOP boost the scoring pipeline would have written, declared here because that
         * pipeline runs above the oracle's entry point (finding 6.1-a). Without a declared boost
         * every synthetic action carries 0, the MOP short-circuit is a no-op, and no step can be
         * attributed {@code MOP} — so a scenario that wants a MOP-attributed step declares it,
         * exactly as it declares validity and priority (finding 1.2-b).
         */
        public int getMopBoost() { return mopBoost; }

        /**
         * {@code ModelAction.isSaturated()} is visit-based for targetless actions but
         * {@code resolvedSaturation >= 1.0F} for targeted ones ({@code ModelAction.java:154-159}),
         * so a merely-visited widget still gets offered by the EARLY_STAGE rungs. Descending to
         * EPSILON_GREEDY requires saturation, not visits.
         */
        public float getSaturation() { return saturation; }
    }

    /** One screen of the synthetic app: an activity, its widgets, and its BACK/MENU state. */
    public static final class Screen {
        private final String name;
        private final String activity;
        private final List<Widget> widgets;
        private final int navPriority;
        private final boolean navVisited;

        Screen(String name, String activity, int navPriority, boolean navVisited,
               List<Widget> widgets) {
            this.name = name;
            this.activity = activity;
            this.navPriority = navPriority;
            this.navVisited = navVisited;
            this.widgets = Collections.unmodifiableList(new ArrayList<>(widgets));
        }

        public String getName() { return name; }
        public String getActivity() { return activity; }
        public List<Widget> getWidgets() { return widgets; }
        /** Priority of the state's MODEL_BACK and MODEL_MENU actions. */
        public int getNavPriority() { return navPriority; }
        /** Whether MODEL_BACK and MODEL_MENU start visited — for them, visited means saturated. */
        public boolean isNavVisited() { return navVisited; }
    }

    /** The scripted LLM consultation of one step. */
    public static final class LlmEntry {
        private final boolean routeNewState;
        private final boolean routeStagnation;
        private final boolean routeRandom;
        private final LlmVerdict verdict;
        private final String selector;

        LlmEntry(boolean routeNewState, boolean routeStagnation, boolean routeRandom,
                 LlmVerdict verdict, String selector) {
            this.routeNewState = routeNewState;
            this.routeStagnation = routeStagnation;
            this.routeRandom = routeRandom;
            this.verdict = verdict;
            this.selector = selector;
        }

        public boolean routesNewState() { return routeNewState; }
        public boolean routesStagnation() { return routeStagnation; }
        public boolean routesRandom() { return routeRandom; }
        public LlmVerdict getVerdict() { return verdict; }

        /** Names how ACCEPT picks from the offered action list; null for DECLINE and TIMEOUT. */
        public String getSelector() { return selector; }
    }

    /** One selection step: the agent-side state the driver injects before invoking the ladder. */
    public static final class Step {
        private final boolean isNewState;
        private final int graphStableCounter;
        private final LlmEntry llm;

        Step(boolean isNewState, int graphStableCounter, LlmEntry llm) {
            this.isNewState = isNewState;
            this.graphStableCounter = graphStableCounter;
            this.llm = llm;
        }

        public boolean isNewState() { return isNewState; }
        public int getGraphStableCounter() { return graphStableCounter; }
        /** The scripted consultation, or null when the step expects none. */
        public LlmEntry getLlm() { return llm; }
    }

    private final String name;
    private final long seed;
    private final List<Screen> screens;
    private final Map<String, Screen> screensByName;
    private final Map<String, String> transitions;
    private final List<Step> steps;
    private final int stepsSinceLauncherFiring;
    private final List<String> exhaustedActivities;

    private ScenarioScript(String name, long seed, List<Screen> screens,
                           Map<String, String> transitions, List<Step> steps,
                           int stepsSinceLauncherFiring, List<String> exhaustedActivities) {
        this.stepsSinceLauncherFiring = stepsSinceLauncherFiring;
        this.exhaustedActivities =
                Collections.unmodifiableList(new ArrayList<>(exhaustedActivities));
        this.name = name;
        this.seed = seed;
        this.screens = Collections.unmodifiableList(new ArrayList<>(screens));
        this.transitions = Collections.unmodifiableMap(new HashMap<>(transitions));
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        Map<String, Screen> byName = new LinkedHashMap<>();
        for (Screen screen : this.screens) {
            byName.put(screen.getName(), screen);
        }
        this.screensByName = Collections.unmodifiableMap(byName);
    }

    public String getName() { return name; }

    /** Seeds both RNG streams: {@code RandomHelper.seed} and the agent's {@code getRandom()}. */
    public long getSeed() { return seed; }

    public List<Screen> getScreens() { return screens; }
    public List<Step> getSteps() { return steps; }

    /**
     * The value {@code _stepsSinceLauncherFiring} starts the run at (design D2). The launcher fires
     * on an exact equality against {@code Config.activityTriggerStagnationStep} (50 by default) and
     * the counter's increment is the block's first statement, so a run that starts at 0 reaches the
     * fire on its 50th non-preempted pass. Declaring the starting value is how a scenario puts that
     * fire on a step a reviewer can actually read. The driver never touches the counter afterwards —
     * that prohibition is what finding 3.3-1 and INV-ORA-05 rest on.
     */
    public int getStepsSinceLauncherFiring() { return stepsSinceLauncherFiring; }

    /**
     * Activities whose iteration budget is declared exhausted (design D2), so the ladder's first
     * block is actually entered. {@code ActivityBudgetTracker.isBudgetExhausted} answers false for
     * an unregistered activity, and both registration ({@code StatefulAgent.java:765}) and the
     * iteration count ({@code :1410}) live above the oracle's entry point — so without this
     * declaration the block is never entered and a golden cannot tell "budget disabled" from
     * "budget enabled with nothing trivial to navigate to". What the open gate leads to is a
     * separate matter: the trivial-action return is outside the capture boundary (finding 6.1-b).
     */
    public List<String> getExhaustedActivities() { return exhaustedActivities; }

    /** The screen the run starts on: the first declared. */
    public Screen getEntryScreen() { return screens.get(0); }

    public Screen getScreen(String screenName) {
        Screen screen = screensByName.get(screenName);
        if (screen == null) {
            throw new IllegalArgumentException("no such screen in scenario " + name + ": " + screenName);
        }
        return screen;
    }

    /**
     * The screen reached by selecting {@code targetXPath} on {@code fromScreen}. An unmapped
     * selection — including every targetless action — stays on the same screen.
     */
    public String nextScreen(String fromScreen, String targetXPath) {
        String to = transitions.get(transitionKey(fromScreen, targetXPath));
        return to != null ? to : fromScreen;
    }

    private static String transitionKey(String from, String targetXPath) {
        return from + " -> " + targetXPath;
    }

    // ---- authoring API --------------------------------------------------------------------

    /** An unvisited, unsaturated widget at the default priority, carrying no MOP boost. */
    public static Widget widget(String xpath) {
        return new Widget(xpath, DEFAULT_PRIORITY, false, 0.0F, 0);
    }

    public static Widget widget(String xpath, int priority, boolean visited, float saturation) {
        return new Widget(xpath, priority, visited, saturation, 0);
    }

    /** A widget carrying a declared MOP boost — see {@link Widget#getMopBoost()}. */
    public static Widget widget(String xpath, int priority, boolean visited, float saturation,
                                int mopBoost) {
        return new Widget(xpath, priority, visited, saturation, mopBoost);
    }

    /** A screen whose BACK and MENU actions are unvisited, at the default priority. */
    public static Screen screen(String name, String activity, Widget... widgets) {
        return new Screen(name, activity, DEFAULT_PRIORITY, false, java.util.Arrays.asList(widgets));
    }

    public static Screen screen(String name, String activity, int navPriority, boolean navVisited,
                                Widget... widgets) {
        return new Screen(name, activity, navPriority, navVisited, java.util.Arrays.asList(widgets));
    }

    /** A step with no scripted LLM consultation. */
    public static Step step(boolean isNewState, int graphStableCounter) {
        return new Step(isNewState, graphStableCounter, null);
    }

    public static Step step(boolean isNewState, int graphStableCounter, LlmEntry llm) {
        return new Step(isNewState, graphStableCounter, llm);
    }

    /** An entry that routes and accepts, picking from the offered list by {@code selector}. */
    public static LlmEntry accept(boolean newState, boolean stagnation, boolean random,
                                  String selector) {
        if (selector == null) {
            throw new IllegalArgumentException("an ACCEPT entry must name its selector");
        }
        return new LlmEntry(newState, stagnation, random, LlmVerdict.ACCEPT, selector);
    }

    public static LlmEntry decline(boolean newState, boolean stagnation, boolean random) {
        return new LlmEntry(newState, stagnation, random, LlmVerdict.DECLINE, null);
    }

    public static LlmEntry timeout(boolean newState, boolean stagnation, boolean random) {
        return new LlmEntry(newState, stagnation, random, LlmVerdict.TIMEOUT, null);
    }

    public static Builder named(String name, long seed) {
        return new Builder(name, seed);
    }

    /** Assembles a script and validates it, so an authoring mistake fails at build time. */
    public static final class Builder {
        private final String name;
        private final long seed;
        private final List<Screen> screens = new ArrayList<>();
        private final Map<String, String> transitions = new HashMap<>();
        /** The screens transitions start on, kept so {@link #build} can validate that half too. */
        private final Set<String> transitionSources = new LinkedHashSet<>();
        private final List<Step> steps = new ArrayList<>();
        private final List<String> exhaustedActivities = new ArrayList<>();
        private int stepsSinceLauncherFiring;

        private Builder(String name, long seed) {
            this.name = name;
            this.seed = seed;
        }

        /** See {@link ScenarioScript#getStepsSinceLauncherFiring()}. */
        public Builder stepsSinceLauncherFiring(int seeded) {
            this.stepsSinceLauncherFiring = seeded;
            return this;
        }

        /** See {@link ScenarioScript#getExhaustedActivities()}. */
        public Builder budgetExhausted(String... activities) {
            Collections.addAll(exhaustedActivities, activities);
            return this;
        }

        public Builder screens(Screen... declared) {
            Collections.addAll(screens, declared);
            return this;
        }

        public Builder transition(String from, String targetXPath, String to) {
            transitions.put(transitionKey(from, targetXPath), to);
            transitionSources.add(from);
            return this;
        }

        public Builder steps(Step... declared) {
            Collections.addAll(steps, declared);
            return this;
        }

        public ScenarioScript build() {
            if (screens.isEmpty()) {
                throw new IllegalStateException("scenario " + name + " declares no screen");
            }
            if (steps.isEmpty()) {
                throw new IllegalStateException("scenario " + name + " declares no step");
            }
            List<String> names = new ArrayList<>();
            for (Screen screen : screens) {
                if (names.contains(screen.getName())) {
                    throw new IllegalStateException("duplicate screen name in " + name + ": "
                            + screen.getName());
                }
                names.add(screen.getName());
            }
            for (Map.Entry<String, String> transition : transitions.entrySet()) {
                if (!names.contains(transition.getValue())) {
                    throw new IllegalStateException("transition " + transition.getKey()
                            + " targets an undeclared screen: " + transition.getValue());
                }
            }
            // The source half matters as much as the target half, and it fails far more quietly: a
            // transition keyed on a screen that does not exist simply never matches, so the driver
            // leaves the agent where it was and the run walks a topology the author did not write.
            // A scenario that stalls looks exactly like a scenario that works, which is why this is
            // a build-time error rather than something a golden diff is expected to reveal.
            for (String source : transitionSources) {
                if (!names.contains(source)) {
                    throw new IllegalStateException("scenario " + name + " declares a transition"
                            + " starting on an undeclared screen: " + source);
                }
            }
            for (String activity : exhaustedActivities) {
                boolean known = false;
                for (Screen screen : screens) {
                    known = known || screen.getActivity().equals(activity);
                }
                if (!known) {
                    throw new IllegalStateException("scenario " + name + " declares the budget of "
                            + activity + " exhausted, but no screen runs on that activity");
                }
            }
            return new ScenarioScript(name, seed, screens, transitions, steps,
                    stepsSinceLauncherFiring, exhaustedActivities);
        }
    }
}

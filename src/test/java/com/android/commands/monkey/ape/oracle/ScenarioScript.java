package com.android.commands.monkey.ape.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p><b>LLM entries.</b> A step's entry declares which of the three hooks the script wants
 * routed and what the router answers. It is consumed by {@code ScriptedLlmRouter} (task 3.1),
 * which honors the agent-side arguments on top of these flags: a scripted
 * {@code routeStagnation} still yields to the episode's single-shot flag, and a scripted
 * {@code routeNewState} still yields to {@code _isNewState}. A step with no entry declares that
 * no consultation is expected; the router fails loudly if the agent asks anyway.
 */
public final class ScenarioScript {

    /**
     * The priority every scripted action carries unless the scenario says otherwise. Any positive
     * value works — {@code State.countActionPriority} only requires {@code > 0} — and the value is
     * deliberately not derived from any {@code Config} scoring weight, since the oracle enters
     * below the pipeline that would have computed one.
     */
    public static final int DEFAULT_PRIORITY = 8;

    /** What the scripted router answers when a hook routes. */
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

        Widget(String xpath, int priority, boolean visited, float saturation) {
            this.xpath = xpath;
            this.priority = priority;
            this.visited = visited;
            this.saturation = saturation;
        }

        public String getXPath() { return xpath; }
        public int getPriority() { return priority; }
        public boolean isVisited() { return visited; }

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

    private ScenarioScript(String name, long seed, List<Screen> screens,
                           Map<String, String> transitions, List<Step> steps) {
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

    /** An unvisited, unsaturated widget at the default priority. */
    public static Widget widget(String xpath) {
        return new Widget(xpath, DEFAULT_PRIORITY, false, 0.0F);
    }

    public static Widget widget(String xpath, int priority, boolean visited, float saturation) {
        return new Widget(xpath, priority, visited, saturation);
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
        private final List<Step> steps = new ArrayList<>();

        private Builder(String name, long seed) {
            this.name = name;
            this.seed = seed;
        }

        public Builder screens(Screen... declared) {
            Collections.addAll(screens, declared);
            return this;
        }

        public Builder transition(String from, String targetXPath, String to) {
            transitions.put(transitionKey(from, targetXPath), to);
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
            return new ScenarioScript(name, seed, screens, transitions, steps);
        }
    }
}

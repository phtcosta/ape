package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.agent.scoring.ScoringContext;
import com.android.commands.monkey.ape.agent.scoring.ScoringPipeline;
import com.android.commands.monkey.ape.llm.LlmRouter;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ActivityNode;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.Model;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateActionDiffer;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.naming.Namer;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.RandomHelper;
import com.android.commands.monkey.ape.utils.UICoverageTracker;
import com.android.commands.monkey.ape.utils.Utils;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * rearch-01 group 2 — the oracle's construction layer: it allocates a {@link OracleSataAgent}
 * without running a constructor, injects the fields the selection ladder reads, and materializes
 * a {@link ScenarioScript.Screen} into a synthetic {@code State} registered in a synthetic graph.
 *
 * <p>This javadoc is <b>the harness's honesty ledger</b> (design D7): everything the ladder reads
 * that the harness fakes is named here, together with why it is faked and what that costs. It is
 * the frozen record of the task-1 spike (which this class replaced) plus what group 2 added. When
 * a stage-2/3 rename forces an adaptation, this is the one file that changes — the goldens and the
 * scenario scripts do not (INV-ORA-07).
 *
 * <p>This is the ledger's <b>injection</b> half: what the harness fakes before the first call into
 * the ladder. Its <b>replay</b> half — what the driver does between two calls, which is what makes a
 * multi-step golden more than a repeated first step — is {@link OracleDriver}'s javadoc.
 *
 * <h2>Why the constructor cannot run (finding 1.1-b)</h2>
 * {@code StatefulAgent}'s constructor takes a {@code MonkeySourceApe}, which does not class-load
 * off device ({@code MonkeySourceApeForeignGuardTest:19-21} — its {@code UiAutomation} field), and
 * stubbing {@code android.*} toward it does not converge: {@code IUiAutomationConnection} →
 * {@code RemoteException} → {@code Build$VERSION}, the last of which would require inventing an
 * SDK level the production code branches on. So the agent is {@code Unsafe}-allocated, the field
 * {@code ape} stays null for the whole run, and every collaborator the constructor would have
 * built is injected below.
 *
 * <h2>The injection ledger (design D7)</h2>
 * {@code Unsafe.allocateInstance} skips <i>every</i> field initializer, so an inline-initialized
 * field is as absent as a constructor-assigned one. What the ladder reads, and where it comes from:
 * <table border="1">
 *   <caption>fields the harness supplies</caption>
 *   <tr><th>Field</th><th>Owner</th><th>Why the ladder touches it</th></tr>
 *   <tr><td>{@code newState}</td><td>StatefulAgent</td><td>the state under selection; every rung</td></tr>
 *   <tr><td>{@code newGUITree}</td><td>StatefulAgent</td><td>passed to the LLM hooks and nowhere else in the ladder — stays null, see finding 2.1-a</td></tr>
 *   <tr><td>{@code timestamp}</td><td>ApeAgent</td><td>{@code getTimestamp()}, form-completion probes</td></tr>
 *   <tr><td>{@code model}</td><td>StatefulAgent</td><td>{@code getGraph()} — the global-action scan, the launcher's visited set, path search</td></tr>
 *   <tr><td>{@code _mopData}</td><td>StatefulAgent</td><td>preset axis; launcher and component-trigger gates</td></tr>
 *   <tr><td>{@code _llmRouter}</td><td>StatefulAgent</td><td>preset axis; the three LLM hooks</td></tr>
 *   <tr><td>{@code _coverageTracker}</td><td>StatefulAgent</td><td>{@code computeDynamicEpsilon()} ({@code Config.dynamicEpsilon} defaults true)</td></tr>
 *   <tr><td>{@code _budgetTracker}</td><td>StatefulAgent</td><td>the first block dereferences it unconditionally — {@code Config.activityBudgetEnabled} defaults <b>true</b>, so null is not the "budget off" wiring, it is a crash</td></tr>
 *   <tr><td>{@code scoringContext}, {@code scoringPipeline}</td><td>StatefulAgent</td><td>not read by the ladder itself; wired because the profile mirrors the constructor</td></tr>
 *   <tr><td>{@code actionBuffer}</td><td>StatefulAgent</td><td>{@code actionBufferSize()} in all three LLM preconditions, {@code selectNewActionFromBuffer()}</td></tr>
 *   <tr><td>{@code _actionHistory}</td><td>StatefulAgent</td><td>argument of {@code selectAction}</td></tr>
 *   <tr><td>{@code actionDiffer}</td><td>SataAgent</td><td>{@code getGreedyActions} → the EARLY_STAGE rungs</td></tr>
 *   <tr><td>{@code backMenuPicks}</td><td>SataAgent</td><td>the back/menu pick cap in EPSILON_GREEDY and the capped filter</td></tr>
 *   <tr><td>{@code mopTargetPicks}</td><td>SataAgent</td><td>{@code pickCappedMopTarget}, reached from EARLY_STAGE <i>and</i> EPSILON_GREEDY</td></tr>
 *   <tr><td>{@code actionCounters} (int[])</td><td>SataAgent</td><td>{@code logEvent} on every selected rung; {@code printCounters()} in the logging preamble</td></tr>
 *   <tr><td>{@code epsilon}</td><td>SataAgent</td><td>{@code computeDynamicEpsilon()} floor; set to {@code Config.defaultEpsilon}, the value the production constructor passes</td></tr>
 *   <tr><td>{@code backToActivity}</td><td>SataAgent</td><td>null keeps {@code selectNewActionBackToActivity()} out of {@code AndroidDevice} (see the boundary below)</td></tr>
 *   <tr><td>{@code _isNewState}, {@code graphStableCounter}, {@code stagnationHookFired}</td><td>StatefulAgent</td><td>the LLM hooks' agent-side arguments and episode state; the driver rewrites them per step</td></tr>
 *   <tr><td>{@code _stepsSinceLauncherFiring}</td><td>SataAgent</td><td>the launcher's cadence counter; <b>seeded once here</b> from the scenario and never touched again (design D2, INV-ORA-05)</td></tr>
 * </table>
 *
 * <p>Two of those are <b>declared scenario inputs rather than defaults</b> (design D2), because the
 * production values are the product of dozens of steps and a golden that had to spend them would be
 * too long to review: {@code _stepsSinceLauncherFiring}, and the registration plus iteration count
 * inside {@code _budgetTracker}. Both are seeded at construction; neither is advanced per step. The
 * distinction matters for the second one especially — {@code ActivityBudgetTracker.isBudgetExhausted}
 * answers false for an <i>unregistered</i> activity, so an untouched tracker is not "budget off", it
 * is a ladder whose first block is never entered at all.
 *
 * <p><b>Deliberately left null, and why that is safe.</b>
 * <ul>
 *   <li>{@code ape} — see above; the whole reason the RNG seam of task 1.6 exists.</li>
 *   <li>{@code _broadcastCatalog} — the constructor builds it from
 *       {@code SystemBroadcastCatalog.load()}, which reads through {@code android.util.JsonReader}
 *       and logs through {@code android.util.Log}: neither loads on the JVM. Its only reader is
 *       {@code dispatchTrigger}, which is device-bound regardless (see the boundary below).</li>
 *   <li>{@code actionCounters} (the {@code ActionCounters} of {@code StatefulAgent}, distinct from
 *       {@code SataAgent}'s {@code int[]} of the same name) — read only by the teardown dump.
 *       Hierarchy-walking injection finds {@code SataAgent}'s field first, which is the one the
 *       ladder uses.</li>
 *   <li>{@code widgetDiffer}, {@code validatedActionFilter},
 *       {@code refreshStatesCheckingBlacklist}, and the four class-level {@code SubsequenceFilter}
 *       anonymous fields of {@code SataAgent} ({@code unsaturatedActionsFilter},
 *       {@code backtrackSubsequenceFilter}, {@code greedySubsequenceFilter},
 *       {@code weakActionSubsequenceFilter}) — not reached by any rung the scenarios exercise. If
 *       a future scenario reaches one, reconstruct the <i>real</i> anonymous class
 *       ({@code SataAgent$1}… via {@code getDeclaredConstructor(SataAgent.class)}) and never a
 *       test-written substitute: a substitute would change the system under test.</li>
 * </ul>
 *
 * <h2>Preconditions the script must declare (finding 1.2-b)</h2>
 * The oracle enters below {@code adjustActionsByGUITree()} ({@code StatefulAgent.java:1475-1478}),
 * so nothing has assigned action validity or priority. Both are preconditions of the ladder, not
 * incidental: {@code ENABLED_VALID} excludes {@code valid=false} (the {@code Action} default), and
 * {@code State.countActionPriority} throws {@code IllegalStateException} on any included action
 * whose priority is &le; 0. {@link #buildState} therefore declares both from the screen. That is
 * also exactly what keeps the goldens independent of every scoring weight — the boundary the spec
 * draws, and the reason no capture test can guard a weight.
 *
 * <h2>Which rungs execute (design D6)</h2>
 * <ul>
 *   <li>{@code selectNewActionFromBuffer} — safe (an empty buffer returns null;
 *       {@code enableXPathAction} defaults false).</li>
 *   <li>{@code selectNewActionBackToActivity} — safe <b>only</b> while {@code backToActivity} is
 *       null; that guard returns at {@code SataAgent.java:1267-1269}, before
 *       {@code AndroidDevice.getFocusedStack()}. The field is therefore a scenario-level control,
 *       and a scenario that sets it leaves the capture boundary.</li>
 *   <li>{@code selectNewActionEarlyStageForward} — safe, and it is what a fresh state actually
 *       selects: a state with unvisited targeted actions returns a {@code ROULETTE_EARLY} pick,
 *       whose draw comes from {@code RandomHelper}, not the agent stream.</li>
 *   <li>{@code selectNewActionForTrivialActivity} — safe; a graph with fewer activity nodes than
 *       {@code trivialActivityRankThreshold} returns an empty set.</li>
 *   <li>{@code selectNewActionEarlyStageBackwardGreedy} — safe on a graph with no edges.</li>
 *   <li>{@code selectNewActionEpsilonGreedyRandomly} — safe since the task-1.6 seam; both legs
 *       (the least-visited pick and the priority roulette) draw from the overridden
 *       {@code getRandom()}.</li>
 *   <li>{@code handleNullAction} — <b>entered, but not survivable</b> (finding 5.2-a, correcting the
 *       task-1 reading). Its first expression draws from the overridable {@code getRandom()}, which
 *       is what the spike observed, but the same expression passes {@code validatedActionFilter} —
 *       null here, and even reconstructed it calls {@code validateNewAction}
 *       ({@code StatefulAgent.java:1454-1466}), which dereferences the null {@code ape}. A state
 *       with nothing selectable therefore raises a {@code NullPointerException} from the filter, not
 *       the {@code BadStateException} the rung is written to raise. <b>Consequence</b>: no scenario
 *       can capture that exception, so the spec's {@code BadStateException} error case is a
 *       driver-side contract (the run aborts) rather than a reachable ladder outcome; scenarios must
 *       leave an action selectable above this rung, which they were already required to do.</li>
 * </ul>
 * <p>Descending past EARLY_STAGE requires <i>saturated</i>, not merely visited, actions:
 * {@code isSaturated()} is visit-based for targetless actions but
 * {@code resolvedSaturation >= 1.0F} for targeted ones ({@code ModelAction.java:154-159}).
 *
 * <h2>Group-2 findings</h2>
 * <ul>
 *   <li><b>2.1-a — there is no GUITree builder, and none is needed.</b> Design D2 and task 2.1
 *       name one, but within the ladder {@code newGUITree} has exactly one reader: it is passed to
 *       {@code _llmRouter.selectAction}, which the scripted router overrides (task 3.1). Building a
 *       real one is also not possible off device — a {@code GUITree} is assembled from
 *       {@code AccessibilityNodeInfo}. So the field stays null, and the cost is stated rather than
 *       hidden: nothing in the goldens exercises tree-derived behavior, which is already a
 *       Non-Goal of this change.</li>
 *   <li><b>2.1-b — synthetic states are registered into the graph by mirroring
 *       {@code Graph.getOrCreateState}, not by calling it.</b> That production path builds its
 *       {@code State} through {@code new State(stateKey)}, whose {@code buildActions} calls
 *       {@code NamerFactory.decodeActions}, which throws {@code IllegalArgumentException} on any
 *       {@code Name} that is not an {@code ActionPatchName} — and the harness's names are local
 *       xpath-identity stubs, the established {@code StateTest}/{@code LlmRouterDeadPairTest}
 *       idiom. {@link #registerInGraph} therefore performs the same registration steps
 *       ({@code keyToState}/{@code idToState}, {@code addActivity}, {@code addActions}) directly on
 *       the graph's fields. Registration is load-bearing, not cosmetic: without an
 *       {@code ActivityNode}, {@code Graph.markVisited(State,int)} NPEs, and without the action
 *       inventory, {@code Graph.markVisited(ModelAction,int)} throws its sanity check — both of
 *       which the driver's bookkeeping calls (design D7).</li>
 *   <li><b>2.1-c — the component trigger is outside the capture boundary</b>, by owner decision
 *       of 2026-08-03. It never fires: {@code ape.componentPercentage} is set by no arm of the
 *       phase-2 grid — the key exists only in {@code aperv-tool}'s mapping
 *       ({@code tool.py:101}) and is absent from the 18 {@code ARM_DEFINING_KEYS} and both
 *       arm-flag dicts — so every arm runs on the jar default 0.0
 *       ({@code Config.java:256}), which makes the block's first conjunct false in production and
 *       here alike. Even with the gate forced open the committed fixture yields zero tuples (no
 *       receivers, no services, and its one provider has {@code reachesTarget=false}, filtered at
 *       {@code StatefulAgent.java:1246}), so {@code triggerMopComponent()} returns at
 *       {@code :1266} leaving {@code componentTriggerIndex} untouched; and had a tuple existed,
 *       dispatch is device-bound ({@code android.content.Intent} in {@code dispatchTrigger},
 *       {@code AndroidDevice.executeCommandAndWaitFor} in {@code dispatchProvider}, whose wrapper
 *       catches {@code Exception} and not the {@code NoClassDefFoundError} actually thrown). The
 *       exclusion costs no parity: the block returns nothing, and its short-circuiting
 *       conjunction consumes no RNG draw, so it can shift neither a decision nor the agent
 *       stream. Should a future arm enable it, this must be revisited before that arm's
 *       comparability is claimed.</li>
 * </ul>
 *
 * <h2>Group-6 findings</h2>
 * <ul>
 *   <li><b>6.1-a — a MOP-attributed step needs a declared boost, and the state that carries it is a
 *       documented stand-in.</b> {@code selectUnvisitedMopTarget} ranks by
 *       {@code ModelAction.getMopBoost()} ({@code SataAgent.java:727-732}) and
 *       {@code attributeByLargestBoost} ({@code :293}) reads the same fields to decide the source.
 *       Those fields are written in exactly one place ({@code MopWidgetPass}, inside
 *       {@code adjustActionsByGUITree()}) and cleared in exactly one other
 *       ({@code ScoringPipeline}'s {@code resetBoosts()}) — both <i>above</i> this oracle's entry
 *       point, so a boost declared here persists for the whole run and an undeclared one is 0,
 *       which makes the short-circuit a no-op. {@link ScenarioScript.Widget#getMopBoost()} declares
 *       it and {@link #buildState} writes it through the public {@code ModelAction.setMopBoost}, on
 *       the precedent of finding 1.2-b. <b>The honest half</b>: reaching the short-circuit also
 *       needs the action declared <i>unvisited and saturated at once</i>, and per action that pair
 *       does not occur in production — {@code ModelAction.resolveAt} ({@code :243-246}) derives
 *       {@code resolvedSaturation} from {@code visitedCount}. In production the pair arises one
 *       level up, in the differ: {@code StateActionDiffer.getUnsaturated(from,to)} ({@code :74})
 *       drops a matched pair when <i>either</i> side is saturated, so a NamingFactory-refined
 *       sibling offers an action unvisited in {@code to} whose counterpart in {@code from} is
 *       saturated — verbatim what the production comment at {@code SataAgent.java:641-643} calls
 *       re-arming the short-circuit. The harness has no {@code currentState} and no refinement, so
 *       it declares the pair on the action itself, as a stand-in for that path.</li>
 *   <li><b>6.1-b — the budget block's trivial-action return is outside the boundary; its gate is
 *       not.</b> With the exhaustion declared the block is entered, and what it calls next —
 *       {@code selectNewActionForTrivialActivity()} — needs more than
 *       {@code Config.trivialActivityRankThreshold} = 3 activity nodes and then searches a path
 *       over graph <b>edges</b>, of which this harness has none by design ({@link OracleDriver}'s
 *       ledger). {@code ape.doBackToTrivialActivity} is false by default, so the fallback never
 *       reaches {@code AndroidDevice.getFocusedStack()} either: the call simply returns null and the
 *       block falls through. Recording edges to open that path would widen the capture boundary
 *       rather than fill a gap in it — every rung that reads edges would see a different world, and
 *       {@code refillBuffer} would start feeding {@code actionBufferSize()}, which all three LLM
 *       preconditions read. So the gate is pinned and the return is documented as uncaptured.</li>
 * </ul>
 *
 * <h2>Jar-default {@code Config} values the ladder reads</h2>
 * {@code activityBudgetEnabled=true}, {@code activityTriggerEnabled=true},
 * {@code activityTriggerStagnationStep=50}, {@code activityTriggerMaxPerRun=0},
 * {@code componentPercentage=0.0}, {@code dynamicEpsilon=true}, {@code modelMenuEnabled=true},
 * {@code leastVisitedPriorityTiebreak=true}, {@code backMenuPickCap=3}. Each preset test
 * guard-asserts the ones it depends on (design D2). Scoring weights are deliberately <b>not</b>
 * among them: they are consumed above this entry point, so no golden can depend on one, and their
 * guard belongs to {@code rearch-03} INV-ARCH-12.
 */
public final class OracleScaffold {

    /** The MOP fixture the {@code mop} and {@code llm_mop} presets load, via {@code MopData.load}. */
    static final String MOP_FIXTURE = "cryptoapp.apk.gh60-fresh.json";

    private static final String MOP_FIXTURE_PACKAGE = "br.unb.cic.cryptoapp";
    private static final String MOP_FIXTURE_MAIN_ACTIVITY = "br.unb.cic.cryptoapp.MainActivity";

    /** The graph id prefix {@code Graph} assigns to its own states and actions. */
    private static final String GRAPH_ID = "g0";

    private OracleScaffold() {
    }

    /**
     * The four target presets. A preset is realized by the injection profile, not by Config: the
     * presence or absence of {@code _mopData} and {@code _llmRouter} are its only axes (design D2),
     * mirroring what {@code StatefulAgent}'s constructor would have decided from
     * {@code Config.mopDataPath} and {@code Config.llmUrl}.
     */
    public enum Preset {
        APERV(false, false),
        MOP(true, false),
        LLM(false, true),
        LLM_MOP(true, true);

        private final boolean mopData;
        private final boolean llmRouter;

        Preset(boolean mopData, boolean llmRouter) {
            this.mopData = mopData;
            this.llmRouter = llmRouter;
        }

        public boolean hasMopData() { return mopData; }
        public boolean hasLlmRouter() { return llmRouter; }
    }

    // ---- reflection ------------------------------------------------------------------------

    /** Allocates without running any constructor — the {@code PipelineParityTest:56-116} idiom. */
    @SuppressWarnings("unchecked")
    static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    /** Sets a field declared anywhere in {@code obj}'s class hierarchy, subclass first. */
    static void setField(Object obj, String name, Object value) throws Exception {
        declaredField(obj.getClass(), name).set(obj, value);
    }

    static Object getField(Object obj, String name) throws Exception {
        return declaredField(obj.getClass(), name).get(obj);
    }

    private static Field declaredField(Class<?> from, String name) throws NoSuchFieldException {
        Class<?> c = from;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " in " + from.getName());
    }

    // ---- synthetic fixtures ----------------------------------------------------------------

    /**
     * A {@link Name} whose xpath is its identity — the {@code StateTest}/
     * {@code LlmRouterDeadPairTest} idiom. It carries no {@code Namer}, which is what puts the
     * production {@code State} constructor out of reach (finding 2.1-b).
     */
    static Name testName(final String xpath) {
        return new Name() {
            @Override public Namer getNamer() { return null; }
            @Override public Name getLocalName() { return this; }
            @Override public boolean refinesTo(Name other) { return false; }
            @Override public String toXPath() { return xpath; }
            @Override public void appendXPathLocalProperties(StringBuilder sb) { sb.append(xpath); }
            @Override public void toXPath(StringBuilder sb) { sb.append(xpath); }
            @Override public int compareTo(Name o) { return xpath.compareTo(o.toXPath()); }
            @Override public String toString() { return xpath; }
        };
    }

    /**
     * Materializes a screen into a {@code State}: one {@code MODEL_CLICK} per declared widget,
     * plus {@code MODEL_BACK} and — when {@code Config.modelMenuEnabled} — {@code MODEL_MENU},
     * which is the action set {@code State}'s own constructor would produce.
     */
    static State buildState(ScenarioScript.Screen screen) throws Exception {
        List<ScenarioScript.Widget> widgets = screen.getWidgets();
        Name[] names = new Name[widgets.size()];
        for (int i = 0; i < widgets.size(); i++) {
            names[i] = testName(widgets.get(i).getXPath());
        }

        StateKey stateKey = allocate(StateKey.class);
        setField(stateKey, "activity", screen.getActivity());
        setField(stateKey, "widgets", names);

        State state = allocate(State.class);
        setField(state, "stateKey", stateKey);

        List<ModelAction> actions = new ArrayList<>();
        for (int i = 0; i < widgets.size(); i++) {
            ScenarioScript.Widget widget = widgets.get(i);
            ModelAction click = new ModelAction(state, names[i], ActionType.MODEL_CLICK);
            click.setValid(true);
            click.setPriority(widget.getPriority());
            click.setMopBoost(widget.getMopBoost()); // finding 6.1-a
            setField(click, "resolvedSaturation", widget.getSaturation());
            if (widget.isVisited()) {
                markVisited(click);
            }
            actions.add(click);
        }

        ModelAction back = new ModelAction(state, ActionType.MODEL_BACK);
        ModelAction menu = new ModelAction(state, ActionType.MODEL_MENU);
        for (ModelAction nav : new ModelAction[]{back, menu}) {
            nav.setValid(true);
            nav.setPriority(screen.getNavPriority());
            if (screen.isNavVisited()) {
                markVisited(nav);
            }
        }
        actions.add(back);
        if (Config.modelMenuEnabled) {
            actions.add(menu);
        }

        setField(state, "backAction", back);
        setField(state, "menuAction", menu);
        setField(state, "actions", actions.toArray(new ModelAction[0]));
        return state;
    }

    /** Records a visit the way {@code GraphElement.visitedAt} does on a first visit. */
    private static void markVisited(ModelAction action) throws Exception {
        setField(action, "firstVisitTimestamp", 1);
        setField(action, "lastVisitTimestamp", 1);
        declaredField(action.getClass(), "visitedCount").setInt(action, 1);
    }

    /**
     * Registers a synthetic state the way {@code Graph.getOrCreateState} registers a real one —
     * see finding 2.1-b for why the production entry point is unreachable from here. Pre-visited
     * actions go straight into the visited inventory, which is where a real run would have moved
     * them, so {@code Graph.markVisited} finds them and its sanity checks pass.
     */
    @SuppressWarnings("unchecked")
    static void registerInGraph(Graph graph, State state) throws Exception {
        Map<StateKey, State> keyToState = (Map<StateKey, State>) getField(graph, "keyToState");
        if (keyToState.containsKey(state.getStateKey())) {
            return;
        }
        int stateCounter = (Integer) getField(graph, "stateCounter");
        setField(state, "id", GRAPH_ID + "s" + stateCounter);
        setField(graph, "stateCounter", stateCounter + 1);
        keyToState.put(state.getStateKey(), state);
        ((Map<String, State>) getField(graph, "idToState")).put(state.getGraphId(), state);

        Map<String, ActivityNode> activities =
                (Map<String, ActivityNode>) getField(graph, "activities");
        ActivityNode node = activities.get(state.getActivity());
        if (node == null) {
            node = new ActivityNode(state.getActivity());
            activities.put(state.getActivity(), node);
        }
        node.addState(state);

        Map<String, Map<Name, Set<ModelAction>>> nameToActions =
                (Map<String, Map<Name, Set<ModelAction>>>) getField(graph, "nameToActions");
        Map<Name, Set<ModelAction>> byName =
                Utils.getMapFromMap(nameToActions, state.getActivity());
        Set<ModelAction> unvisited = (Set<ModelAction>) getField(graph, "unvisitedActions");
        Set<ModelAction> visited = (Set<ModelAction>) getField(graph, "visitedActions");
        int actionCounter = unvisited.size() + visited.size();
        for (ModelAction action : state.getActions()) {
            setField(action, "id", GRAPH_ID + "a" + actionCounter++);
            (action.isVisited() ? visited : unvisited).add(action);
            if (action.requireTarget()) {
                Utils.addToMapSet(byName, action.getTarget(), action);
            }
        }
    }

    // ---- preset wiring ---------------------------------------------------------------------

    /** Loads the committed MOP fixture through the production path, as {@code MopDataTest} does. */
    static MopData loadMopFixture() {
        URL url = OracleScaffold.class.getResource("/" + MOP_FIXTURE);
        if (url == null) {
            throw new IllegalStateException("MOP fixture not on the test classpath: " + MOP_FIXTURE);
        }
        MopData data = MopData.load(new File(url.getFile()).getAbsolutePath(),
                MOP_FIXTURE_PACKAGE, MOP_FIXTURE_MAIN_ACTIVITY);
        if (data == null) {
            throw new IllegalStateException("MopData.load returned null for " + MOP_FIXTURE);
        }
        return data;
    }

    /**
     * Builds the agent for a preset, wired over the scenario's entry screen and with both RNG
     * streams seeded from the scenario's declared seed.
     *
     * @param router the scripted router for the LLM presets; null for the others. The preset's
     *               declared axis and the argument must agree — a mismatch is an authoring bug and
     *               fails here rather than producing a golden for the wrong preset.
     */
    static OracleSataAgent newAgent(Preset preset, ScenarioScript script, LlmRouter router)
            throws Exception {
        if (preset.hasLlmRouter() != (router != null)) {
            throw new IllegalArgumentException("preset " + preset + " requires an LLM router "
                    + (preset.hasLlmRouter() ? "but none was supplied" : "but one was supplied"));
        }

        OracleSataAgent agent = allocate(OracleSataAgent.class);
        Graph graph = new Graph();
        Model model = new Model(graph);
        UICoverageTracker coverageTracker = new UICoverageTracker();
        MopData mopData = preset.hasMopData() ? loadMopFixture() : null;
        State entryState = buildState(script.getEntryScreen());
        registerInGraph(graph, entryState);

        // --- identity and clock
        setField(agent, "timestamp", 1);
        setField(agent, "model", model);
        setField(agent, "newState", entryState);
        setField(agent, "newGUITree", null); // finding 2.1-a

        // --- the collaborators the constructor builds (StatefulAgent.java:179-208)
        setField(agent, "_mopData", mopData);
        setField(agent, "_coverageTracker", coverageTracker);
        // Config.activityBudgetEnabled defaults true, so the constructor builds a real tracker and
        // the ladder's first block dereferences it unconditionally (SataAgent.java:468).
        setField(agent, "_budgetTracker", budgetTracker(script));
        setField(agent, "_llmRouter", router);
        final MopData contextMopData = mopData;
        ScoringContext scoringContext = new ScoringContext() {
            @Override public MopData getMopData() { return contextMopData; }
            @Override public UICoverageTracker getCoverageTracker() { return coverageTracker; }
            @Override public Graph getGraph() { return graph; }
            @Override public int getTimestamp() { return agent.getTimestamp(); }
            @Override public boolean menuPickEligible(String activity) { return true; }
        };
        setField(agent, "scoringContext", scoringContext);
        setField(agent, "scoringPipeline", ScoringPipeline.fromConfig(null, scoringContext));

        // --- fields with inline initializers, which Unsafe.allocateInstance also skipped
        setField(agent, "actionBuffer", new LinkedList<>());
        setField(agent, "_actionHistory", new ArrayList<>());
        setField(agent, "backMenuPicks", new HashMap<String, Integer>());
        setField(agent, "mopTargetPicks", new HashMap<String, Integer>());
        setField(agent, "actionCounters", new int[sataEventTypeCount()]);
        setField(agent, "actionDiffer", new StateActionDiffer());

        // --- episode state; the driver rewrites the first three per step
        setField(agent, "_isNewState", false);
        setField(agent, "graphStableCounter", 0);
        setField(agent, "stagnationHookFired", false);
        setField(agent, "backToActivity", null);
        setField(agent, "epsilon", Config.defaultEpsilon);
        // The launcher's cadence counter is seeded once, here, and never touched again: the ladder
        // owns every later write (SataAgent.java:522,529) and the driver is forbidden from touching
        // it at all (INV-ORA-05). See ScenarioScript.getStepsSinceLauncherFiring().
        setField(agent, "_stepsSinceLauncherFiring", script.getStepsSinceLauncherFiring());

        RandomHelper.seed(script.getSeed());
        agent.pinned = new Random(script.getSeed());
        return agent;
    }

    /**
     * The budget tracker the constructor would have built, advanced to the state the production
     * loop would have reached for every activity the scenario declares exhausted (design D2).
     *
     * <p>Registration and the iteration count are replayed here rather than per step because both
     * production sites sit above the oracle's entry point — {@code registerActivity} in
     * {@code updateStateInternal} ({@code StatefulAgent.java:765}), {@code recordIteration} in
     * {@code moveForward} ({@code :1410}) — and because exhaustion takes
     * {@code activityBaseBudget + widgets * activityBudgetPerWidget} iterations, far more than a
     * readable scenario has steps. Iterating until {@code isBudgetExhausted} answers true reaches
     * exactly the count that production would have, rather than a number written down here.
     */
    private static ActivityBudgetTracker budgetTracker(ScenarioScript script) {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(
                Config.activityBaseBudget, Config.activityBudgetPerWidget);
        for (String activity : script.getExhaustedActivities()) {
            tracker.registerActivity(activity, targetedActionCount(script, activity));
            while (!tracker.isBudgetExhausted(activity)) {
                tracker.recordIteration(activity);
            }
        }
        return tracker;
    }

    /**
     * The widget count production would have registered for {@code activity}: the number of
     * target-carrying actions of the first screen declared on it, which is the screen whose first
     * visit would have registered the activity ({@code registerActivity} is idempotent, so later
     * states never revise the budget).
     */
    private static int targetedActionCount(ScenarioScript script, String activity) {
        for (ScenarioScript.Screen screen : script.getScreens()) {
            if (screen.getActivity().equals(activity)) {
                return screen.getWidgets().size();
            }
        }
        throw new IllegalArgumentException("no screen runs on " + activity);
    }

    /**
     * The length {@code SataAgent}'s constructor gives {@code actionCounters}. Read from the enum
     * rather than hard-coded because {@code logEvent} indexes it by ordinal: a rung added upstream
     * must widen the array here, and reading the real length makes that automatic instead of an
     * out-of-bounds surprise. The enum is package-private to {@code ape.agent}, hence the lookup
     * by name; it declares no {@code android.*} signature, so reflecting on it is safe.
     */
    private static int sataEventTypeCount() throws Exception {
        return Class.forName(SataAgent.class.getName() + "$SataEventType").getEnumConstants().length;
    }
}

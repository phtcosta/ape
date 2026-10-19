package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.utils.StaticGuiModel;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.agent.MopInteractionLedger;
import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.agent.pipeline.DecisionPipeline;
import com.android.commands.monkey.ape.agent.pipeline.BreakerGate;
import com.android.commands.monkey.ape.agent.pipeline.DecisionStage;
import com.android.commands.monkey.ape.agent.pipeline.LlmCall;
import com.android.commands.monkey.ape.agent.pipeline.LlmGate;
import com.android.commands.monkey.ape.agent.pipeline.StepContext;
import com.android.commands.monkey.ape.agent.pipeline.StepRouting;
import com.android.commands.monkey.ape.agent.scoring.ScoringContext;
import com.android.commands.monkey.ape.agent.scoring.ScoringParams;
import com.android.commands.monkey.ape.agent.scoring.ScoringPipeline;
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
import com.android.commands.monkey.ape.runtime.Feature;
import com.android.commands.monkey.ape.runtime.Presets;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.RandomHelper;
import com.android.commands.monkey.ape.utils.StaticArtifactLoader;
import com.android.commands.monkey.ape.utils.UICoverageTracker;
import com.android.commands.monkey.ape.utils.Utils;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
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
 *   <tr><td>{@code _mopContext}</td><td>StatefulAgent</td><td>preset axis; launcher and component-trigger gates</td></tr>
 * *   <tr><td>{@code _coverageTracker}</td><td>StatefulAgent</td><td>{@code computeDynamicEpsilon()} ({@code DYNAMIC_EPSILON}, an ADDONS member)</td></tr>
 *   <tr><td>{@code _budgetTracker}</td><td>StatefulAgent</td><td>the BUDGET stage; built iff {@code ACTIVITY_BUDGET} is mounted (an ADDONS member), as the constructor builds it, and null otherwise, when no BUDGET stage is assembled</td></tr>
 *   <tr><td>{@code scoringContext}, {@code scoringPipeline}</td><td>StatefulAgent</td><td>not read by the ladder itself; wired because the profile mirrors the constructor</td></tr>
 *   <tr><td>{@code actionBuffer}</td><td>StatefulAgent</td><td>{@code actionBufferSize()} in all three LLM preconditions, {@code selectNewActionFromBuffer()}</td></tr>
 *   <tr><td>{@code _actionHistory}</td><td>StatefulAgent</td><td>argument of {@code selectAction}</td></tr>
 *   <tr><td>{@code actionDiffer}</td><td>SataAgent</td><td>{@code getGreedyActions} → the EARLY_STAGE rungs</td></tr>
 *   <tr><td>{@code backMenuPicks}</td><td>SataAgent</td><td>the back/menu pick cap in EPSILON_GREEDY and the capped filter</td></tr>
 *   <tr><td>{@code actionCounters} (int[])</td><td>SataAgent</td><td>{@code logEvent} on every selected rung; {@code printCounters()} in the logging preamble</td></tr>
 *   <tr><td>{@code epsilon}</td><td>SataAgent</td><td>{@code computeDynamicEpsilon()} floor; set to the plan's {@code ape.defaultEpsilon}, the value the production constructor passes</td></tr>
 *   <tr><td>{@code backToActivity}</td><td>SataAgent</td><td>null keeps {@code selectNewActionBackToActivity()} out of {@code AndroidDevice} (see the boundary below)</td></tr>
 *   <tr><td>{@code _isNewState}, {@code graphStableCounter}</td><td>StatefulAgent</td><td>the LLM stages' agent-side arguments; the driver rewrites them per step</td></tr>
 *   <tr><td>{@code decisionPipeline}</td><td>SataAgent</td><td>the run's assembled policy, built by {@code DecisionPipeline.fromSpec} from the preset's plan; the stages own their own episode state, which starts armed</td></tr>
 *   <tr><td>{@code stepsSinceFiring}</td><td>MopLauncherStage</td><td>the launcher's cadence counter; <b>seeded once here</b> from the scenario, on the stage that owns it, and never touched again (design D2, INV-ORA-05)</td></tr>
 * </table>
 *
 * <p>Two of those are <b>declared scenario inputs rather than defaults</b> (design D2), because the
 * production values are the product of dozens of steps and a golden that had to spend them would be
 * too long to review: the launcher's {@code stepsSinceFiring}, and the registration plus iteration count
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
 *       {@code LlmEngine.selectAction}, which the scripted engine overrides (task 3.1). Building a
 *       real one is also not possible off device — a {@code GUITree} is assembled from
 *       {@code AccessibilityNodeInfo}. So the field stays null, and the cost is stated rather than
 *       hidden: nothing in the goldens exercises tree-derived behavior, which is already a
 *       Non-Goal of this change.</li>
 *   <li><b>2.1-b — synthetic states are registered into the graph by mirroring
 *       {@code Graph.getOrCreateState}, not by calling it.</b> That production path builds its
 *       {@code State} through {@code new State(stateKey)}, whose {@code buildActions} calls
 *       {@code NamerFactory.decodeActions}, which throws {@code IllegalArgumentException} on any
 *       {@code Name} that is not an {@code ActionPatchName} — and the harness's names are local
 *       xpath-identity stubs, the established {@code StateTest}/{@code CoordinateMapperDeadPairTest}
 *       idiom. {@link #registerInGraph} therefore performs the same registration steps
 *       ({@code keyToState}/{@code idToState}, {@code addActivity}, {@code addActions}) directly on
 *       the graph's fields. Registration is load-bearing, not cosmetic: without an
 *       {@code ActivityNode}, {@code Graph.markVisited(State,int)} NPEs, and without the action
 *       inventory, {@code Graph.markVisited(ModelAction,int)} throws its sanity check — both of
 *       which the driver's bookkeeping calls (design D7).</li>
 *   <li><b>2.1-c — the component trigger is inside the capture boundary as a recorded no-op.</b>
 *       The STATIC block mounts it at {@code ape.componentPercentage=0.03}, so it is in the roster
 *       of the four {@code as*} cells. Its coin is {@code u_C} from {@code StepRng}'s component
 *       stream, so it shifts no agent, helper or other coin draw. Its dispatch is device-bound
 *       ({@code android.content.Intent}, {@code AndroidDevice.executeCommandAndWaitFor}), so
 *       {@link OracleSataAgent#triggerComponent} dispatches nothing and records the target the
 *       cursor selected, which the golden carries as {@code trig} (design D-B14.5). The committed
 *       fixture's pool is its one provider's three operations.</li>
 * </ul>
 *
 * <h2>Group-6 findings</h2>
 * <ul>
 *   <li><b>6.1-a — a MOP-coin step needs a declared weight.</b> {@code MopCoinStage} is eligible
 *       only on an action with a positive undivided {@code ModelAction.getMopWeight()} that has no
 *       first interaction in the ledger. The weight is written in exactly one place
 *       ({@code MopWidgetPass}, inside {@code adjustActionsByGUITree()}) — <i>above</i> this oracle's
 *       entry point, so a weight declared here persists for the whole run and an undeclared one is
 *       0. {@link ScenarioScript.Widget#getMopWeight()} declares it and {@link #buildState} writes
 *       it through the public {@code ModelAction.setMopWeight}, on the precedent of finding 1.2-b.
 *       The ledger is written in {@code moveForward}, which the driver does not run, so a declared
 *       weight stays eligible on every step of the scenario.</li>
 *   <li><b>6.1-b — the budget block's trivial-action return is outside the boundary; its gate is
 *       not.</b> With the exhaustion declared the block is entered, and what it calls next —
 *       {@code selectNewActionForTrivialActivity()} — needs more than
 *       the plan's {@code ape.trivialActivityRankThreshold} = 3 activity nodes and then searches a path
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
 * Every golden silently depends on these, so the list is {@link LadderConfigGuard}'s assertions and
 * nothing else — the guard is what fails with the key named when one moves, and a list here that
 * were shorter than it would understate what the goldens rest on. Read in every preset:
 * {@code activityBudgetEnabled=true}, {@code activityBaseBudget=50},
 * {@code activityBudgetPerWidget=5}, {@code trivialActivityRankThreshold=3},
 * {@code doBackToTrivialActivity=false}, {@code componentPercentage=0.0},
 * {@code modelMenuEnabled=true}, {@code useActionDiffer=true},
 * {@code leastVisitedPriorityTiebreak=true}, {@code backMenuPickCap=3},
 * {@code dynamicEpsilon=true}, {@code defaultEpsilon=0.05}, {@code minEpsilon=0.02},
 * {@code maxEpsilon=0.15}. Read only where {@code _mopContext} is present:
 * {@code activityTriggerEnabled=true}, {@code activityTriggerStagnationStep=50},
 * {@code activityTriggerMaxPerRun=0}. Each preset test guard-asserts
 * the half it depends on (design D2). Scoring weights are deliberately <b>not</b> among them: they
 * are consumed above this entry point, so no golden can depend on one, and their guard belongs to
 * {@code rearch-03} INV-ARCH-12. {@code llmPercentage} and
 * {@code graphStableRestartThreshold} are likewise absent — an LLM preset states both in its plan
 * (see {@link #newAgent}) rather than inheriting the jar's, so no LLM golden reads either default.
 */
public final class OracleScaffold {

    /**
     * The artifact fixture every cell with STATIC or MOP loads, via {@code StaticArtifactLoader.load}.
     *
     * <p>It is the derived artifact, not the static-analysis document it came from: after the
     * cutover the loader reads only the compact format, so pointing the presets at the source would
     * make every MOP golden run with a null {@code MopData} and quietly become an APERV golden.
     * The projections are the same either way — that equality is what the cutover gate established
     * before it was deleted — so the goldens do not move.
     */
    static final String MOP_FIXTURE = "cryptoapp.apk.mop.json";

    private static final String MOP_FIXTURE_PACKAGE = "br.unb.cic.cryptoapp";
    private static final String MOP_FIXTURE_MAIN_ACTIVITY = "br.unb.cic.cryptoapp.MainActivity";

    /** The graph id prefix {@code Graph} assigns to its own states and actions. */
    private static final String GRAPH_ID = "g0";

    private OracleScaffold() {
    }

    /**
     * The six cells (guidance-groups). A cell is realized by its preset's resolved plan and the
     * injection profile that follows from it, as the composition root wires it: whether the
     * artifact's providers exist, which stages, gates and coins are assembled, and which X1/X3/X4
     * variants are mounted are the plan's features, exactly as in production (parity-oracle
     * "Per-Preset Golden Decision-Sequence Capture").
     */
    public enum Cell {
        BASE(Presets.BASE, false, false),
        MOP(Presets.MOP, true, false),
        AS(Presets.AS, true, false),
        AS_MOP(Presets.AS_MOP, true, false),
        AS_LLM(Presets.AS_LLM, true, true),
        AS_MOP_LLM(Presets.AS_MOP_LLM, true, true);

        private final String preset;
        private final boolean staticData;
        private final boolean llm;

        Cell(String preset, boolean staticData, boolean llm) {
            this.preset = preset;
            this.staticData = staticData;
            this.llm = llm;
        }

        /** The cell's {@code ape.preset} name, which is also its golden directory. */
        public String preset() { return preset; }
        /** Whether the cell loads the artifact: it carries STATIC or MOP. */
        public boolean hasStaticData() { return staticData; }
        public boolean hasLlm() { return llm; }
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
     * {@code CoordinateMapperDeadPairTest} idiom. It carries no {@code Namer}, which is what puts the
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
            click.setMopWeight(widget.getMopWeight()); // finding 6.1-a
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
        if (RunContext.current().spec().has(Feature.MODEL_MENU)) {
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

    /**
     * The plan a cell's agent is built over: the cell's preset plus the deployment keys and the
     * two injection-profile adaptations {@link #newAgent} explains, resolved under {@code seed} —
     * the run seed the four {@code StepRng} streams are derived from. Also what
     * {@link LadderConfigGuard} asserts the cell's ladder values against.
     *
     * @param overrides {@code ape.*} key/value pairs stated over the cell, as a variant's dose
     *        override is (guidance-groups); none for the cell itself
     */
    static RunSpec planOf(Cell cell, long seed, String... overrides) {
        List<String> keys = new ArrayList<>(Arrays.asList("ape.preset", cell.preset()));
        if (cell.hasStaticData()) {
            keys.addAll(Arrays.asList("ape.staticDataPath", TestRunSpecs.MOP_PATH));
        }
        if (cell.hasLlm()) {
            keys.addAll(Arrays.asList("ape.llmUrl", "http://127.0.0.1:30000/v1",
                    "ape.graphStableRestartThreshold", "10",
                    "ape.llmPercentage", "1.0"));
        }
        keys.addAll(Arrays.asList(overrides));
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i += 2) {
            entries.put(keys.get(i), keys.get(i + 1));
        }
        return RunSpec.resolve(entries, RunSpec.CliValues.of("sata", seed, null));
    }

    /** Loads the committed MOP fixture through the production path, as {@code MopDataTest} does. */
    static StaticArtifactLoader.Loaded loadMopFixture() {
        URL url = OracleScaffold.class.getResource("/" + MOP_FIXTURE);
        if (url == null) {
            throw new IllegalStateException("MOP fixture not on the test classpath: " + MOP_FIXTURE);
        }
        StaticArtifactLoader.Loaded data = StaticArtifactLoader.load(
                new File(url.getFile()).getAbsolutePath(),
                MOP_FIXTURE_PACKAGE, MOP_FIXTURE_MAIN_ACTIVITY, new NoopSink());
        if (data == null) {
            throw new IllegalStateException("StaticArtifactLoader.load returned null for "
                    + MOP_FIXTURE);
        }
        return data;
    }

    /**
     * Builds the agent for a cell, wired over the scenario's entry screen and with every RNG
     * stream seeded from the scenario's declared seed.
     *
     * @param llm the scripted LLM for the LLM cells; null for the others. The cell's declared
     *            axis and the argument must agree — a mismatch is an authoring bug and fails here
     *            rather than producing a golden for the wrong cell.
     */
    static OracleSataAgent newAgent(Cell cell, ScenarioScript script, ScriptedLlm llm)
            throws Exception {
        return newAgent(cell, script, llm, null);
    }

    /**
     * The same agent, observed by a sink of the caller's choosing.
     *
     * <p>The neutrality gate (R7, INV-SNK-07) is the only caller that passes one: it replays a
     * cell under one seed with {@code NdjsonSink} and then with {@code NoopSink} and asserts the
     * decisions did not move. A null means what a run gets — the context builds its own.
     *
     * @param sink the sink the run's context should hold, or null for the one it would build
     */
    static OracleSataAgent newAgent(Cell cell, ScenarioScript script, ScriptedLlm llm,
            EventSink sink) throws Exception {
        return newAgent(cell, script, llm, sink, new String[0]);
    }

    /**
     * The same agent over the cell with {@code overrides} stated (see {@link #planOf}). The RNG
     * neutrality gate is the caller: it replays one cell with a gate or a coin unmounted.
     */
    static OracleSataAgent newAgent(Cell cell, ScenarioScript script, ScriptedLlm llm,
            EventSink sink, String... overrides) throws Exception {
        if (cell.hasLlm() != (llm != null)) {
            throw new IllegalArgumentException("cell " + cell + " requires a scripted LLM "
                    + (cell.hasLlm() ? "but none was supplied" : "but one was supplied"));
        }

        // The plan the decision pipeline is assembled from, and which the ladder reads: the cell's
        // preset, plus the deployment keys a preset deliberately omits. ape.staticDataPath names a
        // file the scaffold never opens — the providers below are loaded from the committed fixture
        // through the production loader — and ape.llmUrl names a server nothing here contacts:
        // every LLM stage's engine is substituted (installScriptedLlm).
        //
        // Two LLM keys are stated away from the cell's values, and both are injection-profile
        // adaptations to the trigger predicates living in the stages (INV-ORA-07):
        //
        //   ape.graphStableRestartThreshold=10 — the stagnation stage evaluates the real midpoint
        //   predicate, and at the jar default of 100 the midpoint is 50 while every scenario's
        //   graphStableCounter is 0, 5, 6 or 7. At 10 the midpoint is 5, which is the value the
        //   scenarios were written around. The forced restart that also reads it is never reached:
        //   onGraphStable runs from checkStable() at the end of updateStateInternal, and this
        //   harness drives agent.ladder() directly.
        //
        //   ape.llmPercentage=1.0 — the coin is the seeded u_L against p_L, and at the cell's 0.9 a
        //   scripted coin consultation would be lost on one step in ten, which the script cannot
        //   foresee. At 1.0 the coin always passes and the scripted verdict decides.
        RunSpec spec = planOf(cell, script.getSeed(), overrides);
        if (sink != null) {
            RunContext.installForTest(spec, sink);
        } else {
            RunContext.installForTest(spec);
        }

        OracleSataAgent agent = allocate(OracleSataAgent.class);
        Graph graph = new Graph();
        Model model = new Model(graph);
        UICoverageTracker coverageTracker = new UICoverageTracker();
        StaticArtifactLoader.Loaded loaded = cell.hasStaticData() ? loadMopFixture() : null;
        // As the constructor: MopData reaches the agent iff MOP is mounted (design D-B14.21).
        MopData mopData = loaded == null || !spec.has(Feature.MOP) ? null : loaded.mopData;
        final StaticGuiModel staticModel = loaded == null ? null : loaded.staticModel;
        State entryState = buildState(script.getEntryScreen());
        registerInGraph(graph, entryState);

        // --- identity and clock
        setField(agent, "timestamp", 1);
        setField(agent, "model", model);
        setField(agent, "newState", entryState);
        setField(agent, "newGUITree", null); // finding 2.1-a

        // --- the collaborators the constructor builds (StatefulAgent.java:179-208)
        // The artifact reaches the agent inside its MOP context (design D17, Plumbing), built from
        // the plan installed above exactly as the constructor builds it — the injection profile
        // adapting to a relocated collaborator (INV-ORA-07); the agent derives getMopData() from it.
        final MopContext mopContext = MopContext.fromSpec(mopData, staticModel, spec.mop());
        setField(agent, "_mopContext", mopContext);
        // The static model and the per-step streams, as the constructor takes them (design D-B9,
        // D-B14.20): the model over the loaded fixture, the streams from the installed context.
        setField(agent, "_staticModel", staticModel);
        setField(agent, "_stepRng", RunContext.current().stepRng());
        // The MOP interaction ledger, as the constructor builds it: present iff MOP is mounted.
        setField(agent, "_mopLedger", spec.has(Feature.MOP)
                ? new MopInteractionLedger(spec.mop().coinLedgerMaxInteractions()) : null);
        setField(agent, "_coverageTracker", coverageTracker);
        // As the constructor: a tracker iff ACTIVITY_BUDGET, the BUDGET stage's only reader.
        setField(agent, "_budgetTracker",
                spec.has(Feature.ACTIVITY_BUDGET) ? budgetTracker(script, spec) : null);
        ScoringContext scoringContext = new ScoringContext() {
            @Override public StaticGuiModel getStaticModel() { return staticModel; }
            @Override public MopContext getMopContext() { return mopContext; }
            @Override public UICoverageTracker getCoverageTracker() { return coverageTracker; }
            @Override public Graph getGraph() { return graph; }
            @Override public int getTimestamp() { return agent.getTimestamp(); }
        };
        setField(agent, "scoringContext", scoringContext);
        // The scoring parameters now arrive by injection rather than off static Config, so the
        // scaffold derives them from the same plan it installed above — the injection profile
        // adapting to a relocated collaborator, which is the one adaptation INV-ORA-07 permits
        // while the extraction is in flight. No golden moves: the pipeline runs in
        // adjustActionsByGUITree(), above this harness's entry point, so no golden record has ever
        // depended on a scoring weight.
        setField(agent, "scoringPipeline",
                ScoringPipeline.fromParams(ScoringParams.fromSpec(spec), scoringContext, new NoopSink()));

        // --- fields with inline initializers, which Unsafe.allocateInstance also skipped
        setField(agent, "actionBuffer", new LinkedList<>());
        setField(agent, "_actionHistory", new ArrayList<>());
        setField(agent, "backMenuPicks", new HashMap<String, Integer>());
        setField(agent, "actionCounters", new int[sataEventTypeCount()]);
        setField(agent, "actionDiffer", new StateActionDiffer());

        // --- episode state; the driver rewrites the first two per step
        setField(agent, "_isNewState", false);
        setField(agent, "graphStableCounter", 0);
        setField(agent, "backToActivity", null);
        setField(agent, "epsilon", spec.exploration().defaultEpsilon());
        // A restart never falls due: its start action leaves the JVM (getStartAction reads the
        // device through ape), so the RESTART guard is assembled but never selects (the boundary
        // below). Unsafe left the threshold at 0, which would make a periodic restart due at once.
        setField(agent, "nextRestartThreshold", Integer.MAX_VALUE);
        // The selection ladder's own parameters now arrive by injection rather than off Config's
        // statics, so the scaffold takes them from the same plan it installed above — the injection
        // profile adapting to a relocated collaborator, the one adaptation INV-ORA-07 permits while
        // the extraction is in flight. No golden moves: these are the jar defaults the goldens were
        // captured over, reaching the same read sites by a different route.
        setField(agent, "exploration", spec.exploration());
        // The decision policy the constructor would have assembled (SataAgent.java), built from the
        // same plan installed above and against this agent's own action producers. Last, because
        // nothing it binds may be read before every field it reaches through is set — this is the
        // injection profile adapting to a relocated collaborator, the one adaptation INV-ORA-07
        // permits while the extraction is in flight.
        DecisionPipeline pipeline = DecisionPipeline.fromSpec(spec, agent);
        setField(agent, "decisionPipeline", pipeline);
        // The launcher's cadence counter is seeded once, here, and never touched again: the stage
        // owns every later write and the driver is forbidden from touching it at all (INV-ORA-05).
        // It is seeded after assembly because the stage that holds it does not exist before then.
        // See ScenarioScript.getStepsSinceLauncherFiring().
        for (DecisionStage stage : pipeline.stages()) {
            if (stage.name().equals(DecisionPipeline.Candidate.MOP_LAUNCHER.stageName())) {
                setField(stage, "stepsSinceFiring", script.getStepsSinceLauncherFiring());
            }
        }
        if (llm != null) {
            installScriptedLlm(pipeline, llm);
        }

        RandomHelper.seed(script.getSeed());
        agent.pinned = new Random(script.getSeed());
        return agent;
    }

    /**
     * Replaces, on each LLM stage, the engine and the breaker gate with the script's.
     *
     * <p>Per-stage rather than per-run because the gate is where a hook's verdict lives: the stage
     * evaluates its own trigger first and consults the gate chain last, so a gate that could not
     * tell which hook was asking could not express a script (design D3). Assembly hands the three
     * stages one {@link LlmCall}; each gets its own here, built from the assembled one with the same
     * {@code StepRouting}, so the step's one-call budget (INV-RTR-26) is still shared, the
     * {@link com.android.commands.monkey.ape.agent.pipeline.MinActionsGate} is still the plan's, and
     * only the breaker — which reads the wall clock — is replaced by the hook's scripted verdict.
     *
     * <p>The coin needs no substitution: {@code u_L} is drawn at step open from its own stream
     * ({@code StepRng}), never from the agent's pinned one, so no epsilon-greedy draw can move.
     */
    private static void installScriptedLlm(DecisionPipeline pipeline, ScriptedLlm llm)
            throws Exception {
        for (DecisionStage stage : pipeline.stages()) {
            String hook = null;
            if (stage.name().equals(DecisionPipeline.Candidate.LLM_NEW_STATE.stageName())) {
                hook = ScriptedLlm.NEW_STATE;
            } else if (stage.name().equals(DecisionPipeline.Candidate.LLM_STAGNATION.stageName())) {
                hook = ScriptedLlm.STAGNATION;
            } else if (stage.name().equals(DecisionPipeline.Candidate.LLM_COIN.stageName())) {
                hook = ScriptedLlm.RANDOM;
            }
            if (hook != null) {
                setField(stage, "llm", scriptedCall((LlmCall) getField(stage, "llm"), llm, hook));
            }
        }
    }

    /** {@code assembled} with the script's engine, and the hook's verdict in the breaker's place. */
    @SuppressWarnings("unchecked")
    private static LlmCall scriptedCall(LlmCall assembled, ScriptedLlm llm, String hook)
            throws Exception {
        List<LlmGate> gates = new ArrayList<>();
        for (LlmGate gate : assembled.gates()) {
            if (!BreakerGate.NAME.equals(gate.name())) {
                gates.add(gate);
            }
        }
        java.util.function.BooleanSupplier verdict = llm.gateFor(hook);
        gates.add(new LlmGate() {
            @Override public String name() { return BreakerGate.NAME; }
            @Override public boolean admits(StepContext ctx) { return verdict.getAsBoolean(); }
        });
        return new LlmCall(llm.engine(), gates, (StepRouting) getField(assembled, "routing"),
                (java.util.function.Consumer<ModelAction>) getField(assembled, "resolveSynthesizedTap"),
                (java.util.function.Consumer<String>) getField(assembled, "abandonBuffer"),
                () -> null);
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
    private static ActivityBudgetTracker budgetTracker(ScenarioScript script, RunSpec spec) {
        ActivityBudgetTracker tracker = new ActivityBudgetTracker(
                spec.exploration().integer("ape.activityBaseBudget"),
                spec.exploration().integer("ape.activityBudgetPerWidget"));
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

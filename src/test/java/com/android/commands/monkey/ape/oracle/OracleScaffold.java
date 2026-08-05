package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.agent.SataAgent;
import com.android.commands.monkey.ape.agent.pipeline.DecisionPipeline;
import com.android.commands.monkey.ape.agent.pipeline.DecisionStage;
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
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
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
 * *   <tr><td>{@code _coverageTracker}</td><td>StatefulAgent</td><td>{@code computeDynamicEpsilon()} ({@code Config.dynamicEpsilon} defaults true)</td></tr>
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
 *   <li><b>2.1-c — the component trigger is outside the capture boundary</b>, by owner decision
 *       of 2026-08-03. It never fires: {@code ape.componentPercentage} is set by no arm of the
 *       phase-2 grid — the key exists only in {@code aperv-tool}'s mapping
 *       ({@code tool.py:101}) and is absent from the 18 {@code ARM_DEFINING_KEYS} and both
 *       arm-flag dicts — so every arm runs on the jar default 0.0
 *       ({@code Config.java:256}), which makes the rate non-positive in production and here alike.
 *       Since the extraction moved that rate into the plan, the same fact reads structurally: no
 *       preset states the key, so {@code COMPONENT_TRIGGER} is in no preset's plan and
 *       {@code ComponentTriggerStage} is in no preset's roster (INV-DP-03). Even with the gate
 *       forced open the committed fixture yields zero tuples (no receivers, no services, and its
 *       one provider has {@code reachesTarget=false}, filtered at {@code StatefulAgent.java:1246}),
 *       so {@code mopComponentTargetCount()} reports zero and the stage's cursor never moves; and
 *       had a tuple existed, dispatch is device-bound ({@code android.content.Intent} in
 *       {@code dispatchTrigger}, {@code AndroidDevice.executeCommandAndWaitFor} in
 *       {@code dispatchProvider}, whose wrapper catches {@code Exception} and not the
 *       {@code NoClassDefFoundError} actually thrown). The exclusion costs no parity: an absent
 *       stage decides nothing and draws nothing, exactly as the short-circuiting conjunction it
 *       replaced did. Should a future arm enable it, this must be revisited before that arm's
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
 * Every golden silently depends on these, so the list is {@link LadderConfigGuard}'s assertions and
 * nothing else — the guard is what fails with the key named when one moves, and a list here that
 * were shorter than it would understate what the goldens rest on. Read in every preset:
 * {@code activityBudgetEnabled=true}, {@code activityBaseBudget=50},
 * {@code activityBudgetPerWidget=5}, {@code trivialActivityRankThreshold=3},
 * {@code doBackToTrivialActivity=false}, {@code componentPercentage=0.0},
 * {@code modelMenuEnabled=true}, {@code useActionDiffer=true},
 * {@code leastVisitedPriorityTiebreak=true}, {@code backMenuPickCap=3},
 * {@code dynamicEpsilon=true}, {@code defaultEpsilon=0.05}, {@code minEpsilon=0.02},
 * {@code maxEpsilon=0.15}. Read only where {@code _mopData} is present:
 * {@code activityTriggerEnabled=true}, {@code activityTriggerStagnationStep=50},
 * {@code activityTriggerMaxPerRun=0}, {@code mopTargetPickCap=3}. Each preset test guard-asserts
 * the half it depends on (design D2). Scoring weights are deliberately <b>not</b> among them: they
 * are consumed above this entry point, so no golden can depend on one, and their guard belongs to
 * {@code rearch-03} INV-ARCH-12. {@code llmPercentage} and
 * {@code graphStableRestartThreshold} are likewise absent — an LLM preset states both in its plan
 * (see {@link #newAgent}) rather than inheriting the jar's, so no LLM golden reads either default.
 */
public final class OracleScaffold {

    /**
     * The MOP fixture the {@code mop} and {@code llm_mop} presets load, via {@code MopData.load}.
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
     * The four target presets. A preset is realized by the plan it states and the injection profile
     * that follows from it: the presence or absence of {@code _mopData} and of the LLM feature are
     * its only axes (design D2), which is what {@code StatefulAgent}'s constructor used to decide
     * from {@code Config.mopDataPath} and {@code Config.llmUrl} and what {@code RunSpec} decides
     * now.
     */
    public enum Preset {
        APERV(false, false),
        MOP(true, false),
        LLM(false, true),
        LLM_MOP(true, true);

        private final boolean mopData;
        private final boolean llm;

        Preset(boolean mopData, boolean llm) {
            this.mopData = mopData;
            this.llm = llm;
        }

        public boolean hasMopData() { return mopData; }
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
                MOP_FIXTURE_PACKAGE, MOP_FIXTURE_MAIN_ACTIVITY, new NoopSink());
        if (data == null) {
            throw new IllegalStateException("MopData.load returned null for " + MOP_FIXTURE);
        }
        return data;
    }

    /**
     * Builds the agent for a preset, wired over the scenario's entry screen and with both RNG
     * streams seeded from the scenario's declared seed.
     *
     * @param llm the scripted LLM for the LLM presets; null for the others. The preset's declared
     *            axis and the argument must agree — a mismatch is an authoring bug and fails here
     *            rather than producing a golden for the wrong preset.
     */
    static OracleSataAgent newAgent(Preset preset, ScenarioScript script, ScriptedLlm llm)
            throws Exception {
        return newAgent(preset, script, llm, null);
    }

    /**
     * The same agent, observed by a sink of the caller's choosing.
     *
     * <p>The neutrality gate (R7, INV-SNK-07) is the only caller that passes one: it replays a
     * preset under one seed with {@code NdjsonSink} and then with {@code NoopSink} and asserts the
     * decisions did not move. A null means what a run gets — the context builds its own.
     *
     * @param sink the sink the run's context should hold, or null for the one it would build
     */
    static OracleSataAgent newAgent(Preset preset, ScenarioScript script, ScriptedLlm llm,
            EventSink sink) throws Exception {
        if (preset.hasLlm() != (llm != null)) {
            throw new IllegalArgumentException("preset " + preset + " requires a scripted LLM "
                    + (preset.hasLlm() ? "but none was supplied" : "but one was supplied"));
        }

        // The plan the decision pipeline is assembled from, and which the ladder reads. A preset
        // must state itself as a RunSpec because what used to be a Config read at the decision site
        // is now an assembly condition — the launcher gate first, and since the extraction started,
        // the presence of each stage. This is the injection scaffold adapting to relocated
        // configuration, the one adaptation INV-ORA-07 permits while stages 2 and 3 are in flight.
        //
        // The values are the jar defaults, deliberately: the goldens were captured over
        // jar-default Config (design D2), so a MOP arm at its defaults reproduces exactly the
        // launcher gate the capture ran under (activityTriggerEnabled true), and an LLM arm gets the
        // three hooks the capture ran with (llmOnNewState/llmOnStagnation true, llmPercentage 0.02).
        // A preset without the substrate states neither key, which closes the same gates its absent
        // MopData and absent LLM stages already closed.
        //
        // ape.llmUrl is a plan value only. Every stage's engine and gate is substituted below, so
        // nothing in this harness opens a socket — what the URL buys is the LLM feature, and with it
        // the LLM stages.
        //
        // Two LLM keys are stated away from their jar defaults, and both are injection-profile
        // adaptations to the trigger predicates having moved into the stages (INV-ORA-07):
        //
        //   ape.graphStableRestartThreshold=10 — the stagnation stage now evaluates the real
        //   midpoint predicate, and at the jar default of 100 the midpoint is 50 while every
        //   scenario's graphStableCounter is 0, 5, 6 or 7. At 10 the midpoint is 5, which is the
        //   value the scenarios were written around. This does not touch the forced restart, and the
        //   reason is the harness's boundary rather than where the threshold is read: onGraphStable
        //   consults the same plan value, but it is reached only from checkStable() at the end of
        //   updateStateInternal, and this harness drives agent.ladder() directly. No scenario ever
        //   runs the restart check, so lowering the threshold cannot trigger one.
        //
        //   ape.llmPercentage=1.0 — the probabilistic stage now draws a real coin, and at the jar
        //   default of 0.02 a scripted random hook would be refused ~98% of the time. At 1.0 the
        //   coin always passes and the scripted gate below is what decides. The stream it draws
        //   from is substituted as well (installScriptedLlm), so the draw does not touch the
        //   agent's pinned stream and no golden moves.
        String[] llmKeys = preset.hasLlm()
                ? new String[] {"ape.llmUrl", "http://127.0.0.1:30000/v1",
                        "ape.graphStableRestartThreshold", "10",
                        "ape.llmPercentage", "1.0"}
                : new String[0];
        RunSpec spec = preset.hasMopData()
                ? TestRunSpecs.mopSpec(llmKeys)
                : TestRunSpecs.spec(llmKeys);
        if (sink != null) {
            RunContext.installForTest(spec, sink);
        } else {
            RunContext.installForTest(spec);
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
        final MopData contextMopData = mopData;
        ScoringContext scoringContext = new ScoringContext() {
            @Override public MopData getMopData() { return contextMopData; }
            @Override public UICoverageTracker getCoverageTracker() { return coverageTracker; }
            @Override public Graph getGraph() { return graph; }
            @Override public int getTimestamp() { return agent.getTimestamp(); }
            @Override public boolean menuPickEligible(String activity) { return true; }
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
        setField(agent, "mopTargetPicks", new HashMap<String, Integer>());
        setField(agent, "actionCounters", new int[sataEventTypeCount()]);
        setField(agent, "actionDiffer", new StateActionDiffer());

        // --- episode state; the driver rewrites the first two per step
        setField(agent, "_isNewState", false);
        setField(agent, "graphStableCounter", 0);
        setField(agent, "backToActivity", null);
        setField(agent, "epsilon", Config.defaultEpsilon);
        // The selection ladder's own parameters now arrive by injection rather than off Config's
        // statics, so the scaffold takes them from the same plan it installed above — the injection
        // profile adapting to a relocated collaborator, the one adaptation INV-ORA-07 permits while
        // the extraction is in flight. No golden moves: these are the jar defaults the goldens were
        // captured over, reaching the same read sites by a different route.
        setField(agent, "exploration", spec.exploration());
        setField(agent, "mopTargetPickCap",
                spec.has(Feature.MOP) ? spec.mop().targetPickCap() : 0);
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
     * Replaces each LLM stage's engine and breaker gate with the script's, on the stage that owns
     * them.
     *
     * <p>Per-stage rather than per-run because the gate is where a hook's verdict now lives: the
     * stage evaluates its own condition first and consults the gate last, so a gate that could not
     * tell which hook was asking could not express a script (design D3). The engine is shared —
     * only the hook whose gate answered true can reach it.
     *
     * <p>The probabilistic stage's coin is substituted as well, and that is the one substitution
     * that would move a golden if it were omitted: assembly hands the stage the agent's own
     * generator, and a draw taken from it here would shift every later epsilon-greedy draw the
     * ladder makes — four committed goldens moved by an artifact of the harness. Overwriting the
     * field leaves the agent's pinned stream untouched. The pre-decomposition harness replaced the
     * coin outright by overriding the predicate that held it; this replaces the stream it draws
     * from, which is the same replacement one layer down.
     */
    private static void installScriptedLlm(DecisionPipeline pipeline, ScriptedLlm llm)
            throws Exception {
        for (DecisionStage stage : pipeline.stages()) {
            String hook = null;
            if (stage.name().equals(DecisionPipeline.Candidate.LLM_NEW_STATE.stageName())) {
                hook = ScriptedLlm.NEW_STATE;
            } else if (stage.name().equals(DecisionPipeline.Candidate.LLM_STAGNATION.stageName())) {
                hook = ScriptedLlm.STAGNATION;
            } else if (stage.name().equals(DecisionPipeline.Candidate.LLM_RANDOM.stageName())) {
                hook = ScriptedLlm.RANDOM;
                setField(stage, "random", new Random(0L));
            }
            if (hook != null) {
                setField(stage, "engine", llm.engine());
                setField(stage, "breakerAllows", llm.gateFor(hook));
            }
        }
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

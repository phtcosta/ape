package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActivityTriggerAction;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.Model;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.utils.MopData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * rearch-01 task 5.1 — the step loop: it drives {@code selectNewActionNonnull()} once per scripted
 * step, records what came back, and replays between steps the bookkeeping the production loop would
 * have done on a device (design D7).
 *
 * <p>This javadoc is <b>the replay half of the harness's honesty ledger</b>; the injection half is
 * {@link OracleScaffold}'s. Everything the driver does <i>between</i> two calls into the ladder is
 * named here, because that is what makes step <i>i+1</i> see a different world than step <i>i</i> —
 * and therefore what makes a multi-step golden mean anything.
 *
 * <h2>One step, in order</h2>
 * <ol>
 *   <li>resolve the current screen's {@code State} (built once, then reused — see below) and inject
 *       it as {@code newState};</li>
 *   <li>inject the step's scripted {@code _isNewState} and {@code graphStableCounter} — the two
 *       agent-side values the production loop computes in {@code updateStateInternal}, off the
 *       JVM;</li>
 *   <li>{@code graph.markVisited(state, timestamp)};</li>
 *   <li>{@code llm.beginStep(i)} when the preset has a scripted LLM;</li>
 *   <li>{@code agent.ladder()} — the system under test, unchanged;</li>
 *   <li>{@code llm.finishStep()}, which fails when the step's scripted consultations did not
 *       happen;</li>
 *   <li>build the {@link DecisionRecord} from the returned action;</li>
 *   <li>{@code graph.markVisited(action, timestamp)} for a {@code ModelAction} return;</li>
 *   <li>advance the clock, then the screen, through the script's transition table.</li>
 * </ol>
 *
 * <h2>Why the state is marked visited <i>before</i> the ladder (finding 5.1-a)</h2>
 * Design D7 calls the bookkeeping "post-step", and for the action it is. For the state it is not:
 * production marks {@code newState} visited at {@code StatefulAgent.java:749}, <b>before</b>
 * {@code resolveNewAction()} reaches the ladder, and it computes {@code _isNewState} from the visit
 * count one line earlier ({@code :748}) precisely because the mark is about to erase that
 * information. A harness that marked the state afterwards would run every first selection against a
 * state reporting {@code isUnvisited()}, which is an input several rungs read — the golden would then
 * pin a state the production loop never presents. So the order here mirrors production, and the
 * script's {@code _isNewState} is what carries the pre-mark answer, exactly as {@code :748} does.
 *
 * <h2>A screen is one {@code State}, built once (finding 5.1-b)</h2>
 * The driver caches the {@code State} per screen name and reuses it on every revisit. That is not an
 * economy — it is the harness's stand-in for {@code Graph.getOrCreateState}'s key lookup. Rebuilding
 * a screen would produce a <i>different</i> {@code StateKey}: {@code StateKey.equals} compares its
 * {@code Name[]} with {@code Arrays.equals}, and the scaffold's {@code testName} stubs are anonymous
 * instances with identity equality, so two builds of the same screen never compare equal. The graph
 * would gain a duplicate state, and the agent would meet a screen it had already explored as if it
 * were new.
 *
 * <p>The consequence is deliberate and worth stating rather than discovering: <b>a revisited screen
 * carries the visit marks of its earlier visits</b>, so a scenario's second pass over a screen can
 * descend a different rung than its first. That is the whole point of a multi-step scenario, and it
 * is why a golden's later steps are not simply a repetition of its first.
 *
 * <h2>What the driver deliberately does not touch</h2>
 * <ul>
 *   <li>{@code _stepsSinceLauncherFiring} — the launcher's cadence counter is owned by the ladder
 *       itself ({@code SataAgent.java:522}, incremented as the block's first statement, which is the
 *       mechanism of finding 3.3-1: an LLM block that returns above it never reaches the increment).
 *       A driver that reset or advanced it between steps would erase precisely the interaction the
 *       preemption golden exists to pin (INV-ORA-05).</li>
 *   <li>the stagnation stage's single-shot flag — episode state, burned by the stage on any
 *       stagnation consultation and re-armed only by a new edge, of which the driver records none.
 *       One run is one episode.</li>
 *   <li>{@code _activityTriggerLaunchCount} and {@code _triggerRoundRobinIndex} — the launcher's own
 *       run-scoped counters, advanced by the block that reads them.</li>
 *   <li>the graph's edges. No {@code StateTransition} is ever recorded: the driver moves the agent to
 *       the next screen by injecting {@code newState}, not by walking an edge. Every rung that reads
 *       edges (backward greedy, trivial-activity path search) therefore sees an edgeless graph, which
 *       is the boundary design D6 draws and the reason those rungs return null rather than reaching
 *       {@code AndroidDevice}.</li>
 * </ul>
 *
 * <h2>The clock</h2>
 * {@code ApeAgent} advances {@code timestamp} at the <i>start</i> of a step
 * ({@code ApeAgent.java:355}, {@code ++timestamp} in the step banner) and the scaffold hands the
 * driver an agent already sitting at 1 — step 0's value. The driver therefore advances at the end of
 * a step, which yields the same sequence 1, 2, 3, … with the visit marks of step <i>i</i> stamped at
 * <i>i+1</i>, as production stamps them.
 *
 * <h2>The sink load, and why the driver is the one that applies it (task 6.2)</h2>
 * {@link #runUnderSinkLoad} adds one thing to the loop above: it makes the run's {@code EventSink}
 * work while the ladder decides. Nothing else changes — same injections, same seeds, same order —
 * and the plain {@link #run} overloads do not drive the sink at all, so no golden can move.
 *
 * <p>It exists because the neutrality gate could not otherwise reach the property it claims. Every
 * production sink call sits <i>above</i> {@code agent.ladder()}: {@code beginStep} and
 * {@code decision} are in {@code resolveNewAction}, {@code outcome} is in {@code updateGraph}, and
 * {@code resolveNewAction} cannot be the entry point — its second statement is
 * {@code adjustActionsByGUITree()}, which needs a {@code GUITree} the JVM cannot build (finding
 * 2.1-a). So a replay left the real sink holding nothing, and comparing two such replays proved
 * only that <i>installing</i> a different implementation is harmless. What R7 asks is that a sink
 * <i>doing its work</i> is.
 *
 * <p><b>The calls are placed by the harness, and that is a real difference worth naming.</b> They
 * are made around the ladder rather than inside it, at the points of the step where production
 * makes them — the outcome of step <i>i-1</i> and then the envelope of step <i>i</i> before
 * selection, the decision after it, the teardown flush at the end. That reaches every channel
 * through which a sink could actually perturb a decision, because those channels are global rather
 * than positional: the shared {@code RandomHelper} statics, the agent's pinned stream, and the
 * {@code Model}/{@code Graph}/{@code State} objects whose strings the sink is handed. A sink that
 * drew from a seeded stream, or mutated what it was given, moves the next {@code ladder()} call's
 * decision whether it was called two statements earlier or two frames deeper.
 *
 * <p><b>What the load is not.</b> The records a replay writes are not a production trace and no
 * test asserts their content — the point is the work, not the bytes. Three specifics, so nobody
 * reads more into them than is there:
 * <ul>
 *   <li>{@code outcome} is driven whenever the previous step selected a {@code ModelAction},
 *       standing in for production's guard (a recorded transition, reference-equal to the buffered
 *       decision). This graph has no edges by design, so the production guard has no analogue here;
 *       the substitute is the script's own transition table, which is what already moves the agent
 *       from screen to screen.</li>
 *   <li>the two tri-states are handed {@code ABSENT}. For {@code patched} that is what production
 *       would compute — the harness's actions have no resolved {@code GUITreeNode} — and for
 *       {@code cf} it is one member's difference on the four MOP-sensitive channels, whose
 *       counterfactual pick is written above the entry point and is therefore null here.</li>
 *   <li>{@code mopExposure} and the {@code llm[]} sub-events stay undriven: the first is emitted
 *       inside {@code adjustActionsByGUITree()} and the second by the units the scaffold replaces
 *       with {@link ScriptedLlm}. {@code SinkNeutralityTest} keeps a test on the undriven path that
 *       says so.</li>
 * </ul>
 *
 * <h2>Failures propagate unchanged</h2>
 * Nothing here catches anything. A {@code BadStateException} (the ladder found no available action),
 * a {@code NoClassDefFoundError} (a scenario drove selection into a device-only branch), and the
 * script's {@code IllegalStateException} (a scripted consultation that never happened) all leave the
 * driver as they were thrown. Wrapping them would cost the one property design D6 asks of the
 * boundary — that crossing it fails <i>loudly</i>, as itself, and gets the scenario redesigned rather
 * than an {@code @Ignore}.
 */
public final class OracleDriver {

    private OracleDriver() {
    }

    /**
     * Replays {@code script} against {@code agent}, one {@link DecisionRecord} per scripted step.
     *
     * <p>The parameter is an {@link OracleSataAgent} rather than the {@code SataAgent} of design
     * D7's sketch for the reason finding 1.1-a establishes: {@code selectNewActionNonnull()} is
     * protected and cannot be entered reflectively, so {@link OracleSataAgent#ladder()} is the only
     * way in.
     *
     * @param agent  wired by {@link OracleScaffold#newAgent} for the scenario's preset, with both
     *               RNG streams already seeded from the scenario's seed
     * @param script the scenario; its entry screen must be the state the scaffold injected
     * @return the decision sequence, in step order, ready for {@code GoldenFile} to write or compare
     */
    static List<DecisionRecord> run(OracleSataAgent agent, ScenarioScript script) throws Exception {
        return run(agent, script, null);
    }

    /**
     * The same replay, with the run's sink driven around each selection (task 6.2).
     *
     * <p>The only caller is the neutrality gate, which replays one preset under one seed with
     * {@code NdjsonSink} and then with {@code NoopSink} and asserts the decisions did not move. The
     * sink is taken from {@code RunContext} rather than passed in, because that is the route
     * production takes to it — the scaffold installs the implementation under test, and this method
     * exercises whatever it installed. What the load consists of, and what it deliberately leaves
     * out, is the class javadoc's sink section.
     */
    static List<DecisionRecord> runUnderSinkLoad(OracleSataAgent agent, ScenarioScript script,
            ScriptedLlm llm) throws Exception {
        return run(agent, script, llm, true);
    }

    /**
     * Replays {@code script}, driving {@code llm}'s per-step bookkeeping around each selection.
     *
     * <p>The scripted LLM arrives as an argument rather than being read off the agent, because there
     * is no longer one field to read it from: the script reaches the pipeline as a gate and an
     * engine installed on each of the three LLM stages, so the object the driver has to arm is the
     * one the caller built, not one the agent holds.
     *
     * @param llm the scripted LLM for an LLM preset, or null for a preset that has none
     */
    static List<DecisionRecord> run(OracleSataAgent agent, ScenarioScript script, ScriptedLlm llm)
            throws Exception {
        return run(agent, script, llm, false);
    }

    private static List<DecisionRecord> run(OracleSataAgent agent, ScenarioScript script,
            ScriptedLlm llm, boolean driveSink) throws Exception {
        Graph graph = ((Model) OracleScaffold.getField(agent, "model")).getGraph();
        MopData mopData = (MopData) OracleScaffold.getField(agent, "_mopData");
        EventSink sink = driveSink ? RunContext.current().sink() : null;

        Map<String, State> statesByScreen = new HashMap<>();
        String screen = script.getEntryScreen().getName();
        // The scaffold already built and registered the entry state; rebuilding it here would be the
        // duplicate-StateKey mistake finding 5.1-b describes, one step before the loop even starts.
        statesByScreen.put(screen, (State) OracleScaffold.getField(agent, "newState"));

        List<ScenarioScript.Step> steps = script.getSteps();
        List<DecisionRecord> records = new ArrayList<>(steps.size());
        Action decided = null;
        String decidedOn = null;
        for (int index = 0; index < steps.size(); index++) {
            ScenarioScript.Step step = steps.get(index);
            State state = stateOf(graph, script, statesByScreen, screen);

            OracleScaffold.setField(agent, "newState", state);
            OracleScaffold.setField(agent, "_isNewState", step.isNewState());
            OracleScaffold.setField(agent, "graphStableCounter", step.getGraphStableCounter());
            graph.markVisited(state, agent.getTimestamp()); // before the ladder — finding 5.1-a
            if (sink != null) {
                // The order production runs them in: updateGraph resolves the previous step's
                // outcome, and only then does resolveNewAction open this step's record.
                if (decided instanceof ModelAction) {
                    sink.outcome(step.isNewState(), state.getStateKey().toString(),
                            state.getActivity(), hasMop(mopData, state.getActivity()),
                            !state.getActivity().equals(decidedOn));
                }
                sink.beginStep(agent.getTimestamp(), RunContext.current().elapsedMs(),
                        state.getActivity(), hasMop(mopData, state.getActivity()),
                        state.getStateKey().toString());
            }
            if (llm != null) {
                llm.beginStep(index);
            }

            Action selected = agent.ladder();

            if (llm != null) {
                llm.finishStep();
            }
            if (sink != null) {
                decision(sink, selected);
            }
            records.add(record(index, selected, llm));
            if (selected instanceof ModelAction) {
                graph.markVisited((ModelAction) selected, agent.getTimestamp());
            }
            decided = selected;
            decidedOn = state.getActivity();
            OracleScaffold.setField(agent, "timestamp", agent.getTimestamp() + 1);
            screen = script.nextScreen(screen, targetXPath(selected));
        }
        if (sink != null) {
            // What teardown does with the record the run ended inside of, which is the last one here
            // by construction: no step follows to close it.
            sink.flushPendingStep();
        }
        return records;
    }

    /**
     * Fills the open record's {@code dec} section, mirroring {@code resolveNewAction}'s two branches.
     *
     * <p>A {@code ModelAction} carries its own provenance and boosts, so those are read off the
     * action rather than restated here. A non-model action carries neither — the two labels
     * production derives from the action type are its rule, not the harness's, so the harness passes
     * the type it can see and no channel at all. Nothing asserts either string; what they are for is
     * to make the sink escape and write something of the right shape and size.
     */
    private static void decision(EventSink sink, Action selected) {
        if (selected instanceof ModelAction) {
            ModelAction picked = (ModelAction) selected;
            sink.decision(picked.toString(), picked.getDecisionSource().name(),
                    picked.getPickChannel().getLabel(), picked.getPriority(),
                    picked.getMopBoost(), picked.getMopFrontierBoost(), picked.getWtgBoost(),
                    picked.getCoverageBoost(), picked.getMenuBoost(), picked.getFormBoost(),
                    picked.getWtgSource(), EventSink.ABSENT, EventSink.ABSENT, null);
            return;
        }
        sink.decisionNonModel(selected.toString(), selected.getType().name(), null);
    }

    /** {@code activityHasMop}, which is private to the agent: no MOP data means no MOP activity. */
    private static boolean hasMop(MopData mopData, String activity) {
        return mopData != null && activity != null && mopData.activityHasMop(activity);
    }

    /** The screen's state, built and registered on its first visit and reused on every later one. */
    private static State stateOf(Graph graph, ScenarioScript script, Map<String, State> cache,
                                 String screen) throws Exception {
        State state = cache.get(screen);
        if (state == null) {
            state = OracleScaffold.buildState(script.getScreen(screen));
            OracleScaffold.registerInGraph(graph, state);
            cache.put(screen, state);
        }
        return state;
    }

    /**
     * Renders one selection.
     *
     * <p>Three shapes, and which fields each one carries is the whole of the golden's vocabulary:
     * <ul>
     *   <li>a {@code ModelAction} always carries both provenance fields — {@code ModelAction.java:91-92}
     *       initializes them to {@code SATA}/{@code sata_other} and {@code logActionSelected}
     *       ({@code SataAgent.java:226-238}) rewrites the source on every branch except
     *       {@code EARLY_STAGE} and {@code EPSILON_GREEDY}, which stamp their own at the pick sites.
     *       Their absence is the launcher's signature, never a "not set yet";</li>
     *   <li>an {@code ActivityTriggerAction} carries the candidate class as its target and neither
     *       provenance field: it is not a {@code ModelAction}, so it has none to read
     *       (the spec's "Launcher step rendering" scenario);</li>
     *   <li>any other non-model return carries its type alone.</li>
     * </ul>
     *
     * <p>The {@code llm} field distinguishes <i>absent</i> from {@code not_routed}, which are
     * different statements: absent means the preset has no LLM at all, so no consultation was
     * possible; {@code not_routed} means one was present and the step passed it by. Only a
     * preset that actually has one can make the second claim.
     */
    private static DecisionRecord record(int step, Action selected, ScriptedLlm scripted) {
        String llm = scripted == null ? null : scripted.getProvenance().getLabel();
        if (selected instanceof ModelAction) {
            ModelAction picked = (ModelAction) selected;
            return new DecisionRecord(step, picked.getType().name(), targetXPath(picked),
                    picked.getDecisionSource().name(), picked.getPickChannel().getLabel(), llm);
        }
        if (selected instanceof ActivityTriggerAction) {
            return new DecisionRecord(step, selected.getType().name(),
                    ((ActivityTriggerAction) selected).getClassName(), null, null, llm);
        }
        return new DecisionRecord(step, selected.getType().name(), null, null, null, llm);
    }

    /**
     * The action's {@code Name.toXPath()} — the golden's target for a targeted action and the
     * transition table's key half. Null for everything else, including the launcher: its target is a
     * class name, and feeding that to the transition table would let a scenario accidentally key a
     * transition on a value that is not a widget identity.
     */
    private static String targetXPath(Action selected) {
        if (!(selected instanceof ModelAction)) {
            return null;
        }
        Name target = ((ModelAction) selected).getTarget();
        return target == null ? null : target.toXPath();
    }
}

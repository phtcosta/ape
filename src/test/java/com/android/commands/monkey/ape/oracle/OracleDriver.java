package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActivityTriggerAction;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.Model;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.naming.Name;

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
 *   <li>{@code router.beginStep(i)} when the preset has one;</li>
 *   <li>{@code agent.ladder()} — the system under test, unchanged;</li>
 *   <li>{@code router.finishStep()}, which fails when the step's scripted consultations did not
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
 * <h2>Failures propagate unchanged</h2>
 * Nothing here catches anything. A {@code BadStateException} (the ladder found no available action),
 * a {@code NoClassDefFoundError} (a scenario drove selection into a device-only branch), and the
 * router's {@code IllegalStateException} (a scripted consultation that never happened) all leave the
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
        Graph graph = ((Model) OracleScaffold.getField(agent, "model")).getGraph();
        ScriptedLlmRouter router = (ScriptedLlmRouter) OracleScaffold.getField(agent, "_llmRouter");

        Map<String, State> statesByScreen = new HashMap<>();
        String screen = script.getEntryScreen().getName();
        // The scaffold already built and registered the entry state; rebuilding it here would be the
        // duplicate-StateKey mistake finding 5.1-b describes, one step before the loop even starts.
        statesByScreen.put(screen, (State) OracleScaffold.getField(agent, "newState"));

        List<ScenarioScript.Step> steps = script.getSteps();
        List<DecisionRecord> records = new ArrayList<>(steps.size());
        for (int index = 0; index < steps.size(); index++) {
            ScenarioScript.Step step = steps.get(index);
            State state = stateOf(graph, script, statesByScreen, screen);

            OracleScaffold.setField(agent, "newState", state);
            OracleScaffold.setField(agent, "_isNewState", step.isNewState());
            OracleScaffold.setField(agent, "graphStableCounter", step.getGraphStableCounter());
            graph.markVisited(state, agent.getTimestamp()); // before the ladder — finding 5.1-a
            if (router != null) {
                router.beginStep(index);
            }

            Action selected = agent.ladder();

            if (router != null) {
                router.finishStep();
            }
            records.add(record(index, selected, router));
            if (selected instanceof ModelAction) {
                graph.markVisited((ModelAction) selected, agent.getTimestamp());
            }
            OracleScaffold.setField(agent, "timestamp", agent.getTimestamp() + 1);
            screen = script.nextScreen(screen, targetXPath(selected));
        }
        return records;
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
     * different statements: absent means the preset has no router at all, so no consultation was
     * possible; {@code not_routed} means a router was present and the step passed it by. Only a
     * preset that actually has one can make the second claim.
     */
    private static DecisionRecord record(int step, Action selected, ScriptedLlmRouter router) {
        String llm = router == null ? null : router.getProvenance().getLabel();
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

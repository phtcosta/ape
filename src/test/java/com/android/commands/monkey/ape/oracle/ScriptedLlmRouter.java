package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmRouter;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.MopData;

import java.util.List;
import java.util.Random;

/**
 * rearch-01 task group 3 — the deterministic LLM (design D3). It replaces the three routing
 * predicates and {@code selectAction} with per-step scripted answers, so an LLM preset's golden
 * depends on the scenario and nothing else: no SGLang server, no screenshot, no wall clock.
 *
 * <p><b>What it does not replace.</b> The agent-side arguments are honored, not ignored:
 * {@code shouldRouteNewState} still yields to {@code _isNewState} and
 * {@code shouldRouteStagnation} still yields to the episode's single-shot flag. That is
 * deliberate — those two contracts are agent behavior, and a stub that ignored them would
 * capture a ladder that does not exist. What the stub does own is the <i>verdict</i>: whether a
 * hook routes at all, and what comes back.
 *
 * <p><b>Why the predicates are replaced rather than driven.</b> The real
 * {@code shouldRouteRandom} consumes an RNG draw against {@code Config.llmPercentage} and every
 * predicate consults {@code breakerAllows()}, whose {@code LlmCircuitBreaker} reads
 * {@code System.currentTimeMillis()} ({@code LlmCircuitBreaker.java:54,77,95}). Both are
 * determinism hazards, and the predicates' own logic is already unit-covered by
 * {@code LlmRouterTest} and {@code LlmCircuitBreakerTest}. Overriding them outright is also what
 * satisfies INV-ORA-03: no override reaches the breaker, so no golden can depend on a breaker
 * transition, and scripts never model a breaker-open episode.
 *
 * <p><b>Accept returns a member of the offered list, never a synthesized tap.</b> That is not a
 * stylistic choice: an accepted {@code MODEL_LLM_TAP} is resolved against {@code newState}
 * ({@code LlmGate.accept} into {@code SataAgent.resolveSynthesizedTap}), which leaves the JVM. A selector therefore only
 * ever picks from the {@code actions} the agent offered.
 *
 * <p><b>Consumption bookkeeping (task 3.2).</b> A step's entry is a claim about what the agent
 * will do, and a claim that does not come true is a scenario bug, not a skip. {@link #finishStep}
 * fails when a declared hook's predicate was never <i>invoked</i> — which is what happens when the
 * shared LLM precondition ({@code LlmGate.allows}: an empty action buffer and more
 * than two actions on the state) short-circuits ahead of it. Invocation, not the return value, is the criterion: a predicate that
 * ran and answered false because the agent-side argument suppressed it did exactly what the script
 * asked. And once a hook accepts, the step is decided, so the hooks below it are correctly
 * unreached and not checked.
 */
public class ScriptedLlmRouter extends LlmRouter {

    /** Selector picking the first action carrying a target. */
    static final String FIRST_TARGETED = "first-targeted";

    /** Selector picking the first unvisited action carrying a target. */
    static final String FIRST_UNVISITED_TARGETED = "first-unvisited-targeted";

    /** Selector prefix naming an exact target xpath, e.g. {@code xpath://*[@resource-id='ok']}. */
    static final String XPATH_PREFIX = "xpath:";

    /** What a step's {@code llm} golden field records. */
    public enum Provenance {
        ACCEPTED("accepted"),
        DECLINED("declined"),
        TIMEOUT("timeout"),
        NOT_ROUTED("not_routed");

        private final String label;

        Provenance(String label) {
            this.label = label;
        }

        /** The value written to the golden. */
        public String getLabel() {
            return label;
        }
    }

    private final ScenarioScript script;

    private int stepIndex = -1;
    private ScenarioScript.LlmEntry entry;
    private boolean newStateConsulted;
    private boolean stagnationConsulted;
    private boolean randomConsulted;
    private Provenance provenance = Provenance.NOT_ROUTED;

    ScriptedLlmRouter(ScenarioScript script) {
        // The real constructor is JVM-safe (LlmRouterTest:47) and wires collaborators this stub
        // never reaches. Its Random is likewise never drawn from: shouldRouteRandom is overridden.
        super(new Random(script.getSeed()));
        this.script = script;
    }

    // ---- step bookkeeping, driven by OracleDriver -------------------------------------------

    /** Arms the router for one selection step. */
    void beginStep(int index) {
        List<ScenarioScript.Step> steps = script.getSteps();
        if (index >= steps.size()) {
            throw new IllegalStateException("scenario " + script.getName() + " is exhausted: step "
                    + index + " was requested but the script declares " + steps.size());
        }
        stepIndex = index;
        entry = steps.get(index).getLlm();
        newStateConsulted = false;
        stagnationConsulted = false;
        randomConsulted = false;
        provenance = Provenance.NOT_ROUTED;
    }

    /** Fails when the step's declared consultations did not happen. See the class javadoc. */
    void finishStep() {
        if (entry == null || provenance == Provenance.ACCEPTED) {
            return;
        }
        if (entry.routesNewState() && !newStateConsulted) {
            throw unconsumed("new-state");
        }
        if (entry.routesStagnation() && !stagnationConsulted) {
            throw unconsumed("stagnation");
        }
        if (entry.routesRandom() && !randomConsulted) {
            throw unconsumed("random");
        }
    }

    private IllegalStateException unconsumed(String hook) {
        return new IllegalStateException("scenario " + script.getName() + " step " + stepIndex
                + " scripts the " + hook + " hook, but the agent never consulted it — the ladder's"
                + " precondition (actionBufferSize() == 0 && getActions().size() > 2) blocked it");
    }

    /** The provenance of the step just run, for the step's golden record. */
    Provenance getProvenance() {
        return provenance;
    }

    // ---- the overridden router surface -------------------------------------------------------

    @Override
    public boolean shouldRouteNewState(boolean isNewState) {
        newStateConsulted = true;
        return entry != null && entry.routesNewState() && isNewState;
    }

    @Override
    public boolean shouldRouteStagnation(int graphStableCounter, boolean firedThisEpisode) {
        stagnationConsulted = true;
        return entry != null && entry.routesStagnation() && !firedThisEpisode;
    }

    @Override
    public boolean shouldRouteRandom() {
        randomConsulted = true;
        return entry != null && entry.routesRandom();
    }

    @Override
    public ModelAction selectAction(GUITree tree,
                                    State state,
                                    List<ModelAction> actions,
                                    MopData mopData,
                                    List<ApePromptBuilder.ActionHistoryEntry> recentActions,
                                    String mode,
                                    int step) {
        if (entry == null) {
            throw new IllegalStateException("scenario " + script.getName() + " step " + stepIndex
                    + " declares no LLM entry, but the agent routed a " + mode + " consultation");
        }
        switch (entry.getVerdict()) {
            case ACCEPT:
                provenance = Provenance.ACCEPTED;
                return select(actions, entry.getSelector());
            case DECLINE:
                provenance = Provenance.DECLINED;
                return null;
            case TIMEOUT:
                // Same observable as a decline — null, fall through. The difference is provenance,
                // and the golden is where it is recorded (design D3, stated as a known limit).
                provenance = Provenance.TIMEOUT;
                return null;
            default:
                throw new IllegalStateException("unhandled verdict " + entry.getVerdict());
        }
    }

    /** Resolves an ACCEPT entry's selector against the actions the agent offered. */
    private ModelAction select(List<ModelAction> actions, String selector) {
        for (ModelAction action : actions) {
            if (matches(action, selector)) {
                return action;
            }
        }
        throw new IllegalStateException("scenario " + script.getName() + " step " + stepIndex
                + " scripts an accept with selector '" + selector
                + "', which matches none of the " + actions.size() + " offered actions");
    }

    private boolean matches(ModelAction action, String selector) {
        if (selector.startsWith(XPATH_PREFIX)) {
            return action.getTarget() != null
                    && action.getTarget().toXPath().equals(selector.substring(XPATH_PREFIX.length()));
        }
        if (FIRST_TARGETED.equals(selector)) {
            return action.getTarget() != null;
        }
        if (FIRST_UNVISITED_TARGETED.equals(selector)) {
            return action.getTarget() != null && action.isUnvisited();
        }
        throw new IllegalStateException("scenario " + script.getName() + " step " + stepIndex
                + " names an unknown LLM selector: '" + selector + "'");
    }
}

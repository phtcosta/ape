package com.android.commands.monkey.ape.oracle;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.llm.LlmEngine;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.MopData;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * rearch-01 task group 3 — the deterministic LLM (design D3). It replaces, per hook, the breaker
 * gate the stage consults and the engine it calls, so an LLM preset's golden depends on the
 * scenario and nothing else: no SGLang server, no screenshot, no wall clock.
 *
 * <p><b>What it does not replace.</b> The agent-side conjuncts are honored, not ignored: the
 * new-state stage still yields to {@code _isNewState}, the stagnation stage still evaluates the real
 * midpoint predicate against its own episode flag, and the probabilistic stage still draws a coin.
 * That is deliberate — those are agent and stage behavior, and a stub that replaced them would
 * capture a pipeline that does not exist. What this owns is the <i>verdict</i>: whether a hook
 * routes once its own condition has held, and what comes back when it does.
 *
 * <p><b>Where the seam sits, and what that costs.</b> Each hook's conjunct order is
 * precondition &rarr; the stage's own condition &rarr; the breaker gate (INV-DP-10), and the gate is
 * the last of the three. So a hook whose own condition is false never reaches this object at all,
 * and "consulted" now means "the shared precondition passed <b>and</b> the stage's own condition
 * held" — a sharper statement than the pre-decomposition one, which only meant the precondition
 * passed. {@link #finishStep} is written against the sharper meaning; see its comment.
 *
 * <p><b>Why the gate rather than a mode argument.</b> A hook-blind verdict cannot express a script,
 * because the script's per-hook booleans are exactly what selects which hook fires on a step whose
 * agent-side conditions hold for several of them. Handing each stage its own gate makes the verdict
 * hook-aware by construction rather than by carrying an argument production would ignore. Overriding
 * the gate outright is also what satisfies INV-ORA-03: no override reaches the real breaker, whose
 * {@code LlmCircuitBreaker} reads {@code System.currentTimeMillis()}, so no golden can depend on a
 * breaker transition and scripts never model a breaker-open episode.
 *
 * <p><b>Accept returns a member of the offered list, never a synthesized tap.</b> That is not a
 * stylistic choice: an accepted {@code MODEL_LLM_TAP} is resolved against {@code newState}
 * ({@code LlmGate.accept} into {@code SataAgent.resolveSynthesizedTap}), which leaves the JVM. A
 * selector therefore only ever picks from the {@code actions} the agent offered.
 */
public class ScriptedLlm {

    /** The three hooks, labelled as the ladder consults them. */
    static final String NEW_STATE = "new-state";
    static final String STAGNATION = "stagnation";
    static final String RANDOM = "random";

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
    private boolean anyConsulted;
    private Provenance provenance = Provenance.NOT_ROUTED;

    ScriptedLlm(ScenarioScript script) {
        this.script = script;
    }

    // ---- step bookkeeping, driven by OracleDriver -------------------------------------------

    /** Arms the script for one selection step. */
    void beginStep(int index) {
        List<ScenarioScript.Step> steps = script.getSteps();
        if (index >= steps.size()) {
            throw new IllegalStateException("scenario " + script.getName() + " is exhausted: step "
                    + index + " was requested but the script declares " + steps.size());
        }
        stepIndex = index;
        entry = steps.get(index).getLlm();
        anyConsulted = false;
        provenance = Provenance.NOT_ROUTED;
    }

    /**
     * Fails when a step declaring LLM routing never reached the LLM block at all.
     *
     * <p>A step's entry is a claim about what the agent will do, and a claim that does not come true
     * is a scenario bug rather than a skip. The failure this catches is the shared precondition
     * ({@code LlmGate.allows}: an empty action buffer and more than two actions on the state)
     * short-circuiting ahead of every hook, which is what leaves a scripted step silently unrouted.
     *
     * <p><b>The criterion is the LLM block, not the individual hook, and no protection is lost by
     * that.</b> Each stage now evaluates its own condition before the gate, so a declared hook whose
     * condition is false never consults — but such a hook did not route before the decomposition
     * either, because the stub's own predicates ANDed with the same agent-side arguments and
     * answered false. A per-hook form would therefore fail on steps that have always behaved this
     * way. What the precondition blocks is different in kind: it blocks all three at once, so
     * "no hook was consulted on a step that declares one" is exactly the authoring bug, expressed as
     * a property that survives the hooks moving into the stages. And once a hook accepts, the step
     * is decided and the hooks below it are correctly unreached.
     */
    void finishStep() {
        if (entry == null || provenance == Provenance.ACCEPTED) {
            return;
        }
        if ((entry.routesNewState() || entry.routesStagnation() || entry.routesRandom())
                && !anyConsulted) {
            throw new IllegalStateException("scenario " + script.getName() + " step " + stepIndex
                    + " scripts LLM routing, but no hook was consulted — the shared precondition"
                    + " (actionBufferSize() == 0 && getActions().size() > 2) blocked all three");
        }
    }

    /** The provenance of the step just run, for the step's golden record. */
    Provenance getProvenance() {
        return provenance;
    }

    // ---- the substituted seams ---------------------------------------------------------------

    /** The breaker gate the stage owning {@code hook} consults, in place of the real one. */
    BooleanSupplier gateFor(final String hook) {
        return new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return consult(hook);
            }
        };
    }

    /**
     * The engine every LLM stage calls, in place of the real one.
     *
     * <p>One instance rather than three: the verdict is already hook-aware by the time the engine is
     * reached, because only the hook whose gate answered true can call it, and the {@code mode}
     * argument names which one did.
     */
    LlmEngine engine() {
        return new ScriptedEngine();
    }

    /**
     * Whether {@code hook} routes on this step, recording that it was asked.
     *
     * <p>Overridable so an observer can record the order the hooks were consulted in without adding
     * observation to this class — see {@code PreemptionGoldenTest.HookOrderRecorder}.
     */
    protected boolean consult(String hook) {
        anyConsulted = true;
        if (entry == null) {
            return false;
        }
        if (NEW_STATE.equals(hook)) {
            return entry.routesNewState();
        }
        if (STAGNATION.equals(hook)) {
            return entry.routesStagnation();
        }
        if (RANDOM.equals(hook)) {
            return entry.routesRandom();
        }
        throw new IllegalStateException("unknown hook " + hook);
    }

    /** The scripted answer, replacing the ten-step pipeline outright. */
    private final class ScriptedEngine extends LlmEngine {

        ScriptedEngine() {
            // Every step of the real pipeline is replaced below, so none of the units it composes is
            // reachable and none needs to exist.
            super(null, null, null, null, null, null);
        }

        @Override
        public ModelAction selectAction(GUITree tree,
                                        State state,
                                        List<ModelAction> actions,
                                        MopData mopData,
                                        List<ApePromptBuilder.ActionHistoryEntry> recentActions,
                                        String mode) {
            if (entry == null) {
                throw new IllegalStateException("scenario " + script.getName() + " step " + stepIndex
                        + " declares no LLM entry, but the agent routed a " + mode
                        + " consultation");
            }
            switch (entry.getVerdict()) {
                case ACCEPT:
                    provenance = Provenance.ACCEPTED;
                    return select(actions, entry.getSelector());
                case DECLINE:
                    provenance = Provenance.DECLINED;
                    return null;
                case TIMEOUT:
                    // Same observable as a decline — null, fall through. The difference is
                    // provenance, and the golden is where it is recorded (design D3, stated as a
                    // known limit).
                    provenance = Provenance.TIMEOUT;
                    return null;
                default:
                    throw new IllegalStateException("unhandled verdict " + entry.getVerdict());
            }
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

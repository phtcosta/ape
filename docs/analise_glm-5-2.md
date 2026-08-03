# APE-RV Re-architecture — Preliminary Architecture Study

| | |
|---|---|
| **Model** | `z-ai/glm-5.2` (`openrouter/z-ai/glm-5.2`) |
| **Report file** | `docs/analise_glm-5-2.md` |
| **Date** | 2026-08-01 |
| **Commit analysed** | `5dcf225` (branch `master`) |
| **Working language** | English (report) / Portuguese (interaction) |

## Method

I read the full prompt (§0–§10) and the evidence dossier, then launched six
parallel verification/research subagents against the live codebase and the sibling
`rv-android` repo. Their findings were reconciled and are cited below as
`file:line`. Where the dossier's line numbers had drifted, I give the corrected
location and flag the discrepancy. Inference is marked **(inference)**.

Subagent fan-out (all completed):

1. §3.1 fork/upstream frontier — verified against `ape-original/` (upstream clone).
2. §3.2 mode/feature control surface — verified the five hotspots and the scoring pipeline.
3. §3.5 memory & disk — traced the retention graph for `GUITree`.
4. §3.6 + §3.10 + §3.11 — telemetry channels, the Python plugin, the calibration report.
5. §3.7 + §2.3 — resilience + the thesis/paper research context.
6. Prior-art scan — Monkey, DroidBot, Humanoid, Fastbot2, Stoat, ARES, GPTDroid/droidagent, upstream APE.

Verification overturned three dossier claims and surfaced two missed facts that
matter for the design (see §A3, §A4). The dossier is otherwise accurate in substance;
its line citations are stale for `selectNewActionNonnull`, `LlmRouter.selectAction`,
the stop-condition site, and the stagnation-trigger sites.

---

## Part A — Diagnosis

### A1. What APE-RV is today, structurally

The honest layering, top to bottom:

```
rv-experiment (Python, L5)        — campaign lifecycle, task matrix
  └─ rv-platform (Python, L4)     — emulator sessions, TaskStorage (atomic per-task), Command (timeout+kill)
       └─ aperv-tool (Python, L2) — the DE FACTO MODE SYSTEM: 26 arms, 52-key mapping, deploy contract
            └─ [subprocess boundary: adb shell app_process … Monkey]   ← the only seam
                 └─ Monkey (Java, AOSP)            — entry, IActivityController, deadline loop
                      └─ MonkeySourceApe            — refetch loop, guards, fuzzing hook
                           └─ Agent (StatefulAgent base)
                                └─ SataAgent        — selectNewActionNonnull: the precedence ladder
                                     ├─ ScoringPipeline (7 passes)   ← the ONE real abstraction
                                     ├─ LlmRouter (3 hooks, in-process, synchronous)
                                     ├─ MopData / MopScorer (static-analysis JSON)
                                     ├─ ActivityBudgetTracker / UICoverageTracker (LRU)
                                     └─ FormCompletion / ActivityTriggerAction
```

Two facts dominate everything that follows:

- **The mode system lives in Python** (`tool.py:427-659`, 26 arms). The jar has
  *no* representation of "arm" or "mode"; `ApeAgent.createAgent`
  (`ape/agent/ApeAgent.java:68-96`) is a 5-`if` chain over a raw string that
  silently falls through to `SataAgent` and even calls `System.exit(1)` on a
  null replay log. The `--ape <type>` axis (3 values: `sata`/`random`/`replay`)
  and the ~112-flag axis are *orthogonal and unrelated*; MOP, LLM, fuzzing,
  budget, guards, AndroidX are not selectable via `--ape`.
- **The precedence of four independent subsystems (budget → LLM × 3 hooks →
  MOP launcher → component trigger → SATA chain) is encoded as the textual
  order of guarded blocks in one 141-line method**
  (`ape/agent/SataAgent.java:449-589`). Verified: the dossier's sub-line
  citations for this method were stale; the corrected map is `:450` logging,
  `:468` budget gate, `:480/:493/:508` the three LLM hooks (each repeating the
  3-clause precondition `actionBufferSize()==0 && newState.getActions().size()>2
  && _llmRouter != null`), `:522` MOP launcher (`shouldFireLauncher` takes 6
  args, 3 read from `Config` at the call site), `:547` component trigger
  (fire-and-forget side effect, **no return**), `:552-587` the original SATA
  chain — the pattern `resolved = selectNewActionX(); if (resolved != null)
  { logActionSelected(...); return resolved; }` copy-pasted 7 times, `:588`
  `throw new BadStateException`.

The single real abstraction the fork introduced is the `ScoringPass` pipeline
(`ape/agent/scoring/`, 7 passes in fixed order, assembled at
`StatefulAgent.java:208`, applied at `StatefulAgent.java:1631`). It is the only
place where an RV feature surface is structured as polymorphic objects with a
self-gating `isEnabled()` contract — a strict no-op when disabled. Its flaw is
that the dependency-injection seam is *dead*: `ScoringPipeline.fromConfig`'s
`Config cfg` parameter is passed `null` at the sole call site and its javadoc
admits the parameter is "present for signature fidelity"
(`ScoringPipeline.java:47-49`); each pass reads the static `Config` in its own
constructor. So even the one good abstraction is only half-wired.

### A2. Why the structure is where it is — the historical argument

This is a fork that grew by accretion under experimental deadlines, and the
structure has *reasons*. Upstream APE
(`ape-original/src/com/android/commands/monkey/ape/agent/ApeAgent.java:63-96`)
is itself a by-name factory if-chain picking one of `SataAgent` /
`RandomAgent` / `ReplayAgent`; the fork **did not touch the factory**. Every
RV addition (MOP, LLM, budget, menu, trigger, AndroidX) was inserted *inside*
`SataAgent.selectNewActionNonnull` as a new guarded block before the original
ladder, which was preserved verbatim below them. This is the cheapest possible
extension path: no new classes, no factory change, no contract change, no
Python-side change, and the upstream ladder keeps working as the fallback.

The cost is that *every new feature increased the size and the precedence
sensitivity of one method*, and the method now encodes research-relevant
policy (LLM-before-launcher) as statement order with no other representation.
The 2026-07-08 redesign (`docs/20260708_arquitetura_separacao_aperv.md`)
extracted *scoring* into the `ScoringPass` pipeline — the right move for that
subsystem — but left routing, perception, the input chain, object lifetime,
the log, and the process lifecycle in the accreted shape. The
`telemetry-proof-llm-efficacy` change (§3.12, 18 commits immediately before
this study, +2,674/−158 in `src`) illustrates the limit: it added 16 items —
a per-step MOP counterfactual, a `PickChannel` enum, a dead-pair ban, point-to-
rectangle snapping, a constrained ActionType, coverage-dump reordering — and
**`Config.java` is byte-identical**; not one new flag. Behaviour changed; the
control surface did not. Whatever a redesign does about feature management
must make that the natural path, not the heroic one.

### A3. The architectural tensions, traced to drivers

Each tension below is a *causal* finding, not a restatement of §3. Several are
"the same problem wearing different clothes."

**T1 — Two mode systems, no contract (→ D2, D3, D7).** Python owns which 26
named combinations exist; Java owns what each flag means. The seam is an
`ape.properties` text file. Rename or delete an `ape.*` property and `Config`
silently ignores the unknown key, the plugin validates nothing against the
jar, all 83 pytest guards stay green, and the arm silently runs on the jar's
default. The precedent is real: a stale committed jar once made the MOP boost
fire in 0 of 147,153 evaluations (gh71). The kill-switch is duplicated
(`tool.py:264-283`, 18 keys vs `Config.java:343-364`, 27 keys) and the two
copies disagree — and there is no drift test. **This single tension generates
D2 (where does mode live?), D3 (feature management), and half of D7
(provenance): an arm that drifts is an arm whose provenance is a lie.**

**T2 — Precedence as statement order (→ D4).** Four subsystems' priority is
encoded only by the textual order of guarded blocks in one 141-line method
(`SataAgent.java:449-589`). The same 3-clause LLM precondition is repeated
three times by hand; the 7-rung SATA fallback repeats the
`resolved = X(); if (resolved != null) { log; return; }` shape seven times.
The component trigger is a fire-and-forget side effect *inside* the selection
method (`:547`, comment "No return — trigger is a side-effect") — the one
gate that violates the "each block returns or falls through" pattern. This is
the "if/else spaghetti" the owner named, but it is *essential* conditionals
(the fail-open in `treePackageGuard`, the deliberate fall-through in the
activity budget at `:468`) mixed with *accidental* ones (the 7× copy-paste).

**T3 — The control surface is frozen, untyped, and unmanifested (→ D3, D5).**
`Config.java` is 112 `public static [final]` fields loaded once in a static
block (`:32-44`); values are frozen at class-load. There is no schema, no
unknown-key detection (`getInteger/getLong/getDouble` swallow
`NumberFormatException` with empty `catch {}` at `:453-454/:465-466/:477-478`),
no manifest — only a hand-maintained string-literal registry
(`rvForcedOffValues :343-364`, `rvUnsetKeys :366-370`, `rvExemptReasons
:372-398`). Five fields were demoted from `final` *purely to be testable*
(`:149/:151/:153/:165/:245`), each confessing "Non-final: toggled by unit
tests at runtime." Testability was achieved by working around the design, not
through it. Global mutable state in `AndroidDevice` (7 public static handles,
`AndroidDevice.java:61-77`), `NameManager`, `StringCache`, `GUITreeBuilder`,
`GUITree`, and `RandomHelper` is not resettable between tests.

**T4 — Unbounded retention with no pressure feedback (→ D6).** Verified: no
`GUITree` is ever removed from its canonical owners (`State.treeHistory`
`State.java:56`, `Graph.treeTransitionHistory :118`, `Graph.entryGUITrees
:109`, `Model.actionHistory :137`) during normal exploration. The dossier
missed that `ModelAction.resolvedTree`/`resolvedGUITreeAction`
(`ModelAction.java:83-87,231-232`) is an *independent* retainer: even if
`actionHistory` were bounded, `State → actions → ModelAction.resolvedTree`
would keep one `GUITree` per visited action alive forever. The DOM/A11y
payload *is* freed per-step for freshly captured trees (`GUITree.releaseLoadedData`
via `StatefulAgent.notifyActionConsumed`), but the `GUITreeNode` object graph
survives (`GUITree.releaseData :340-344`). The only real eviction in the
codebase is `UICoverageTracker.stateData` (LRU 2000, `:59-69`). `maxStatesPerActivity`/
`maxGUITreesPerState` are *refinement gates*, not memory caps — they return
early from `NamingFactory` to stop new abstraction levels but free nothing.
`printMemoryUsage` logs every step but never acts; the only
`catch (OutOfMemoryError)` is `MopData.java:328` (JSON parse), walled off from
the exploration loop. **T4 is the same root cause as T6 (below): both are
"one live model, no eviction, no checkpoint."**

**T5 — Observability is joinable but fragile, schemaless, and lossy (→ D7).**
The good news: the `step=` join key across `[APE-STEP]`/`[APE-OUTCOME]`/
`[APE-LLM-TEL]`, the `clock=` epoch-millis wall clock, and the `decision_source`
field are what made the 2026-07-24 calibration report (§3.11) provable rather
than assertable. The bad news: 54 free-form `[APE-RV]` sites, space-separated
`key=value` with **no escaping** (activity/state/action are `toString()` of
objects that can contain spaces; only `text="…"` is quoted, at
`LlmRouter.java:595` not `:522` as the dossier said), no run id, no file sink,
stdout-only — **losing adb loses the log, and a SIGKILL skips the `finally`
in `Monkey.run`**. Traces carry NUL bytes (880 tasks → ~3.5 GB; §3.11). And
`[APE-STEP]`/`[APE-OUTCOME]` are forced off under `apePureMode` (`Config.java:346`)
— the baseline arm produces **zero** mechanism telemetry by construction
(INV-ARCH-01). The most valuable phase-3 question — *what fraction of newly-
found violations is attributable to steps whose `decision_source` was MOP/
Coverage/LLM?* — is **not answerable today**, because RVSEC violation events
land in the per-task `.logcat` while APE telemetry lands in the `.trace`, and
**no shared key ties a violation back to the step that caused it**. APE has
zero knowledge of violations (verified: grep for `RVSEC`/`violation` in
`ape/src/main/java` returns only headers) — the violation stream is produced
by JavaMOP monitors inside the instrumented app and parsed by rv-android. **T5
is D7, and it is thesis-blocking (§2.3): the central hypothesis is causal
(guidance causes more violations), but the measurement is per-run (aggregate).**

**T6 — One live model, no checkpoint, no resume (→ D8).** `sataModel.obj`
(Java serialisation of the whole `Model`) is written *only at teardown*
(`StatefulAgent.saveGraph`, `:1855-1902`). A SIGKILL persists nothing. Resume
after process death is broken in four ways: (i) teardown-only write; (ii)
`readGraph` (`Graph.java:1166-1174`) returns a `Graph` while `saveGraph`
writes a `Model` — type mismatch (INV-EXPL-03); (iii) load failure only logs
"Fail to load graph from …" and silently starts from scratch; (iv) no
shutdown hook anywhere. The stop condition is checked **once per loop
iteration, non-preemptively** — and the dossier's citation was wrong: it is
`Monkey.java:1298-1301`, not `:1560-1582` (which is CLI parsing). A blocking
LLM call (`llmTimeoutMs=15000`) overshoots the deadline. The Python layer
already owns crash-safe resume at *task* granularity (`TaskStorage` rewrites
`tasks.json` atomically after every task; max loss is one in-flight task), and
timeout-as-success is a project-wide law. **So D8 is not "add resilience"; it
is "decide the division of labour" — and the critical defect is that a
truncated run is currently recorded as a completed task**
(`tool.py:1121-1125`: non-zero exit only debug-logged). A run that dies at
second 120 of 600 is lost as a *sample* while counted as a *success*.

**T7 — Baseline fidelity is a thesis-critical property, and it leaks (→ D1).**
Verified against `ape-original/`: `apePureMode=true` produces a *near*-pure
APE, not APE. 14 distinct code-level changes modify behaviour with no `Config`
gate (the dossier's list of ~12 is an undercount): seedable `Random`
(`RandomHelper.java:25-32`), the `containsNamelet >= 0` fix, the
`state.getGUITrees()` refinement fix, the `indexOfName` normalisation, two
graph visit-count fixes, the action-history `RuntimeException` swallow, the
`StringCache` empty-list fallback, two fuzz/pinch fixes, the isolated-teardown
restructure, three new `ActionType` enum members (permanently shifting
ordinals), `isEphemeral()` consulted at five ungated core sites, and
`DecisionSource`+`PickChannel`+6 boost fields as permanent `ModelAction`
state. Verification added one the dossier missed: `StringCache.nextString`
still draws from `ThreadLocalRandom` (`StringCache.java:118`) even with a
fixed seed — a reproducibility hole in the seedable-random claim itself.
**And one collateral divergence the dossier missed**: `apePureMode` forces
`activityStableRestartThreshold` to `Integer.MAX_VALUE` (`Config.java:360-362`)
— upstream-inert, but it means an `ape_pure` arm silently *disables
activity-stagnation restart*, a behavioural divergence from the phase-2 APE
that won. An `ape_pure` arm is defensible as an "RV-feature-off fork baseline,"
**not** as a reproduction of upstream APE. For true parity, the only honest
options are (a) run the actual `ape-original` jar, or (b) maintain a revert
patch over the 14 items.

### A4. What the current design gets right — must not be lost

- **Step-isolated teardown** (INV-EXPL-16/29): `MonkeySourceApe.safeStep`
  (`:226`, 6 steps) and `StatefulAgent.safeStep` (`:1793`, 8 steps) wrap each
  teardown phase in its own try/catch; the `finally` in `Monkey.run`
  (`:775-799`) may not throw. This is the best-engineered part of the system.
- **Fail-fast on MOP load** (`openspec/specs/mop-guidance/spec.md:506`): a
  failed load with `mopDataPath` set aborts via `StopTestingException` so an
  arm is never silently mislabelled. The architecture must preserve this.
- **The `step=`/`clock=` join design** (`StatefulAgent.java:117-124,1485-1496`)
  with an explicit buffer because the action selected at step N only yields
  its transition later. This is what made §3.11 provable.
- **The no-op-when-disabled pass contract**: a disabled `ScoringPass` is a
  strict no-op; `adjustActionsByGUITree` (`:1571-1632`) is byte-identical to
  upstream for the first 55 lines, with exactly one RV line at `:1631`.
- **Arm-explicitness pytest guards** (INV-APV-14/16/17/26): every non-exempt
  arm sets every arm-defining key explicitly. The guard validates a Python
  constant against itself, but the *discipline* it enforces is real.
- **The `UICoverageTracker` LRU pattern** (`:59-69`, `removeEldestEntry` +
  rollup) is the one bounded-eviction template in the codebase and should be
  generalised.
- **`LlmCircuitBreaker`** (`LlmCircuitBreaker.java:36-105`): 3 failures →
  trip, 60 s recovery, HALF_OPEN probe — the field's mature answer to the
  brute-retry (1000×) pattern in droidagent.

---

## Part B — Design space

For each major axis I enumerate the plausible options with trade-offs,
including ones I will not choose, and reference how comparable tools solve
the same problem (prior-art subagent findings).

### B1 — Where does "mode" live?

| Option | Pro | Con | Prior art |
|---|---|---|---|
| **In the jar (Java enum/registry)** | Single source of truth; compiler-enforced; drift impossible | Cross-repo change for every new arm; Python still needs the key mapping | Upstream APE `ApeAgent.createAgent` (by-name if-chain) |
| **In the Python plugin (status quo)** | No jar change to add an arm; experiment-layer flexibility | Drift silent; no contract; 83 guards validate a constant against itself | DroidBot `InputManager.get_input_policy` (by-name if-chain) |
| **Shared declarative artifact (TOML/JSON) both sides read** | Drift becomes loud if either side validates against it; arms diff-able; checksummable | One more file + a parser on each side; format must survive the subprocess boundary (files, not objects) | **(none in the survey — this would be a contribution)** |
| **Generated from a single source** (code-gen the Python mapping from Java annotations, or vice versa) | Zero drift by construction; one source of truth | Build-time coupling; tooling investment | Fastbot2 (single `--agent` token, build-time) |

My judgement: **the shared declarative artifact is the right answer for this
project** because the subprocess boundary is "files and command lines, never
objects" — a TOML/JSON manifest *is* a file. Code-gen is elegant but over-built
for one maintainer (constraint 7).

### B2 — How are features represented?

| Option | Pro | Con | Prior art |
|---|---|---|---|
| **Flags (status quo)** | Cheapest; no new concepts | 112 frozen fields; no manifest; no unknown-key detection; no dependency declaration | Monkey `--pct-*` (flat weights) |
| **Feature objects with declared deps/conflicts/ordering** | Dependencies (F10 needs F2+MOP), conflicts, ordering first-class; testable in isolation | New concepts; registry; indirection | `ScoringPass` (already half-wired here) |
| **Policy/Strategy objects (one per mode)** | Clean dispatch; each mode owns its loop | DroidBot/APE/droidagent all converged here and all hit the same accretion wall inside one policy | DroidBot `UtgBasedInputPolicy`, droidagent collaborator-swap |
| **Probability distribution (weights over actions)** | No precedence ladder; "mode" = a weight vector; sampling is one draw | Loses hard precedence (LLM-before-launcher is a policy, not a weight); needs careful weight calibration | Stoat (MCMC-tuned transition probs), Fastbot2 (RL reward) |
| **Phase-machine (OBSERVE→PLAN→ROUTE→ACT→RECORD)** | Separates perceive/decide/route; LLM becomes a phase, not a hook | New control-flow shape; 5 phases per step | droidagent (PLAN/ACT/OBSERVE/REFLECT) |
| **External decision service over a contract** | Cleanest separation; LLM/MOP lifted out of the agent; Python could be the service | Per-step serialisation cost; GUITree is large; the naming/refinement core must stay in-process | Fastbot2-native (C++ reward over JSON), Humanoid (XML-RPC), ARES/GPTDroid (LLM per-step) |

My judgement: **no single option is right for all features.** Features that
*score* are already objects (`ScoringPass`); features that *perceive*
(`GUITreeBuilder`, guards) want to stay in the device loop; features that
*route* (LLM, launcher) are the accretion epicentre and want either a phase-
machine or a weighted sampler. The honest answer is a *hybrid* — and the
question is which hybrid.

### B3 — How is the decision dispatched?

This is the heart of T2. Options (overlapping with B2):
- **Ordered guarded blocks (status quo)** — one method, 9 blocks, precedence
  by textual order.
- **Chain of responsibility** — each subsystem is a handler that either
  returns an action or calls `next`; precedence is the chain order, explicit
  and modifiable.
- **Weighted multinomial sampler** — collapse the ladder into one weighted
  draw over candidate actions; boosts become weights; precedence becomes
  "weight magnitude." **This is what APE-RV already does for the SATA roulette
  (`selectNewActionEpsilonGreedyRandomly`)** — the question is whether to
  extend it *up* to cover LLM/launcher/budget too.
- **Phase-machine** — explicit phases, each a port; LLM is a phase.
- **Decision service** — out-of-process.

### B4 — How is state owned and bounded?

| Option | Pro | Con |
|---|---|---|
| **One live object graph, no eviction (status quo)** | Simple; replay/refinement have full history | OOM; the §3.5 retainers all unbounded |
| **LRU on canonical owners** (generalise `UICoverageTracker`) | Bounded; proven pattern | Lose refinement history; need to handle `namingToGUITreeNodeCache` (never touched by `release()`) |
| **Append-only on-disk log + in-memory index** | Bounded memory; crash-safe; replay reads disk | Per-step I/O cost (measured 0.037–0.052 pp cov_mop/step — D6) |
| **Compact indexed structure** (ints + offsets, not objects) | Dramatic memory reduction; serialisable | Large rewrite of `Graph`/`State`/`Model`; touches the naming core (constraint 5) |

### B5 — How is observability produced?

| Option | Pro | Con |
|---|---|---|
| **Status quo** (stdout, `key=value`, no schema) | Zero deps; works through logcat | Lossy (NUL bytes, truncation), unparseable when values contain spaces, no run id |
| **Typed events, one JSON line per event, versioned schema** | Machine-readable; joinable; loss-resistant; schema-evolvable | Per-step cost; JSON on Dalvik is not free |
| **Append-only trace file on device + stdout mirror** | Survives adb drop; survives SIGKILL if fsynced | Per-step fsync cost; one more file to manage |
| **Structured records emitted directly** (analysis-ready CSV per step) | No bespoke parser (§3.11 needed one) | More bytes/step; format coupling to analysis |

The load-bearing properties from §3.11 (the acceptance test): (1) per-decision
`step=` join key across step/outcome/LLM-tel; (2) `clock=` epoch-millis wall
clock; (3) `decision_source=` provenance; (4) `mode=` on LLM-tel; (5)
`result=/reason=/repair=/tokens/time_ms` on LLM-tel; (6) per-task Summary
reconciliation; (7) action-type counts and yields; (8) the pure arm emits zero
mechanism telemetry. Any redesign must preserve (1)–(8).

### B6 — How is failure contained?

| Option | Pro | Con |
|---|---|---|
| **In-process recovery (status quo + OOM catch)** | No external change | A restarted run is still one sample?; OOM on exhausted heap |
| **External supervision (Python restarts the jar)** | Jar stays simple; Python already owns task-level resume | Per-run restart loses the model (no checkpoint); does the clock keep running? |
| **Task-level retry (Python re-runs the task)** | Cleanest scientifically (a fresh sample) | Wastes wall-clock; needs the task-storage to mark "truncated" not "completed" (the T6 defect) |
| **Periodic checkpoint in the jar** | Resume after SIGKILL | `sataModel.obj` is whole-`Model` Java serialisation — expensive; per-step cost |

### B7 — Where is the experiment matrix defined?

| Option | Pro | Con |
|---|---|---|
| **Python hand-written arms (status quo)** | Experiment-layer flexibility | INV-APV-14 validates a constant against itself; new jar flag silently inherited |
| **Generated arm matrix from a feature manifest** | Full factorial / fractional factorial becomes mechanical; provenance stamped per arm | 2^10 arms is intractable (D9); needs a screening design |
| **Within-run attribution (status quo + counterfactual)** | One run answers many questions; `decision_source` already per-step | Attribution ≠ causation; the counterfactual is 1-step myopic by contract (§3.12) |

---

## Part C — Three candidate architectures

The three differ in their *organising principle*: (C1) dissolves the ladder
into a weighted draw; (C2) restructures the step into explicit phases with
collaborator ports; (C3) lifts the decision out of the jar behind an explicit
contract. None is "the current design with a registry."

### C1 — "Weighted-Policy Sampler" (`WPS`)

**Organising principle (one paragraph):** *There is no precedence ladder.*
The 9 guarded blocks of `selectNewActionNonnull` collapse into a single
weighted multinomial draw over typed `ActionCandidate`s. Each subsystem
(budget, LLM, launcher, SATA channels, MOP/coverage/frontier boosts) is a
`WeightSource` that *contributes weights* to the candidate set, not a guarded
block that *pre-empts*. "Mode" is a named preset over the weight vector. The
LLM is still synchronous by default but becomes *one weight source among
others* — so when it is circuit-broken its weight collapses to zero and the
sampler draws from the rest, with no special-case fallback code.

**Module/package structure (Java-shaped sketch):**

```java
// ape/policy/  (new package; replaces the precedence ladder)
package ape.policy;

/** A candidate action the sampler can draw. */
public interface ActionCandidate {
    ModelAction asModelAction(SelectionContext ctx);
    DecisionSource decisionSource();   // for [APE-STEP] provenance
    PickChannel pickChannel();
}

/** Contributes weights to the candidate set for the current state. */
public interface WeightSource {
    String name();                      // "mop_widget", "llm_new_state", "sata_buffer", ...
    boolean isEnabled(SelectionContext ctx);   // declared dependencies evaluated here
    void contribute(SelectionContext ctx, WeightedSet<ActionCandidate> out);
    int priority();                     // only for *tie-breaking equal-weight draws*, not pre-emption
}

/** The sampler: one draw per step. */
public final class ActionSampler {
    private final List<WeightSource> sources;     // ordered by priority only for tie-break
    public ActionSampler(List<WeightSource> sources) { ... }
    public ActionCandidate sample(SelectionContext ctx) {
        var out = new WeightedSet<ActionCandidate>();
        for (var s : sources) if (s.isEnabled(ctx)) s.contribute(ctx, out);
        return out.sample(ctx.random());           // one multinomial draw
    }
}
```

The `ScoringPass` pipeline is preserved as the *weight-calibration* phase
that runs *before* sampling: passes adjust the weights `WeightSource`s will
propose. The DI seam that is dead today (`Config cfg` passed `null`) becomes
live: each `WeightSource` receives its `FeatureSet` (a frozen record of
enabled features + parameters) at construction, not the global `Config`.

**How the five modes (and a sixth) are expressed:**

```toml
# arms/ape.toml          — upstream APE; no WeightSource enabled except sata_*
# arms/aperv.toml        — sata_* + budget + coverage + form + guards; no mop, no llm
# arms/mop.toml          — aperv + mop_widget + menu_gateway + wtg + frontier + mop_frontier
# arms/llm.toml          — aperv + llm_new_state (weight w_ns) + llm_random (weight w_r)
# arms/llm_mop.toml      — aperv + all mop sources + all llm sources
# arms/mop_frontier_only.toml  — aperv + frontier (generic) + mop_frontier; no widget mop
```

"Mode" survives only as a *named preset over the weight vector* — answering
the §2.2 question "is mode the right primitive?" with **no, a mode is a
preset over features; the primitive is the feature set**.

**Answers to the §2.2 open questions, in WPS terms:**
- *Does `mop` include `aperv` features?* **Yes.** `mop` = `aperv` + MOP
  sources. The defensible default is that MOP is *added* to the best plain
  explorer, not to bare APE — because the phase-2 winner was already a
  full-featured APE, and the thesis compares against that. A separate
  `ape+mop_only` arm is cheap to declare for ablation.
- *Widget-level vs frontier-level?* **Orthogonal feature axes, both on by
  default in `mop`.** They contribute weights independently; the sampler
  combines them. A `mop_frontier_only` preset (widget mop weight = 0)
  is one line. Arm count stays low (5 base + a few ablation presets);
  statistical power is unaffected because the axes are *additive in weights*,
  not cross-product arms.
- *LLM fallback?* **Implicit.** When the circuit breaker is open, the LLM
  `WeightSource.isEnabled()` returns false and its weight is zero; the
  sampler draws from the rest. The "fallback base" (ape/aperv/mop) is just
  *which other sources are enabled in the same preset* — so the fallback is a
  mode parameter, but it is encoded as *co-enabled sources*, not a separate
  dimension. This dissolves the "two-dimensional mode" worry: there is one
  dimension (the feature set), and the LLM's fallback is whatever else is in
  the set.
- *Is "mode" the right primitive?* **No.** The primitive is the feature set
  with declared dependencies; modes are named presets.

**Driver satisfaction:**
- **D1 (baseline fidelity)**: `ape.toml` enables only `sata_*` sources. But
  WPS does *not* fix the 14 ungated changes of T7 — it makes them *visible*
  (the preset declares what is enabled, not what the code does). For true
  parity, pair WPS with a `--ape-pure` build flag that selects a strict-revert
  source set (see C1 weakness).
- **D2 (mode first-class)**: the manifest is the single source of truth; both
  sides validate against it; `--ape mop` at the CLI reads `arms/mop.toml`
  instead of silently falling through.
- **D3 (feature management)**: `WeightSource` declares `isEnabled` (deps),
  `contribute` (weights), and `priority` (tie-break only). F10 (menu-gateway)
  declares `deps = {F2, MOP}`; F12 (frontier) declares `deps = {WTG}` and
  silently no-ops without MOP (as today). Ordering is *not* load-bearing
  (only tie-break), so the LLM-before-launcher precedence becomes a *weight
  magnitude* policy, auditable in the manifest.
- **D4 (kill the spaghetti)**: `selectNewActionNonnull` is *deleted*; it
  becomes 3 lines (`var c = sampler.sample(ctx); logActionSelected(c);
  return c.asModelAction(ctx)`). The 7× copy-paste SATA chain becomes 7
  `WeightSource`s. The component-trigger side-effect becomes a `WeightSource`
  that contributes a 0-weight candidate but records an intent — making the
  fire-and-forget pattern explicit instead of hidden.
- **D5 (testability)**: `ActionSampler` is a pure function of
  `SelectionContext` + a seeded `Random`; testable on a plain JVM with no
  Android. `WeightSource`s are tested in isolation with a fake context. The
  `Config` freeze is localised: a `FeatureSet` record is built once at
  startup from the manifest + properties, not read at 200 scattered sites.
- **D6 (memory)**: WPS does not directly address retention; it must be
  paired with the §C-shared memory work (see below). But it *enables* a
  bounded history: because `WeightSource` only needs the current
  `SelectionContext` (not the full `ModelAction` history), the action
  history can become an append-only log + in-memory index without changing
  the decision logic.
- **D7 (traceability)**: every draw emits `[APE-STEP]` with `decision_source`
  = the `WeightSource` that won the draw, `pick_channel` = the candidate, and
  the full weight vector. The pure arm emits zero telemetry (preserve
  INV-ARCH-01) by a manifest flag `emit_step_telemetry = false`.
- **D8 (resilience)**: WPS is neutral; the §C-shared resilience work applies.
- **D9 (ablation)**: a feature manifest → arm matrix generator is natural:
  each `WeightSource` has an `on: bool` field; fractional factorial designs
  over the ~10 binary features become a script that emits TOML files.
  Within-run attribution is *native*: `decision_source` is the draw winner,
  and the weight vector is logged, so a post-hoc "what if the LLM weight were
  zero" rerun is a parsing exercise, not a new campaign.
- **D10 (simplicity)**: new concepts: `ActionCandidate`, `WeightSource`,
  `ActionSampler`, `FeatureSet`, manifest TOML. Net LOC delta: **−** (the
  141-line method + 7 copy-pasted rungs + 3 LLM preconditions collapse into
  ~7 `WeightSource` classes of ~30 LOC each + a 50-line sampler). The
  `ScoringPass` pipeline is reused, not replaced.

**Migration path:** (1) Introduce `FeatureSet` as a thin wrapper over `Config`
(no behaviour change); (2) port the 7 SATA rungs to `WeightSource`s with
weights that reproduce the current precedence exactly (verified by a
differential test on recorded traces); (3) port budget/LLM/launcher as
sources; (4) flip the call site; (5) deprecate `selectNewActionNonnull`.
Cross-repo: add the TOML manifest reader to `aperv-tool`; generate
`ape.properties` from the manifest. Each stage keeps experiments runnable.

**Honest weakness:**
- **Hard precedence is now soft.** The LLM-before-launcher precedence is a
  research-relevant policy (§3.4). WPS encodes it as a weight magnitude, which
  is *auditable* but *not enforced* — a misconfigured weight could let the
  launcher fire when the LLM should have. If the thesis needs to *prove* the
  precedence was exactly as declared, WPS needs a `preempt_group` field (a
  small concession to the ladder).
- **The sampler hides per-channel yield.** §3.11 measured "LLM-decided
  actions yield a new state 31.9%" — that measurement depends on knowing
  which channel *won the draw*. WPS preserves this (`decision_source` is the
  winner), but a weighted sampler makes the "would the LLM have won if
  enabled" counterfactual *cheaper and more credible* than today's 1-step
  myopic replay — which is good for D9 but means the counterfactual is now a
  first-class metric, with all the validation burden that implies.
- **Does not fix T7 (baseline fidelity) by itself.** A weighted sampler over
  the fork's features is still the fork, not upstream APE. The `ape` preset
  is honest only if paired with a revert path for the 14 ungated changes.
- **Weight calibration is a new research variable.** §3.11 showed the LLM
  costs ~0.95 steps/call; WPS makes the LLM's weight a knob, which means a
  *calibration campaign* to set it. That is more work, but it is work the
  thesis needs to do anyway.
- **When I would NOT choose WPS:** if the owner wants the LLM to *always*
  take precedence when it fires (a hard policy, not a soft one), WPS is the
  wrong shape — use C2 (phase-machine) where the LLM phase pre-empts.

**Complexity budget:** 4 new concepts (`ActionCandidate`, `WeightSource`,
`ActionSampler`, `FeatureSet`), 1 new file format (TOML manifest), ~7 new
small classes, −141 LOC in `SataAgent`, −~60 LOC of copy-paste. A newcomer
learns: "each subsystem contributes weights; the sampler draws; the manifest
says which subsystems are on."

---

### C2 — "Phase-Machine with Collaborator Ports" (`PMC`)

**Organising principle (one paragraph):** *The step is explicit phases.* The
loop becomes `OBSERVE → PLAN → ROUTE → ACT → RECORD`, each phase a port with
a default implementation and swappable collaborators. MOP, LLM, coverage,
form-completion are *collaborators* plugged into `PLAN`/`ROUTE`, not guarded
blocks inside one method. Modes are *phase-configurations* (which
collaborator is plugged into each port). The LLM is a `RouteCollaborator`
that pre-empts the default router when it fires — preserving hard precedence
without a ladder — and the SATA chain lives entirely inside the default
`Router`, untouched.

**Module/package structure:**

```java
// ape/phase/  (new package)
package ape.phase;

public enum Phase { OBSERVE, PLAN, ROUTE, ACT, RECORD }

public interface PhasePort<C> {
    Phase phase();
    C run(StepContext ctx, C in);   // in is the prior phase's output
}

// The orchestrator is 20 lines:
public final class StepOrchestrator {
    private final PhasePort<Observation> observer;
    private final PhasePort<Plan> planner;
    private final PhasePort<Route> router;
    private final PhasePort<Effect> actor;
    private final PhasePort<Record> recorder;
    public Effect step(StepContext ctx) {
        var o = observer.run(ctx, null);
        var p = planner.run(ctx, o);
        var r = router.run(ctx, p);     // router may pre-empt: if r.isResolved(), skip default
        var a = actor.run(ctx, r);
        recorder.run(ctx, a);
        return a.effect();
    }
}

// ape/phase/collaborator/
public interface RouteCollaborator {
    boolean wantsToFire(Plan p, StepContext ctx);   // the 3-clause precondition lives HERE
    Route fire(Plan p, StepContext ctx);            // returns a resolved Route
    int precedence();                               // explicit, not statement order
}

// ape/phase/route/  (the default router — owns the SATA chain unmolested)
public final class SataRouter implements PhasePort<Route> {
    private final List<RouteCollaborator> collaborators;  // [LLM, Launcher, ComponentTrigger]
    public Route run(StepContext ctx, Plan p) {
        for (var c : collaborators)                       // precedence explicit, ordered once
            if (c.wantsToFire(p, ctx)) return c.fire(p, ctx);
        return sataChain(ctx, p);                         // the original 7 rungs, untouched
    }
}
```

**How the five modes are expressed:**

```toml
# arms/ape.toml       — observer=default, planner=default, router=SataRouter (no collaborators), actor=default
# arms/aperv.toml     — planner=+Budget+Coverage, router=SataRouter (no collaborators), actor=+FormCompletion
# arms/mop.toml       — planner=+MopWidget+MopFrontier+MenuGateway, router=SataRouter+[Launcher], actor=+FormCompletion
# arms/llm.toml       — router=SataRouter+[LlmCollaborator(new_state,stagnation,random)]
# arms/llm_mop.toml   — planner=+all mop, router=SataRouter+[LlmCollaborator, Launcher]
```

A sixth (`llm_only`, ARES-style) is `router=LlmCollaborator-only` (no SataRouter
fallback) — a mode that is *currently impossible* in APE-RV but is exactly the
ARES/GPTDroid prior-art shape (prior-art subagent: "ARES is the existence proof
that the LLM-only organising principle is viable").

**Answers to the §2.2 open questions, in PMC terms:**
- *Does `mop` include `aperv` features?* Yes — `mop` plugs MOP collaborators
  into the same phase-config that `aperv` uses. The phase-config *is* the
  feature set.
- *Widget vs frontier?* Two `PlannerCollaborator`s (`MopWidget`, `MopFrontier`),
  independently toggleable. The `mop` preset enables both; `mop_frontier_only`
  disables `MopWidget`. Same cost as WPS.
- *LLM fallback?* The `RouteCollaborator.precedence()` makes the fallback
  *explicit and hard*: if `LlmCollaborator.wantsToFire` returns true, it
  pre-empts; if false (circuit-broken, declined), the `SataRouter` runs. The
  "fallback base" is the `SataRouter`'s own config — so `llm` on top of `ape`
  vs `aperv` vs `mop` is *which collaborators the SataRouter was configured
  with*, i.e. a two-dimensional choice made explicit in the manifest. This is
  the §2.2 "base explorer × guidance stack" framing, *embraced*.
- *Is "mode" the right primitive?* **Partly.** The primitive is the
  *phase-config* (which collaborator is plugged into each port); modes are
  named presets over phase-configs. "Mode" survives but as a derived name,
  not a first-class concept.

**Driver satisfaction:**
- **D1**: same as WPS — PMC makes the feature set visible but does not fix the
  14 ungated changes.
- **D2**: the manifest is the source of truth; `--ape mop` loads `mop.toml`.
- **D3**: collaborators declare `wantsToFire` (deps + preconditions),
  `fire`, `precedence`. F10's `deps = {F2, MOP}` is a `wantsToFire`
  precondition. Ordering *is* load-bearing here (the LLM pre-empts the
  launcher by `precedence()`) — but it is *declared* in one place, not
  spread across `SataAgent` and `LlmRouter`.
- **D4**: `selectNewActionNonnull` is *deleted*; the 9 blocks become 3
  `RouteCollaborator`s + the `SataRouter` (which keeps the 7 rungs intact).
  The 3-clause LLM precondition moves into `LlmCollaborator.wantsToFire`,
  *de-duplicated* (the dossier claimed it was duplicated at `SataAgent:436`/
  `LlmRouter:225`; verification showed it was already de-duplicated into
  `stagnationMidpointReached` — PMC makes this the rule, not the exception).
- **D5**: each phase is testable with a fake `StepContext`; the `SataRouter`
  is testable with a fake `Plan`; collaborators are tested in isolation.
  The `AndroidDevice` seam is at `OBSERVE` (the only phase that touches
  `android.*`).
- **D6**: neutral; paired with the shared memory work.
- **D7**: `RECORD` is a phase — the natural home for a typed event emitter.
  Each phase emits a phase-event with `step=`; the orchestrator emits a
  step-summary. The join key is the step counter, preserved.
- **D8**: neutral; paired with the shared resilience work. But `RECORD` as
  a phase makes "checkpoint every N steps" a collaborator, not a teardown hack.
- **D9**: collaborators are the ablation units — disabling a collaborator is
  an arm; the phase-config is the feature set. Within-run attribution is
  `which collaborator fired` = `decision_source`.
- **D10**: new concepts: `Phase`, `PhasePort`, `StepOrchestrator`,
  `RouteCollaborator`, `PlannerCollaborator`. Net LOC: the orchestrator is
  ~20 lines; each collaborator is ~30-50; the `SataRouter` is the existing
  7 rungs moved verbatim. *Subtraction:* `selectNewActionNonnull` (−141), the
  3 LLM precondition duplications (−~20), the `shouldFireLauncher` 6-arg call
  (−~15).

**Migration path:** (1) Extract the `SataRouter` from the current
`selectNewActionNonnull` (the 7 SATA rungs, unchanged); (2) make the 3 LLM
hooks + launcher + component-trigger into `RouteCollaborator`s with
`wantsToFire` exactly reproducing current preconditions; (3) introduce the
`StepOrchestrator` and flip the call site; (4) move perception/guards into
`OBSERVE`, telemetry into `RECORD`. Each stage keeps experiments runnable.

**Honest weakness:**
- **5 phases per step is more ceremony than 9 blocks.** For a tool whose
  wall-clock budget is fixed (§3.11: 0.037–0.052 pp cov_mop/step), the
  per-step overhead of 5 port-calls + 5 phase-events must be measured. If
  each phase emits a telemetry line, that is 5× the `[APE-STEP]` volume.
- **The `SataRouter` keeps the 7 copy-pasted rungs.** PMC does not collapse
  them (WPS would). If the rungs are the real spaghetti, PMC only moves it.
- **Phase-machines can grow phase-local state** that is hard to reason about
  (droidagent's `mode` field is a global). PMC must keep phase state inside
  `StepContext`, not in fields.
- **When I would NOT choose PMC:** if the owner's primary complaint is the
  copy-pasted SATA rungs (not the pre-emption), PMC is half a fix — it
  *extracts* pre-emption but *preserves* the rungs. WPS dissolves both.

**Complexity budget:** 5 new concepts, ~6-8 collaborator classes, 1
orchestrator, 1 manifest format. LOC delta roughly neutral (−141 in
`SataAgent`, +~250 in new classes). A newcomer learns: "the step is 5 phases;
each phase has a default and optional collaborators; the manifest says which
collaborators are plugged in."

---

### C3 — "Externalised Decision Contract + Thin Driver" (`EDC`)

**Organising principle (one paragraph):** *Split the jar in two.* A **Thin
Driver** (Java, on-device, in `app_process`) owns only: accessibility
capture, event injection, the model graph, naming/refinement, and
persistence. A **Decision Service** owns: scoring, MOP, LLM, policy. They
communicate over a typed contract carried by a Unix-domain socket or
stdin/stdout JSON-lines — *one request per step* (a compact `DecisionRequest`
with a screen digest + candidate actions + MOP flags, **not** the full
`GUITree`), *one response per step* (a `DecisionResponse` with the chosen
action + provenance). The Decision Service can be the *same jar* (a thread)
or a *separate process* (Python, or a second `app_process`) — the contract
makes the boundary movable. This is the Fastbot2-native organising principle
(prior-art subagent: "the cleanest separation of decision logic from device
driving in the survey").

**Module/package structure:**

```java
// ape/driver/  (the Thin Driver — what's left after extraction)
package ape.driver;
public final class ThinDriver {   // ~300 LOC; what MonkeySourceApe + Agent minus selection becomes
    void step() {
        var tree = observe();                  // GUITreeBuilder, guards
        var candidates = enumerateActions(tree);
        var req = new DecisionRequest(tree.digest(), candidates, mopFlags(tree), stepCtx());
        var resp = decisionService.decide(req);     // blocking call over the contract
        var effect = inject(resp.action());
        model.recordTransition(tree, resp.action(), effect, resp.provenance());
        recorder.emit(resp.provenance(), effect);   // [APE-STEP] with decision_source from the service
    }
}

// ape/contract/  (shared by both sides; serialisable)
public final class DecisionRequest {
    long step; long clock; ScreenDigest screen; List<CandidateId> candidates;
    MopFlags mop; TelemetryHint hint;   // no GUITree, no Naming — just what the decider needs
}
public final class DecisionResponse {
    CandidateId chosen; DecisionSource source; PickChannel channel;
    Map<String,Object> provenance;      // weights, prompt-id, counterfactual, ...
}

// ape/decisionsvc/  (the Decision Service — owns everything the fork added)
public interface DecisionService {
    DecisionResponse decide(DecisionRequest req);
}
// implementations: WeightedDecisionService (≈ WPS in-process), LlmDecisionService, PythonProxyService, ...
```

The contract is *versioned* (`contract_version: 1`); both sides reject
mismatches loudly at handshake — **drift becomes impossible by construction**,
fixing T1 at the root.

**How the five modes are expressed:**

The mode is *which DecisionService implementation is configured*, and (for
the weighted one) *which WeightSources it loads*. The manifest declares the
service + its config. `ape` = `SataOnlyService` (the 7 rungs, no weights);
`aperv` = `WeightedService(sata+budget+coverage+form)`; `mop` = `WeightedService(+mop_*)`;
`llm` = `LlmService` (with a `fallback:` field pointing at `aperv` or `mop`);
`llm_mop` = `LlmService(fallback=mop)`.

A radical sixth: `python` — the Decision Service is a Python process, and the
*entire mode system that today lives in `tool.py`* becomes the service. The
thin driver is the same for every arm; the arm is the service config. This is
the natural end-state of "the mode system already lives in Python" (§3.10).

**Answers to the §2.2 open questions, in EDC terms:**
- *Does `mop` include `aperv`?* The `WeightedService` is composable: `mop`
  loads `aperv`'s weights + MOP's weights. Same answer, different mechanism.
- *Widget vs frontier?* Two `WeightSource`s in the service; same as WPS.
- *LLM fallback?* **This is where EDC shines.** The `LlmService` has an
  explicit `fallback: DecisionService` reference — so "LLM on top of ape vs
  aperv vs mop" is literally `LlmService(fallback=SataOnlyService)` vs
  `LlmService(fallback=WeightedService(aperv))` vs
  `LlmService(fallback=WeightedService(mop))`. The two-dimensional mode is
  *first-class* (a service wrapping a service).
- *Is "mode" the right primitive?* **No — the service is.** A mode is a
  service configuration; the contract is the invariant.

**Driver satisfaction:**
- **D1**: the `SataOnlyService` can be a *literal extraction* of upstream
  APE's `selectNewAction` (from `ape-original`), compiled into the same jar
  but selected by the `ape` manifest — giving a *behaviourally-faithful*
  baseline that is not contaminated by the 14 ungated changes (because the
  service does not link them). This is the strongest D1 story of the three
  candidates.
- **D2**: the contract is the source of truth; drift is rejected at
  handshake. `--ape mop` loads the `mop` service config.
- **D3**: services are composable; `LlmService(fallback=...)` is the
  dependency declaration; the contract makes it enforceable.
- **D4**: `selectNewActionNonnull` is *deleted*; the 9 blocks are services.
  The thin driver has no `if` for mode at all.
- **D5**: the thin driver is the only part that touches `android.*`; the
  decision service is testable on a plain JVM with a fake `DecisionRequest`.
  This is the cleanest testability seam of the three — *and* it enables a
  simulated-GUI CI run (the driver against a replayed `DecisionRequest`
  stream).
- **D6**: the thin driver's model graph is the only retainer; the decision
  service is stateless (or holds only the LLM circuit-breaker state). So the
  memory work is localised to the driver. But the per-step request/response
  serialisation is a new cost.
- **D7**: the contract *is* the telemetry schema. `DecisionResponse.provenance`
  is a typed map; the recorder emits it as one JSON line per step. The
  `step=`/`clock=`/`decision_source` fields are contract fields, not
  string-concatenated log lines. **The cross-boundary telemetry gap (T5) is
  fixable here in a way it is not in C1/C2**: because the contract is
  versioned and both sides see it, the Python layer can extend the
  `DecisionResponse` with a `violation_correlation_hint` field without the
  jar changing — or vice versa.
- **D8**: if the decision service is a separate process and it crashes, the
  thin driver can fall back to a `SataOnlyService` in-process — *graceful
  degradation per step*. If the thin driver crashes, the Python layer's
  task-level resume applies. The contract makes "a restarted run is still
  one sample" answerable: the recorder marks `restart_count` per step.
- **D9**: services are the ablation units; the contract carries the
  provenance; within-run attribution is native.
- **D10**: new concepts: `DecisionRequest`/`DecisionResponse` (the
  contract), `DecisionService`, `ThinDriver`. *Subtraction:* the entire
  `ape/agent/scoring/` + `ape/llm/` + `MopData` + `ActivityBudgetTracker` +
  `FormCompletion` + `ActivityTriggerAction` move *out* of the driver into
  the service. The driver shrinks to ~300 LOC.

**Migration path:** (1) Define the `DecisionRequest`/`DecisionResponse`
contract (versioned); (2) implement an in-process `WeightedDecisionService`
that wraps the current `selectNewActionNonnull` (no behaviour change; the
service is a thread); (3) flip the driver to call the service; (4) move the
LLM into an `LlmService`; (5) *optionally* externalise the service as a
separate process. Stages 1-4 keep experiments runnable; stage 5 is optional
and the most work.

**Honest weakness:**
- **Per-step serialisation cost.** This is the make-or-break. A
  `DecisionRequest` with a screen digest + ~20 candidate actions + MOP flags
  is small (~1-2 KB JSON), but at ~250 steps/run × 21,681 tasks the JSON
  parse cost on Dalvik is non-trivial. §3.11 measured 0.037–0.052 pp
  cov_mop/step — every millisecond matters. **This must be measured before
  committing (§E).**
- **The naming/refinement core cannot move.** `NamingFactory` needs the full
  `GUITree`, not a digest — so refinement stays in the driver, and the
  service's `DecisionResponse` cannot cause a refinement directly (it must
  ask the driver to refine via a return-channel). This complicates the
  contract.
- **The `DecisionRequest` must carry enough for MOP/LLM.** The LLM needs a
  *screenshot* — so the driver must capture and encode it, and the service
  must receive it. A screenshot is ~50-100 KB base64; per-step that is a real
  cost. The current in-process design avoids this.
- **Two processes on an emulator** is more to keep alive; if the service
  dies, the driver must fall back. This is a strength (D8) but also a new
  failure mode.
- **When I would NOT choose EDC:** if the per-step serialisation cost
  (measured) exceeds the breakeven ~0.6–0.9 s/call from §3.11, EDC is a net
  loss — the LLM is already the bottleneck, and adding transport overhead
  makes it worse. EDC is only viable if the contract is *compact* and the
  transport is *in-process* (a thread + a queue, not a socket) for the
  common case, with *out-of-process* as an option for the LLM-only arm.

**Complexity budget:** 3 new concepts (`DecisionRequest`, `DecisionResponse`,
`DecisionService`), 1 contract version, the thin driver (~300 LOC). LOC
delta: large negative in the driver, large positive in the service (net
roughly neutral, but the *separation* is the value). A newcomer learns:
"the driver captures and injects; the service decides; the contract is the
wall between them."

---

## Part D — Comparison and recommendation

### Scoring table (D1–D10)

Weights are the owner's to change; I state mine. I weight D1 (thesis-critical
baseline), D7 (thesis-blocking telemetry), D9 (thesis RQs), and D4 (the named
pain) highest. D10 (simplicity, for one maintainer) is a strong modifier.

**Note:** After the owner's answers (Q1=hard precedence, Q3=one sample /
discard), C2's D4 and D8 improve: hard precedence is C2's native shape (not
C1's), and D8 collapses to task-level retry for all candidates (the jar needs
no checkpoint). C1's D4 weakens because soft weights cannot enforce hard
precedence — making C1 a partial solution, not a spine.

| Driver | W | C1 WPS | C2 PMC | C3 EDC |
|---|---|---|---|---|
| D1 baseline fidelity | 3 | 2 (visible but not fixed) | 2 | **3** (SataOnlyService = faithful) |
| D2 mode first-class | 2 | 3 (manifest = preset) | **3** (manifest = phase-config) | **3** (contract = source of truth) |
| D3 feature mgmt | 2 | 3 (deps in WeightSource) | **3** (deps in collaborator + precedence) | 3 (deps in service) |
| D4 kill spaghetti | 3 | 2 (soft weights ≠ hard precedence) | **3** (hard pre-empt + weighted SATA fallback) | 3 (deletes from driver) |
| D5 testability | 2 | 2 (pure sampler) | 2 (phase fakes) | **3** (driver/service split) |
| D6 memory | 2 | 2 (enables bounded history) | 2 | 2 (localises retention to driver) |
| D7 traceability | 3 | 2 (weight vector logged) | 2 (RECORD phase) | **3** (contract = schema; cross-boundary fixable) |
| D8 resilience | 1 | **3** (Q3: all candidates = task-level retry) | **3** (Q3: all candidates = task-level retry) | **3** (Q3 + service-crash fallback) |
| D9 ablation | 3 | 3 (feature manifest → matrix) | **3** (collaborator = ablation unit) | 3 (service = ablation unit) |
| D10 simplicity | 2 | 2 (4 concepts, −141 LOC) | 2 (5 phases, but fold C1's sampler inside) | 1 (contract + serialisation cost) |
| **Weighted total** | | **45** | **48** | **51** |

With Q1=hard, C2 overtakes C1 on D4 (the named pain) without losing D9.
C3 remains highest on raw points but its per-step serialisation cost is
unmeasured (§E1). The hybrid recommendation (C2 spine + C1 sampler inside
SataRouter + C3 contract in-process) captures the best of all three.

### Recommendation — resolved by owner's answers (2026-08-01)

The owner answered two of the three open questions on 2026-08-01:

> **Q1: Is the LLM's precedence over the launcher a hard policy?**
> **Owner: hard.** The LLM must fire when eligible; it is not a soft weight.
>
> **Q3: Is a restarted run one sample or two?**
> **Owner: one sample; the previous run's data is discarded.**

These answers **flip the spine from C1 to C2** and **dramatically simplify D8**.

**My recommendation: adopt C2 (PMC) as the spine for the pre-emption layer,
fold C1's weighted sampler into the `SataRouter` fallback, and steal C3's
contract idea to fix T1 and T5.**

Concretely:
1. **Now (cheap, high-value, no architecture risk):** Introduce a versioned
   `DecisionRequest`/`DecisionResponse` *contract* even if the service is
   in-process (a thread, not a socket). This makes T1's drift loud at
   handshake and makes T5's cross-boundary gap *structurally* closeable — the
   Python layer can extend the response with a `violation_correlation_hint`
   without the jar changing. This is C3's core insight, and it does not
   require the two-process split.
2. **The spine — PMC pre-emption layer (owner's Q1 = hard):** Adopt C2's
   `StepOrchestrator` + `RouteCollaborator` structure. The LLM, the MOP
   launcher, and the component-trigger become `RouteCollaborator`s with
   explicit `precedence()` and `wantsToFire()` — *hard* pre-emption, exactly
   as the owner requires. `selectNewActionNonnull`'s 9 guarded blocks
   collapse into a `SataRouter` that runs collaborators in `precedence()`
   order (LLM → launcher → component-trigger) then falls to the SATA chain.
   The 3-clause LLM precondition moves into `LlmCollaborator.wantsToFire`,
   de-duplicated once and for all.
3. **Fold C1 inside the `SataRouter`:** The 7 copy-pasted SATA rungs
   (`selectNewActionFromBuffer` → `handleNullAction`) collapse into a
   `WeightedActionSampler` with 7 `WeightSource`s — C1's idea applied *only*
   to the fallback chain, not the pre-emption layer. This dissolves the
   copy-paste (D4) without weakening the LLM's hard precedence (Q1). The
   `ScoringPass` pipeline is reused as weight-calibration, with its dead DI
   seam made live (`FeatureSet` replaces the `null Config`).
4. **Do NOT adopt C3's two-process split** until §E's per-step serialisation
   measurement is in. The in-process contract (step 1) gets 80% of C3's
   benefit at 20% of its cost.
5. **D8 — simplified by owner's Q3 answer (one sample, discard previous):**
   The jar does **not** need checkpointing, resume, or the `readGraph`/
   `saveGraph` type-mismatch fix (INV-EXPL-03). A restarted run is a fresh
   sample — the Python layer's task-level retry (`TaskStorage` marks the
   task for re-run) is the *entire* resilience story. The one critical fix
   is the `tool.py:1121-1125` defect: a truncated run must be marked
   **FAILED/TRUNCATED**, not COMPLETED, so the retry fires. The +15 s grace
   window is still useful for flushing the trace, but the model does not
   need to be saved for resume. The non-preemptive deadline check
   (`Monkey.java:1298-1301`) stays as-is.

**What gets deleted (subtraction, per the prompt):**
- `SataAgent.selectNewActionNonnull` (−141 LOC) → 3 lines + 7 `WeightSource`s.
- The 3-hand-repeated LLM precondition (−~20 LOC).
- The `shouldFireLauncher` 6-arg call with 3 `Config` reads at the call site
  (−~15 LOC) — the `LauncherWeightSource` reads its own `FeatureSet`.
- The `Config cfg` decorative parameter of `ScoringPipeline.fromConfig`
  (−1 lie) — replaced by a live `FeatureSet`.
- The Python `_APE_PURE_ARM_FLAGS` / Java `rvForcedOffValues` duplication
  (−2 lists) — replaced by one manifest with `ape_pure` as a named preset.
- `ApeAgent.createAgent`'s silent-fallthrough + `System.exit(1)` (−1 footgun)
  — `--ape mop` loads a manifest or fails loudly.

### What each candidate keeps from the current design (do not silently lose)

- **The `ScoringPass` pipeline** (C1/C2 reuse it as weight-calibration inside
  the `SataRouter`; C3 keeps it as a service-internal phase).
- **`apePureMode`'s defence-in-depth property** ("a future RV flag cannot be
  added without registering in the kill-switch") — becomes "a future feature
  cannot be added without declaring it in the manifest and what it depends
  on." The test that enforced it (INV-APV-14) becomes a manifest-validation test.
- **The fail-fast on MOP load** (`StopTestingException` on a set-but-failed
  `mopDataPath`) — preserved as a `RouteCollaborator.wantsToFire` (or
  `WeightSource.isEnabled`) precondition that *aborts* rather than silently
  no-ops.
- **The step/clock join design** (§3.11 acceptance test) — preserved as
  contract fields / `RECORD`-phase fields.
- **`LlmCircuitBreaker`** — preserved as the `LlmCollaborator`'s gate; the
  hard-pre-empt contract is "if `wantsToFire` is true, fire; `wantsToFire`
  checks the breaker."
- **The teardown isolation invariants** (INV-EXPL-16/29) — untouched; the
  spine only changes the *selection* method, not the teardown chain. And
  with the owner's Q3 answer (discard previous run), the teardown chain
  no longer needs to save a resumable model — `saveGraph` becomes a
  *post-mortem artifact*, not a *resume checkpoint*, which removes the
  pressure to serialise the whole `Model` on an exhausted heap.
- **The no-resume decision (Q3)** also dissolves INV-EXPL-03 (the
  `readGraph`/`saveGraph` type mismatch): `--ape-model` can be deprecated
  or repurposed as a *replay-from-trace* debug tool, not a resume mechanism.

---

## Part E — Risks, unknowns, next steps

### E1. What I could not determine from the code — needs measurement

- **Actual heap high-water mark over a 600 s run.** The retention graph (T4)
  is confirmed structurally, but the *rate* of growth and the *failure mode*
  (slow creep vs cliff) is an empirical question. **Cheap experiment:** run
  `ape_pure` and `sata_mop` on the 3 worst-4 APKs from §3.11 for 600 s with
  `printMemoryUsage` (already per-step) and plot used-MB over step. If the
  curve is concave-up, T4 is urgent; if linear-with-plateau, it is not.
- **Per-step telemetry cost.** The §3.11 acceptance test requires preserving
  the step/clock/decision_source fields, but the *cost* of emitting them as
  JSON vs `key=value` is unmeasured. **Cheap experiment:** add a JSON-line
  emitter behind a flag, run 100 steps, compare wall-clock. If < 0.5 ms/step,
  JSON is free; if > 2 ms/step, stay with `key=value` but add escaping.
- **EDC per-step serialisation cost (the make-or-break for C3).** Build a
  skeleton `DecisionRequest`/`DecisionResponse` round-trip in-process
  (JSON + a queue), measure the per-step overhead. If it exceeds 1 ms, C3's
  two-process split is not viable; if < 0.2 ms, it is.
- **Real distribution of run terminations.** The T6 defect (truncated run
  counted as success) — what fraction of the 880 calibration runs were
  truncated? §3.11 says 42.3% lost the coverage dump at teardown; the
  question is how many were *recorded as completed*. **Cheap experiment:**
  parse the 880 traces for `restart_count` / missing Summary lines and
  cross-reference `tasks.json` status.

### E2. Cheap experiments that discriminate between candidates

- **WPS vs PMC — does the LLM need to hard-pre-empt?** Run the existing
  `sata_llm` arm with the LLM weight *doubled* and the launcher weight *halved*
  (a 2-line `ape.properties` change today, since weights are already there for
  MOP). If the LLM's share of `decision_source` scales with its weight, WPS
  (soft precedence) is fine. If the LLM *must* always fire when eligible to
  reproduce §3.11's per-action yields, PMC (hard pre-empt) is needed.
- **C3 contract viability — is the GUITree digest enough?** Hash the
  `Naming`-abstracted `State` (not the raw tree) and check collision rate
  over a 600 s run. If the digest uniquely identifies the decision context,
  the contract can carry a digest, not a tree.
- **Baseline fidelity — is `ape-original` actually equivalent to
  `ape_pure`?** Run both on 5 APKs from the phase-2 grid for 300 s and diff
  the action-history logs. If they diverge by > 5% of steps, the 14 ungated
  changes matter and D1 needs the revert path; if < 1%, "APE + bugfixes" is
  defensible.

### E3. Questions for the owner (one line each)

Two of the original three have been answered (2026-08-01):

> **Q1 (RESOLVED): Is the LLM's precedence over the launcher a hard policy?**
> Owner: **hard.** → spine is C2 (PMC), not C1 (WPS). See updated recommendation above.
>
> **Q3 (RESOLVED): Is a restarted run one sample or two?**
> Owner: **one sample; discard previous.** → D8 is task-level retry; the jar
> does not checkpoint or resume. See updated recommendation above.

Still open:

1. **Is a `ape_pure` arm that is "RV-features-off fork" defensible for the
   thesis, or must it be byte-faithful to upstream APE?** — decides whether
   D1 needs a revert patch / `ape-original` jar. The 14 ungated changes
   (plus the `activityStableRestartThreshold → MAX_VALUE` collateral
   divergence) mean "near-pure" is not "pure."
2. **Should the manifest live in the jar, in the plugin, or in a third
   repo both read?** — decides where the single source of truth is. Given
   the subprocess boundary (files only), a TOML in the jar repo read by
   both sides is the simplest, but the owner may prefer the experiment
   repo to own it.
3. **Is the per-step JSON telemetry cost acceptable if it closes the
   violation-attribution gap?** — decides whether D7 can switch formats.
   Must be measured (§E1) before committing.

### E4. What this analysis might be wrong about

- **I may be underestimating the cost of the manifest.** A TOML format both
  sides read is one more file + one more parser on each side; for one
  maintainer (constraint 7) that is real. The alternative — code-gen the
  Python mapping from Java annotations — is over-built but zero-drift; the
  tradeoff depends on how often arms change (experiment-driven: often).
- **I may be overestimating C3's benefit.** The contract is the valuable
  part; the two-process split may be ceremony. If the in-process contract
  (step 1 of the recommendation) closes T1 and T5, C3's full form may be
  unnecessary — and then C1 + contract is the whole answer.
- **I may be wrong that WPS preserves the §3.11 acceptance test.** The
  weight-vector log is richer than today's `decision_source`, but it
  changes the *shape* of the analysis scripts. The 2026-07-24 report was
  written from `key=value` traces; if the new format is JSON, the parser
  must be rewritten — and the parser is the provenance of the findings.
  **Mitigation:** emit both formats during migration, deprecate
  `key=value` only after the new parser is proven on a calibration corpus.
- **The 14 ungated changes (T7) may be more or less behaviourally
  significant than I assessed.** The `activityStableRestartThreshold →
  MAX_VALUE` collateral divergence is the one I can defend as
  thesis-relevant; the others (binarySearch fixes, `isEphemeral`) may be
  noise or may be load-bearing. **Only a differential run on the phase-2
  grid can settle this** (§E2).
- **I did not verify the `telemetry-proof-llm-efficacy` change's on-device
  efficacy (task 17.4 open).** My D9 claims assume the counterfactual and
  `PickChannel` work as designed; if they do not, the within-run attribution
  story weakens for *all* candidates.

---

*End of report. The single artifact is this file, `docs/analise_glm-5-2.md`.*
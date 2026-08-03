# parity-oracle — delta: rearch-01-parity-oracle

## Purpose

The parity oracle is the acceptance gate for the "Disposable Run Kernel" re-architecture
(`docs/analise_fable-selecao.md` rev. 3, Sec. 9.9, Sec. 10 stage 1). The precedence semantics
of APE-RV's action selection live implicitly in the textual order of
`SataAgent.selectNewActionNonnull()` (`SataAgent.java:449-589`): budget-trivial navigation,
then three LLM hooks (new-state, stagnation, random), then the cadence-based MOP launcher,
then the component-trigger side-effect, then the seven-rung SATA chain. `rearch-02-runspec`
and `rearch-03-decision-pipeline` will restructure the configuration authority and this
ladder; comparability with the phase-2 experimental grid (21,681 tasks plus calibration)
requires that the restructured code reproduce today's decisions exactly under the same seeds.
Before this change, nothing detects a regression of that kind.

The oracle is a pure-JVM golden-capture harness living entirely in the test tree
(`src/test/java`, `src/test/resources`), exercised by `mvn test` with no device, no emulator,
and no network. For each target preset — the property sets corresponding to today's `aperv`,
`mop`, `llm`, and `llm_mop` experimental arms — it drives the unmodified
`selectNewActionNonnull()` over scripted synthetic states under fixed seeds and compares the
resulting decision sequence against a committed golden file. A dedicated preemption golden
pins the hard precedence order on a synthetic state that qualifies every mechanism
simultaneously, including the undocumented cadence interaction (report finding 3.3-1): a step
preempted by the LLM does **not** advance the launcher cadence counter
(`_stepsSinceLauncherFiring++` at `SataAgent.java:522` executes only after the LLM blocks
return at `:485`/`:504`/`:513`).

The goldens are **harness-relative**: they pin the decision logic under synthetic states and
a scripted (deterministic, offline) LLM, not the byte sequence of a real device run.
Device-level behavior (GUITree construction from `AccessibilityNodeInfo`, naming/refinement,
model evolution, event injection, restart/teardown, branches reaching `AndroidDevice`)
is outside the capture boundary and remains validated by the existing rv-platform smoke path.
Within that boundary, the oracle's contract is strict: same preset, same seed, same scenario
⇒ identical decision sequence, before and after any stage-2/3 production change. A golden
that no longer matches is either a migration regression (fix the code) or a deliberately
decided behavior change (regenerate the golden through the documented procedure and record
why) — never something to silently re-record.

## Data Contracts

### Input

- `ScenarioScript` — hand-written deterministic synthetic app: ordered screens (widget lists
  materialized as `ModelAction`s with `Name` targets), a transition table, per-step LLM script
  entries, per-step scripted agent-side state (`_isNewState`, `graphStableCounter`)
  (source: test tree, committed)
- `preset injection profile` — the field set wired into the Unsafe-allocated agent, mirroring
  `StatefulAgent`'s constructor (`StatefulAgent.java:179-208`): `_mopData` (null or loaded
  fixture), `_llmRouter` (null or scripted stub), `_budgetTracker`, `scoringContext`,
  `scoringPipeline`, epsilon, counters (source: `OracleScaffold`)
- `seed: long` — seeds **both** RNG streams: `RandomHelper.seed(seed)` (static stream) and the
  agent's `getRandom()` stream via the harness subclass override (source: capture test)
- `MopData fixture` — `src/test/resources/cryptoapp.apk.gh60-fresh.json`, loaded through the
  production `MopData.load` path (JVM-safe, org.json) for the `mop`/`llm_mop` presets
- `-Dape.oracle.regenerate=true` — the only trigger for capture (write) mode
  (source: developer command line, never CI)

### Output

- `src/test/resources/goldens/<preset>/<scenario>.ndjson` — one header record
  (`kind`, `preset`, `scenario`, `seed`, `fixture`, `capturedAt`) followed by one record per
  step: `step`, `actionType`, `target` (the action `Name.toXPath()`, absent for targetless
  actions), `decisionSource`, `pickChannel` (absent for non-`ModelAction` returns), `llm`
  (`accepted | declined | timeout | not_routed`, absent for presets without the router),
  `componentTrigger` (present and `true` only on steps where the trigger side-effect fired)
  (consumer: the compare mode of this suite; the stage-2/3 migration gate)
- First-divergence report on comparison failure: `preset`, `scenario`, `step`, `field`,
  golden value, actual value, plus the total count of divergent records
  (consumer: the developer migrating stages 2–3)

### Side-Effects

- **Capture mode**: rewrites golden files under `src/test/resources/goldens/` in the working
  tree (visible in `git status`; committing them is the deliberate act).
- **Compare mode (default)**: none — read-only against the working tree.

### Error

- `AssertionError` (divergence) — replayed sequence differs from the golden; carries the
  first-divergence report
- `AssertionError` (missing golden) — compare mode with no committed golden; the message
  contains the regeneration instructions; the harness SHALL NOT auto-capture
- `NoClassDefFoundError: android.*` — a scenario drove selection into a device-only branch;
  the scenario is redesigned (loud failure, never an `@Ignore`)
- `IllegalStateException` (script exhaustion / unconsumed mandatory LLM entry) — scenario
  and agent disagree about how many LLM consultations occur; a scenario bug, failed loudly
- `BadStateException` — the ladder found no available action; scenarios SHALL always leave
  at least one selectable action

## Invariants

- **INV-ORA-01**: The oracle SHALL live entirely in the test tree. It SHALL NOT modify any
  file under `src/main/java/`, SHALL NOT add production seams, and SHALL NOT place a class on
  the test classpath that shadows a production class. Source stubs under
  `src/test/java/android/**` (the established `Rect.java` pattern) are permitted for
  `android.*` framework types only.
- **INV-ORA-02**: Capture and replay SHALL be deterministic: the same harness, seed, preset,
  and scenario SHALL produce an identical decision-record sequence on every execution. Both
  RNG streams (`RandomHelper` and the agent's `getRandom()`) SHALL be seeded from the
  scenario's declared seed.
- **INV-ORA-03**: No oracle code path SHALL perform network I/O, capture a screenshot, or
  depend on wall-clock time. In particular the scripted LLM router SHALL never invoke the
  real `selectAction` pipeline and SHALL never drive `LlmCircuitBreaker` transitions
  (`System.currentTimeMillis()`-based, `LlmCircuitBreaker.java:54,77,95`).
- **INV-ORA-04**: The default test run (`mvn test`, CI included) SHALL only compare; golden
  files SHALL be written exclusively when `-Dape.oracle.regenerate=true` is set explicitly.
  No mechanism SHALL regenerate goldens automatically.
- **INV-ORA-05**: The preemption golden SHALL assert, as captured current behavior, that a
  step whose selection is preempted by an accepted LLM result does NOT advance
  `_stepsSinceLauncherFiring` (finding 3.3-1), until stage 3 replaces this assertion with a
  decided behavior.
- **INV-ORA-06**: A comparison failure SHALL identify the preset, scenario, step index, and
  the first divergent field with both golden and actual values.
- **INV-ORA-07**: While `rearch-02-runspec` and `rearch-03-decision-pipeline` are in flight,
  golden files and scenario scripts SHALL NOT change; only the injection scaffold MAY be
  adapted to renamed or relocated fields. A behavior change in those stages that requires
  regenerating a golden SHALL be treated as a deviation requiring an explicit owner decision.

## ADDED Requirements

### Requirement: Per-Preset Golden Decision-Sequence Capture

The oracle SHALL capture and commit, per target preset, the ordered action-selection sequence
produced by the current `SataAgent.selectNewActionNonnull()` over that preset's scenario
scripts under a fixed declared seed. The target presets SHALL be the four property sets
corresponding to today's experimental arms: `aperv` (no MOP data, no LLM), `mop` (MOP data
loaded from the committed static-analysis fixture, no LLM), `llm` (scripted LLM router, no
MOP data), and `llm_mop` (both). A preset SHALL be realized by the harness's injection
profile mirroring the constructor wiring of `StatefulAgent.java:179-208` — `_mopData` and
`_llmRouter` presence/absence are the preset axes — over jar-default `Config` values.

Each preset's capture SHALL include at least one multi-step baseline scenario exercising:
the SATA chain fall-through (buffer, early-stage, epsilon-greedy rungs), and — where the
preset enables them — MOP-boosted picks, the cadence launcher firing, the component-trigger
side-effect, and all three LLM hooks with accept, decline, and timeout verdicts.

Each preset capture test SHALL guard-assert the jar-default `Config` values the ladder reads
for that preset (e.g. `activityBudgetEnabled`, `activityTriggerEnabled`,
`activityTriggerStagnationStep`, `componentPercentage`), so a future default change fails the
guard explicitly instead of surfacing as an unexplained golden divergence.

The scope of that guard is exactly "the values the ladder reads", and the boundary matters
because it is easy to over-read. The scoring weights (`mopWeightDirect`,
`mopWeightTransitive`, `mopWeightOpenMenu`, `mopWeightWtg`, `frontierBoostWeight`,
`mopFrontierWeight`, `coverageBoostWeight`, `formCompletionEnabled`) are **not** among them:
scoring happens in `adjustActionsByGUITree()`, which `resolveNewAction()` calls above this
oracle's entry point (`StatefulAgent.java:1475-1478`), so no golden record depends on a
scoring weight and no capture test can guard one. Their guard belongs where the injection is
built — `rearch-03-decision-pipeline`, INV-ARCH-12 — and this requirement SHALL NOT be cited
as covering them.

#### Scenario: aperv preset golden compares green at HEAD

- **WHEN** `mvn test` runs the `aperv` preset capture test in compare mode against the
  committed golden at the commit that captured it
- **THEN** the replayed decision sequence SHALL equal the golden record-by-record, including
  record count
- **AND** every record SHALL carry `actionType` and, for targeted actions, the `Name.toXPath()`
  target

#### Scenario: mop preset exercises MOP mechanisms against the committed fixture

- **WHEN** the `mop` preset baseline scenario runs with `_mopData` loaded from
  `cryptoapp.apk.gh60-fresh.json` and `_llmRouter` null
- **THEN** the golden SHALL contain at least one step attributed to a MOP mechanism
  (`decisionSource=MOP` or a launcher `EVENT_TRIGGER_ACTIVITY` step)
- **AND** the LLM field SHALL be absent from every record

#### Scenario: Config default drift is caught by the guard, not the golden

- **WHEN** a future change alters a jar-default `Config` value that the selection ladder reads
  (e.g. `ape.activityTriggerStagnationStep`)
- **THEN** the preset's Config guard assertion SHALL fail with the changed key named
- **AND** the failure SHALL NOT be reported as a golden divergence

#### Scenario: Determinism — double capture is identical

- **WHEN** the same preset scenario is captured twice in one JVM with the same seed (both RNG
  streams re-seeded before each run)
- **THEN** the two decision-record sequences SHALL be identical

### Requirement: Golden File Format and Location

Golden files SHALL be NDJSON under `src/test/resources/goldens/<preset>/<scenario>.ndjson`,
committed to the repository. The first record SHALL be a header carrying `kind:"header"`,
`preset`, `scenario`, `seed`, `fixture` (when a MopData fixture participates), and
`capturedAt` (the capturing commit). Every subsequent record SHALL describe exactly one
selection step with `step` (monotonically increasing), `actionType` (the `ActionType` name;
`EVENT_TRIGGER_ACTIVITY` for launcher steps, with the candidate class recorded in `target`),
and — when applicable — `target` (`Name.toXPath()`), `decisionSource`, `pickChannel`, `llm`
(`accepted | declined | timeout | not_routed`), and `componentTrigger`. Fields that do not
apply SHALL be absent, not null-valued. Files SHALL be written and read by the same
serializer (org.json), round-trip tested, one physical line per record.

#### Scenario: Round-trip fidelity

- **WHEN** a captured decision-record list is written to NDJSON and read back
- **THEN** the parsed records SHALL equal the originals field-by-field
- **AND** each record SHALL occupy exactly one physical line

#### Scenario: Launcher step rendering

- **WHEN** a captured step's selection returned an `ActivityTriggerAction` for
  `com.example.SettingsActivity`
- **THEN** the golden record SHALL carry `actionType:"EVENT_TRIGGER_ACTIVITY"` and
  `target:"com.example.SettingsActivity"`
- **AND** `pickChannel` and `decisionSource` SHALL be absent (the return is not a
  `ModelAction`)

#### Scenario: Component side-effect recorded on the step it preceded

- **WHEN** the component trigger fires as a side-effect and the SATA chain then selects a
  `MODEL_CLICK`
- **THEN** that step's record SHALL carry `componentTrigger:true` AND
  `actionType:"MODEL_CLICK"` — the side-effect-without-return semantics is part of the golden

### Requirement: Preemption-Order Golden

The oracle SHALL include a preemption golden built on synthetic states that simultaneously
qualify the LLM hooks, the MOP launcher (cadence reached, census candidate available), the
component trigger, and the SATA chain, asserting the hard precedence order currently
implemented by textual position in `selectNewActionNonnull()` (report V1/V4): an accepted LLM
result preempts the launcher; the launcher, when it fires, preempts the component trigger and
the SATA chain; the component trigger is a side-effect that never returns; the SATA chain is
the fallback. The budget block precedes them all and SHALL be pinned in both its outcomes:
trivial-action return and fall-through.

The preemption golden SHALL additionally pin, as current behavior:

1. **Finding 3.3-1**: on a step where an LLM hook accepts, `_stepsSinceLauncherFiring` SHALL
   NOT be incremented (the increment at `SataAgent.java:522` is unreached); on the next
   non-preempted step it SHALL resume from its pre-preemption value.
2. **Stagnation single-shot burn**: when the stagnation hook is consulted,
   `stagnationHookFired` SHALL become `true` regardless of the verdict — an LLM decline still
   burns the episode's shot (`SataAgent.java:499`).
3. **Counter reset on accept only**: `graphStableCounter` SHALL be reset to 0 by a stagnation
   accept (`:503`) and SHALL be left unchanged by a stagnation decline.
4. **Hook order**: on a step where multiple LLM hooks would route, the new-state hook SHALL
   be consulted before stagnation, and stagnation before random.

#### Scenario: LLM accept preempts a launcher that was due to fire

- **WHEN** a step's state qualifies the new-state LLM hook (scripted accept) AND
  `_stepsSinceLauncherFiring` is one below `Config.activityTriggerStagnationStep` with an
  unvisited census candidate available
- **THEN** the selected action SHALL be the LLM-accepted `ModelAction`
- **AND** `_stepsSinceLauncherFiring` SHALL be unchanged after the step (finding 3.3-1)
- **AND** the next step without LLM routing SHALL fire the launcher exactly when the cadence
  is reached counting from the pre-preemption value

#### Scenario: LLM decline falls through to the launcher

- **WHEN** the same simultaneous-qualification state runs with every scripted LLM verdict set
  to decline and the cadence reached
- **THEN** the selected action SHALL be the launcher's `EVENT_TRIGGER_ACTIVITY`
- **AND** the step's record SHALL carry `llm:"declined"`

#### Scenario: Stagnation decline burns the single shot

- **WHEN** the stagnation hook routes and the scripted verdict is decline, and a later step in
  the same episode would qualify the stagnation hook again
- **THEN** the first step SHALL set `stagnationHookFired` to `true` with `graphStableCounter`
  unchanged
- **AND** the later step SHALL NOT consult the stagnation hook (the stub honors the
  `firedThisEpisode` argument)

#### Scenario: Component trigger is a side-effect, never a selection

- **WHEN** a step qualifies the component trigger (scripted draw below
  `Config.componentPercentage`, MOP components present) and no LLM or launcher preempts
- **THEN** the step's selected action SHALL come from the SATA chain
- **AND** the record SHALL carry `componentTrigger:true`

#### Scenario: Budget-exhausted trivial return precedes every other mechanism

- **WHEN** the budget tracker reports the current activity exhausted and a trivial navigation
  action exists, while the same step qualifies the LLM hooks
- **THEN** the selected action SHALL be the budget-trivial action and no LLM hook SHALL be
  consulted
- **AND** when no trivial action exists the step SHALL fall through to the LLM hooks
  (fall-through pinned, report finding on the budget gate)

### Requirement: Deterministic LLM Stubbing

LLM-preset goldens SHALL be captured with a scripted router (`ScriptedLlmRouter extends
LlmRouter`) and never a live SGLang server. The stub SHALL override the three routing
predicates with per-step scripted verdicts that honor the agent-side arguments
(`shouldRouteNewState` honors `isNewState`; `shouldRouteStagnation` honors
`firedThisEpisode`), and SHALL override `selectAction` with scripted results: **accept**
(a `ModelAction` chosen from the passed action list by a deterministic selector named in the
script), **decline** (null), or **timeout** (null, recorded distinctly as provenance). The
stub SHALL NOT perform HTTP, SHALL NOT capture screenshots, and SHALL NOT record breaker
transitions; scripts SHALL NOT model breaker-open episodes (out of oracle scope — wall-clock
dependent). Script exhaustion, or a scripted consultation the agent never performed, SHALL
fail the test.

#### Scenario: Accept returns a deterministic member of the offered actions

- **WHEN** a scripted accept names selector "first-unvisited-targeted" and the step offers a
  stable action list
- **THEN** `selectAction` SHALL return that exact `ModelAction` on every capture and replay
- **AND** no network connection SHALL be attempted at any point

#### Scenario: Decline and timeout share the fall-through, differ in provenance

- **WHEN** one step is scripted decline and another timeout
- **THEN** both steps SHALL fall through to the mechanisms below the LLM blocks
- **AND** their golden records SHALL carry `llm:"declined"` and `llm:"timeout"` respectively

#### Scenario: Unconsumed script entry fails loudly

- **WHEN** the script declares a routed consultation at step 5 but the agent's preconditions
  (e.g. `actionBufferSize() != 0`) prevent the hook from being consulted
- **THEN** the harness SHALL fail the test naming the unconsumed entry
- **AND** SHALL NOT silently continue to the next entry

### Requirement: Golden Comparison as the Migration Gate

The compare mode SHALL be the acceptance gate for `rearch-02-runspec` and
`rearch-03-decision-pipeline` (report Sec. 9.9): those changes land only with every preset
golden and the preemption golden green under `mvn test` against the goldens captured at the
pre-migration behavior. On divergence the comparator SHALL report the first divergent field
with preset, scenario, step, golden value, and actual value (INV-ORA-06). Goldens and
scenario scripts are frozen during those stages (INV-ORA-07); the injection scaffold is the
only adaptation layer.

#### Scenario: Stage-3 regression is caught at the first divergent step

- **WHEN** a pipeline restructuring accidentally evaluates the launcher before the LLM
  stagnation hook and the `llm_mop` preemption scenario is replayed
- **THEN** the comparison SHALL fail at the first step whose selected action differs
- **AND** the report SHALL name the preset, scenario, step index, and the divergent field with
  both values

#### Scenario: Missing golden never auto-captures

- **WHEN** compare mode runs and the golden file for a scenario does not exist
- **THEN** the test SHALL fail with a message containing the deliberate-regeneration
  instructions
- **AND** no golden file SHALL be written

### Requirement: Deliberate Golden Regeneration

Regeneration SHALL be a deliberate, documented act: it SHALL run only when
`-Dape.oracle.regenerate=true` is passed explicitly, SHALL rewrite the golden files in the
working tree (surfacing in `git status` for review and commit), and SHALL never run in CI or
in the default build (INV-ORA-04). The procedure — when regeneration is legitimate (a decided
behavior change, never a red comparison one wants green), how to run it, what to review in
the diff, and that the commit message must state why the behavior changed — SHALL be
documented in `src/test/resources/goldens/README.md`.

#### Scenario: Default build never writes goldens

- **WHEN** `mvn test` runs without the regeneration property
- **THEN** no file under `src/test/resources/goldens/` SHALL be created or modified

#### Scenario: Regeneration is reviewable

- **WHEN** a developer runs the capture tests with `-Dape.oracle.regenerate=true` after a
  decided behavior change
- **THEN** the rewritten goldens SHALL appear as working-tree modifications
- **AND** the goldens README SHALL instruct that the change is committed with the decision
  that motivated it

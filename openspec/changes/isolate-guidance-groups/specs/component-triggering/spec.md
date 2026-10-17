# Delta: Component Triggering (isolate-guidance-groups)

## Purpose

**The launcher's cadence becomes global** (D-A8, D-A16, D-B11). Before this change, the `MopLauncher` stage
counted only the steps that reached it. A step taken by an LLM stage therefore did not advance the clock, and the
launcher fired in proportion to `1 − (LLM share)`. After this change:
- the counter advances on every step, in the stage's `onStepOpen` hook;
- the launch happens on the first step that reaches the MOP/SATA branch once the counter is due;
- the launcher still never comes before the LLM.

**Component triggering moves to STATIC** (D-B13, author 2026-10-17). `componentPercentage` has defaulted to 0
since gh15 A-3, because it had been derived from `mopDataPath` and confounded the `sata`/`sata_mop` arms. With the
groups, that confound is gone by construction:
- `COMPONENT_TRIGGER` requires `STATIC`, not `MOP`, and the STATIC block sets `ape.componentPercentage=0.03`.
- The trigger pool reads no MOP data: the `reachesTarget` filter is removed.
- The coin moves to its own stream, `StepRng.component` (`decision-pipeline` INV-DP-13).
- The intent carries the matched intent filter's `data`, read from a new additive `data` member of
  `intentFilters` (`schemes`, `hosts`, `paths`, `pathPrefixes`, `mimeTypes`; design D-B14.11). The artifact stays `formatVersion: 2`, and an absent member means "no data".
- The broadcast catalog is loaded iff `COMPONENT_TRIGGER` is mounted.

## Invariants

- **INV-CT-01 (amended)**: Component triggering SHALL fire only when the plan mounts `COMPONENT_TRIGGER`. That
  requires the `STATIC` group and `ape.componentPercentage > 0`, and the static provider must report
  `hasComponents()`. When `componentPercentage` is `0.0`, the jar default, behaviour SHALL be identical to APE-RV
  without component triggering, whatever the artifact holds.
- **INV-CT-02 (amended)**: Component triggering SHALL be probabilistic. On each step that reaches the
  `ComponentTrigger` stage, the trigger fires when `u_C < componentPercentage`, where `u_C` was drawn at step open
  from `StepRng`'s `component` stream on every step of a plan that mounts the trigger. The trigger is a side
  effect, and action selection continues regardless.
- **INV-CT-03 (amended)**: The probabilistic pool SHALL hold the following and nothing else:
  - the BroadcastReceiver and Service tuples, one per (intent filter × action);
  - one component-name-only tuple per Service with no filter action;
  - the ContentProvider tuples, one per (provider with a non-empty `authorities` × operation `query`, `insert`,
    `update`).

  Activities SHALL NOT enter the pool under any configuration. They are launched only by the cadence launcher
  (`EVENT_TRIGGER_ACTIVITY`, `dec.src="launcher"`).
- **INV-CT-04 (amended)**: The package component of every trigger `ComponentName` SHALL equal the artifact's
  `package`, read from the static provider. No trigger path SHALL derive it from the component class name.
- **INV-CT-16**: The pool and the dispatch SHALL read no MOP data. Neither the pool's membership nor any trigger
  intent SHALL depend on `reachesMop`/`reachesTarget`, `targetMethods`, `hasTargetMethods`, distances or
  handlers. Two artifacts that differ only in MOP data SHALL yield the same pool in the same order.
- **INV-CT-17**: A receiver or service tuple whose intent filter carries a `data` member SHALL dispatch an intent
  built from it:
  - `setDataAndType(uri, mimeType)` when the member has a scheme and a MIME type;
  - `setData(uri)` when it has a scheme only;
  - `setType(mimeType)` when it has a MIME type only.

  `uri` is `scheme://host + path`, built from the filter's first scheme, first host and first path, the path
  falling back to the first path prefix, with `host` and `path` empty when absent (design D-B14.11, D-B14.15);
  `mimeType` is its first MIME type. `ports` and `pathPatterns` are not projected. A filter without a `data`
  member SHALL dispatch with no data and no type.
- **INV-CT-18**: `SystemBroadcastCatalog` SHALL be loaded if and only if `COMPONENT_TRIGGER` is mounted.

## MODIFIED Requirements

### Requirement: Probabilistic component triggering in SataAgent

Probabilistic component triggering SHALL be a `ComponentTrigger` decision stage. It is assembled after the
`MopLauncher` stage and before the `MopCoin` and `SataChain` stages. The stage exists only when the plan mounts
`COMPONENT_TRIGGER`, a member of the STATIC group with `componentPercentage > 0` (design D-B13). When the feature
is absent, so is the stage (`decision-pipeline` INV-DP-03). The stage reads the trigger pool from the static
provider and never from `MopData` (INV-CT-16).

On each step that reaches it, when the static provider reports `hasComponents()`, the stage SHALL compare the
step's `u_C` with `componentPercentage`. When `u_C` is below it, the stage SHALL call `triggerComponent()`, which
fires one tuple of the pool (round-robin, cursor owned by the stage), and return `StageResult.SideEffect`.
Otherwise it SHALL return `Continue`. A `SideEffect` never decides the step: the pipeline records it and
continues, and the `MopCoin` stage (when it is assembled and takes the step) or the `SataChain` stage selects the
step's action.

`u_C` SHALL be drawn at step open from `StepRng`'s `component` stream on every step of a plan that mounts the
trigger, whether or not the step reaches the stage (`decision-pipeline` INV-DP-13). It SHALL NOT come from the
agent's generator. Mounting the trigger therefore never shifts SATA's draws. This changes the random sequence
relative to `eea2b1a9`, where the coin came from the agent stream only on steps that reached the stage.

#### Scenario: Component trigger fires
- **WHEN** the stage is assembled with `componentPercentage=0.03`, the static provider reports `hasComponents()`, and `u_C < 0.03` on a step that reaches the stage
- **THEN** `triggerComponent()` SHALL be called and the stage SHALL return `SideEffect`
- **AND** the `MopCoin` or `SataChain` stage SHALL still select the step's action (trigger does not replace the step)

#### Scenario: Component trigger does not fire
- **WHEN** `u_C >= componentPercentage`
- **THEN** no component trigger occurs and the stage SHALL return `Continue`

#### Scenario: No mopDataPath set
- **WHEN** the plan does not mount `COMPONENT_TRIGGER`, because `componentPercentage` is 0, the STATIC group is off, or `ape.staticDataPath` is absent
- **THEN** no `ComponentTrigger` stage SHALL exist in the pipeline
- **AND** no `u_C` SHALL ever be drawn, and no catalog SHALL be loaded (INV-CT-18)

#### Scenario: Preempted step draws no coin
- **WHEN** a guard, an LLM stage or the launcher decides a step on a plan that mounts the trigger
- **THEN** the `ComponentTrigger` stage SHALL NOT be evaluated on that step and SHALL consume no draw from the agent or helper stream
- **AND** `u_C` SHALL still have been drawn at that step's open, so the next step's `u_C` is the stream's next value

---

### Requirement: Broadcast triggering

`triggerComponent()` SHALL send targeted broadcasts to BroadcastReceivers. The intent SHALL be constructed with:
- the action string of the tuple, which comes from one of the receiver's intent-filter actions;
- a ComponentName targeting `(packageName, receiverClassName)` for explicit delivery;
- the data and type of the filter that supplied the action, when that filter carries a `data` member
  (INV-CT-17);
- typed extras from `SystemBroadcastCatalog`, when the action matches a known system broadcast.

The static analysis emits no intent extras, so the catalog is the only source of extras.

Receivers with no intent-filter actions (`intentFilters: []`) SHALL be skipped. The pool admits every receiver
with a filter action, whether or not the MOP data says it reaches a target (INV-CT-16). The trigger log line SHALL
carry the permission gate and SHALL NOT claim `reachesTarget=true`.

#### Scenario: Broadcast with catalog extras
- **WHEN** `triggerComponent()` selects a receiver with action `android.intent.action.BOOT_COMPLETED`
- **AND** `SystemBroadcastCatalog` has an entry for this action
- **THEN** APE-RV SHALL send the broadcast with action, ComponentName, and catalog extras

#### Scenario: Broadcast with no catalog match
- **WHEN** the receiver has action `com.example.CUSTOM_ACTION` not in the catalog, and its filter carries no `data` member
- **THEN** APE-RV SHALL send the broadcast with action + ComponentName only (no extras, no data, no type)

#### Scenario: Protected broadcast
- **WHEN** the broadcast action is protected (e.g., `BOOT_COMPLETED`)
- **AND** Android throws `SecurityException`
- **THEN** the exception SHALL be caught and logged as WARNING
- **AND** exploration SHALL continue normally

#### Scenario: Broadcast carries the filter's data
- **WHEN** a receiver's filter has action `com.x.IMPORT` and `"data": {"schemes": ["content"], "hosts": ["com.x.files"], "paths": ["/in"], "mimeTypes": ["text/csv", "text/plain"]}`
- **THEN** the intent SHALL carry `setDataAndType(Uri.parse("content://com.x.files/in"), "text/csv")`
- **AND WHEN** another filter's `data` holds only `"mimeTypes": ["image/png"]`
- **THEN** its intent SHALL carry `setType("image/png")` and no data

---

### Requirement: Service triggering

`triggerComponent()` SHALL start Services via `AndroidDevice.startService()`. The intent SHALL use ComponentName
for explicit delivery, with the tuple's action when a filter supplied one. It SHALL also carry the data and type
of that filter, when the filter has a `data` member (INV-CT-17). Services without intent-filters can still be
started by ComponentName alone. Every such service yields one component-name-only tuple, without reading
`targetMethods` (INV-CT-16).

`AndroidDevice.startService()` SHALL use reflection to handle different `IActivityManager.startService()` signatures across Android versions (M through Q).

#### Scenario: Service started
- **WHEN** `triggerComponent()` selects a service
- **THEN** APE-RV SHALL start it via `AndroidDevice.startService(intent)`
- **AND** when the service's filter has `"data": {"schemes": ["myapp"]}`, the intent SHALL carry `setData(Uri.parse("myapp://"))` and no type

---

### Requirement: SystemBroadcastCatalog

APE-RV SHALL load a catalog of system broadcast actions with typed extras from
`/data/local/tmp/system-broadcast.json` on the device. The catalog is loaded **if and only if** `COMPONENT_TRIGGER`
is mounted (INV-CT-18). The tool pushes it for the four `as*` cells and not for `base` or `mop`. The catalog
provides lookup by action string.

For each entry, extras are parsed from the `adb` command field: `--es` (String), `--ei` (int), `--ez` (boolean), `--el` (long), `--ef` (float).

If the catalog file is absent on a plan that mounts the trigger, an empty catalog SHALL be used: no extras for any
action, and no error.

#### Scenario: Catalog lookup for known action
- **WHEN** `COMPONENT_TRIGGER` is mounted and `SystemBroadcastCatalog.lookup("android.net.conn.CONNECTIVITY_CHANGE")` is called
- **THEN** it SHALL return typed extras for that action

#### Scenario: Catalog lookup for unknown action
- **WHEN** `SystemBroadcastCatalog.lookup("com.example.CUSTOM")` is called
- **THEN** it SHALL return an empty list
- **AND** on the `base` and `mop` cells, which do not mount the trigger, the catalog file SHALL NOT be read at all

---

### Requirement: Config — componentPercentage

`ape.componentPercentage` (double) SHALL control the probability of component triggering per step. The key is
owned by the STATIC member `COMPONENT_TRIGGER` (POSITIVE, neutral value 0) and read through the plan. Its jar
default SHALL be `0.0`. The STATIC group block sets it to `0.03` (design D-B13), so the cells `as`, `as_mop`,
`as_llm` and `as_mop_llm` trigger at 3 % per step unless a variant overrides it.

Component triggering is decoupled from MOP. Neither `ape.mop` nor any MOP key SHALL change `componentPercentage`,
or which components the pool holds. Setting `ape.componentPercentage > 0` explicitly without the STATIC group
aborts resolution as a missing dependency (`run-spec`). The value is clamped to `[0, 1]` at resolution.

#### Scenario: Default with mopDataPath set
- **WHEN** `ape.properties` sets `ape.staticDataPath` and `ape.mop=true` but neither `ape.static` nor `ape.componentPercentage` (the `mop` cell)
- **THEN** `COMPONENT_TRIGGER` SHALL be absent from the plan (triggering disabled)
- **AND** no component triggering SHALL occur

#### Scenario: Default without mopDataPath
- **WHEN** `ape.properties` sets neither `ape.staticDataPath` nor `ape.componentPercentage` (the `base` cell)
- **THEN** the plan's `componentPercentage` SHALL be `0.0` and no `ComponentTrigger` stage SHALL exist

#### Scenario: Explicit override enables triggering
- **WHEN** the `as` cell is resolved with an explicit `ape.componentPercentage=0.10`
- **THEN** the plan's `componentPercentage` SHALL be `0.10`, whatever the MOP group's state
- **AND** triggering SHALL fire with probability `0.10` per step that reaches the stage (subject to INV-CT-01)

---

### Requirement: ComponentName Package Derivation

`StatefulAgent.dispatchTrigger` SHALL build the `ComponentName` for every trigger kind (broadcast, service and
activity). It SHALL pair the app package from the artifact's `package` field with the component's
fully-qualified class name. For the probabilistic pool, the package is read through the static provider
(`StaticGuiModel`), because that pool reads no MOP data (INV-CT-16). It SHALL NOT derive the package by truncating
the class name at its last dot. That heuristic yields the enclosing Java namespace, which differs from the app
package for any component declared in a subpackage (e.g. class `br.unb.app.receivers.MyReceiver` in app package
`br.unb.app`). The result would be an invalid `ComponentName` and a silently failed trigger.

#### Scenario: subpackaged receiver gets a valid ComponentName
- **WHEN** the JSON declares `package="br.unb.app"` and a receiver class `br.unb.app.receivers.MyReceiver` is triggered
- **THEN** the `ComponentName` SHALL be `("br.unb.app", "br.unb.app.receivers.MyReceiver")`

#### Scenario: top-level component unchanged
- **WHEN** the component class lives directly in the app package (`br.unb.app.MainReceiver`)
- **THEN** the `ComponentName` SHALL be `("br.unb.app", "br.unb.app.MainReceiver")` (same result as before)

---

### Requirement: Cadence-Based MOP Activity Launch

The cadence launcher SHALL be a `MopLauncher` decision stage, assembled when the plan enables activity triggering and MOP data is loaded, positioned after the guards and the LLM stages and before the `ComponentTrigger` and `MopCoin` stages — so a guard or an enabled LLM stage takes precedence at a shared step (`decision-pipeline` INV-DP-02, design D-A8). The stage SHALL own its episode state: the dedicated launcher step counter, the per-run launch budget counter, and the round-robin cursor (`decision-pipeline` INV-DP-07).

**Global cadence** (design D-A16, D-B11). The stage's `onStepOpen` hook SHALL increment the launcher step counter once per step, on every step, whichever stage then takes it (`decision-pipeline` INV-DP-08). A step decided by an earlier stage (a guard or an LLM stage) therefore advances the counter. The former behaviour, in which an LLM-preempted step never reached the increment (finding 3.3-1), is removed: it starved the launcher in proportion to the LLM's dose. The stage's `decide()` SHALL fire on the first step on which the counter is at or past the configured cadence and the pipeline reaches the stage. Reaching the stage means that no guard and no LLM stage took the step. When the counter is at or past the configured cadence (`activityTriggerStagnationStep`, injected from the plan; the property name is kept for the rv-android `tool.py` mapping) and the per-run launch budget is not exhausted (`activityTriggerMaxPerRun == 0` OR launches emitted this run `< activityTriggerMaxPerRun`), the stage SHALL reset the step counter to 0 and attempt to select a launch candidate. The stage SHALL NOT read or reset `graphStableCounter`.

**Eligibility.** A manifest activity is eligible when it satisfies ALL of — a member of the arm's MOP census (`className` in `MopData.getMopActivities()`, the `activityHasMop` reachability-augmented set of INV-MOP-27; the component-level `ComponentInfo.reachesMop` field SHALL NOT be used — it false-negatives lambda-triggered activities), `permission == null`, not the main activity, currently unvisited (`Graph.getActivityNode(className) == null` at fire time), and **not framework/tooling-namespaced**: a candidate whose `className` starts with any prefix in the code constant `FRAMEWORK_ACTIVITY_PREFIXES` — `android.`, `androidx.`, `com.google.android.`, `kotlin.`, `kotlinx.`, `junit.`, `org.junit.`, `leakcanary.` — SHALL be ineligible. The match is a class-name **prefix** match (never substring). The denylist is a fixed code constant located with the candidate selection (a correctness filter, not a tunable — no configuration key). Eligibility SHALL NOT include an `exported` test: the dispatch path (`AndroidDevice.startActivity` → `IActivityManager.startActivity` from uid 2000) launches non-exported activities. Under this change the rule is structural rather than stated — the compact artifact carries no `exported` field at all (`static-analysis-entrypoints` §7), so there is nothing for the stage to consult. There SHALL be no fallback outside the MOP census: when the census yields no eligible candidate, no launch occurs. Eligibility is the same in both scoring modes.

**Choice among eligible activities, by scoring mode** (`ape.mopScoring`, `run-spec` "Feature Model with Declared Dependencies"; design D20):

- **`flag`** (default): the launch candidate is the next eligible manifest activity in round-robin order persisted across firings, exactly as before this change.
- **`distance`**: the launch candidate is chosen by a rotation over the following order. An activity's key is the smallest `d` over the pairs `[i, d]` of its `activityDist` entry whose target `i` is not retired and whose `d ≤ ape.mopLauncherDmax` (default 6) — `MopRetirement.nearest(pairs, mopLauncherDmax)` of the `mop-guidance` design API. Activities are sorted by that key ascending, ties by manifest order. An activity with no such pair (no `activityDist` entry, only distances above the bound, or only retired targets) sorts after every activity that has one, in manifest order among themselves. The order is recomputed at each firing point, so a target that retired since the last firing no longer counts. The stage walks this order as a rotation: it remembers the sort position `(key, manifest index)` of the activity it selected last, and the candidate is the first eligible activity whose position comes after it, wrapping to the head of the order when none does. The remembered position is the stage's round-robin cursor (`decision-pipeline` INV-DP-07), which under `distance` holds the sort position of the last selection instead of a firing count; the stage still owns that one field (INV-DP-07), whose content changes kind with the mode. It moves at every selection, whatever the launch result. Without the rotation, an activity whose launch is refused would stay unvisited at the head of the order and be selected at every firing point (the analysis's "rodízio dentro de faixas", `20261006_analise_rigorosa_plano_guia_mop.md:288`). The keys are recomputed at every firing, so a retirement between two firings can move the last selection to a later position, and the rotation can return to it before it has covered the others: the rotation is a full cycle only while the keys stay fixed (review NT-03).

A launched activity that opens becomes visited and leaves the eligible set, so neither mode selects it again. An activity whose launch does not open it stays eligible: under `flag` the cursor still advances by one position per firing, as before; under `distance` it keeps its place in the order, and the rotation moves past it to the next activity (INV-CT-14 forbids reacting to the recorded result; the rotation does not read it).

When a candidate exists, the stage SHALL increment the per-run launch counter, abandon a non-empty SATA buffer without weakening (`decision-pipeline` INV-DP-15, `dec.buf="launcher"`), and return `StageResult.Select` carrying a first-class `EVENT_TRIGGER_ACTIVITY` action with the candidate's class name and, when the artifact supplies one, its deep-link URI — the action is the step (it produces exactly one step record with `dec.src="launcher"` and `dec.ch="launcher"` and is NOT a graph edge label, mirroring `EVENT_RESTART` semantics; the record is written by the non-model branch of `StatefulAgent.resolveNewAction`). The step's owner SHALL be `launcher` (`dec.src`, `dec.own.i`), and each launch adds one to `RUN_END.counters.launches`. When no candidate exists (census exhausted, all visited, or all denylisted), the stage SHALL return `Continue` with no side effects beyond the already-performed step-counter reset — in particular the launch counter SHALL NOT be incremented.

Event generation SHALL dispatch the action as an explicit intent (`ComponentName(StaticGuiModel.getPackageName(), className)`, `FLAG_ACTIVITY_NEW_TASK`) via `AndroidDevice.startActivity`; the package component SHALL be `StaticGuiModel.getPackageName()` and SHALL NOT be derived from the target class name (INV-CT-04, "ComponentName Package Derivation"). When the candidate carries a non-null `deepLinkUri`, the intent SHALL instead be `ACTION_VIEW` with `Uri.parse(deepLinkUri)`, targeted at the **package** (`Intent.setPackage(StaticGuiModel.getPackageName())`) and carrying no component: the platform, not the launcher, resolves which activity of that package handles the URI. The URI SHALL be taken verbatim from the artifact: the jar SHALL NOT inspect intent-filter data, which no longer exists on the wire (INV-CT-13; the assembly rule itself is `static-analysis-entrypoints` INV-DRV-07, applied host-side by the generator).

*This sentence formerly read "still targeted at the component", and had done since the requirement was written (`2026-07-08-activity-frontier`, design item 6). No code path has ever set the component on this branch — `MonkeySourceApe.generateActivityTriggerEvent` calls `setAction`/`setData`/`setPackage` and returns. The restatement corrects the spec to the shipped behaviour rather than the code to the spec, because this change relocates **where the URI is computed** and changing what is dispatched is not in its scope: the group-4 gate compares derived data, so it could not attest a dispatch change, and the launcher carries the study's strongest mechanism result. The two are distinguishable only when two activities of one package assemble the same URI — measured over the pinned 345 at **7 apps** (`content://` ×3, `file://` ×2, `geo://`, `mailto://`), every one of them a case where `scheme + "://" + host + path` discards the `mimeTypes`/`pathPrefixes`/`pathPatterns` that actually discriminate those filters. In those 7, the launcher can open an activity other than the one it selected. That is a defect in the assembly rule (INV-DRV-07), not in the dispatch, and is recorded as issue #17 rather than repaired here.* Activities SHALL NOT participate in the `componentPercentage` probabilistic pool under any configuration.

**The dispatch SHALL record whether it was accepted.** `AndroidDevice.startActivity` today returns `true` whenever the reflective call did not throw, discarding the `START_*` code that `IActivityManager.startActivity` returns one frame below — so a launch refused by the platform and one that opened the activity are, in every artifact this study has, the same evidence. The method SHALL surface that result code, and the launcher SHALL pass it to the sink as the step record's `dec.comp` (`event-sink` capability). This is a recording change and nothing more: no retry, no re-dispatch, no second probe of the device, and no effect on the round-robin cursor, the distance order or the launch budget, which continue to count *returned actions* exactly as INV-CT-12 requires. It matters because the launcher carries this study's strongest mechanism result — roughly 1,075 direct launches in the guided arm against none in the control, and an activity-coverage gain of about 14 percentage points that rests on them — while the trace currently cannot say how many of those intents the platform accepted.

The cadence (`ape.activityTriggerStagnationStep`, default `50`; a configured value `<= 0` clamped to the default at plan resolution, logged) and the per-run cap (`ape.activityTriggerMaxPerRun`, default `0` = unlimited; `< 0` clamped to `0`, logged) SHALL be injected into the stage from the resolved plan at assembly, and so SHALL the scoring mode and `ape.mopLauncherDmax` (both owned by the `MOP` feature, `run-spec`); the stage SHALL NOT read static `Config` (`decision-pipeline` INV-DP-12). The retirement state the `distance` order reads is the run's single `MopRetirement` instance owned by `StatefulAgent` (`mop-guidance` "Target Retirement"); the stage reads it and never writes it. Their plan grounding from `rearch-02-runspec` is **carried forward unchanged**: both cadence keys are declared in the run-spec `Feature` model as sub-parameters owned by the `ACTIVITY_TRIGGER` feature (which requires `MOP`); when the feature is absent from the resolved plan no launcher mechanism exists and the two keys are accepted only at their neutral values (INV-RUN-05 of `run-spec` — the recorded substitute for the dissolved INV-ARCH-06 kill-switch registration). `Config.triggerMopFirst` SHALL NOT exist (deleted at stage 2). Only actually returned `EVENT_TRIGGER_ACTIVITY` actions consume budget; a firing whose candidate scan comes up empty does not. Sustained exploration yields a firing point every `cadence` steps of the run, independent of graph growth. When a guard or an LLM stage takes the step on which the counter reaches the cadence, the firing is deferred to the next step that reaches the stage, and the next window starts from that firing. Expected launches per run ≈ `min(maxPerRun, steps/cadence, |unvisited eligible census|)`, minus the deferral.

- **INV-CT-05 (amended)**: At most one launch attempt SHALL occur per cadence window: the counter is reset at the firing point and must again reach the cadence before the next firing. The stage SHALL NOT read or reset `graphStableCounter`. The counter SHALL advance exactly once per step, in `onStepOpen`, whichever stage takes the step (global cadence, D-A16; `decision-pipeline` INV-DP-08). Only the stage's `decide()` resets it.
- **INV-CT-06 (unchanged)**: Every launched activity SHALL satisfy, at fire time: member of `MopData.getMopActivities()`, permission-free, non-main, same-package, unvisited, and not framework/tooling-namespaced (`FRAMEWORK_ACTIVITY_PREFIXES` prefix match). Exported status SHALL NOT be consulted — and after this change cannot be, the field having left the wire.
- **INV-CT-07 (unchanged in effect, relocated in mechanism)**: every launch model-visible as exactly one step record with `dec.src="launcher"`; no graph edge labeled by the launch. Its dispatch precondition — the deep-link URI — is now a wire field rather than a jar-side computation (INV-CT-13).
- **INV-CT-14**: The launch result SHALL be recorded on the launch's own step record and SHALL NOT be acted upon. No control-flow decision — retry, re-dispatch, candidate re-selection, cursor or budget adjustment — may depend on it, in the jar or in the harness. It is an observation of a value the dispatch already produced, and the launcher's behaviour with the recording present SHALL be identical to its behaviour without it.
- **INV-CT-08 (unchanged)**: when the plan does not enable activity triggering, no activity SHALL ever be launched by APE-RV — realized structurally: no `MopLauncher` stage exists (neither does the probabilistic pool contain activities).
- **INV-CT-10 (unchanged)**: no `EVENT_TRIGGER_ACTIVITY` action SHALL ever carry a class name matching a `FRAMEWORK_ACTIVITY_PREFIXES` prefix; the denylist SHALL be consulted only inside the stage's candidate eligibility — no second exclusion mechanism.
- **INV-CT-12 (unchanged)**: when `ape.activityTriggerMaxPerRun` is `N > 0`, the number of `EVENT_TRIGGER_ACTIVITY` actions emitted in a run SHALL never exceed `N`; when `0`, no cap applies. Budget accounting SHALL count only returned actions (an empty candidate scan consumes nothing).
- **INV-CT-15**: The scoring mode SHALL change only which eligible activity is chosen, never which activities are eligible. Under `ape.mopScoring=flag` the stage's choice SHALL be identical to the round-robin of the pre-change requirement; under `distance` it SHALL be a function of the eligible set, the artifact's `activityDist`, the retirement state, `ape.mopLauncherDmax` and the position of the previous selection, with manifest order as the only tie-break, and never of a launch result.

**On the scenario headers below.** `openspec archive` matches scenarios by name and cannot tell a rename from a deletion, so a renamed scenario must keep the main spec's header. Two here read in retired vocabulary as a result: "clamped at load" names a site stage 2 moved to plan resolution, and "launcher disabled" implies a kill switch stage 2 dissolved into feature absence. The bodies are current; the headers are the archive's cost. The scenarios that do not name a scoring mode hold in both.

#### Scenario: deep link dispatched from the wire field
- **WHEN** the artifact's activity entry for `com.x.DetailActivity` carries `"deepLinkUri": "myapp://detail/x"` and the stage selects it
- **THEN** the injected intent SHALL be `ACTION_VIEW` with `Uri.parse("myapp://detail/x")` and `setPackage("com.x")`, with no component set
- **AND** no jar code SHALL have inspected an intent-filter, a scheme list, a host or a path to produce it

#### Scenario: activity without a deep link falls back to the explicit component
- **WHEN** the artifact's activity entry omits `deepLinkUri`
- **THEN** the injected intent SHALL be the explicit component intent with `FLAG_ACTIVITY_NEW_TASK`
- **AND** the launch SHALL otherwise behave identically to one carrying a URI (same step accounting, same budget consumption, same `[APE-STEP]` line)

#### Scenario: cadence fires independently of graph growth
- **WHEN** the cadence is 10, the exploration graph grows on every step (no stagnation ever), and no guard or LLM stage takes any step
- **THEN** the stage SHALL fire at steps 10, 20, 30 of the run

#### Scenario: periodic firing under the default cadence
- **WHEN** the cadence is unset (default 50) and eligible census candidates remain
- **THEN** the stage SHALL fire on the run's 50th step when that step reaches it, reset its step counter, and fire again after each further 50 steps

#### Scenario: LLM-preempted steps do not advance the cadence (finding 3.3-1)
- **WHEN** the stage's counter is at 49 and the next step is decided by an LLM stage
- **THEN** the counter SHALL be 50 after that step, because `onStepOpen` advanced it, and the stage SHALL NOT have fired
- **AND** the stage SHALL fire on the next step that falls through to it, with the counter at 51
- **AND** this body replaces the pre-change behaviour (3.3-1); the header is kept because `openspec archive` matches scenarios by name

#### Scenario: non-exported census activity is launched
- **WHEN** the arm census contains `com.x.CryptoActivity`, `permission=null`, unvisited, declared `android:exported="false"` in the app's manifest
- **THEN** the stage SHALL select it and return an `EVENT_TRIGGER_ACTIVITY` action for it
- **AND** the manifest's export status SHALL NOT have reached the decision, the artifact carrying no `exported` field to convey it

#### Scenario: non-census activity is never launched
- **WHEN** `com.x.AboutActivity` is exported, permission-free, non-main and unvisited but not in `MopData.getMopActivities()`
- **THEN** the stage SHALL NOT select it, even when no census candidate is eligible (no fallback), in either scoring mode
- **AND** under `distance` an `activityDist` entry for it SHALL NOT make it eligible

#### Scenario: census exhausted falls through without side effects
- **WHEN** a firing point is reached but every census activity is visited or denylisted
- **THEN** no launch SHALL occur, the launch budget SHALL be unchanged, the step counter SHALL reset, and the stage SHALL return `Continue` (the `ComponentTrigger`, `MopCoin` and `SataChain` stages decide the step)

#### Scenario: permission-gated census activity skipped
- **WHEN** a census activity declares `permission="android.permission.MANAGE_DOCUMENTS"`
- **THEN** the candidate selection SHALL skip it, whatever its distance under `distance`

#### Scenario: denylisted census entry skipped
- **WHEN** the census contains `androidx.activity.ComponentActivity` (over-approximated reachability) and `com.x.HistoryActivity`, both otherwise eligible
- **THEN** the stage SHALL skip the `androidx.` entry and launch `com.x.HistoryActivity`, in either scoring mode

#### Scenario: cap exhausts the launch budget
- **WHEN** `activityTriggerMaxPerRun=2` and two `EVENT_TRIGGER_ACTIVITY` actions have been emitted this run
- **THEN** the firing predicate SHALL return false at every subsequent firing point and the stage SHALL return `Continue`

#### Scenario: cap zero means unlimited
- **WHEN** `activityTriggerMaxPerRun=0` (default) and 10 launches have already been emitted
- **THEN** the stage SHALL still fire at the next firing point (subject to the other gates)

#### Scenario: invalid values clamped at load
- **WHEN** the properties set `ape.activityTriggerStagnationStep=0` and `ape.activityTriggerMaxPerRun=-3`
- **THEN** plan resolution SHALL clamp them to `50` and `0` respectively and log each clamp

#### Scenario: launcher disabled
- **WHEN** the plan does not enable activity triggering
- **THEN** no `MopLauncher` stage SHALL exist, no `EVENT_TRIGGER_ACTIVITY` step SHALL ever be produced, and the probabilistic pool SHALL contain no activities

#### Scenario: arm contrast is the launched set
- **WHEN** the control arm's census (`ape.mopActivitySourceComponents=false`, so the artifact's `mopActivities`) is `{A}` and the treatment arm's (`=true`, so its `mopActivitiesAugmented`) is `{A, B, C}`
- **THEN** the control arm's `MopLauncher` SHALL only ever launch `A` while the treatment arm's can launch `A`, `B` and `C`
- **AND** the contrast SHALL come from the census the stage reads, never from the stage's own firing rule — both arms assemble the same stage with the same cadence, cap, scoring mode and choice rule
- **AND** this SHALL remain true after the union moves host-side: the two sets arrive precomputed on the wire and the stage still reads exactly one of them, selected at load by the flag (`mop-guidance` INV-MOP-27), so relocating *where* the sets are computed changes neither which set an arm reads nor what the contrast measures

#### Scenario: distance mode launches the nearest eligible activity first
- **WHEN** `ape.mopScoring=distance`, the manifest lists eligible activities `P`, `Q`, `R` in that order, and their `activityDist` entries are `P: [[4, 5]]`, `Q: [[0, 2], [7, 3]]`, `R: [[2, 2]]`, no target retired
- **THEN** successive firing points SHALL launch `Q`, then `R` (same distance 2, later in manifest order), then `P`, each one leaving the eligible set once it is visited

#### Scenario: distances beyond the bound and retired targets do not count
- **WHEN** `ape.mopScoring=distance`, `ape.mopLauncherDmax=6`, eligible activities `P`, `Q`, `R` in manifest order have `P: [[1, 7]]`, `Q: [[3, 1], [5, 4]]` with target 3 retired, and `R` has no `activityDist` entry
- **THEN** the order SHALL be `Q` (key 4, from the unretired target 5), then `P` and `R` in manifest order, both without a key

#### Scenario: flag mode keeps the round-robin
- **WHEN** `ape.mopScoring=flag` and the artifact carries `activityDist` for every eligible activity
- **THEN** the stage SHALL select exactly the activity the pre-change round-robin selects from the same cursor, and SHALL NOT read `activityDist` or the retirement state

#### Scenario: a refused launch is not retired under distance
- **WHEN** `ape.mopScoring=distance`, the first activity of the order is `Q`, and its launch returns a non-success `START_*` code, so `Q` stays unvisited
- **THEN** the next firing point SHALL select the eligible activity that follows `Q` in the order, and `Q` SHALL be selected again only after the rotation wraps
- **AND** no target of `Q` SHALL be counted toward retirement by the failed launch (INV-CT-14)

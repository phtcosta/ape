# Delta: Component Triggering (gh-tbd-base-equals-ape)

## MODIFIED Requirements

### Requirement: Cadence-Based MOP Activity Launch

The cadence launcher SHALL be a `MopLauncher` decision stage (`MopLauncherStage`), assembled when the plan mounts `MOP_LAUNCHER` (a MOP member; key `ape.activityTriggerEnabled`), positioned after the guards and the LLM stages and before the `ComponentTrigger` and `MopCoin` stages — so a guard or an enabled LLM stage takes precedence at a shared step (`decision-pipeline` INV-DP-02, design D-A8). The stage SHALL own its episode state: the dedicated launcher step counter, the per-run launch budget counter, the round-robin cursor and the set of activities it launched (`decision-pipeline` INV-DP-07).

**Global cadence** (design D-A16, D-B11). The stage's `onStepOpen` hook SHALL increment the launcher step counter once per step, on every step, whichever stage then takes it (`decision-pipeline` INV-DP-08). A step decided by an earlier stage (a guard or an LLM stage) therefore advances the counter, so the LLM's dose does not starve the launcher. The stage's `decide()` SHALL fire on the first step on which the counter is at or past the configured cadence and the pipeline reaches the stage. Reaching the stage means that no guard and no LLM stage took the step. When the counter is at or past the configured cadence (`activityTriggerStagnationStep`, injected from the plan; the property name is kept for the rv-android `tool.py` mapping) and the per-run launch budget is not exhausted (`activityTriggerMaxPerRun == 0` OR launches emitted this run `< activityTriggerMaxPerRun`), the stage SHALL reset the step counter to 0 and attempt to select a launch candidate. The stage SHALL NOT read or reset `graphStableCounter` (INV-CT-05).

**Eligibility** (`MopLauncherStage.isEligible`, INV-CT-06). A manifest activity (the activities, the main activity and the package are read from `StaticGuiModel`; the MOP census and `activityDist` from `MopData`) is eligible when it satisfies ALL of — a member of the arm's MOP census (`className` in `MopData.getMopActivities()`, the selected set of `mop-guidance` INV-MOP-27; the component-level `ComponentInfo.reachesMop` field SHALL NOT be used — it false-negatives lambda-triggered activities), `permission == null`, not the main activity (neither `isMain` nor equal to the main activity's name), currently unvisited (the activity has no `ActivityNode` in the graph at fire time), **not launched before in this run** (the stage's set of launched activities, filled when the agent keeps the stage's launch; design D-B15), and **not framework/tooling-namespaced**: a candidate whose `className` starts with any prefix in the code constant `FRAMEWORK_ACTIVITY_PREFIXES` — `android.`, `androidx.`, `com.google.android.`, `kotlin.`, `kotlinx.`, `junit.`, `org.junit.`, `leakcanary.` — SHALL be ineligible (INV-CT-10). The match is a class-name **prefix** match (never substring). The denylist is a fixed code constant located with the candidate selection (a correctness filter, not a tunable — no configuration key). There is no package test: the census is the only membership filter, and the launch intent is addressed to the artifact's package. Eligibility SHALL NOT include an `exported` test: the dispatch path (`AndroidDevice.startActivity` → `IActivityManager.startActivity` from uid 2000) launches non-exported activities, and the compact artifact carries no `exported` field at all (`static-analysis-entrypoints` "Derived compact MOP artifact — projection contents" item 7), so there is nothing for the stage to consult. There SHALL be no fallback outside the MOP census: when the census yields no eligible candidate, no launch occurs. Eligibility is the same in both scoring modes.

**Choice among eligible activities, by scoring mode** (`ape.mopScoring`, `run-spec` "Feature Model with Declared Dependencies"; design D20; INV-CT-15):

- **`flag`** (default): the launch candidate is the next eligible manifest activity in round-robin order persisted across firings, over an eligible set that holds no activity launched before in this run (design D-B15).
- **`distance`**: the launch candidate is chosen by a rotation over the following order. An activity's key is the smallest `d` over the pairs `[i, d]` of its `activityDist` entry whose target `i` is not retired and whose `d ≤ ape.mopLauncherDmax` (default 6) — `MopRetirement.nearest(pairs, mopLauncherDmax)` of the `mop-guidance` design API. Activities are sorted by that key ascending, ties by manifest order. An activity with no such pair (no `activityDist` entry, only distances above the bound, or only retired targets) sorts after every activity that has one, in manifest order among themselves. The order is recomputed at each firing point, so a target that retired since the last firing no longer counts. The stage walks this order as a rotation: it remembers the sort position `(key, manifest index)` of the activity it selected last, and the candidate is the first eligible activity whose position comes after it, wrapping to the head of the order when none does. The remembered position is the stage's round-robin cursor (`decision-pipeline` INV-DP-07), which under `distance` holds the sort position of the last selection instead of a firing count; the field's content changes kind with the mode, and the stage's launched set (design D-B15) is a second field of its episode state (INV-DP-07). It moves at every selection, whatever the launch result. An activity whose launch is refused leaves the eligible set anyway (D-B15); the rotation decides the order among the activities not yet launched (the analysis's "rodízio dentro de faixas", `20261006_analise_rigorosa_plano_guia_mop.md:288`). The keys are recomputed at every firing, so a retirement between two firings can move the last selection to a later position, and the rotation can return to it before it has covered the others: the rotation is a full cycle only while the keys stay fixed (review NT-03).

A launched activity leaves the eligible set for the rest of the run, whether or not its launch opened it: one that opens becomes visited, and every launched one is in the stage's launched set (design D-B15). This keeps an activity that crashes on a cold launch from being selected again at most firings (`RecordAudioActivity` of spell4wiki in `e03iggsmk`: 3 of 4 launches in `mop`, 7 of 10 in `as_mop`, before D-B15). The launched set is filled from the stage's own kept selections, never from the launch result, so INV-CT-14 holds.

When a candidate exists, the stage SHALL abandon a non-empty SATA buffer without weakening (`decision-pipeline` INV-DP-15, `dec.buf="launcher"`), and return `StageResult.Select` carrying a first-class `EVENT_TRIGGER_ACTIVITY` action (`ActivityTriggerAction`) with `StaticGuiModel.packageName()`, the candidate's class name and, when the artifact supplies one, its deep-link URI — the action is the step (it produces exactly one step record with `dec.src="launcher"` and `dec.ch="launcher"` and is NOT a graph edge label, mirroring `EVENT_RESTART` semantics; the record is written by the non-model branch of `StatefulAgent.resolveNewAction`; INV-CT-07). The step's owner SHALL be `launcher` (`dec.src`, `dec.own.i`).

**A launch is committed when the agent keeps it** (option b of review WR-02). After `checkRestart`, the agent forwards a kept selection to the stages' `onSelectionKept` hook (`decision-pipeline` "Episode-State Ownership and Reset"). When the kept action is the stage's launch, the stage SHALL add the activity to its launched set, increment the per-run launch counter, and count one `RUN_END.counters.launches`. A launch that a post-selection restart replaces (a plan without `APE_FIXES`, such as the `mop` cell; `decision-pipeline` "Post-Selection Restart Model Effect") is not executed and SHALL commit none of the three, so its activity stays eligible and the budget unspent. The cadence reset and the round-robin cursor move at the firing, as for a kept launch. With the `Restart` stage mounted no restart replaces a selection, so every launch is kept on the step that selects it. When no candidate exists (census exhausted, all visited, all launched before in this run, or all denylisted), the stage SHALL return `Continue` with no side effects beyond the already-performed step-counter reset — in particular the launch counter SHALL NOT be incremented (INV-CT-12).

Event generation (`MonkeySourceApe.generateActivityTriggerEvent`) SHALL dispatch the action as an explicit intent (`ComponentName(action.getPackageName(), action.getClassName())`) via `AndroidDevice.startActivity`, which adds `FLAG_ACTIVITY_NEW_TASK`; the package component SHALL be the action's package, `StaticGuiModel.packageName()`, and SHALL NOT be derived from the target class name (INV-CT-04, "ComponentName Package Derivation"). When the candidate carries a non-null `deepLinkUri`, the intent SHALL instead be `ACTION_VIEW` with `Uri.parse(deepLinkUri)`, targeted at the **package** (`Intent.setPackage(...)`) and carrying no component: the platform, not the launcher, resolves which activity of that package handles the URI. The URI SHALL be `deepLinkUri` verbatim: the launcher SHALL NOT read intent-filter `data` (the assembly rule itself is rv-android INV-DRV-07, applied host-side by the generator). The filter `data` that the wire carries since D-B13 is read only by the probabilistic pool's receiver and service dispatch (INV-CT-17), never by the launcher.

When two activities of one package assemble the same URI, the platform can open an activity other than the one the launcher selected. Measured over the pinned 345 apps, this happens in **7 apps** (`content://` ×3, `file://` ×2, `geo://`, `mailto://`), each a case where `scheme + "://" + host + path` discards the `mimeTypes`/`pathPrefixes`/`pathPatterns` that discriminate those filters. That is a defect in the assembly rule (rv-android INV-DRV-07), not in the dispatch, and is recorded as issue #17. Activities SHALL NOT participate in the `componentPercentage` probabilistic pool under any configuration.

**The dispatch SHALL record whether it was accepted.** `AndroidDevice.startActivity` SHALL return an `AndroidDevice.LaunchResult` carrying the `START_*` code that `IActivityManager.startActivity` returns (`code`), or, when the platform never answered, an error label (`no_method` or `exception`) and no code. `generateActivityTriggerEvent` SHALL pass it to the sink (`EventSink.componentLaunch`) as the launch step record's `dec.comp` (`event-sink` capability), and log a warning `[APE-RV] Triggering activity failed: <package>/<class>` when the intent was not dispatched. This is a recording and nothing more: no retry, no re-dispatch, no second probe of the device, and no effect on the round-robin cursor, the distance order or the launch budget, which continue to count *kept actions* exactly as INV-CT-12 requires (INV-CT-14). It matters because the launcher carries this study's strongest mechanism result — roughly 1,075 direct launches in the guided arm against none in the control, and an activity-coverage gain of about 14 percentage points that rests on them.

The cadence (`ape.activityTriggerStagnationStep`, default `50`; a configured value `<= 0` is clamped to `50` at plan resolution) and the per-run cap (`ape.activityTriggerMaxPerRun`, default `0` = unlimited; `< 0` is clamped to `0`) SHALL be injected into the stage from the resolved plan at assembly, and so SHALL the scoring mode and `ape.mopLauncherDmax` (both owned by the `MOP` feature, `run-spec`); the stage SHALL NOT read static `Config` (`decision-pipeline` INV-DP-12). The two clamps are silent (`RunSpec.clamp` writes no log line); the plan carries the clamped value. The retirement state the `distance` order reads is the run's single `MopRetirement` instance owned by `StatefulAgent` (`mop-guidance` "Target Retirement"); the stage reads it and never writes it. Both cadence keys are declared in the run-spec `Feature` model as sub-parameters owned by the `MOP_LAUNCHER` feature (which requires `MOP`); when the feature is absent from the resolved plan no launcher mechanism exists and the two keys are accepted only at their neutral values (`run-spec` INV-RUN-05). There is no `triggerMopFirst` key and no `Config.triggerMopFirst` field. Only kept `EVENT_TRIGGER_ACTIVITY` actions consume budget; a firing whose candidate scan comes up empty does not, and neither does a launch a post-selection restart replaces. Sustained exploration yields a firing point every `cadence` steps of the run, independent of graph growth. When a guard or an LLM stage takes the step on which the counter reaches the cadence, the firing is deferred to the next step that reaches the stage, and the next window starts from that firing. Expected launches per run ≈ `min(maxPerRun, steps/cadence, |unvisited eligible census|)`, minus the deferral.

The launcher's invariants are INV-CT-05, INV-CT-06, INV-CT-07, INV-CT-08, INV-CT-10, INV-CT-12, INV-CT-14 and INV-CT-15, stated in the Invariants section.

**On the scenario headers below.** `openspec archive` matches scenarios by name and cannot tell a rename from a deletion, so a renamed scenario must keep the main spec's header. Two here read in retired vocabulary as a result: "clamped at load" names a site that is plan resolution, and "launcher disabled" names what is feature absence. The bodies are current; the headers are the archive's cost. The scenarios that do not name a scoring mode hold in both.

#### Scenario: an activity whose launch did not open it is not launched again
- **WHEN** the census holds `com.x.RecordAudioActivity` and `com.x.SearchActivity`, both eligible, the stage launches `RecordAudioActivity` at a firing and the app crashes, so the activity never becomes visited
- **THEN** at every later firing of the run `RecordAudioActivity` SHALL NOT be eligible, under `flag` and under `distance` alike, and `SearchActivity` SHALL be selected while it is eligible
- **AND** when no other activity is eligible, the firing SHALL find no candidate (`dec.own.fb:"ineligible"`) and the launch counter SHALL NOT be incremented
- **AND** no input of the decision SHALL be the launch result (`dec.comp`, INV-CT-14)

#### Scenario: a launch replaced by a post-selection restart commits nothing
- **WHEN** in the `mop` cell the stage selects `com.x.Target` on a step on which a periodic restart is due, so `checkRestart` replaces the launch by a start action
- **THEN** `com.x.Target` SHALL NOT enter the launched set, the launch counter SHALL NOT be incremented and `RUN_END.counters.launches` SHALL NOT count the step
- **AND** at the next firing `com.x.Target` SHALL be eligible while it stays unvisited

#### Scenario: deep link dispatched from the wire field
- **WHEN** the artifact's activity entry for `com.x.DetailActivity` carries `"deepLinkUri": "myapp://detail/x"` and the stage selects it
- **THEN** the injected intent SHALL be `ACTION_VIEW` with `Uri.parse("myapp://detail/x")` and `setPackage("com.x")`, with no component set
- **AND** the launcher SHALL NOT have read intent-filter `data`, a scheme list, a host or a path to produce it

#### Scenario: activity without a deep link falls back to the explicit component
- **WHEN** the artifact's activity entry omits `deepLinkUri`
- **THEN** the injected intent SHALL be the explicit component intent with `FLAG_ACTIVITY_NEW_TASK`
- **AND** the launch SHALL otherwise behave identically to one carrying a URI (same step accounting, same budget consumption, same step record)

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
- **AND** the header is kept because `openspec archive` matches scenarios by name: preempted steps do advance the counter

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
- **THEN** plan resolution SHALL clamp them to `50` and `0` respectively, silently, and the plan SHALL carry the clamped values

#### Scenario: launcher disabled
- **WHEN** the plan does not enable activity triggering
- **THEN** no `MopLauncher` stage SHALL exist, no `EVENT_TRIGGER_ACTIVITY` step SHALL ever be produced, and the probabilistic pool SHALL contain no activities

#### Scenario: arm contrast is the launched set
- **WHEN** the control arm's census (`ape.mopActivitySourceComponents=false`, so the artifact's `mopActivities`) is `{A}` and the treatment arm's (`=true`, so its `mopActivitiesAugmented`) is `{A, B, C}`
- **THEN** the control arm's `MopLauncher` SHALL only ever launch `A` while the treatment arm's can launch `A`, `B` and `C`
- **AND** the contrast SHALL come from the census the stage reads, never from the stage's own firing rule — both arms assemble the same stage with the same cadence, cap, scoring mode and choice rule
- **AND** the two sets arrive precomputed on the wire and the stage reads exactly one of them, selected at load (`mop-guidance` INV-MOP-27), so *where* the sets are computed changes neither which set an arm reads nor what the contrast measures

#### Scenario: distance mode launches the nearest eligible activity first
- **WHEN** `ape.mopScoring=distance`, the manifest lists eligible activities `P`, `Q`, `R` in that order, and their `activityDist` entries are `P: [[4, 5]]`, `Q: [[0, 2], [7, 3]]`, `R: [[2, 2]]`, no target retired
- **THEN** successive firing points SHALL launch `Q`, then `R` (same distance 2, later in manifest order), then `P`, each one leaving the eligible set once it is launched

#### Scenario: distances beyond the bound and retired targets do not count
- **WHEN** `ape.mopScoring=distance`, `ape.mopLauncherDmax=6`, eligible activities `P`, `Q`, `R` in manifest order have `P: [[1, 7]]`, `Q: [[3, 1], [5, 4]]` with target 3 retired, and `R` has no `activityDist` entry
- **THEN** the order SHALL be `Q` (key 4, from the unretired target 5), then `P` and `R` in manifest order, both without a key

#### Scenario: flag mode keeps the round-robin
- **WHEN** `ape.mopScoring=flag` and the artifact carries `activityDist` for every eligible activity
- **THEN** the stage SHALL select the next eligible activity of the manifest-order round-robin from its cursor (the eligible set excluding activities launched before in this run, design D-B15), and SHALL NOT read `activityDist` or the retirement state

#### Scenario: a refused launch is not retired under distance
- **WHEN** `ape.mopScoring=distance`, the first activity of the order is `Q`, and its launch returns a non-success `START_*` code, so `Q` stays unvisited
- **THEN** the next firing point SHALL select the eligible activity that follows `Q` in the order, and `Q` SHALL NOT be selected again in the run, because it is in the stage's launched set (design D-B15)
- **AND** no target of `Q` SHALL be counted toward retirement by the failed launch (INV-CT-14)

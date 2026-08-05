# Delta: Component Triggering (rearch-07-compact-static-artifact)

## Purpose

This delta records where the activity launcher's deep-link URI comes from after the static-analysis artifact is compacted. Nothing about *what gets dispatched* changes: an `EVENT_TRIGGER_ACTIVITY` still becomes an explicit component intent, or an `ACTION_VIEW` intent carrying a URI when the target activity declares a viewable deep link. What changes is who assembles that URI.

Today the jar does it: `MopLauncherStage.buildDeepLinkUri` walks the target activity's `intentFilters`, finds the first filter declaring `android.intent.action.VIEW` with a non-empty scheme list, and concatenates `scheme + "://" + host + path`. That requires the whole `IntentFilter.data` structure (D15 `DataSpec`) to be on the device. Under this change the device receives only the explorer-shaped projection, so the assembly moves host-side with every other parse-time semantic (`static-analysis-entrypoints` INV-DRV-07) and the wire carries the finished string as `components.activities[].deepLinkUri`.

The requirement is restated here in full for two reasons. First, the sentence naming the jar as the assembler becomes false and must not survive into the synced main spec. Second, the preceding stage's delta (`rearch-03-decision-pipeline`) rewrote this requirement around the `MopLauncher` stage and, in doing so, dropped the dispatch paragraph entirely — a MODIFIED block replaces the whole requirement at archive time, so leaving it out would silently delete the explicit-intent rule (INV-CT-04 grounding), the deep-link rule, and the exclusion of activities from the probabilistic pool. The paragraph is restored below, in its post-compaction form, on top of the `rearch-03` text this change inherits.

## Invariants

- **INV-CT-13**: The activity launcher SHALL NOT assemble a deep-link URI from intent-filter data. It SHALL read `ComponentInfo.deepLinkUri` — a value derived host-side per `static-analysis-entrypoints` INV-DRV-07 — and treat its absence as "dispatch the explicit component intent". No jar code path SHALL read an intent-filter `data` block, because none exists on the wire.

## MODIFIED Requirements

### Requirement: Cadence-Based MOP Activity Launch

The cadence launcher SHALL be a `MopLauncher` decision stage, assembled when the plan enables activity triggering and MOP data is loaded, positioned after the LLM stages and before the `ComponentTrigger` stage — so an enabled LLM stage takes precedence at a shared step (`decision-pipeline` INV-DP-02). The stage SHALL own its episode state: the dedicated launcher step counter, the per-run launch budget counter, and the round-robin cursor (`decision-pipeline` INV-DP-07).

The stage's `decide()` SHALL increment the launcher step counter once per evaluation (per step that reaches the stage). Because preemption is hard, a step decided by an earlier stage SHALL NOT advance the counter — the pre-change behavior in which an LLM-preempted step never reached the increment (finding 3.3-1) is preserved by construction and pinned by a permanent test (`decision-pipeline` INV-DP-08). When the counter reaches exactly the configured cadence (`activityTriggerStagnationStep`, injected from the plan; the property name is kept for the rv-android `tool.py` mapping) and the per-run launch budget is not exhausted (`activityTriggerMaxPerRun == 0` OR launches emitted this run `< activityTriggerMaxPerRun`), the stage SHALL reset the step counter to 0 and attempt to select a launch candidate. The stage SHALL NOT read or reset `graphStableCounter`.

A launch candidate is the next manifest activity, in round-robin order persisted across firings, satisfying ALL of — a member of the arm's MOP census (`className` in `MopData.getMopActivities()`, the `activityHasMop` reachability-augmented set of INV-MOP-27; the component-level `ComponentInfo.reachesMop` field SHALL NOT be used — it false-negatives lambda-triggered activities), `permission == null`, not the main activity, currently unvisited (`Graph.getActivityNode(className) == null` at fire time), and **not framework/tooling-namespaced**: a candidate whose `className` starts with any prefix in the code constant `FRAMEWORK_ACTIVITY_PREFIXES` — `android.`, `androidx.`, `com.google.android.`, `kotlin.`, `kotlinx.`, `junit.`, `org.junit.`, `leakcanary.` — SHALL be ineligible. The match is a class-name **prefix** match (never substring). The denylist is a fixed code constant located with the candidate selection (a correctness filter, not a tunable — no configuration key). Eligibility SHALL NOT include an `exported` test: the dispatch path (`AndroidDevice.startActivity` → `IActivityManager.startActivity` from uid 2000) launches non-exported activities. Under this change the rule is structural rather than stated — the compact artifact carries no `exported` field at all (`static-analysis-entrypoints` §7), so there is nothing for the stage to consult. There SHALL be no fallback outside the MOP census: when the census yields no eligible candidate, no launch occurs.

When a candidate exists, the stage SHALL increment the per-run launch counter and return `StageResult.Select` carrying a first-class `EVENT_TRIGGER_ACTIVITY` action with the candidate's class name and, when the artifact supplies one, its deep-link URI — the action is the step (it produces exactly one `[APE-STEP]` line with `decision_source=Component` and is NOT a graph edge label, mirroring `EVENT_RESTART` semantics; the `[APE-STEP]` line is emitted by the non-model branch of `StatefulAgent.resolveNewAction`, which derives the decision source from the action). When no candidate exists (census exhausted, all visited, or all denylisted), the stage SHALL return `Continue` with no side effects beyond the already-performed step-counter reset — in particular the launch counter SHALL NOT be incremented.

Event generation SHALL dispatch the action as an explicit intent (`ComponentName(MopData.getPackageName(), className)`, `FLAG_ACTIVITY_NEW_TASK`) via `AndroidDevice.startActivity`; the package component SHALL be `MopData.getPackageName()` and SHALL NOT be derived from the target class name (INV-CT-04, "ComponentName Package Derivation"). When the candidate carries a non-null `deepLinkUri`, the intent SHALL instead be `ACTION_VIEW` with `Uri.parse(deepLinkUri)`, targeted at the **package** (`Intent.setPackage(MopData.getPackageName())`) and carrying no component: the platform, not the launcher, resolves which activity of that package handles the URI. The URI SHALL be taken verbatim from the artifact: the jar SHALL NOT inspect intent-filter data, which no longer exists on the wire (INV-CT-13; the assembly rule itself is `static-analysis-entrypoints` INV-DRV-07, applied host-side by the generator).

*This sentence formerly read "still targeted at the component", and had done since the requirement was written (`2026-07-08-activity-frontier`, design item 6). No code path has ever set the component on this branch — `MonkeySourceApe.generateActivityTriggerEvent` calls `setAction`/`setData`/`setPackage` and returns. The restatement corrects the spec to the shipped behaviour rather than the code to the spec, because this change relocates **where the URI is computed** and changing what is dispatched is not in its scope: the group-4 gate compares derived data, so it could not attest a dispatch change, and the launcher carries the study's strongest mechanism result. The two are distinguishable only when two activities of one package assemble the same URI — measured over the pinned 345 at **7 apps** (`content://` ×3, `file://` ×2, `geo://`, `mailto://`), every one of them a case where `scheme + "://" + host + path` discards the `mimeTypes`/`pathPrefixes`/`pathPatterns` that actually discriminate those filters. In those 7, the launcher can open an activity other than the one it selected. That is a defect in the assembly rule (INV-DRV-07), not in the dispatch, and is recorded as issue #17 rather than repaired here.* Activities SHALL NOT participate in the `componentPercentage` probabilistic pool under any configuration.

**The dispatch SHALL record whether it was accepted.** `AndroidDevice.startActivity` today returns `true` whenever the reflective call did not throw, discarding the `START_*` code that `IActivityManager.startActivity` returns one frame below — so a launch refused by the platform and one that opened the activity are, in every artifact this study has, the same evidence. The method SHALL surface that result code, and the launcher SHALL pass it to the sink as the step record's `dec.comp` (`event-sink` capability). This is a recording change and nothing more: no retry, no re-dispatch, no second probe of the device, and no effect on the round-robin cursor or the launch budget, which continue to count *returned actions* exactly as INV-CT-12 requires. It matters because the launcher carries this study's strongest mechanism result — roughly 1,075 direct launches in the guided arm against none in the control, and an activity-coverage gain of about 14 percentage points that rests on them — while the trace currently cannot say how many of those intents the platform accepted.

The cadence (`ape.activityTriggerStagnationStep`, default `50`; a configured value `<= 0` clamped to the default at plan resolution, logged) and the per-run cap (`ape.activityTriggerMaxPerRun`, default `0` = unlimited; `< 0` clamped to `0`, logged) SHALL be injected into the stage from the resolved plan at assembly; the stage SHALL NOT read static `Config` (`decision-pipeline` INV-DP-12). Their plan grounding from `rearch-02-runspec` is **carried forward unchanged**: both keys are declared in the run-spec `Feature` model as sub-parameters owned by the `ACTIVITY_TRIGGER` feature (which requires `MOP`); when the feature is absent from the resolved plan no launcher mechanism exists and the two keys are accepted only at their neutral values (INV-RUN-05 of `run-spec` — the recorded substitute for the dissolved INV-ARCH-06 kill-switch registration). `Config.triggerMopFirst` SHALL NOT exist (deleted at stage 2). Only actually returned `EVENT_TRIGGER_ACTIVITY` actions consume budget; a firing whose candidate scan comes up empty does not. Sustained exploration yields periodic firing points every cadence steps *that reach the stage*, independent of graph growth; expected launches per run = `min(maxPerRun, non-preempted steps/cadence, |unvisited eligible census|)`.

- **INV-CT-05 (amended)**: At most one launch attempt SHALL occur per cadence window (the exact-equality gate on the stage-owned step counter plus its reset at the firing point make re-fire impossible within a window). The stage SHALL NOT read or reset `graphStableCounter`. The counter SHALL advance only on steps where the stage is evaluated — a step decided by an earlier stage advances nothing (finding 3.3-1 preserved; `decision-pipeline` INV-DP-08).
- **INV-CT-06 (unchanged)**: Every launched activity SHALL satisfy, at fire time: member of `MopData.getMopActivities()`, permission-free, non-main, same-package, unvisited, and not framework/tooling-namespaced (`FRAMEWORK_ACTIVITY_PREFIXES` prefix match). Exported status SHALL NOT be consulted — and after this change cannot be, the field having left the wire.
- **INV-CT-07 (unchanged in effect, relocated in mechanism)**: every launch model-visible as exactly one `[APE-STEP]` line with `decision_source=Component`; no graph edge labeled by the launch. Its dispatch precondition — the deep-link URI — is now a wire field rather than a jar-side computation (INV-CT-13).
- **INV-CT-14**: The launch result SHALL be recorded on the launch's own step record and SHALL NOT be acted upon. No control-flow decision — retry, re-dispatch, candidate re-selection, cursor or budget adjustment — may depend on it, in the jar or in the harness. It is an observation of a value the dispatch already produced, and the launcher's behaviour with the recording present SHALL be identical to its behaviour without it.
- **INV-CT-08 (unchanged)**: when the plan does not enable activity triggering, no activity SHALL ever be launched by APE-RV — realized structurally: no `MopLauncher` stage exists (neither does the probabilistic pool contain activities).
- **INV-CT-10 (unchanged)**: no `EVENT_TRIGGER_ACTIVITY` action SHALL ever carry a class name matching a `FRAMEWORK_ACTIVITY_PREFIXES` prefix; the denylist SHALL be consulted only inside the stage's candidate eligibility — no second exclusion mechanism.
- **INV-CT-12 (unchanged)**: when `ape.activityTriggerMaxPerRun` is `N > 0`, the number of `EVENT_TRIGGER_ACTIVITY` actions emitted in a run SHALL never exceed `N`; when `0`, no cap applies. Budget accounting SHALL count only returned actions (an empty candidate scan consumes nothing).

#### Scenario: deep link dispatched from the wire field
- **WHEN** the artifact's activity entry for `com.x.DetailActivity` carries `"deepLinkUri": "myapp://detail/x"` and the stage selects it
- **THEN** the injected intent SHALL be `ACTION_VIEW` with `Uri.parse("myapp://detail/x")` and `setPackage("com.x")`, with no component set
- **AND** no jar code SHALL have inspected an intent-filter, a scheme list, a host or a path to produce it

#### Scenario: activity without a deep link falls back to the explicit component
- **WHEN** the artifact's activity entry omits `deepLinkUri`
- **THEN** the injected intent SHALL be the explicit component intent with `FLAG_ACTIVITY_NEW_TASK`
- **AND** the launch SHALL otherwise behave identically to one carrying a URI (same step accounting, same budget consumption, same `[APE-STEP]` line)

#### Scenario: cadence fires independently of graph growth
- **WHEN** the cadence is 10 and the exploration graph grows on every step (no stagnation ever)
- **THEN** the stage SHALL still reach a firing point at every 10th evaluated step

#### Scenario: periodic firing under the default cadence
- **WHEN** the cadence is unset (default 50) and eligible census candidates remain
- **THEN** the stage SHALL fire at its 50th evaluation, reset its step counter, and fire again after each further 50 evaluations

#### Scenario: LLM-preempted steps do not advance the cadence (finding 3.3-1)
- **WHEN** the stage's counter is at 49 and the next step is decided by an LLM stage
- **THEN** the counter SHALL remain 49 after that step
- **AND** the firing point SHALL be reached on the next step that falls through to the stage

#### Scenario: non-exported census activity is launched
- **WHEN** the arm census contains `com.x.CryptoActivity`, `permission=null`, unvisited, declared `android:exported="false"` in the app's manifest
- **THEN** the stage SHALL select it and return an `EVENT_TRIGGER_ACTIVITY` action for it
- **AND** the manifest's export status SHALL NOT have reached the decision, the artifact carrying no `exported` field to convey it

#### Scenario: non-census activity is never launched
- **WHEN** `com.x.AboutActivity` is exported, permission-free, non-main and unvisited but not in `MopData.getMopActivities()`
- **THEN** the stage SHALL NOT select it, even when no census candidate is eligible (no fallback)

#### Scenario: census exhausted falls through without side effects
- **WHEN** a firing point is reached but every census activity is visited or denylisted
- **THEN** no launch SHALL occur, the launch budget SHALL be unchanged, the step counter SHALL reset, and the stage SHALL return `Continue` (the `ComponentTrigger`/`SataChain` stages select the step)

#### Scenario: permission-gated census activity skipped
- **WHEN** a census activity declares `permission="android.permission.MANAGE_DOCUMENTS"`
- **THEN** the candidate selection SHALL skip it

#### Scenario: denylisted census entry skipped
- **WHEN** the census contains `androidx.activity.ComponentActivity` (over-approximated reachability) and `com.x.HistoryActivity`, both otherwise eligible
- **THEN** the stage SHALL skip the `androidx.` entry and launch `com.x.HistoryActivity`

#### Scenario: cap exhausts the launch budget
- **WHEN** `activityTriggerMaxPerRun=2` and two `EVENT_TRIGGER_ACTIVITY` actions have been emitted this run
- **THEN** the firing predicate SHALL return false at every subsequent firing point and the stage SHALL return `Continue`

#### Scenario: cap zero means unlimited
- **WHEN** `activityTriggerMaxPerRun=0` (default) and 10 launches have already been emitted
- **THEN** the stage SHALL still fire at the next firing point (subject to the other gates)

#### Scenario: invalid values clamped at plan resolution
- **WHEN** the properties set `ape.activityTriggerStagnationStep=0` and `ape.activityTriggerMaxPerRun=-3`
- **THEN** plan resolution SHALL clamp them to `50` and `0` respectively and log each clamp

#### Scenario: launcher absent from the plan
- **WHEN** the plan does not enable activity triggering
- **THEN** no `MopLauncher` stage SHALL exist, no `EVENT_TRIGGER_ACTIVITY` step SHALL ever be produced, and the probabilistic pool SHALL contain no activities

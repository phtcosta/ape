# component-triggering — delta: mop-activity-consumers

## Purpose

Stop the stagnation activity launcher from spending its one-shot-per-episode on
framework/tooling activities. cmpft4 evidence (2026-07-10 forensic): of 114
`EVENT_TRIGGER_ACTIVITY` launches, 63 (55%) targeted `androidx.compose.ui.tooling.PreviewActivity`,
19 (17%) the abstract `androidx.activity.ComponentActivity`, ~5 test scaffolds — only ~24% were
genuine app activities. These classes are legitimate exported components of the app package
(debug-build manifest merging), so the existing eligibility conjunction passes them; the
discriminator is the class **namespace**. This delta extends the single existing eligibility
predicate (`SataAgent.firstEligible`) — verified 2026-07-10 that no other activity exclusion
list exists in the codebase — with a fixed framework/tooling prefix denylist.

## MODIFIED Requirements

### Requirement: Stagnation-Triggered Activity Launch

When `Config.activityTriggerEnabled` is true, `MopData` is loaded, and `graphStableCounter`
reaches exactly `graphStableRestartThreshold / 2` (evaluated in `SataAgent.selectNewActionNonnull`
after the LLM hooks, so an enabled LLM stagnation hook takes precedence), the agent SHALL attempt
to select a launch candidate: the next manifest activity, in round-robin order persisted across
episodes, satisfying ALL of — `exported == true`, `permission == null`, not the main activity,
currently unvisited (`Graph.getActivityNode(className) == null` at fire time), and **not
framework/tooling-namespaced**: a candidate whose `className` starts with any prefix in the code
constant `FRAMEWORK_ACTIVITY_PREFIXES` — `android.`, `androidx.`, `com.google.android.`,
`kotlin.`, `kotlinx.`, `junit.`, `org.junit.`, `leakcanary.` — SHALL be ineligible. The match is
a class-name **prefix** match (never substring); app classes whose package merely contains such
a token elsewhere remain eligible. The denylist is a fixed code constant located with
`firstEligible` (a correctness filter, not a tunable — no Config flag), and it applies
identically in all `selectTriggerCandidate` ordering modes (`triggerMopFirst` on or off), before
ordering: it narrows the eligible set, never the ordering rules. When a candidate exists, the
agent SHALL reset `graphStableCounter` to 0 and return a first-class `EVENT_TRIGGER_ACTIVITY`
action carrying the candidate's class name and, when available, its deep-link URI — the action
is the step (it produces exactly one `[APE-STEP]` line with `decision_source=Component` and is
NOT a graph edge label, mirroring `EVENT_RESTART` semantics). Because `EVENT_TRIGGER_ACTIVITY`
is a non-model action, its `[APE-STEP]` line is emitted by the else-branch of
`StatefulAgent.resolveNewAction` — which derives the decision source from the action
(attributing `EVENT_TRIGGER_ACTIVITY` as `Component`) rather than emitting a literal `SATA`.
When no candidate exists (including when every remaining candidate is denylisted), selection
SHALL fall through to the normal SATA chain with no side effects.

Event generation SHALL dispatch the action as an explicit intent
(`ComponentName(MopData.getPackageName(), className)`, `FLAG_ACTIVITY_NEW_TASK`) via
`AndroidDevice.startActivity`; the package component SHALL be `MopData.getPackageName()` and
SHALL NOT be derived from the target class name (main-spec INV-CT-04, ComponentName Package
Derivation). When the candidate's intent-filters contain an `ACTION_VIEW` filter with non-empty
`data.schemes`, the intent SHALL instead be `ACTION_VIEW` with a best-effort URI assembled from
the filter's first scheme, host and path, still targeted at the component. Activities SHALL NOT
participate in the `componentPercentage` probabilistic pool under any configuration.

- **INV-CT-05**: An activity launch SHALL occur at most once per stagnation episode (the
  counter-equality gate plus the reset make re-fire impossible within an episode).
- **INV-CT-06 (amended)**: Every launched activity SHALL satisfy, at fire time: exported,
  permission-free, non-main, same-package, unvisited, and not framework/tooling-namespaced
  (`FRAMEWORK_ACTIVITY_PREFIXES` prefix match). The Invariants-block entry for INV-CT-06 SHALL
  be updated to this text at archive time.
- **INV-CT-07**: Every launch SHALL be model-visible as exactly one `[APE-STEP]` line with
  `decision_source=Component`; no graph edge SHALL be labeled by the launch.
- **INV-CT-08**: With `ape.activityTriggerEnabled=false`, no activity SHALL ever be launched by
  APE-RV (neither by the launcher nor by the probabilistic pool, which contains no activities).
- **INV-CT-10**: No `EVENT_TRIGGER_ACTIVITY` action SHALL ever carry a class name matching a
  `FRAMEWORK_ACTIVITY_PREFIXES` prefix; the denylist SHALL be consulted only inside the
  launcher eligibility (`firstEligible`) — no second exclusion mechanism.

#### Scenario: stagnation launches an unvisited exported activity
- **WHEN** `graphStableCounter` reaches `graphStableRestartThreshold / 2`, the LLM is disabled, and the manifest has an exported, permission-free, unvisited `com.x.SettingsActivity`
- **THEN** the step SHALL be an `EVENT_TRIGGER_ACTIVITY` action for `com.x.SettingsActivity`, the `[APE-STEP]` line SHALL carry `decision_source=Component`, and `graphStableCounter` SHALL be reset to 0

#### Scenario: tooling activity skipped in favor of a genuine one
- **WHEN** the round-robin order reaches `androidx.compose.ui.tooling.PreviewActivity` (exported, permission-free, unvisited) followed by `com.x.HistoryActivity` (same eligibility)
- **THEN** the launcher SHALL skip `PreviewActivity` and launch `com.x.HistoryActivity`

#### Scenario: only denylisted candidates remain — fall through
- **WHEN** every remaining unvisited exported activity is framework/tooling-namespaced (e.g. `androidx.activity.ComponentActivity`, `leakcanary.internal.activity.LeakActivity`)
- **THEN** no launch SHALL occur, `graphStableCounter` SHALL NOT be reset by the launcher, and the normal SATA chain SHALL select the step

#### Scenario: prefix match, not substring
- **WHEN** the candidate is `com.foo.androidxutils.MainActivity` (eligible otherwise)
- **THEN** it SHALL remain eligible (no `FRAMEWORK_ACTIVITY_PREFIXES` entry is a prefix of its class name)

#### Scenario: deep-link candidate launched with VIEW intent
- **WHEN** the selected candidate has an intent-filter with `android.intent.action.VIEW` and `data.schemes=["myscheme"]`, `hosts=[]`
- **THEN** dispatch SHALL use `ACTION_VIEW` with URI `myscheme://` targeted at the component

#### Scenario: launcher disabled
- **WHEN** `ape.activityTriggerEnabled=false`
- **THEN** no `EVENT_TRIGGER_ACTIVITY` step SHALL ever be produced and the probabilistic pool SHALL contain no activities

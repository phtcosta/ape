## Invariants

- **INV-GRP-06**: Every read site of an ADDONS, STATIC or MOP mechanism SHALL read the plan. No such site SHALL
  read the mechanism's switch from `Config`, and `Config` SHALL declare no field for a plan-read switch. In
  particular the `State` constructor (`MODEL_MENU`), `GUITreeBuilder.treeEnhancementsEnabled()` and its callers in
  `GUITreeBuilder` and `GUITreeNode` (`TREE_ENHANCEMENTS`), `ApeAgent.generateInputText` (`HEURISTIC_INPUT`),
  `MonkeySourceApe.generateEvents` (the foreign-activity and tree-package guards) and `StaticArtifactLoader.load`
  (`MOP_ACTIVITY_SOURCE`) SHALL read `spec.has(<member>)`. Coverage: `GroupReadSiteTest`.

## MODIFIED Requirements

### Requirement: Static Data Root and Split Providers

`STATIC_DATA` SHALL be the only feature that reads the artifact path. When the plan mounts `STATIC` or `MOP` (both
require `STATIC_DATA`), `StaticArtifactLoader.load` SHALL parse the artifact once and yield two read-only providers.
When `STATIC_DATA` is in the plan and neither group is mounted, the path is inert: it SHALL be listed in
`RUN_START.inert`, and no load, no `MOP_DATA` census and no rejection follow (design D-B14.20). The providers are:

- `StaticGuiModel`: the WTG, the per-widget static metadata (`inputType`, `hint`, labels), the manifest components
  with their intent filters (including the filter's `data`), the artifact's package and main activity;
- `MopData`: `targets`, per-widget `dist`, `handlers`, `activityDist`, the MOP activity set and the menu gateways.

The loader SHALL hand `StaticGuiModel` to the mounted STATIC members and both providers to the mounted MOP members,
and nothing to a member that is not mounted. The MOP members that need the WTG or the components (`WtgPass`,
`MopFrontierPass`, `MopScorer`, `MopLauncherStage`) read `StaticGuiModel` for them and `MopData` for the targets
(plan P-1, "MOP reads both"). `NAV_FRONTIER` (`agent/scoring/NavFrontierPass`) and the typed-input
generator and the component-trigger pool SHALL read `StaticGuiModel` only. The wire format SHALL remain format 2.
`formatVersion`, the generator string and the `source.digest` chain are unchanged, and so is the format apart from
the additive intent-filter `data` member (D-B13): the derive adds one optional member, the reduced `data` of each
component intent filter, which the loader reads as "no data" when absent.
`MOP_DATA` remains the load census.

#### Scenario: STATIC reads no MOP data

- **WHEN** the `as` cell runs with `ape.staticDataPath=/data/local/tmp/mop-artifact.json`
- **THEN** `NavFrontierPass` and the typed-input generator SHALL be constructed with a `StaticGuiModel`
- **AND** no constructed component of the run SHALL hold a `MopData` reference

#### Scenario: one parse feeds both groups

- **WHEN** the `as_mop` cell runs on `cryptoapp.apk.mop.json`
- **THEN** the artifact SHALL be parsed exactly once
- **AND** `NavFrontierPass` and `WtgPass` SHALL read the same WTG instance from `StaticGuiModel`

#### Scenario: format 2 is still the only format read

- **WHEN** `ape.staticDataPath` names an artifact with `formatVersion: 1`
- **THEN** `StaticArtifactLoader.load` SHALL reject it with `version-mismatch` (its `formatVersion` gate), and
  neither provider SHALL be built

### Requirement: Addon Read Sites Read the Plan

Every site that decides whether an ADDONS, STATIC or MOP mechanism acts SHALL read the plan through
`RunContext.current().spec()` (INV-GRP-06). These sites read the plan, and `Config` has no field for any of
their switches:

| Mechanism | Site | Plan read |
|---|---|---|
| `MODEL_MENU` | the `State` constructor (`model/State`) | `spec.has(Feature.MODEL_MENU)` |
| `TREE_ENHANCEMENTS` | `GUITreeBuilder.treeEnhancementsEnabled()`, read by `GUITreeBuilder.checkAndRemoveWebView`, `GUITreeNode.isEditText` and `GUITreeNode.getScrollType` | `spec.has(Feature.TREE_ENHANCEMENTS)` |
| `HEURISTIC_INPUT` | `ApeAgent.generateInputText` | `spec.has(Feature.HEURISTIC_INPUT)` |
| `FOREIGN_ACTIVITY_GUARD`, `TREE_PACKAGE_GUARD` | `MonkeySourceApe.generateEvents` | `spec.has(...)` of each |
| `MOP_ACTIVITY_SOURCE` | `StaticArtifactLoader.load` | `spec.has(Feature.MOP_ACTIVITY_SOURCE)` |
| `FORM_COMPLETION` | `StatefulAgent.inFormCompletionContext` (read by `ApeAgent.checkInput`), `SataAgent.mountExtensionPoints` (the submit exclusion), `ScoringParams.fromSpec` (`FormCompletionPass`) and the `MopCoin` stage's assembly in `DecisionPipeline.fromSpec` | `spec.has(Feature.FORM_COMPLETION)` |
| `FUZZING` (BASE) | `MonkeySourceApe.generateEvents` | `spec.has(Feature.FUZZING)` and its `ape.fuzzingRate` parameter |
| restart wrapping (BASE) | `ApeAgent.checkRestart` and the `Restart` candidate of `DecisionPipeline` | the `ExplorationParams` value of `ape.checkRestart` |
| `BACK_MENU_CAP` | the BACK/MENU cap in `SataAgent` | its parameter, `exploration().backMenuPickCap()` (`0` when the feature is absent) |
| `ACTIVITY_STABLE_RESTART` | the activity-stability restart check (`StatefulAgent`) | its parameter, `exploration().activityStableRestartThreshold()`; absent ⇒ threshold `Integer.MAX_VALUE` |

The two BASE rows carry no group switch; they read the plan so that `Config` is not a second authority over a
planned key (design D-B6, decision A10), and they change no behaviour. When a member is absent, its site SHALL
behave as upstream APE does for that mechanism. The absent
`ACTIVITY_STABLE_RESTART` threshold is the upstream `Integer.MAX_VALUE` (`8f51b99c` `utils/Config.java:48`).

#### Scenario: base runs without the model menu

- **WHEN** the `base` cell runs with the jar default `ape.modelMenuEnabled`
- **THEN** `State.getActions()` SHALL contain no `MODEL_MENU` action

#### Scenario: activity-stable restart off restores upstream

- **WHEN** the `mop` cell runs and an activity stays stable for 500 consecutive steps
- **THEN** no restart SHALL be due on the activity-stability criterion

#### Scenario: guards follow the plan, not Config

- **WHEN** `GroupReadSiteTest` inspects `Config` and the plan
- **THEN** `Config` SHALL declare no field named `modelMenuEnabled`, `treeEnhancementsEnabled`, `heuristicInput`,
  `foreignActivityGuard`, `treePackageGuard`, `mopActivitySourceComponents`, `doFuzzing`, `fuzzingRate`,
  `checkRestart`, `minEpsilon`, `maxEpsilon`, `mopDataPath`, `mopTargetPickCap`, `leastVisitedPriorityTiebreak` or
  `frontierBoostWeight`
- **AND** under the `base` cell every ADDONS member the table names SHALL be absent from the plan, the BACK/MENU
  cap SHALL read `0` and the activity-stable threshold `Integer.MAX_VALUE`, while `FUZZING` and `ape.checkRestart`
  keep their BASE defaults

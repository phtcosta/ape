## MODIFIED Requirements

### Requirement: Declared Consequences of the Cell Composition

Two consequences of the composition SHALL be declared rather than treated as leaks.

First, the `mop` cell carries no `MODEL_MENU`, because the menu action is an addon. `MENU_GATEWAY` requires
`MODEL_MENU`, so it SHALL be absent from the `mop` cell. Resolving the `mop` cell SHALL succeed: the MOP block
does not state `ape.mopWeightOpenMenu` (design D-B14.1), because a preset key counts as explicit and would abort the
`mop` cell with `MISSING_DEPENDENCY`. Its jar default, 250, activates `MENU_GATEWAY` where `MODEL_MENU` is present
and is left out silently where it is not. In `as_mop` and `as_mop_llm` the gateway is present.

Second, the `base` cell SHALL run the APE algorithm of the upstream `ape.jar` (`8f51b99c`) at every point the e03igg4
audit (rv-android `docs/20261021_e03igg4_investigacao.md` §7.4–7.5, item numbers below are its numbers; item 16 comes
from the e03igg5 wait analysis, rv-android `docs/20261023_espera_activity_loading_e03igg5.md`) lists,
except the points declared here. It SHALL NOT be claimed equal to `8f51b99c` byte for byte. In every cell, BASE
carries, with no key:

- the `produce`/`consume` logs and the end-of-run writes are not produced (item 2);
- `Graph.rebuildHistory` does not double-increment (item 5);
- `GUITree.contains` tests `< 0` (item 7);
- `GUITreeNode.clearChildren` removes every child with the first-child loop, with no exception path (item 8,
  `ui-tree`);
- an empty `StringCache` is handled;
- the RNG is seeded instead of `ThreadLocalRandom`, and `RandomHelper` is seeded with `seed ^ RANDOM_HELPER`,
  separate from `Monkey.mRandom`, which is seeded with the run seed (item 13, `run-spec` "Run Identity and Seed");
- the per-step telemetry of `event-sink` (item 14);
- the action-history memory bound (item 15);
- per-step PNG and XML artifacts are off (item 1, the one declared exception to APE's defaults), and new-state
  capture is on (`ape.takeScreenshotForNewState` jar default `true`, `exploration`).

At the points below, `base` and `mop`, whose plans do not contain `APE_FIXES`, SHALL run APE's code, and every cell
that mounts ADDONS, in which `APE_FIXES` is present at its default `true`, SHALL run the fork's correction:

| Item | Without `APE_FIXES` (APE) | With `APE_FIXES` |
|---|---|---|
| 3 | the `maxGUITreesPerState` guard of both `NamingFactory` sites tests `an.getStates().size()` | it tests `state.getGUITrees().size()` (`naming`) |
| 4 | an activity visit is counted again during a model rebuild | it is not counted (`Graph.markVisited`, `model`) |
| 6 | `Naming.containsNamelet` tests `binarySearch(...) != -1` | it tests `binarySearch(...) >= 0` (`naming`) |
| 9 | the pipeline does not mount `Restart`; `ApeAgent` applies `checkRestart` after selection, SATA marks the replaced action visited and the restart's new episode weakens the planned edge | the `Restart` stage selects the restart before any other stage (`decision-pipeline`) |
| 10 | a target without visible bounds is clicked at the screen centre, the display bounds substituting for them | the action is dropped (`exploration` INV-EXPL-19) |
| 11 | the foreground wait is unbounded | it is bounded at 100 cycles and followed by a relaunch (`exploration` INV-EXPL-15) |
| 12 | the pinch/zoom event is built but not appended; the fuzz toss is `RandomHelper.toss(fuzzingRate)`, drawn only when `FUZZING` is in the plan | the pinch is appended; the fuzz toss is drawn from `StepRng`'s own stream every step (`decision-pipeline` INV-DP-13) |
| 16 | every relaunch of the app under test targets a launcher component resolved at startup | every relaunch resolves the launcher again at the moment of the relaunch, falling back to the startup list when the query returns nothing (`exploration` INV-EXPL-33) |

Form completion's deterministic fill and submit exclusion (addon F) are governed by `ape.formCompletionEnabled` and
are therefore off in `base`. The SATA extension points X1, X3 and X4 SHALL take the APE default in `base`, including
their RNG consumption (`action-selection`). `RUN_START.build` identifies the jar, and the equivalence with upstream is
checked by an A/A on a subset of APKs (plan §6.4).

#### Scenario: the mop cell has no menu gateway and does not abort

- **WHEN** a properties file contains `ape.preset=mop` and `ape.staticDataPath=/data/local/tmp/mop-artifact.json`
- **THEN** resolution SHALL succeed
- **AND** `features` SHALL contain neither `MODEL_MENU` nor `MENU_GATEWAY`, and no `StepRecord` of the run SHALL
  carry a non-zero `menu` field

#### Scenario: the gateway returns with the addons

- **WHEN** the same file names `ape.preset=as_mop` instead
- **THEN** `features` SHALL contain `MODEL_MENU` and `MENU_GATEWAY`

#### Scenario: the base cell runs the bug fixes and none of the addons

- **WHEN** the `base` cell runs
- **THEN** no ADDONS member SHALL be constructed (no `MODEL_MENU` action in `State.getActions()`, no BACK/MENU cap,
  no form fill)
- **AND** `RUN_START.features` SHALL be `[FUZZING]`, without `APE_FIXES`, and `RUN_START.build` SHALL name the
  APE-RV build
- **AND** `PIPELINE.stages` SHALL NOT contain `Restart`, and a due restart SHALL be applied by `ApeAgent.checkRestart`
  after selection
- **AND** a fuzz toss SHALL draw from `RandomHelper` and from no `StepRng` stream

#### Scenario: the ADDONS cells run the fork's corrections

- **WHEN** the `as`, `as_mop`, `as_llm` or `as_mop_llm` cell resolves with `ape.checkRestart` at its default
- **THEN** `features` SHALL contain `APE_FIXES`
- **AND** `PIPELINE.stages` SHALL contain `Restart`, and the fuzz toss SHALL draw from `StepRng`'s fuzz stream

#### Scenario: an ADDONS cell without the corrections

- **WHEN** a properties file contains `ape.preset=as`, the artifact path and `ape.apeFixes=false`
- **THEN** resolution SHALL succeed with every other ADDONS member present and `APE_FIXES` absent
- **AND** items 3, 4, 6, 9, 10, 11, 12 and 16 SHALL run APE's code, as in `base`

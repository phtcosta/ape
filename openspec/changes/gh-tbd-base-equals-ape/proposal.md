## Why

`aperv:base` is the study's control, "the fork without guidance", and the comparison `base` × `mop` only
isolates the guidance if `base` runs the APE algorithm. It does not: the e03igg4 audit
(rv-android `docs/20261021_e03igg4_investigacao.md` §7.4–7.5) found sixteen differences between upstream APE
(`8f51b99c`) and `aperv:base` at `23c772c1`, and in e03igg4 `base` found more MOP misuses than `ape` (203 × 200).
Pedro decided on 2026-10-21 (rv-android `docs/20261021_plano_base_igual_ape.md`) which of them leave `base`, which
stay, and which change in every cell. No GitHub issue.

## What Changes

- **New ADDONS member `APE_FIXES`** (`ape.apeFixes`, boolean; requires `ape.addons` like every other member; jar
  default `true`, `false` when ADDONS is off). Off — `base` and `mop` — each point below runs APE's code; on — every
  cell that mounts ADDONS — it runs the fork's correction, as today. Each point has exactly one decision point, the
  member read through the plan.
  - **3** per-state GUI-tree cap: off `an.getStates().size() > maxGUITreesPerState` (APE's test, which never fires),
    on `state.getGUITrees().size() > maxGUITreesPerState` — both `NamingFactory` sites.
  - **4** activity visits during a model rebuild: off counted again, as in APE; on not counted (`Graph.markVisited`).
  - **6** namelet membership in `Naming.select`: off `binarySearch(...) == -1`, on `>= 0`.
  - **9** restart timing: on, the `Restart` stage selects the restart before any other stage (today); off, the
    pipeline does not mount `Restart` and `ApeAgent` applies `checkRestart` after selection, as APE does. Off, SATA
    marks the replaced action visited and the restart's new episode weakens the planned edge, as in APE; the fork's
    own bookkeeping (MOP interaction ledger, target retirement, LLM action history) treats the replaced action as
    not executed; the step record names the restart that ran (`dec.src`/`dec.ch` `restart`, `dec.restart`, the
    `restarts` counter). A post-selection restart replaces the action of any owner.
  - **10** off-screen target: off, the display bounds substitute for the missing visible bounds and the click lands
    at the screen centre, as in APE; on, the action is dropped (INV-EXPL-19).
  - **11** foreground wait: off unbounded, as in APE; on bounded at 100 cycles and a relaunch (INV-EXPL-15).
  - **12** fuzzing: off, the pinch/zoom event is built but not appended and the fuzz toss is
    `RandomHelper.toss(fuzzingRate)`, drawn only when fuzzing is on, as in APE; on, the pinch is appended and the toss
    comes from `StepRng`'s own stream (today).
- **Both groups, no member:**
  - **8** `GUITreeNode.clearChildren` removes children with
    `while (domNode.getFirstChild() != null) domNode.removeChild(domNode.getFirstChild())`; the `catch (DOMException)`
    and its log line are deleted. The current `getChildNodes().item(0)` loop aborts every prune on device (base
    408/408, mop 520/520, as_mop 284/284 in e03igg4), because the Android DOM returns a copy from `getChildNodes()`.
  - **13** `RandomHelper` is seeded with `seed ^ K` instead of the seed `Monkey.mRandom` uses, so the two generators
    stop producing one sequence at two offsets. `Monkey.mRandom` keeps `new Random(mSeed)`.
- **Jar default `ape.takeScreenshotForNewState = true`** (today `false`), for the mini-experiment; per-step PNG/XML
  stay off. A jar default, not a cell key.
- **Unchanged in `base`** (decided): items 1 (per-step artifacts stay off: the one declared exception), 2, 5, 7, the
  empty `StringCache` guard and the seeded generator of 13, 14 (telemetry), 15 (action-history memory).
- `backup/` (gitignored) is created locally for files a change supersedes as a whole; this change supersedes none,
  and code superseded inside a file is deleted, not kept.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `guidance-groups`: ADDONS gains `APE_FIXES`; the list of what `base` carries over upstream shrinks to the
  decided items, and the restart guard and fuzz stream are no longer structural changes of every cell.
- `run-spec`: the `ape.apeFixes` key and its owner; `ape.takeScreenshotForNewState` default `true`; `RandomHelper`
  seeded with `seed ^ K`.
- `decision-pipeline`: the `Restart` candidate is assembled iff `ape.checkRestart` and `APE_FIXES`; INV-DP-14
  holds for that pipeline; the post-selection restart of a pipeline without `Restart`.
- `exploration`: restart timing per the member (INV-EXPL-32), the wait bound (INV-EXPL-15), the off-screen drop
  (INV-EXPL-19), pinch emission and the fuzz-toss source per the member, the seed of INV-EXPL-14, the new-state
  capture default.
- `naming`: the `maxGUITreesPerState` guard and the namelet membership test per the member (INV-NAME-13 scoped).
- `model`: activity visit counts during a rebuild per the member (INV-MODEL-11).
- `ui-tree`: `clearChildren` removes every DOM child with the first-child loop and no exception path (INV-TREE-10).
- `event-sink`: the restart a post-selection `checkRestart` applies is recorded as the step's owner, with
  `dec.restart` and the `restarts` counter.
- `mop-guidance`: ledger and retirement count an action only when no post-selection restart replaced it.

## Impact

- Code (`src/main/java/com/android/commands/monkey/`): `ape/runtime/Feature.java`, `KeyOwnership.java`,
  `Presets.java`, `RunContext.java`; `ape/naming/NamingFactory.java`, `ape/naming/Naming.java`,
  `ape/model/Graph.java`; `ape/agent/ApeAgent.java`, `ape/agent/StatefulAgent.java`,
  `ape/agent/pipeline/DecisionPipeline.java`; `MonkeySourceApe.java`; `ape/events/ApeFuzzer.java`,
  `ape/agent/StepRng.java`; `ape/tree/GUITreeNode.java`.
- Tests: `src/test/resources/cells/*.run_start.json` regenerated (the four ADDONS cells gain `APE_FIXES`; every
  cell's digest changes, because the digest covers base defaults and `ape.takeScreenshotForNewState` changed); oracle goldens are expected not to move; `RngNeutralityTest` and the
  restart tests are scoped to the member; `GUITreeWebViewPruneTest` runs without a fake DOM.
- Cross-component: rv-android's aperv-tool offline cell gate and mini-experimento's device cell gate compare
  `RUN_START` with the cell fixtures and must take the new ones together with the jar. The harness half of the plan
  (ape host grace, `ape.properties` for the `ape` arm, derive cut) is rv-android's and needs no jar change.
- Measurement: `base` and `mop` lose the corrections; the `ape` × `base` parity is measured only when Pedro asks.
  Turning on new-state capture adds one `mRandom` draw per new state (`MonkeySourceApe.nextImageWriter`) in every
  aperv cell, as in the `ape` arm with the same setting.

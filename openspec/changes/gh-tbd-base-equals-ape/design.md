## Context

`aperv:base` is the study's control and must run the APE algorithm of `8f51b99c`. The e03igg4 audit (rv-android
`docs/20261021_e03igg4_investigacao.md` §7.4–7.5, item numbers below are its numbers) lists sixteen differences;
Pedro's plan of 2026-10-21 (rv-android `docs/20261021_plano_base_igual_ape.md`) decides each of them. This design
fixes how each decision is placed in the code so that every point has one decision point and no compatibility path.

Every path below is relative to `src/main/java/com/android/commands/monkey/`.

Current state (master `51902b5c`):

- BASE carries the fork's corrections without a key. Items 3, 4, 6, 10, 11 and the pinch of 12 were keyless
  fixes; the restart guard (9) and the fuzz stream (12) were structural changes of every cell
  (`guidance-groups`, "base cell" requirement).
- The restart is selected by `ape/agent/pipeline/RestartStage`, mounted iff `ape.checkRestart`. `ApeAgent`
  keeps `checkRestart` for agents without a pipeline and chooses between the two with
  `restartIsAGuard() = this instanceof StageCollaborators` (`ape/agent/ApeAgent.java:415`).
- `RandomHelper` and `Monkey.mRandom` are both `new Random(seed)` (`ape/runtime/RunContext.java:154`,
  `Monkey.java:708`).
- `GUITreeNode.clearChildren` loops on `getChildNodes().item(0)` inside a `catch (DOMException)`.

## Architecture

One new ADDONS member, `Feature.APE_FIXES`, activation key `ape.apeFixes`. Use sites read it through
`RunContext.current().spec().has(Feature.APE_FIXES)` — the member, never the group. With ADDONS off the member is
absent (dependency rule), so `base` and `mop` run APE's code at every point; with ADDONS on it is present at its
default `true`, so `as`, `as_mop`, `as_llm`, `as_mop_llm` keep today's behaviour.

```
RunSpec ──has(APE_FIXES)──┬─ NamingFactory (3, two sites)      Naming.containsNamelet (6)
                          ├─ Graph.markVisited(State) (4)
                          ├─ DecisionPipeline.Candidate.RESTART (9) ──► SataAgent: stage mounted?
                          │                                              └─► StatefulAgent: post-selection restart
                          ├─ MonkeySourceApe: null visible bounds (10), waitForActivity bound (11), fuzz toss (12)
                          └─ ApeFuzzer: pinch appended (12)
both groups:  GUITreeNode.clearChildren (8)   RunContext: RandomHelper.seed(seed ^ K) (13)
jar default:  ape.takeScreenshotForNewState = true (KeyOwnership)
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `ape/runtime/Feature.APE_FIXES` | the member; depends on `ADDONS` | `ape.apeFixes` | plan membership |
| `ape/runtime/KeyOwnership` | owns `ape.apeFixes` (BOOLEAN, `true`); `ape.takeScreenshotForNewState` default `true` | — | key registry |
| `ape/runtime/Presets.addonsBlock` | states `ape.apeFixes=true` with the other addon keys | — | cell plans |
| `ape/runtime/RunContext` | seeds `RandomHelper` with `seed ^ RANDOM_HELPER` | run seed | seeded helper |
| `ape/naming/NamingFactory` | tree cap per member (both sites) | state, activity node | refinement allowed? |
| `ape/naming/Naming.containsNamelet` | membership test per member | sorted list, namelet | boolean |
| `ape/model/Graph.markVisited(State,int)` | activity visit counted during rebuild iff member off | state, timestamp | counters |
| `ape/agent/pipeline/DecisionPipeline` | `RESTART` assembled iff `checkRestart && APE_FIXES` | plan | stage list |
| `ape/agent/ApeAgent` | restart decision and start action; latch handling per path | step | action |
| `ape/agent/StatefulAgent` | post-selection restart placement and its bookkeeping/telemetry | selected action | executed action |
| `MonkeySourceApe` | off-screen bounds, foreground wait, fuzz toss per member | — | events |
| `ape/events/ApeFuzzer` | pinch appended iff member | — | events |
| `ape/tree/GUITreeNode.clearChildren` | first-child loop, no exception path | DOM element | empty element |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| guidance-groups: ADDONS member `APE_FIXES` | `Feature`, `KeyOwnership`, `Presets` | `FeatureGroupsTest`/`PresetsTest` (existing, extended), `CellRunStartFixtureTest` |
| run-spec: `RandomHelper` seed `seed ^ K` | `RunContext` | `RunContextTest` (seed-separation case) |
| run-spec/exploration: new-state capture default | `KeyOwnership` | `KeyOwnershipTest` default case |
| naming: tree cap (3) | `NamingFactory` | `NamingFactory*Test` with member on/off |
| naming: membership (6), INV-NAME-13 | `Naming.containsNamelet` | `NamingSelectTest` (existing binarySearch case, on/off) |
| model: rebuild visits (4), INV-MODEL-11 | `Graph.markVisited` | `GraphRebuild*Test` on/off |
| decision-pipeline: Restart candidate, INV-DP-14 | `DecisionPipeline.Candidate.RESTART` | `DecisionPipelineAssemblyTest`, `RestartGuardTest` |
| exploration: post-selection restart (INV-EXPL-32) | `StatefulAgent.updateStateInternal`, `ApeAgent.checkRestart` | `PostSelectionRestartTest` (new) |
| event-sink: restart record of a post-selection restart | `StatefulAgent` → `EventSink.decisionNonModel/restart/count` | `PostSelectionRestartTest` |
| mop-guidance: ledger/retirement skip a replaced action | `StatefulAgent.moveForward` not reached | `PostSelectionRestartTest` (MOP plan) |
| exploration: off-screen (10), INV-EXPL-19 | `MonkeySourceApe.generateClickEventAt` | device smoke (class not loadable off-device) |
| exploration: wait bound (11), INV-EXPL-15 | `MonkeySourceApe.checkAppActivity` | device smoke |
| exploration: fuzz toss + pinch (12), INV-DP-13 | `MonkeySourceApe`, `ApeFuzzer` | `ApeFuzzerTest` on/off, `RngNeutralityTest` scoped |
| ui-tree: clearChildren (8), INV-TREE-10 | `GUITreeNode.clearChildren` | `GUITreeWebViewPruneTest` (no fake DOM) |

## Goals / Non-Goals

**Goals:**
- `base` and `mop` run APE's code at items 3, 4, 6, 9, 10, 11, 12; the ADDONS cells run today's code there.
- Items 8 and 13 change in every cell.
- `ape.takeScreenshotForNewState` defaults to `true`.
- Every point has one decision point; code that becomes unused is deleted.

**Non-Goals:**
- Items 1, 2, 5, 7, the `StringCache` guard and the seeded generator of 13, 14 and 15 do not change.
- `Monkey.mRandom` is not touched (it is APE's generator, seeded as APE seeds it).
- No measurement of `ape` × `base` parity; no campaign.
- The harness half of the plan (rv-android) and the distance cut / `k` (unchanged) are outside this change.

## Decisions

**D1 — One member, name `APE_FIXES` / `ape.apeFixes`.** The member holds the fork's corrections of APE's own
behaviour, as distinct from the addons, which add mechanisms. Declared like `FOREIGN_ACTIVITY_GUARD`:
`APE_FIXES("ape.apeFixes", Rule.TRUE, "false")` in `Feature`, added to the ADDONS dependency loop;
`feature(Feature.APE_FIXES, "ape.apeFixes", ValueType.BOOLEAN, "true")` in `KeyOwnership`; and
`block.put("ape.apeFixes", "true")` in `Presets.addonsBlock`, so the four ADDONS cells state it as they state the
other addon keys. Alternative: seven members, one per item — rejected by the plan (one member).

**D2 — Item 3.** At both guards (`NamingFactory.java:280` and `:1180`) the tested count is
`has(APE_FIXES) ? state.getGUITrees().size() : an.getStates().size()`; the threshold and the log line are unchanged
(the log keeps printing `state.getGUITrees().size()`, as APE's does). At `:1180` the activity node is obtained the
way APE obtains it at that site.

**D3 — Item 4.** `Graph.markVisited(State, int)` counts the activity visit unless `rebuilding && has(APE_FIXES)`.
The `rebuilding` flag and `setRebuilding` stay (the member-on path reads them).

**D4 — Item 6.** `Naming.containsNamelet` returns `binarySearch(...) >= 0` with the member, and
`binarySearch(...) != -1` without it, which is APE's `== -1 → break` written as a membership test.

**D5 — Item 9, mounting.** `DecisionPipeline.Candidate.RESTART` gets `Feature.APE_FIXES` as its gate and the
condition `spec.exploration().checkRestart() && spec.has(Feature.APE_FIXES)`. `ApeAgent.restartIsAGuard()` is
deleted; `ApeAgent` declares `protected boolean restartStageMounted()` returning `false`, and `SataAgent` overrides
it with whether its assembled pipeline contains `RESTART`. That is the single decision point: the plan decides the
assembly, and the agent asks its pipeline. `RandomAgent` and `ReplayAgent` have no pipeline and always restart
after selection, as today.

**D6 — Item 9, the post-selection restart.** Without the stage, the restart is applied inside
`StatefulAgent.updateStateInternal`, between `getGraph().markVisited(action)` and `recordActionHistory`/`moveForward`:

```
Action action = resolveNewAction();
if (action.isModelAction()) getGraph().markVisited((ModelAction) action, ts);   // APE: marked visited
action = checkRestart(action);                                                    // no-op when the stage is mounted
if (action is the selected model action) { recordActionHistory(...); moveForward(); } else resetTrace();
```

and `ApeAgent.updateStateWrapper` becomes one path, `checkInput(checkFuzzing(updateStateInternal(...)))`, with the
latch cleared at step start when the stage is not mounted (APE: `disableRestart = false` before selection).

Model effect equals APE's: in APE, `moveForward` shifts the pointers and the restart's `startNewEpisode` →
`resetTrace` then nulls all of them and `clearBuffer` weakens the planned edge; here `resetTrace` runs on the same
buffer and nulls the same pointers, so the graph, the visit marks and the weakening are the same. The fork-only
bookkeeping in `moveForward` and `recordActionHistory` — MOP interaction ledger and target retirement
(`recordMopInteraction`, `dec.ret`), UI-coverage interaction count, budget iteration, LLM action history — is not
reached for a replaced action, which is Pedro's rule "the replaced action is not executed" with no undo code.

`ApeAgent.checkRestart(origin)` becomes: return `origin` when the stage is mounted or `ape.checkRestart` is
`false`; otherwise `restartDue()` (whose rule — flag up, or threshold passed and latch not set — is APE's
`checkRestart` rule once the latch is cleared at step start) and, when due, `startRestart()`. `startRestart()` is
today's `ApeAgent.restartAction` body (kind, `lastRestartStep`, threshold redraw for `periodic`, new episode,
`dec.restart`, `restarts` counter, kind toss). The stage keeps calling `restartAction()`, which `StatefulAgent`
implements as `actionBuffer.clear(); return startRestart();` — the buffer drop without weakening belongs to the
stage path only.

After a replacement, `StatefulAgent` rewrites the step's decision as a restart decision:
`sink().decisionNonModel(start.toString(), DecisionSource.RESTART.owner(), PickChannel.RESTART.getLabel())`, and
clears `lastDecisionAction`, so the record describes the restart that ran, as a stage-selected restart step does,
and closes the way a non-model decision closes. A post-selection restart replaces the action of any owner (SATA,
MOP coin, launcher, LLM); LLM attempts already made stay in the record's `llm[]`. If `NdjsonSink.decisionNonModel`
after `decision` in the same step leaves fields of the replaced decision, `NdjsonSink` is changed so the second
call replaces the first.

**D7 — Item 10.** The substitution belongs to the node-action path, which is APE's code:
`MonkeySourceApe.generateClickEventAt(Rect nodeRect, long, ClickPoint)` computes `getVisibleBounds(nodeRect)` and,
when it is null and the member is off, replaces it by `AndroidDevice.getDisplayBounds()` with APE's
`"Error to fetch bounds."` warning before calling the explicit-bounds overload. The explicit-bounds overload
`generateClickEventAt(Rect, long, ClickPoint, Rect bounds)` keeps dropping a null `bounds` in every cell, because it
also carries `MODEL_LLM_TAP` (`tap.clipToDisplay`), which has no APE counterpart (INV-EXPL-30 unchanged). With the
member a node action with no visible bounds is dropped, as today. The later `bounds.contains` check is APE's and
stays.

**D8 — Item 11.** The 100-cycle bound in `checkAppActivity` runs only with the member: the condition becomes
`has(APE_FIXES) && ++waitForActivityCycles > 100`.

**D9 — Item 12.** The fuzz toss in `MonkeySourceApe` (after `generateEventsForAction(action)`) is one expression:

```
boolean fuzz = spec.has(APE_FIXES)
        ? stepRng.fuzzToss(rate) && spec.has(FUZZING)     // drawn every step (today, INV-DP-13)
        : spec.has(FUZZING) && RandomHelper.toss(rate);   // APE: drawn only when fuzzing is on
```

`ApeFuzzer.generatePinchOrZoomEvent` builds the points with the same draws in both cases and appends the event only
with the member. The array keeps the corrected size `6 + (count << 1)`: its size consumes no draw.

**D10 — Item 8.** `clearChildren` removes `domNode.getFirstChild()` while it is non-null; the
`try`/`catch (DOMException)` and the `"clearChildren DOM prune aborted"` log line are deleted, and imports that
become unused are removed. On Xerces and on the Android DOM the loop ends with zero children.

**D11 — Item 13.** `RunContext` seeds `RandomHelper.seed(seed ^ RANDOM_HELPER)` with a fixed odd 64-bit constant
distinct from `StepRng`'s four (for example `0x2545F4914F6CDD1DL`); `Monkey.mRandom` stays `new Random(mSeed)`.
`RUN_START.seed` keeps the run seed.

**D12 — New-state capture.** `KeyOwnership` default of `ape.takeScreenshotForNewState` becomes `true`.
`StatefulAgent.onAddNode` already reads `takeScreenshot && takeScreenshotForNewState`. The capture's
`nextImageWriter()` draws `mRandom`, as in APE with the same setting.

**D13 — `backup/`.** The plan creates `backup/` at the repository root for files a change supersedes whole.
`.gitignore:13` already ignores `backup/`, so the folder is local and never committed. This change supersedes no
whole file; it creates the folder with a short `README.md` and moves nothing into it.

## API Design

### `Feature.APE_FIXES`
Activation `ape.apeFixes == true`; depends on `ADDONS`. Stating `ape.apeFixes=true` without `ape.addons=true` aborts
with `missing_dependency`; stating `false` is inert.

### `protected boolean ApeAgent.restartStageMounted()`
`false` in `ApeAgent`; `SataAgent`: whether its `DecisionPipeline` assembled `RESTART`. Pure; no RNG draw.

### `protected Action ApeAgent.checkRestart(Action origin)`
Pre: called once per step, after selection. Post: returns `origin` when the stage is mounted, `ape.checkRestart`
is `false`, or no restart is due; otherwise the start action, with `dec.restart`, `restarts` and a new episode.

### `public Action ApeAgent.startRestart()` / `public Action StatefulAgent.restartAction()`
`startRestart` throws `IllegalStateException` when no restart is due (as `restartAction` does today);
`restartAction` (stage path) drops the buffer first.

## Data Flow

Member on: step → guards → `Restart` stage (may select start action) → … → `SataChain` → `moveForward` → events.
Member off: step → guards → … → `SataChain` → `markVisited(action)` → `checkRestart` (may replace) →
`moveForward` (kept action) or `resetTrace` (replaced) → events → fuzz toss on `RandomHelper` when fuzzing.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `missing_dependency` | `ape.apeFixes=true` without ADDONS | plan abort (existing rule) | fix properties |
| `IllegalStateException` | `startRestart` with no due restart | programming error | none (as today) |
| DOM child removal failure | `clearChildren` | none expected on either DOM; not caught | process dies, supervisor retries |

## Risks / Trade-offs

- [The ADDONS cells must not move] → oracle goldens and the four ADDONS `RUN_START` fixtures (apart from the new
  feature and digest) are the regression check; a moved golden in an ADDONS cell is a defect of this change. Item 13
  changes every `RandomHelper` draw and so moves every golden; it is applied last (tasks 6.3–6.4), after the check
  has run with the old seed, and the goldens are then regenerated deliberately.
- [The post-selection path has no oracle coverage: `OracleDriver` enters below `updateStateWrapper`] → a dedicated
  unit test drives `updateStateInternal` with the stage unmounted.
- [Items 10 and 11 live in `MonkeySourceApe`, which does not load off-device] → covered by the device smoke.
- [`decisionNonModel` after `decision` in one step] → D6 makes the second call replace the first; tested.
- [New-state capture shifts SATA's `mRandom` draws in every aperv cell] → symmetric with the `ape` arm under the
  same setting; reported by the paper.
- [Consumers compare `RUN_START`] → every cell's `digest` changes (the new-state default is a base default and the
  digest covers base defaults), so aperv-tool's offline cell gate and mini-experimento's device gate take all six
  new fixtures with the jar.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit | member resolution, presets, fixtures | `RunSpec.resolve` | ~4 |
| Unit | items 3, 4, 6, 12 (pinch), 13 on/off | plan installed with/without ADDONS | ~10 |
| Unit | post-selection restart: marking, weakening, no ledger/retirement/history, record | `installForTest` + scripted agent | ~5 |
| Unit | clearChildren on Xerces without a fake DOM | existing test | 1 |
| Oracle | goldens unchanged | `mvn test` | all |
| Device | smoke `base` and `as` on cryptoapp; prune log absent; off-screen/centre and wait paths by log | AVD | 2 runs |

## Open Questions

None. Every point above is decided by Pedro's plan of 2026-10-21 and his answers of the same day.

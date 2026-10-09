# Design: llm-coordinate-single-base

## Context

The proposal states the defect and its measured effect. This section records what the investigations of 2026-10-03, 2026-10-05 and 2026-10-07 (D13, D14) established about the code at `e93dea86`, because the decisions below depend on it.

**Three conversions and one band, two bases.**

| Site | Converts | Divides / multiplies by | Source of the dimensions |
|---|---|---|---|
| `ape/llm/ApePromptBuilder.java:131-140` → `:405-406` (shared line formatter), `:638-639` (`v13`), `:717-718` (`v17`) | widget centre → [0,1000) | `rootBounds.right`, `rootBounds.bottom`; constants 1080 × 1920 if the tree or root is null | `tree.getRootNode().getBoundsInScreen()` |
| `ape/agent/StatefulAgent.java:1833-1837` (`recordActionHistory`) | executed widget centre → [0,1000), **not clamped** | `display.right`, `display.bottom` | `AndroidDevice.getDisplayBounds()` |
| `ape/llm/LlmEngine.java:95-97, :157` → `CoordinateMapper.toPixels` → `CoordinateNormalizer.normalize` (`:33-35`) | answer → pixel | `deviceWidth`, `deviceHeight` | `ScreenshotStep.deviceDimensions(tree)` (`:50-68`): display first, root only if the display throws |
| `ape/llm/CoordinateMapper.java:128-135` (boundary reject) | rejects `pixelY < H × 0.05` or `pixelY > H × 0.94` | fractions of `deviceHeight` | the same `deviceDimensions` pair, but the fractions were written for a 1920-high screen (gh6 `design.md:294`: "navigation bar (bottom 6% of screen)") |

`ScreenshotCapture.captureViaSurfaceControl` crops `Rect(0, 0, width, height)` from the display with the same `deviceDimensions` pair, so the image and the answer mapping share a base. `AndroidDevice.getDisplayBounds()` (`ape/AndroidDevice.java:130-140`) is `Display.getSize`: on the campaign AVD (`hw.lcd` 1080 × 1920, density 420) it reports 1080 × 1794, excluding the 126 px navigation bar.

**The root is a window, not the screen.** `GUITreeBuilder.buildGUITree` builds from the `AccessibilityNodeInfo` of the active window's root. `MonkeySourceApe` obtains it each step with `getRootInActiveWindow()` and hands it to the agent (`MonkeySourceApe.java:803,854`). `StatefulAgent`'s refresh paths use `getRootInActiveWindowSlow()` (`ape/agent/StatefulAgent.java:682,734`), which returns the same `UiAutomation` root after waiting for idle (`MonkeySourceApe.java:487-498`). Node bounds are copied raw from `AccessibilityNodeInfo.getBoundsInScreen` (`ape/tree/GUITreeBuilder.java:603-604`). The root's bounds are therefore the active window's frame:

- a full-screen activity: `[0,0][1080,1920]`, because the DecorView, whose `navigationBarBackground` child sits at `[0,1794][1080,1920]`, extends behind the navigation bar;
- a dialog or popup: its own rectangle (`[28,690][1052,1166]`, `[555,74][1070,368]`, …);
- a window the keyboard resized, or a bottom sheet: a shortened full-width frame (`H < 1794`; the "short window" row of the table below, 3,937 calls).

Evidence from existing data, gathered without new runs:

- **E6 arm 4.** Per matched decision, the list's base for the executed widget was the full window 1080 × 1920 in 50,018 (159 APKs), a window-sized base in 4,802 (any other root: dialogs and popups, keyboard-resized, tall and landscape windows; 149 APKs), the display 1080 × 1794 in 74 (11 APKs), and unresolved in 4,064 (`settings-audit/coordinate_base.out` at `rvsec-study03-replication-package@d8ec828`; the same script rerun on 2026-10-05 over the 163 traces, avare's now holding no LLM answer, gives 49,766 / 4,777 / 4,063 and 158 / 148 APKs, `evidence/audit_rerun.out`). The fallback constant cannot produce a varying base, and a null root cannot occur on this path, so the root node is the source.
- **UIAutomator dumps.** In the 468 dumps of `rvsec-study03-replication-package/data/raw/grounding-screens/`, the root shows `[0,0][1080,1794]` in 310, but `navigationBarBackground` appears as `[0,0][0,0]` in 250 of them and no node ends below y=1794. `uiautomator dump` clips every node to `Display.getSize`, so these dumps cannot show the raw root. They are why the two bases looked identical offline.
- **E6 arm 4, per prompt (2026-10-05, `evidence/coordinate_counterfactual.out`).**
  - In 130,199 of the 130,248 calls (all but 49), the prompt's numbered entries line up 1:1, in order, with the target-requiring actions the trace prints after `Sata Strategy: buffer size`. That gives an exact list base for every decision, not only the matched ones:

    | List base | Calls |
    |---|---:|
    | full 1080 × 1920 | 105,488 |
    | narrow window (W < 1080) | 19,271 |
    | short window (W = 1080, H < 1794) | 3,937 |
    | tall window (1794 < H < 1920) | 944 |
    | landscape | 328 |
    | display | 169 |
    | other | 62 |

  - A replica of `CoordinateMapper.map` plus the dead-pair ban, fed the snap floor and bands from each run's `RUN_START` (the campaign ran `ape.llmSnapTolerancePx=150`), reproduces the recorded result and reason of 130,199 of 130,199 aligned calls.
- **The bottom band covers app content.**
  - With `H = 1794` the band starts at y = 1686.36. A target-requiring widget's centre lies in (1686, 1794) in 32.8 % of states (2,871 of 8,743), in 146 of 162 APKs; in E5b it does in 51 of 60 APKs. These are bottom-navigation items, `next`/`settings` buttons and snackbar actions.
  - No answer can reach y ≥ 1794, because `normalize` clamps to `H − 1`.
  - The band rejected 4,879 E6 answers that were not list copies (3.7 %). Under this change, 2,995 of these land on a tree widget, 1,865 become off-tree taps, and 19 stay rejected by D8. The replay without the band, which does not model D8, gives 3,007 / 1,872, because it lets the 19 act at the clamped edge.
  - It also rejected the 19 answers with `qwen` y ≥ 1000, not by design: `normalize` clamped them to 1793. Ten of them copy a history coordinate the jar printed outside [0,999] (next bullet); 9 point past the image on their own (`evidence/prompt_zero_scan_e6.out`).
- **The history prints coordinates outside the answer space.** `recordActionHistory` divides the whole centre by the display and does not clamp (`StatefulAgent.java:1836-1837`). A widget centred below the display is shown at y ≥ 1000, one centred left of or above it at a negative value: 2,860 history entries in 2,182 E6 prompts (1.7 %), 134 in 111 E5b prompts (`evidence/prompt_zero_scan_{e6,e5b}.out`).
- **The status bar is 63 px.** `statusBarBackground [0,0][1080,63]` appears in 210 grounding dumps, and 24 dp × 2.625 at 420 dpi gives 63 px. The top band at 0.05 × 1794 = 89.7 px also covers y ∈ [63, 90). E6 has 16 non-copied answers there: 12 would match and 4 would become off-tree taps. Copies also land there: the top-band misses among copies drop from 183 (bottom band removed, top at 0.05) to 70 (top at 0.035). Both E6 `(0,0)` answers are at y = 0, so a 63 px band still rejects them.
- **The data moved under the audit.** The arm-4 trace of `com.ds.avare_404` was overwritten on 2026-10-05 by a run with no LLM answers. The counterfactual excludes it, so it covers 162 traces and 130,248 answers. `evidence/audit_rerun.out` is the audit's own `coordinate_base.py` rerun on 2026-10-05. That script has no exclusion, so it reads 163 traces, but avare's contributes no answer: it counts the same 130,248.

**Why the tests passed.**

- `ApePromptBuilderTest` calls `build(null, …)`, so the dimension block never reads a root.
- `ApePromptBuilderIdentifierTest` builds real lines with a null tree and asserts only that a coordinate is present (`text.contains("@(")`, `:95`), never its value.
- `PromptIntegrationTest:246-299` re-implements the formula with its own constants (`:261-264`, `:292-295`) instead of calling the builder.
- `ScreenshotStep.deviceDimensions` is untestable in the JVM (`ScreenshotStepTest:17`), and so is `LlmEngine.selectAction` (`LlmEngineTest:21`).
- The history normalization has no test.
- The band scenarios (`llm-routing` spec `:275-291`) and `CoordinateMapIntegrationTest:34-36` use a 1080 × 1920 device. `CoordinateMapperOffTreeTapTest:80-97` uses 1794, but asserts the reject at y=1750 and y=80 as intended behavior: under the old reading of the band, a correct test.

Constraints (SDD principles, written SDD-Pn to keep them apart from the `pending.md` items): for Part A, SDD-P1 (no flag, no new abstraction beyond one primitive); SDD-P3 (remove the `GUITree` parameter and the inline copies rather than keep them); the compose-don't-duplicate rule (one forward primitive next to its inverse); and the JVM suite (1123 tests, 19 skipped) stays green, with the `rearch-01` parity golden suite identical for Part A. Part B adds one key that selects behavior, `ape.mopScoring` (the author's decision, so that one jar runs two arms, D18), and regenerates the MOP goldens (task 17.1).

## Architecture

```
LlmEngine.selectAction(tree, state, actions, mopData, recentActions, mode)
  │
  ├─ step 2  ScreenshotStep.deviceDimensions(tree) ──► {W, H}            (single source)
  │          ScreenshotStep.capture(W, H) ──► image in W×H space
  │
  ├─ step 4  ApePromptBuilder.build(W, H, state, actions, mopData, image, recentActions)
  │            ├─ element list:  visibleCentre(node bounds, W, H) ──► toNormalized(·, W, H)   (none if off screen)
  │            └─ history:       visibleCentre(entry.bounds, W, H)  ──► toNormalized(·, W, H)
  │
  └─ step 8  answer outside [0,999] (tool ≠ back) ──► no_match / boundary   (CoordinateMapper.inAnswerSpace)
             CoordinateMapper.toPixels(answer, W, H) ──► CoordinateNormalizer.normalize
             CoordinateMapper.map(pixel, …, W, H)
               └─ boundary: pixelY < H × topPct (0.035) ──► null      (no bottom band)

StatefulAgent.recordActionHistory(action)
  ├─ node action    ─► ActionHistoryEntry(type, simple class, text|content-desc, new Rect(nodeBounds), typed, "")   (no display read; list helpers, D13)
  ├─ MODEL_LLM_TAP  ─► ActionHistoryEntry(click|long_click, null, null, Rect[x,y][x+1,y+1], null, "")
  └─ MODEL_MENU     ─► ActionHistoryEntry(menu, …, null bounds)   rendered without coordinates, like back
```

### Key Components

| Component | Responsibility | Input | Output |
|---|---|---|---|
| `CoordinateNormalizer.toNormalized` (new) | The single pixel → [0,1000) conversion | `pixelX, pixelY, width, height` | `int[]{normX, normY}` in [0,999] |
| `CoordinateNormalizer.normalize` (unchanged) | The single [0,1000) → pixel conversion | `qwenX, qwenY, width, height` | `int[]{pixelX, pixelY}` |
| `ApePromptBuilder.build` (signature changes) | Renders list and history in the decision's base | `deviceWidth, deviceHeight, state, actions, mopData, base64Image, recentActions` | `List<Message>` |
| `ApePromptBuilder.visibleCentre` (new, private static) | The pixel a widget is shown at: centre of its bounds ∩ `[0,W)×[0,H)` (`Rect.intersect`) | `Rect bounds, W, H` | `int[]{x, y}`, or null when `Rect.intersect` returns false |
| `ApePromptBuilder.ActionHistoryEntry` (fields change) | Carries the executed widget's pixel bounds | `bounds` (`Rect`, pixels) | — |
| `StatefulAgent.recordActionHistory` | Records pixel bounds, no normalization | executed `ModelAction` | appends entry |
| `LlmEngine.selectAction` | Hands the step-2 pair to the builder; refuses an out-of-space answer before it is mapped | `dimensions`, parsed answer | — |
| `CoordinateMapper.inAnswerSpace` (new, static) | Whether an answer lies in the [0,1000) space the prompt and image define | `x, y` | `boolean` |
| `CoordinateMapper.map` (band changes) | One boundary band: the status bar | `pixelY`, `deviceHeight`, `topPct` | null when `pixelY < H × topPct` |
| `ApePromptBuilder.formatHistoryEntry` (all variants) | The only history renderer; `back` and `menu` without coordinates | entry, `W`, `H` | one line or a compact token |

## Mapping: Spec -> Implementation -> Test

| Requirement / invariant | Implementation | Test |
|---|---|---|
| llm-prompt "Single Coordinate Base", INV-PRM-06 | `ApePromptBuilder.build(int, int, …)`; all list sites and `formatHistoryEntry` call `visibleCentre` then `CoordinateNormalizer.toNormalized`; an unresolved or wholly off-screen node renders no `@(x,y)` (D11) | `ApePromptBuilderCoordinateBaseTest` (new): full-screen, dialog, edge-cut and off-screen scenarios per coordinate-bearing variant; unresolved node per variant; list/history agreement |
| llm-prompt "Widget List Generation" (modified bullet) | shared line formatter, `buildRvsmartV13UserText`, `buildRvsmartV17UserText` | `ApePromptBuilderCoordinateBaseTest`; `PromptIntegrationTest` rewritten to read production output |
| llm-prompt "Action History" (pixel bounds, build-time normalization) | `ActionHistoryEntry(bounds)`, `formatHistoryEntry`, `StatefulAgent.recordActionHistory` | `ApePromptBuilderTest` history cases updated to bounds inputs; new scenarios "history entry normalized at build time" and "history entry of a widget cut by or outside the display" |
| llm-infrastructure "CoordinateNormalizer", INV-LLM-13 | `CoordinateNormalizer.toNormalized` | `CoordinateNormalizerTest`: forward scenarios, clamp, exhaustive round trip for `d ∈ {1080, 1794, 1920, 1999}` |
| llm-routing "Action Selection Pipeline", INV-RTR-21 | `LlmEngine.selectAction` passes the `dimensions` local to capture, build and mapping | not JVM-reachable (see `LlmEngineTest:21`): code inspection in the review task plus the device smoke trace check (tasks group 8) |
| llm-routing "Coordinate-to-ModelAction Mapping", INV-RTR-22 | `CoordinateMapper.map` (one band), `CoordinateMapper.inAnswerSpace`, `LlmEngine` step 8 | `CoordinateMapperOffTreeTapTest` (band cases rewritten), new `CoordinateMapperBoundaryTest`: status-bar reject, bottom-navigation match, the `inAnswerSpace` predicate. The step-8 branch that applies it (no `map` call, `back` exempt) is not JVM-reachable (`LlmEngineTest:21`): inspection in task 6.2 plus the device check |
| llm-infrastructure "LLM Configuration Keys" | `Config.llmBoundaryTopPct = 0.035`; bottom field deleted; `KeyOwnership` `feature` line removed and `RETIRED` entry added; `Feature.own` | `ConfigTest`, `KeyOwnershipTotalityTest`, `RunSpecAbortTest` (iterates the retired keys) |
| run-spec "Total Fail-Fast Validation" | `KeyOwnership.RETIRED.put("ape.llmBoundaryBottomPct", …)` | `RunSpecAbortTest` plus one explicit scenario |
| exploration "StatefulAgent — Action History Ring Buffer" | `ActionHistoryEntry(bounds)` in pixels, `menu`, LLM tap as a one-pixel rectangle; no display read in `StatefulAgent.recordActionHistory` | `StatefulAgentActionHistoryTest` (new) or the agent's existing history fixture: node click → `[0,105][1080,252]`; LLM tap → `[600,900][601,901]`; menu → no bounds. If `recordActionHistory` is not reachable from a fixture, `verification.md` records them as covered by the builder tests of task 3.3 plus the device check 8.2 (task 3.5) |
| exploration "StatefulAgent — Action History Ring Buffer" (result of the entry's own action, D12) | `recordActionHistory` appends with an empty result; `updateStateInternal` fills the last empty result after `_lastState`/`_stateBeforeLast` move and before `resolveNewAction` | `StatefulAgentActionHistoryTest` (new) or the agent's existing history fixture: the scenarios "each entry carries its own action's outcome", "a step without a model action does not shift an outcome" and "a model action replaced by a restart leaves no entry" (`StatefulAgent.checkRestart`). If `updateStateInternal` is not reachable from a fixture, `verification.md` records the device check 8.2 as the gate (task 3.7) |
| llm-prompt "Action History" (LLM tap, menu, `v17`) | `StatefulAgent.recordActionHistory`, `formatHistoryEntry`, `buildRvsmartV17UserText` | `ApePromptBuilderCoordinateBaseTest`: `v17` history through the formatter; LLM tap entry; menu entry |
| llm-prompt "Element List of the v13 and v17 Variants" (D10) | `buildRvsmartV13UserText`, `buildRvsmartV17UserText` group target-requiring actions by (target `Name`, resolved-node bounds), no ordinal | `ApePromptBuilderElementListTest` (new): widget with click+long-click → one line; two scroll actions on one widget → one line; two alias nodes with the same bounds → one line; same bounds with different `Name`s, and same `Name` with different bounds → two lines; no line starts with a digit; first-appearance order; `v17` tag sums visits; `v17` screen line counts widgets on both sides |
| llm-prompt "System Message of the v13 Variant" (D14) | `ApePromptBuilder.buildV13System` | `ApePromptBuilderTest` (or a new `ApePromptBuilderV13SystemTest`): the exact `PRIORITY` line; no `unvisited`/`visited` in the `v13` system message; `type_text` listed only with an input widget |
| exploration "StatefulAgent — Action History Ring Buffer" and llm-prompt "Action History" (class and text, D13) | `ApePromptBuilder.simpleClassName`, `ApePromptBuilder.textOrContentDescription` (public static, shared with the list); `StatefulAgent.recordActionHistory` calls them | `ApePromptBuilderCoordinateBaseTest`: scenario "history entry names its widget as the list does"; the agent history fixture: scenario "icon with only a content-description" and "Action history populated after action" (`widgetClass="Button"`) |
| mop-guidance loader, INV-MOP-34 (format 2) (D15) | `MopData.load` (`SUPPORTED_FORMAT_VERSION = 2`), parsers of `targets`, `dist`, `activityDist`, `handlers` | `MopDataTest` (format-2 fixtures; format 1 → `version-mismatch`) |
| mop-guidance "Handler Stamp Lookup" (D17) | `MopData.lookup(activity, node, event)`, `MopData.Lookup` | `MopDataLookupTest` (new): stamp listed, stamp listed with `none`, stamp unlisted → id, no stamp → id, `itemSelected` ignores stamps |
| ui-tree "Handler Stamp Capture" (D16) | `GUITreeBuilder.fillNode`, `GUITreeNode.handlerClick/handlerLongClick` | `@Ignore`d Android-runtime test as `GUITreeBuilderPasswordTest`; device smoke 17.2 |
| mop-guidance "MopScorer — Priority Boost" (modes, D18) | `MopScorer` (flag and distance), `MopWidgetPass`, `ScoringParams` | `MopScorerTest`, `MopScorerDistanceTest` (new): weights per d, tie split by level, retired targets skipped |
| mop-guidance "Parent/child widget granularity reconciliation" (D17) | `MopWidgetPass.mopBoostWithContainment` over nodes (the node walk replacing `MopScorer.containmentShortIds`), each node through `MopData.lookup` | `MopScorerTest` containment cases, `MopScorerDistanceTest` (stamped ancestor) |
| mop-guidance "ApePromptBuilder — Widget Metadata in LLM Context" (D17) | `ApePromptBuilder.buildWidgetMetadata` keeps `MopData.getWidget` (id-keyed) | existing `ApePromptBuilderTest` metadata cases, unchanged |
| mop-guidance "MopScorer — Event-Type-Aware Reachability Scoring" (D17) | `MopScorer.eventTypeOf` feeding `MopData.lookup`; the string-keyed `score` forms removed | `MopScorerTest`, `MopDataLookupTest` |
| mop-guidance "MopData — Load Status Line and Fail-Fast" (D15, D23) | `MopData.load` status record (`targets`, `handlers`, `droppedPairs`); `parse-error` on missing format-2 members; `targets: 0` loads | `MopDataTest` |
| mop-guidance "Target Retirement" (D19) | `MopRetirement` (new, per-target counters) called from `StatefulAgent.moveForward` at selection with the weight's lookup; `EventSink.mopRetired` | `MopRetirementTest` (new); agent fixture where reachable (containment-only weight retires its targets) |
| component-triggering "Cadence-Based MOP Activity Launch" (D20) | `MopLauncherStage.selectTriggerCandidate` | `MopLauncherStageTest` (distance ordering, ties by manifest order, flag mode unchanged) |
| action-selection "MOP-target greedy short-circuit" (D21) | `SataAgent.pickBestMopTarget` | `SataAgentMopShortCircuitTest` (activity-novel first, then undivided weight `mopWeight`, then target count, then priority; a split tie does not invert) |
| llm-prompt "MOP Marker Annotation", "Exploration Context" (D22) | `ApePromptBuilder.buildMopMarker` over the line's actions, `buildExplorationContext` | `ApePromptBuilderMopMarkerTest` (new): the three R9 cases, a retired target, a stamped node |
| event-sink "StepRecord Schema", "Run-Level Records", "RUN_END As the Natural Last Record" (D23) | `StepRecord` (`dec.mopd`, `dec.mops`, `dec.ret`), `EventSink.mopRetired`, `NdjsonSink` (`MOP_DATA`, `RUN_END` counters `stampNodes`, `stampHits`, `retired`) | `NdjsonSinkTest`, `SinkNeutralityTest` |
| run-spec "Total Fail-Fast Validation", "Feature Model with Declared Dependencies", "Level-0 RUN_START Echo", INV-RUN-09 (D18–D20) | `KeyOwnership`, `Feature.MOP`, `RunSpec.MopParams` (`mopScoring`, `mopWeightD1..3`, `mopRetireAfter`, `mopLauncherDmax`), `RunSpecEcho` (mode-dependent `inert`) | `KeyOwnershipTotalityTest`, `RunSpecResolveTest`, `RunSpecAbortTest` (bad `mopScoring` value), `RunSpecEchoTest`, `PresetsTest` |
| static-analysis-entrypoints "Derived compact MOP artifact — projection contents" (D15) | rv-android `derive_mop_artifact.py` (gh122) | rv-android tests; this repository's fixtures regenerated from it |

## Goals / Non-Goals

**Goals**

1. Every [0,1000) coordinate in a prompt is in the space of the screenshot and of the answer mapping, on every window type (INV-PRM-06, INV-RTR-21).
2. One forward conversion in the jar, defined next to its inverse and tested against it (INV-LLM-13).
3. The list and the history cannot diverge for the same widget, by construction rather than by agreement between two call sites.
4. The prompt builder's coordinate output becomes JVM-testable with real dimensions.
5. The boundary reject keeps the model off system UI and nothing else: on the display base that is the status bar (and a degenerate `(0,0)`), not 107 px of app content (INV-RTR-22).
6. The history tells the model what actually ran: an off-tree tap where it tapped, a menu press as a menu press, and each action with its own outcome (D12).
7. The `v13`/`v17` lists show each widget once, without an ordinal nothing reads (D10; an improvement the author confirmed on 2026-10-07).
8. A listed coordinate is a point of the widget that is on screen, the point the jar would tap for it; a widget with no part on screen lists none (D11).
9. The history names a widget as the list does: the class simple name and the text-or-content-description identifier, as the `exploration` field table has always said (D13).
10. The `v13` system message asks for nothing the `v13` prompt does not show (D14).

Part B:

11. The compact artifact carries GATOR's distances and a handler-class table in a format both sides check (D15, INV-MOP-34).
12. A widget whose node carries a handler stamp is judged by the handler that actually runs, with the id key as the fallback (D16, D17).
13. Under `ape.mopScoring=distance`, a MOP action weighs by its nearest unretired target, the launcher goes to the nearest activity first, and exercised targets stop attracting exploration (D18–D20); under `flag`, the E6 mark is still available from the same jar.
14. The MOP shortcut tries widgets new to the activity first (D21).
15. The prompt marks a line exactly when the score treats one of its actions as MOP (D22).
16. Every step records the distance and the lookup source behind a MOP choice, and every retirement (D23).

**Non-Goals**

- **Reproducing E5b/E6.** No flag keeps the old base. An old arm is reproduced by running its jar (`e93dea86` for E6).
- **Changing the base itself** to the real display size (1920): see D1.
- **`CoordinateMapper` beyond the band**: containment, snap tolerance, off-tree tap synthesis, the dead-pair ban and the preserved `type_text` → long-click defect are untouched.
- **What the list offers.** After this change 6,874 E6 copies still miss their widget (scenario S4 of `evidence/coordinate_counterfactual.out` §6). About 86 % of those (5,891) copy an entry no tool can act on as listed:
  - 5,066 are a `click` on an entry whose only action is a scroll;
  - 576 are a `click` on a long-click-only widget;
  - 227 copy an entry with no visible area. 200 of these are nodes entirely off screen, which this change lists without a coordinate (D11), so under the same-entry assumption they cannot be copied at all. The other 27 are zero-height nodes inside the display, which it still lists (D11). All 27, and 101 of the 200, are actions APE had already marked invalid;
  - 22 are a `type_text` on a non-input entry.

  Of the geometric rest, 771 are cases where containment picks a smaller child of the copied container, 159 hit another overlapping or nearby widget, and 53 fall in the status-bar band.

  `v13` lists every target-requiring action with `@(x,y)`, and its lines carry no action type. Scroll is excluded from the LLM's tools by design (gh6: SATA scrolls mechanically, `docs/analise_prompts_sota.md:198,213,540`), so a copied scroll-only entry can only become a `click` that finds no `MODEL_CLICK`. Selection by list index is **not** an option: the author discarded it on 2026-07-29, after an earlier rv-android implementation was abandoned (`rvsec/rv-android/docs/20260724_relatorio_calibracao_aperv.md` §[rev2b]). The consistent remedy is to curate the list so that it offers only entries the tools can act on, which the same report already recommends because containers pollute the list. That is a prompt change with its own proposal.
- **Landscape.** 328 E6 answers (31 APKs) were given on a 1794 × 1080 display. Under this change, copies there reach the named widget 47 % of the time, against 42 % as run (49 % with the clamped centre; 4 landscape copies are of off-screen entries). Capture passes rotation `0` (`ScreenshotCapture.java:80-81`), and whether the crop matches `getSize` in landscape is unverified on device. One base keeps the list and the mapping consistent with each other, not necessarily with the image.
- **`ScreenshotStep.deviceDimensions`'s own fallback order** (display → root → constants). When the display throws, the root pair still becomes the base for capture, prompt and mapping alike, so the three stay consistent with each other. That path was not observed in the campaign data.
- **`ScreenshotCapture`'s UiAutomation fallback.** It resolves `androidx.test.platform.app.InstrumentationRegistry`, which is not on the `app_process` classpath, so in the aperv deployment it fails and capture returns null. Were it ever to succeed, `UiAutomation.takeScreenshot` returns the real display, and the image would not be in the `W × H` space. Recorded as a risk, not changed here.
- **Telemetry of Part A.** No field is added for the coordinate repairs. `llm[].qwen`, `llm[].px` and the `user` dump already let an analysis check the base (`px/qwen` gives the mapping base; the dump gives the list).
- **System message, variant layouts, image processing**: unchanged, apart from one clause of the `v13` system message (D14). One instruction goes stale: three system messages, `v17`'s among them, tell the model to avoid the navigation bar (`ApePromptBuilder.java:212,289,310`), while the screenshot, cropped to the display, no longer contains the navigation bar. It is left as it is, because the system message is out of scope and the line asks for nothing the model can do in the image.

## Decisions

### D1. The base is the display pair `ScreenshotStep.deviceDimensions` returns

That pair is already what the model sees (capture crops `W × H`) and what its answer is multiplied by. Making the prompt follow it changes one consumer. Making anything else the base would change two.

*Alternatives.*
- (a) **Root node for list and answer.** Wrong for every non-full-screen window: the image is the whole display, so an answer read from the image would be scaled into a dialog's rectangle.
- (b) **Real display size (`getRealSize`, 1920) everywhere.** This would put the navigation bar inside the image, and the model's answer space with it. That makes the old bottom band meaningful again, but only by showing the model 126 px it must not tap. It also changes the capture crop and the off-tree tap validity domain (`LlmTapAction.clipToDisplay` with `getDisplayBounds`). Larger blast radius, no measured benefit; rejected. D7 re-derives the band for the base D1 keeps instead.

### D2. Dimensions are arguments of `build`; the `GUITree` parameter is removed

`LlmEngine` already holds the pair. Passing it makes the one-base property a matter of data flow inside one method (`selectAction`), which a reader can check by inspection.

*Alternatives.*
- **The builder calls `ScreenshotStep` itself.** That is a second determination per decision, which can disagree (for example after a rotation between calls), and it couples the builder to Android reflection, making it JVM-untestable.
- **Keep `tree` for future use.** SDD-P3: it has no other use in `build`.

### D3. One forward primitive, `CoordinateNormalizer.toNormalized`, int-only, multiply-first

It sits next to `normalize` so both directions are read and tested together. The arithmetic is `(int)(p * 1000.0 / d)` clamped to [0,999]: the history's form, which had no clamp, plus the clamp the builder's copies had. The builder's copies used `(int)((p / (double) d) * 1000)`. The two forms were compared exhaustively for every integer pixel `0 <= p < d` and every `d` in [500, 2000): they agree everywhere. Consolidating therefore changes no number for a given divisor. Every coordinate change this change causes comes from the divisor (D1), which keeps the before/after comparison attributable to one cause.

*Alternative.* A `Rect`-taking overload. It is not needed: the builder's `visibleCentre` produces the pixel from a `Rect` (D4, D11). An int-only signature keeps `CoordinateNormalizer` free of Android types, like its inverse.

### D4. Centres come from `Rect.centerX()` / `Rect.centerY()` of the visible part

One private helper in the builder, `visibleCentre(Rect, W, H)`, intersects the bounds with the display and returns `centerX()`/`centerY()` of the result, or null (D11). It replaces three inline `(left + right) / 2` expressions in the builder; the one in `StatefulAgent` disappears, because the agent stores bounds (D5). `centerX()` is `(left + right) >> 1`, which equals `/ 2` here: after the intersection both edges are non-negative.

### D5. History entries carry pixel bounds; the builder normalizes them

The history is recorded when an action is selected, before it is dispatched, and shown in a later prompt. Normalizing at record time requires a divisor at record time, which `StatefulAgent` would have to find on its own. That is the second determination this change removes. Normalizing at build time uses the same call and the same pair as the list, so agreement is structural. `StatefulAgent` no longer reads `AndroidDevice.getDisplayBounds()` for the prompt.

The entry stores the node's bounds (a copy of the `Rect`) rather than a centre, because the point shown is the centre of the visible part (D11), which depends on the display of the decision that renders it. Storing the bounds lets the builder apply the one helper to the list and the history alike.

*Alternative.* Keep normalized history and hand `StatefulAgent` the dimensions. That needs a channel from `ScreenshotStep` (owned by `RunContext`'s LLM units) into the agent for a value it would use once. It is more coupling for a weaker guarantee.

### D6. No flag, no kill switch

This is a defect repair to a mechanism that had a stated intended behavior (gh6 design `:277`). A flag that selects the defective base would exist only to reproduce E6, which is reproducible from its jar (SDD-P1). The comparability consequence is documented in the proposal instead.

### D7. The boundary band is re-derived in the display base: one top band, no bottom band

The band's purpose, as gh6 wrote it, is to keep the model off system UI. On the display base D1 keeps, the system UI inside `[0, H)` is the status bar, 63 px on the campaign AVD. The navigation bar is outside the image and outside every pixel `normalize` can produce. So:

- **`ape.llmBoundaryBottomPct` is retired** through `KeyOwnership.RETIRED`, with a reason naming this change.
  - Every value below 1.0 rejects app content; every value from 1.0 up never fires. No value of the key does what its description says.
  - SDD-P3 deletes it rather than parking it at 1.0, where it would be a knob with no effect.
  - A properties file that still sets it aborts resolution, so a configuration written for the old band cannot silently run under the new one.
- **`ape.llmBoundaryTopPct` defaults to `0.035`**, which is 63 / 1794 rounded down to the band edge `62.79`. It rejects `y ≤ 62`: the status bar and every degenerate `(0,0)`, the second purpose the spec names. The key stays: the status bar height depends on density, so a study on another AVD sets it, which is what J1b exposed it for.
- **Not derived at runtime.** Reading the status bar height from the system (`status_bar_height` dimen, or `WindowInsets`) would make the band device-correct without a key. It adds reflection to a JVM-tested unit for a value the campaign AVD fixes. Rejected for now, and recorded as an open question.

*Alternatives.*
- **Keep `0.94` and accept the loss.** Fixing the list without the band leaves copied answers at 83.6 % of their widget, against 87.2 % with D7 (both with the clamped whole centre; 87.1 % with D11 as well). The bands reject 2,177 copies under the display base alone, against 70 with D7 (`evidence/coordinate_counterfactual.out` S1/S3): once the list stops shifting copies upward, copies of bottom widgets fall into the old band (925 copies that reached their widget as run are rejected by it). It also keeps rejecting the 3.7 % of answers that are not list copies. Rejected.
- **Bottom default 1.0, key kept.** This is behaviorally the same as retiring the key, but it leaves a key no value of which is meaningful (SDD-P3). Rejected.

### D8. An answer outside [0,999] is refused before it is mapped

`normalize` clamps, so an answer of y = 1040 became pixel 1793 and was then rejected by the bottom band. That was not by design, but it was the right outcome: the answer is outside the image. With the bottom band gone, the same answer would act on whatever widget sits at the screen's edge.

Of the 19 such E6 answers, 10 copy an out-of-space coordinate the jar itself printed in the history (Context). D5 and D11 stop printing those, so after this change the check is for answers that point past the image on their own: 9 in E6, none in E5b. It stays because it is a predicate and one branch, and because without it the clamp turns such an answer into an action at the screen's edge: 12 matches and 7 off-tree taps among the 19, in the replay without the band. Its wiring is in `LlmEngine.selectAction`, which is not reachable from a JVM test (`LlmEngineTest:21`): the unit test pins `inAnswerSpace`, and the branch itself is checked by inspection (task 6.2).

`CoordinateMapper.inAnswerSpace(x, y)` is `0 <= x <= 999 && 0 <= y <= 999`. For every tool except `back`, `LlmEngine` step 8 does not map an answer that fails it. The decision is `no_match` with reason `boundary`, which `classify` already assigns to a null match that is neither banned nor `(0,0)`. The reason set does not grow. The 19 E6 answers this affects were `boundary` before and stay `boundary`.

*Alternative.* Drop the clamp in `normalize`. The clamp also protects the inverse's other callers and the round-trip property (INV-LLM-13). Changing a primitive to get a policy is the wrong direction. Rejected.

### D9. History entries record what ran, for actions without a resolved node

D5 rewrites `recordActionHistory` to store pixels, so the branch that handled nodeless actions is rewritten anyway. Today it records a `MODEL_LLM_TAP` and a `MODEL_MENU` as `click @(0,0)`. In the prompt, the model then sees a click at the screen's corner that never happened. This is frequent: every one of the 47,573 history entries rendered `@(0,0)` in E6 arm-4 prompts is a class-less `click`, and they appear in 31,466 of 130,266 prompts (24.2 %); in E5b `v13_A`, in 2,157 of 9,586 (22.5 %). The model copied one twice, both `degenerate` answers (`evidence/coordinate_counterfactual.out:172`, `evidence/prompt_zero_scan_e6.out`, `evidence/prompt_zero_scan_e5b.out`).

- **LLM tap.** The entry stores the one-pixel rectangle `[x,y][x+1,y+1]` at `LlmTapAction.getPixelX()/getPixelY()`, whose visible centre is `(x,y)`, and `click` or `long_click` from `isLongClick()`. The class and text stay null, so it renders as `- click @(x,y) → <result>`.
- **Menu press.** The entry's type is `menu`, and `formatHistoryEntry` renders it without coordinates, as it already renders `back`.
- **`v17`.** Its compact `Recent:` line renders each entry inline from the raw fields (`ApePromptBuilder.java:686`), which is how `back@(0,0)` appears. It moves onto a compact form produced by the same formatter, so there is one place where a history entry becomes text.

Scroll actions are still recorded as `click`, by the `default` branch of the type switch. That is out of scope here: it belongs with the list-curation change, which decides whether scroll-only entries reach the prompt at all (Non-Goals).

This is a change to prompt content beyond the base. It is included because it is the same code, it fixes coordinates the model is shown, and leaving it would carry `@(0,0)` into the new pixel field.

### D10. The `v13`/`v17` lists have one line per widget and no ordinal

**Status: an improvement, not a repair, confirmed by the author on 2026-10-07 (`pending.md` P1).** A repeated line is redundant, not wrong; the author judged that a list showing the same widget twice has no reason to stay. On confirmation the grouping key changed from the node's identity, which the first draft used, to the pair (target `Name`, resolved-node bounds), for the reasons in the first bullet below. The omission of `@(x,y)` for an action with no resolved node is a repair, required by "Single Coordinate Base" at all three list sites and implemented by task 2.3; it does not depend on this decision.

`buildRvsmartV13UserText` (`ApePromptBuilder.java:626-652`) and `buildRvsmartV17UserText` (`:693-733`) iterate `state.getActions()` and write a line for every target-requiring action, prefixed `N. `. Two facts make both properties pure overhead:

- **Every field of a `v13` line belongs to the widget, not to the action.** The fields are class, identifier, coordinate, MOP marker and the static metadata of `buildWidgetMetadata`. A widget with click and long-click actions, or with two scroll directions, is written twice with the same text. In E6, 25.3 % of lines share their node's bounds with an earlier line, and 74.2 % of prompts contain such a repeat (`evidence/curation_e6.out`, which identifies widgets by bounds).
- **Nothing reads the ordinal.** The tools take `x, y` only (`LlmClient.java:158-167`). The parser extracts coordinates. `CoordinateMapper.map` matches the pixel against the `actions` list it is given (`state.getActions()`, `LlmRandomStage.java:85-86`), not against prompt lines. The ordinal is a leftover of rvsmart V13's "simple numbered element list" (`:615`).

The lists therefore render one line per widget and drop the ordinal:

- A widget is the pair (target `Name`, resolved-node bounds): two target-requiring actions whose `getTarget()` values are equal and whose resolved nodes have the same four edges share one line, rendered from the first one's node. The bounds are compared by their edges, not with `Rect.equals`, which the JVM test stub of `Rect` does not implement. `getTarget().equals` is safe for every `Name`: each one is interned by `NameManager.getCachedName` (keyed by namer and `toString`), so equal targets are one instance. The composite names also define value `equals`/`hashCode`, but `IndexNamer`'s and `EmptyNamer`'s names do not, so the key relies on the interning, not on value equality.
  - In E6, 279,463 lines (25.3 %) repeat an earlier line's bounds. 274,830 of them (24.9 % of all lines) also share its target `Name` and collapse. The other 4,633, in 4,434 prompts, are distinct `Name`s with identical bounds (a container and its single child) and keep a line each, because their class or identifier differs (`evidence/dup_identity_e6.out`).
  - Conversely, 17,865 lines share a `Name` with an earlier line but resolved to a node with other bounds, because `State.resolveAction` samples one alias per action. They keep a line each: they show another point of the screen, and the mapper matches each action against its own resolved node.
  - *Why the pair and not node identity.* The first draft grouped by identity. Two cases separate the criteria, and in both the pair is the better one. First, two actions of one `Name` may resolve to two alias nodes with identical bounds: 14,241 of the 274,830 collapsing E6 lines have `RN>1`, the listing's count of aliases, so some of them are such pairs. They carry one `Name`, one rectangle and therefore one `@(x,y)`, and the mapper, which matches a coordinate against bounds, cannot tell them apart. Identity would keep both lines: a duplicate the model sees, which is what D10 removes. Second, the trace has no node identity, but `State.printActions` prints the target and the resolved bounds of every listed action, so with the pair an offline analysis rebuilds each line's actions exactly, up to a `Name.toString` collision between two namers, which the jar keeps apart and the trace cannot ("[Offline analyses assume one line per action]" in Risks). The cost is that two alias nodes whose text differs while their `Name` and bounds agree show only the first one's text; both lie under one coordinate, and the trace cannot measure how often their text differs.
- Lines keep the order of each widget's first action in `actions`.
- An action with no resolved node keeps a line of its own, with **no** `@(x,y)`. At `e93dea86` none of the three list sites omits it: `normX`/`normY` start at 0 and are always appended (`ApePromptBuilder.java:398,438`; `:634,646`; `:713,726`), so such a line reads `@(0,0)`. That contradicts the main spec's "Unresolved action node" scenario, which has stood since gh6 and which no test exercises. The repair applies to all three sites ("Single Coordinate Base"). It changes no measured prompt: E6 and E5b have no unresolved listed action, because `StatefulAgent.validateAllNewActions` resolves every action of the state against the latest tree and `GUITree.pickNodes` returns at least one node or throws. The only `@(0,0)` list lines in E6 (25, one prompt of `org.glpi.inventory.agent`) are resolved WebView nodes whose centre lies off-screen at negative coordinates, which the clamp mapped to 0. Under D11 such a node is listed at the centre of its visible part, or without a coordinate when no part of it is on screen.
- `v17`'s status tag (`[UNTESTED]`, `[TESTED-Nx]`, `[WELL-TESTED]`) uses the sum of `getVisitedCount()` over the widget's target-requiring actions. Its screen-info line counts widgets on both sides of the ratio and reads `elements tested`, so the numerator and the denominator share a unit. The `MOP NAVIGATION` block (`buildExplorationContext`) is shared with the default-branch variants, keeps counting actions, and is unchanged.

*The MOP marker and the static metadata of a collapsed line.* Both are keyed by `(activity, shortId)` (`buildMopMarker`, `ApePromptBuilder.java:456-469`; `buildWidgetMetadata`, `:478-485`), and the marker is the OR of the per-event flags (`MopData.java:374-375`). A line's actions share one `shortId`: under the default base naming (`ape.baseNaming` unset, so `actiontype`, `NamingFactory.java:951-966`), every actionable node is named with the type namer, whose `Name` carries the class and the resource id (`NamingFactory.java:896-901`, `TypeNamer.java:119`), and refinement only moves to namers that contain it (`AbstractNamer.refinesTo`). So rendering the line from the first action's node loses no marker and no metadata. Part B of this change brings two things that break this, so the marker gets a rule per line, not per node (D22):

- a per-event distance (rv-android gh120 produces distances per method; consuming them is the APE-RV side of the plan, rv-android `docs/20261005_gator_fragments_compose_guia_teste.md` §6.7). A line that joins a click and a long-click may then have two distances;
- the handler stamp (rv-android gh121, `rvsec.*` extras on the accessibility node, read through `GUITreeBuilder.fillNode`). Two alias nodes in one line may carry different handlers.

The evidence is consistent with the model already choosing per widget, which would leave choice unaffected: `click` copies of repeated widgets occur at 1.05× the per-widget rate, against 0.68× the per-line rate. That holds for `click` only. For `type_text` the ratios are 2.37× and 1.70× (`evidence/curation_e6.out` §C), confounded by input widgets, which nearly always carry both a click and a long-click. Whether the shorter list changes what the model picks is measured on device, not assumed. Mapping is unaffected because it never used lines. Exploration is unaffected because `ModelAction`s, SATA, visit counts and dead-pair keys never read the prompt.

*Not done here:* dropping widgets whose only actions are scrolls. The model copies them as a `click` in 3.9 % of calls (5,066). About 52 % of those copies end `dead_pair` and 3.4 % of the executed ones reach a new state; for the 1,305 that are not containers it is 71.3 % and 1.6 %, the highest `dead_pair` share of any `click` copy category (`evidence/curation_e6.out` §D, lines 78-83). Whether to hide them is a separate decision about what the LLM is shown, and is left open.

### D11. A widget is listed at the centre of its visible part, and not at all when off screen

The list used the centre of the node's whole bounds, clamped to the display by the arithmetic. That is not a point of the widget the model can see, nor the point the jar taps. `MonkeySourceApe.generateClickEventAt` (`:368-440`) taps a node action at the centre of the node's bounds intersected with the visible window (`getVisibleBounds`, `:299-333`; `exactCenterX/Y`, so up to half a pixel from the integer centre the list uses), and drops the action when the intersection is empty (`:375-380`; `exploration` "Off-Screen Action Handling", which the code labels INV-EXPL-19). A dropped action is still marked visited (`StatefulAgent.java:852`) and still counts a dead-pair strike. The list now follows the same rule, against the display the screenshot shows:

- the listed pixel is `visibleCentre(bounds, W, H)`: `bounds ∩ [0,W)×[0,H)` (`Rect.intersect`), then `centerX()`/`centerY()`;
- when `Rect.intersect` returns false, the line keeps its place and carries no `@(x,y)`, as an unresolved node does ("Single Coordinate Base", task 2.3). Whether such a line should be listed at all is the list-curation question this change leaves open;
- `Rect.intersect` compares edges, not areas, so a zero-width or zero-height node lying inside the display intersects it and is listed at the centre of its line. This is also what the dispatcher's `getVisibleBounds` accepts. Its actions are invalid anyway: `validateBounds` rejects an empty node (`MonkeySourceApe.java:619-622`), so neither SATA nor the mapper acts on them;
- the history renders `entry.bounds` through the same helper (D5), so a widget and the entry of a click on it still show one coordinate.

The jar's tap intersects with the window root as well as the display. The list uses the display only, because the builder no longer receives the tree (D2). The two points differ only for a node that extends beyond its own window. The alignment bounds the root's right and bottom edges, which gives a lower bound: in E6, 151 lines in 44 calls have a different visible centre under the two clips, and 83 of them belong to a node outside its window root, which the list shows with a coordinate and the dispatcher drops (`evidence/dup_identity_e6.out`; the root's left and top edges are not recoverable from the trace).

*Evidence* (E6 arm 4; `evidence/dup_identity_e6.out`, and `evidence/coordinate_counterfactual.out` S3 versus S4 and §7).

- **Lines.** 23,080 listed lines (2.1 %, in 3.9 % of prompts) have no visible area in the strict sense of the evidence scripts. 21,938 of them (2.0 %, in 3.6 % of prompts) belong to a node entirely off screen. The other 1,142 belong to a zero-height node inside the display, which `Rect.intersect` accepts, so this rule keeps their coordinate. A further 58,765 lines (5.3 %) belong to a node cut by the display edge.
- **Copies.** 227 of the 53,352 copied answers copied an entry with no visible area: 200 off screen and 27 zero-height. All 27, and 101 of the 200, were actions APE had already marked invalid.
  - As run (S0), the boundary bands rejected 191 of the 227, the bottom band 174 and the top band 17 (nodes above the display), and 3 reached their widget.
  - Under the display base with the re-derived band but the clamped centre (S3), they would map to another widget (103), tap off-tree at the edge (83), be rejected by the top band (17, nodes above the display), or snap to the intended node (24). The dispatcher then drops each of those 24 actions.
- **Effect on hits.** Holding the copied entry fixed, copies mapping to their widget are 87.1 % under this rule against 87.2 % with the clamped centre: the 24 snaps, plus one edge-cut copy that now maps to a neighbour. The script's S4 counts the 27 zero-height entries as uncopiable. Under `Rect.intersect` they are listed at the same point as in S3 and map as they do there (19 off-tree taps, 8 other widgets), so the 87.1 % stands.
- **Where the 24 snaps come from.**
  - 21 are a hazard of D7, not a cost of D11. Their nodes lie below the display, in the strip behind the navigation bar. `validateBounds` accepts such a node because it overlaps the 1920-high root (`MonkeySourceApe.java:619-622`; `GUITreeNode.isOutOfRoot`, `GUITreeNode.java:224-227`). A copy of its clamped `@(x,999)` reaches it at y = 1792 only when there is no bottom band.
  - The other 3 are one landscape node behind the side navigation bar (x 1804–1909 on a 1794-wide display), reached at x = 1792. The band never applied to them, and they are the 3 that reached their widget as run.
  - Each such snap executes nothing while counting a visit and a strike. D11 is what keeps D7 from creating the 21.
- **Edge-cut entries.** Of the 1,756 copies of edge-cut entries, 60 change outcome.
- **E5b** is unchanged at 89.4 % (2 off-screen copies).

*Alternatives.*
- **Clamped whole centre** (the previous design of this change). It lists a point outside the widget's visible part, and for an off-screen node a point on another widget or on nothing, which is the failure D8 refuses for answers. Rejected.
- **Drop the off-screen line.** That decides what the list offers, which is the open curation question. Not here.
- **Clip to the window root as the dispatcher does.** Needs the tree back in `build`, which D2 removed. The display clip is what the screenshot shows, and the two differ on at least 151 E6 lines (0.01 %).

### D12. A history entry's result is the outcome of its own action

Each history entry ends in `→ same`, `→ previous screen` or `→ new screen`, and the `v13` system message ranks "navigation to new screens" right after the MOP-marked elements (`ApePromptBuilder.java:250`), so the result is what tells the model which of its recent actions moved anything. It described the wrong action.

`updateStateInternal` resolves `newState`, the screen the previous action produced, and moves `_lastState` to the state that action was taken from (`StatefulAgent.java:828-829`). It then selects the next action (`:850`) and records it (`:853`). `recordActionHistory` compares `newState` with `_lastState` and `_stateBeforeLast` (`:1848-1854`) and stores the comparison in the entry of the action it has just selected, which has not run yet. Nothing updates the entry afterwards. In every prompt, each entry therefore carries its predecessor's outcome, and the most recent action's own outcome appears nowhere. If A1 opens a new screen and A2 does nothing, the next prompt reads `A1 → new screen`, `A2 → new screen`, and the one after adds `A3 → same`, which is A2's outcome. The defect dates from gh6. The main `exploration` spec says the entry is appended "after each action is executed and the new state is determined", and its scenarios pair a click with that click's outcome; the code never did. This change's first draft of the delta copied both the scenarios and the code's comparison, and so contradicted itself.

*Decision.* The comparison is kept; it moves to the entry it describes. `recordActionHistory` appends the selected action's entry with an empty result. On the next step, once `updateStateInternal` has resolved `newState` and moved `_lastState` and `_stateBeforeLast`, and before `resolveNewAction` runs any LLM stage, the agent fills the last entry's result if it is still empty. The entry stays immutable: the agent replaces it with a copy that carries the result. A filled result is never overwritten, so a step that selects no model action, and appends nothing, cannot shift an outcome onto an older entry. Every LLM stage runs inside `resolveNewAction`, so every entry a prompt shows has its result. An empty result would render without ` → <result>`: `formatHistoryEntry` already omits the arrow for an empty string (`ApePromptBuilder.java:557`).

The outcome is what the next state update sees, so it includes anything else that ran between the two updates, such as a fuzzing event. That was true of the old comparison as well; this decision changes which entry receives it, not what it measures.

*An action a restart replaces leaves no entry.* `ApeAgent.updateStateWrapper` (`ApeAgent.java:362`) passes the action `updateStateInternal` returns through `checkRestart` (`:278-298`; `ape.checkRestart` defaults to `true`). When a restart is requested, or every `restartThresholdMin..Max` steps, `checkRestart` calls `startNewEpisode` and returns a start action in place of the selected one, which therefore never runs. `updateStateInternal` has already recorded that model action, so without a correction its entry stays in the buffer, and the next update fills it with the outcome of the restart (almost always `→ new screen`), shown for up to five prompts as if the action had run. The entry predates this change (at `e93dea86` it carried its predecessor's outcome instead); D12 changes only its result. `StatefulAgent` overrides `checkRestart`: when the returned action is not the one it was given and the given one is a model action, it removes the last history entry if its result is still empty. The empty result identifies the entry recorded at this step, since the update fills every earlier one before `resolveNewAction` runs; a recording that failed, or a run without the LLM feature, leaves a filled or no last entry, and nothing is removed. The ring buffer then holds one entry fewer until the next model action; an entry evicted to make room for the discarded one is not restored. The step record of the replaced decision already closes without an `out` member for the same reason (`NdjsonSink.java:41`).

The removal sits at `checkRestart`, not in `startNewEpisode`. `MonkeySourceApe` also calls `startNewEpisode` after the app switch (`MonkeySourceApe.java:656`), after the rotation reset of the fuzzing paths (`:999`, `:1163`) and after a start action launches the app (`:1251`); in those paths the action recorded last has run, or no model action was recorded, and its entry must stay.

*Alternatives.*
- **Append the entry one step later, with its result.** The entry needs the action's resolved node, and one step later the node may no longer be the one that was tapped: `validateAllNewActions` re-resolves the actions of the new state (`StatefulAgent.java:1544`), which is the same `State` object when the action did not change the screen, and `State.resolveAction` samples one alias at random (`State.java:444-446`); the tree that held the node may also have been released (`GUITree.releaseLoadedData`). The bounds are copied at selection (D5); only the result waits. Rejected.
- **Keep the code and make the text say what it does.** Every prompt with a history would keep telling the model that an action which did nothing opened a new screen, on an arm whose comparability this change already breaks. Rejected.

*Cost.* The history text of the LLM arm changes in every prompt that shows one. The list, the mapping, SATA and the exploration model are unaffected.

### D13. A history entry names its widget with the element list's helpers

*Added 2026-10-07, in the review that separated repairs from improvements (`pending.md`).*

The `exploration` field table has specified since gh6 that `widgetClass` is the class simple name ("Button", "EditText") and `widgetText` the widget's text or content-description, and the scenario "Action history populated after action" asks for `widgetClass="Button"`. `recordActionHistory` stores `node.getClassName()` and `node.getText()` (`StatefulAgent.java:1825-1826`), so neither field ever matched the spec, and no test checked them. The element list of the same prompt names the same widget with `safeGetSimpleClassName` and `safeGetDisplayText` (`ApePromptBuilder.java:839-871`): the simple name, and the text, else the content-description, else `id=<shortId>`.

*Evidence* (`evidence/history_fields_scan.py`, outputs `history_fields_{e6,e5b}.out`):

- Every node-action history entry carried the full package name: 555,981 entries in 98.9 % of E6 arm-4 prompts, 40,859 in 98.8 % of E5b `v13_A` prompts.
- 55.2 % of E6 node-action entries (54.9 % in E5b) carried no text. That is 78.4 % of E6 prompts and 78.1 % of E5b prompts.
- For 22,312 text-less E6 entries, in 14,895 prompts (11.4 %), the same prompt's list labels the same widget with text or content-description (E5b: 1,551 entries, 10.6 % of prompts). The match is estimated (same simple class, same x, y rescaled from the display base to the full-window base), and it misses dialogs and widgets no longer on screen, so these are lower bounds. A text-less entry whose list line shows a label is a content-description the history dropped, unless the text changed between selection and the prompt.

*Decision.* This is a repair: the code diverges from its spec. The two names are produced by one definition each, which the list and the history share:

- `safeGetSimpleClassName` becomes `public static String simpleClassName(GUITreeNode)`, unchanged in behavior. The list keeps calling it.
- `safeGetDisplayText` is split. Its text-then-content-description steps become `public static String textOrContentDescription(GUITreeNode)`, with the same flattening of `\n`/`\r`. The list's identifier composes it and adds the `id=<shortId>` step on top, so its output is unchanged.
- `recordActionHistory` calls both for a node action. `back`, `menu` and an off-tree LLM tap keep a null class and text (D9).

The helpers are public because `StatefulAgent` lives in `ape.agent`. `ApePromptBuilder.isInputClass` is already public and static for the same reason: one definition, several consumers.

*Not done here* (`pending.md`):

- **P2.** The list's third step, `id=<shortId>`, is not applied to the history. The spec does not ask for it, so a history entry without it is not wrong, only less informative.
- **P3.** A `type_text` entry still renders without class and identifier, as the `llm-prompt` format specifies.

*Alternatives.*
- **Store the raw class name and simplify it in `formatHistoryEntry`.** That keeps the agent ignorant of rendering, as D5 does for coordinates. But the `exploration` table defines `widgetClass` as the simple name, and the content-description fallback needs the node, which the formatter does not have. Rejected.
- **Copy the two expressions into `StatefulAgent`.** A second definition of how a widget is named, free to drift from the list (compose, don't duplicate). Rejected.

*Cost.* The class changes in nearly every prompt that shows a history (98.9 % in E6), and the text in at least 11.4 %. The list, the mapping, SATA and the exploration model are unaffected.

### D14. The `v13` system message drops the visit priority it cannot support

*Added 2026-10-07, author's decision.*

`buildV13System` (`ApePromptBuilder.java:245-261`) ranks `PRIORITY: [DM]/[M] elements > navigation to new screens > unvisited elements > visited elements.` The `v13` user text never shows how often an element was visited: its list lines carry class, identifier, coordinate, MOP marker and static metadata, and no visit count (`:642-649`). The other variants do show one: the shared line formatter writes `(v:N)` (`:444`), and `v17` writes `[UNTESTED]`/`[TESTED-Nx]`/`[WELL-TESTED]` (`:708-711`). In `v13` the model can only guess visits from the five history entries, most of which SATA executed. The clause asks for a distinction the prompt does not let the model make, in every `v13` call.

*Decision.* The clause is removed, and nothing replaces it:

```
PRIORITY: [DM]/[M] elements > navigation to new screens.
```

The rest of the message is unchanged. The other two ranks stay, because the model can act on them: `[DM]`/`[M]` work as labels whose meaning it does not need, and "navigation to new screens" can be read from the screenshot and from the history's `→ new screen`, which D12 makes correct. Only `v13` changes. `ape_current`/`compact_v1`/`ape_reasoning` and `v17` show visits, and `visual_only` states no visit priority.

*Not done here.*
- **Showing visits in the `v13` list** (`(v:N)` or a status tag) would make the clause supportable. That adds information to the prompt, so it is an improvement, not a repair.
- **Replacing the clause** with something the prompt does show, such as "elements not in Recent", is a new instruction, also an improvement.
- **Explaining `[DM]`/`[M]`** in the `v13` message. The main `llm-prompt` scenario "System message content" asks every system message to explain them, and `ape_current`'s does not either; that divergence predates this change and is left to the spec sync at archive (task 9.2).

*Cost.* The system text of every `v13` call changes (about 6 tokens fewer). This is the one repair that changes the system message Study 03 calibrated (proposal, "Relation to Study 03").

## Part B: MOP guidance by static distance and handler stamp

*Added 2026-10-07 (evening), author's decisions.* The author folded the APE-RV side of the MOP-guidance plan into this change instead of a separate one. Its sources are rv-android `docs/20261005_gator_fragments_compose_guia_teste.md` (the plan, §6.7–§6.9, §9), `docs/20261005_verificacao_plano_gator_compose.md` (§1, §7.2), `docs/20261006_revisao_analise_plano_guia_mop.md` (R9, R12, §5) and `docs/20261007_variante_a_carimbo_handler.md` (§6.2, §6.3). The producer side is done: GATOR change gh120 (archived `c9a43d98`) writes per-method distances to monitored targets, and the instrumenter change gh121 (`b7abcd6a`, device smoke `c054038a`) writes the handler class on each accessibility node. Both name a consumer change in this repository as the place where the wire shape, the reading and the scoring are decided (gh120 design `:86-87`; gh121 proposal `:25`). gh121 is not archived: INV-INS-178 lives only in its change delta (`openspec/changes/gh121-instr-handler-stamp/specs/instrumentation/spec.md`). rv-android line numbers are those of HEAD `02f973c8`, except for three documents that are untracked there (`docs/20261005_verificacao_plano_gator_compose.md`, `docs/20261007_variante_a_carimbo_handler.md`, `docs/handoff/20261007_sa163_gh120_handoff.md`), cited as they stood in the working tree on 2026-10-07.

The author's answers of 2026-10-07 fix the scope:

| Question | Decision |
|---|---|
| Where the derive change lives | rv-android, under its own issue and a Fast-Forward change `gh122` that implements the wire contract of D15 and amends the `aperv` spec through a delta; this change decides the contract. The track is FF SDD, not Quick Path, because Quick Path has no delta spec (rv-android `docs/WORKFLOW.md:178`) and format 2 is new documented behavior (author, 2026-10-07 night) |
| Decision 10 (plan §9, provisional) | the plan's values: K = 3 pairs per key, D_max = 6 for the launcher, N = 3 for retirement, weights 500 / 400 / 300 for d ≤ 1 / d = 2 / d = 3. The plan gives 0 "além de Dmax" (§6.7 `:888`) and is silent on d = 4–6; they get 0 as well (D18), the author's decision of 2026-10-08: weight at 4–6 would bring back the saturation the distance mode is meant to remove |
| The E6 yes/no mark (+500/+300) | stays selectable by a plan key, so one jar runs both the "E6 mark + ordered shortcut" arm and the "distance + ordered shortcut" arm |
| Also in scope | target retirement, the ordered MOP shortcut (decision 9), boundary targets weighing as direct ones (B = C), and telemetry of the distance |
| An activity's distance and its own constructor | the class's `<init>`/`<clinit>` are left out of `activityDist` (D15) |
| Ties at the state's best distance level | split as the plan says, with a floor at the next level's weight (D18) |
| An activity's own constructor in the A′ source 3 of `mopActivitiesAugmented` | the class's `<init>`/`<clinit>` are left out of source 3 too, by the same argument as `activityDist` (D15) |

Part B changes exploration, not only the prompt. Under the default `ape.mopScoring=flag` and an unstamped APK, the jar's MOP logic behaves as before except for the ordered shortcut (D21) and the marker rule (D22). The artifact it reads does not: a format-2 artifact comes from a gh120 `.apk.json`, whose GATOR recovers more widgets and edges than the September one, and the A′ source 3 of `mopActivitiesAugmented` loses the activity constructors (D15), which moves `activityHasMop` and with it the WTG and frontier passes, the launcher's census, the `stateMopDensity` floor and the OPTIONSMENU gateways that WTG edges into the MOP set qualify (`MopData.recomputeMopOptionsMenus`, `:441-458`).

### D15. Wire format 2 of the compact MOP artifact (contract; implemented in rv-android)

The derive emits `formatVersion: 2` and `source.generator: "aperv-derive/2"`. Everything in format 1 stays, with the same meaning. Four things are added:

- **`targets: <int>`**: the number of entries of the producer's `distanceTargets` (C followed by B \ C, gh120 `analysis` spec `:326`). Every distance pair indexes this list. No signature and no `kind` is shipped: B weighs as C (author's decision), and an offline analysis joins an index to its signature through `source.digest` and the `.apk.json` it names.
- **Per widget, `dist: {<event>: [[i, d], …]}`** beside `mop`, for the same `(base activity, shortId, event)` key. `d(w, c)` is the minimum, over the widget's handlers for that event, of the handler method's `targetDistances` entry for `c`: 0 when the handler is `c`, 1–10 otherwise (gh120 `DIST_MAX = 10`). A handler resolves to its method by the same exact join that gives its flags, and an absent D8 wrapper by the same class recovery (INV-DRV-01, INV-DRV-09), whose distance is the minimum over the recovered `lambda$…` methods. The list keeps the K = 3 nearest targets, sorted by `d`, then `i`; it is omitted when empty. Collisions merge by the minimum per target (an extension of INV-MOP-19: flags keep the strongest-flag rule, distances merge by the minimum per target; plan §6.7 `:903`).
- **Per activity, `activityDist: {<base activity>: [[i, d], …]}`**: the K nearest over the activity's widgets and the reaching methods of the activity's own class (the `activity_classes` index the derive already builds), except that class's `<init>` and `<clinit>`. It feeds the launcher (D20). The exclusion is the author's decision of 2026-10-07: launching any activity runs its constructor, and gh120 keeps each activity constructor as a boundary target (D12), so without it every such activity sits at `d = 0` and the distance order collapses into manifest order (all four activities of the gh120 cryptoapp fixture did).
- **`handlers: {<binary class name>: {mop: {click|longclick: flag}, dist: {click|longclick: [[i, d], …]}}}`**: one record per app class of `reachability[]` that declares a handler method. The event keys are the normalized ones of every other `mop`/`dist` map (`_normalize_event_type`, `derive_mop_artifact.py:351-364`). `onClick(android.view.View)` fills `click`, `onLongClick(android.view.View)` fills `longclick`, and a Compose lambda's `java.lang.Object invoke()` fills both, because the stamp key, not the method, tells the event. On a Compose node gh121 stamps the class of the app's handler lambda: the `onClick`/`onLongClick` field of the modifier node the semantics action's lambda holds (`this$0`), and the action lambda's own class only when no modifier node is reached (INV-INS-178; `RvsecStamp.java:250-267`). `java.lang.Object invoke(java.lang.Object)` fills both in the same way, because gh121 also stamps a `ToggleableNode`'s `onValueChange`, which is a `Function1`, under `rvsec.click` only (INV-INS-178; `RvsecStamp.java:256-258`). Filling `longclick` for it is harmless: no stamp asks for it. Including `invoke(java.lang.Object)` was proposed by this design and confirmed by the author on 2026-10-08: without it a toggle's stamp names an unlisted class, falls back to the id key, and a Compose node almost never has one. `<init>` and `<clinit>` are not handler methods. A class whose handler methods reach nothing is still listed, with `mop` `none` and no `dist`, because a listed class is a statement that this handler does not reach a target (D17). The key is `Class.getName()`'s binary name, the form gh121 writes and `reachability[].className` uses.

One format-1 member changes meaning: the A′ source 3 of `mopActivitiesAugmented` (the activity classes of `reachability[]` with a reaching method) no longer counts the class's own `<init>` and `<clinit>`. The argument is the one for `activityDist`: every activity constructor is a gh120 boundary target (D12), so without the exclusion every activity reaches one and enters the treatment census. On the gh120 cryptoapp fixture, `MainActivity`'s only reaching method is its constructor, so it leaves the augmented set, which then equals `mopActivities`. This is the author's decision of 2026-10-07.

INV-DRV-06 forbids method signatures and any `*Target` key other than `hasTargetMethods` (rv-android `aperv` spec `:181-183`; its test is a substring check for `Target`, `tests/test_aperv_tool.py:1532`, which the lowercase `targets` passes). It is amended, not broken: format 2 ships integer indices, integer distances and class names, and still no signature. The plan asked for the amendment to be explicit (§6.7 `:902`), and the rv-android change writes it.

The jar accepts only format 2: INV-MOP-34 moves to 2, and on the rv-android side its counterparts (`FORMAT_VERSION` and the `aperv` spec's `formatVersion` at `:1256`) move with it. There is no reader for format 1 (SDD-P3). Two consequences:
- every MOP arm needs an artifact derived from a gh120 `.apk.json`. Nothing enforces it: gh120 decided "no compatibility guard in the derive" (gh120 design D9, `:110`), and its corpus hand-off states that an APK without a new artifact is never derived from its September one. A September `.apk.json` has no `distanceTargets`, so the derive turns it into a valid format-2 artifact with `targets: 0`, no pair and the wrapper flags gh120 D9 describes as wrong. The jar does not treat that case: an artifact with `targets: 0` loads in both modes, and the run explores without distance data (under `distance` every weight is 0 and the launcher falls back to manifest order), with `MOP_DATA.targets: 0` on the trace. The author's decision of 2026-10-08: September documents will not be used, and if one slips in the run continues normally rather than failing. The same holds for an empty artifact from a GATOR timeout (the gh51-D5 bucket), which also runs in both arms. The night analysis of the 163 APKs is a precondition of any MOP run with this jar;
- the host cache must not push a format-1 `.mop.json` it derived before: today it compares only `source.digest` (rv-android `tool.py:723-735`), and the rv-android change adds the format to the cache key.

### D16. The node carries the handler stamp

`GUITreeBuilder.fillNode` (`tree/GUITreeBuilder.java:582-607`) reads `AccessibilityNodeInfo.getExtras()` and copies two strings into `GUITreeNode`: `rvsec.click` → `handlerClick`, `rvsec.longClick` → `handlerLongClick` (gh121 `instrumentation` spec `:27`, INV-INS-178). A missing bundle, a missing key or a non-string value leaves the field null. The fields sit beside `isPassword` (`GUITreeNode.java:64`, INV-TREE-09) and are copied with the node; nothing else of the bundle is read. The framework stub exposes `getExtras()` and `BaseBundle.getString` (checked with `javap` on `framework/classes-full-debug.jar`).

Reading is not a feature and has no key. On an APK instrumented without `--stamp-handlers` every field is null and the lookup falls back to the id key (D17), which is the E6 behavior; the run reports how many nodes carried a stamp (D23).

### D17. One lookup for an action: stamp first, then the id key

A new value class, `MopData.Lookup`, holds what MOP knows about one (node, event): the direct and transitive flags, the distance pairs, and the source (`STAMP`, `ID` or `NONE`). `MopData.lookup(activity, node, eventType)` computes it:

1. if the event is `click` or `longClick`, the node's stamp for that event is non-null, and `handlers` lists that class, the result is that class's record for the event (source `STAMP`), even when it reaches nothing;
2. otherwise, the id key `(activity, shortId, event)` with today's per-event fallback to the aggregate (`MopData.Widget.isDirectMop`/`isTransitiveMop`, `:782,791`) and the widget's `dist` for the event (source `ID`);
3. otherwise `NONE`.

A stamp naming a class that `handlers` does not list falls back to the id key. That covers the stamps gh121 lists as library dispatchers: `AppCompatViewInflater$DeclaredOnClickListener` for XML `android:onClick`, toolbar, menu and `SearchView` classes (gh121 design, Risks `:238` at HEAD, `:239` in the rv-android working tree: "the consumer must keep the resource-id key as fallback"). A listed class that reaches nothing wins over the id key: the stamp is the run-time handler, while the id key carries GATOR's attribution, which spreads one handler over many widgets (68.3 % of handlers sit in four keys or more, verification §3 table, `:311`).

Every reader goes through this lookup: the scoring pass, the shortcut ranking, the marker (D22), the `MOP NAVIGATION` count, the state MOP density of the navigation tiebreak (`MopScorer.stateMopDensity`, `:143-174`) and the retirement trigger (D19). Event keys are compared in the normalized form the derive and `MopData.normalizeEventType` already use (`click`, `longclick`). The two spellings meet here once: the node's extras keys are gh121's `rvsec.click` and `rvsec.longClick` (D16), the wire's event keys are `click` and `longclick`, so `handlerClick` answers the wire key `click` and `handlerLongClick` the wire key `longclick`. `getWidget` stays for the static metadata of the list (`buildWidgetMetadata`) and for typed input (`ApeAgent.generateInputText`), which are keyed by id, not by handler. Under `distance`, `stateMopDensity` counts the actions of the state whose own-node lookup (no containment, as today) gives a positive weight under the active mode; under `flag` it is unchanged. `MopScorer`'s containment (`containmentShortIds`, `:65-85`) becomes containment over nodes: the lookup runs on the node, up to two ancestors and the descendants two levels down, and the best result wins (the smallest unretired distance, or the strongest flag).

**Plumbing.** A `MopContext` value holds the loaded `MopData`, the scoring mode, the weights and the run's `MopRetirement` (D19; null under `flag`). `StatefulAgent` builds it once, after the artifact loads, and it is passed wherever `MopData` is passed today: in `ScoringContext` for `MopWidgetPass`; in the decision `StepContext` for `MopLauncherStage`, with no static (`decision-pipeline` INV-DP-12); as a new parameter of `pickBestMopTarget`, beside the activity and the coverage tracker (D21), where it gives the target count; and to `ApePromptBuilder.build`, whose `MopData` parameter becomes `MopContext`. Part A's `build` signature is otherwise unchanged, and `LlmEngine.selectAction` passes the context.

### D18. Two scoring modes, selected by `ape.mopScoring`

`ape.mopScoring` is a string key owned by the `MOP` feature, `flag` or `distance`, default `flag`.

- **`flag`** is today's `MopScorer.score` (`:35-54`) with the lookup of D17: `mopWeightDirect` (500) for a direct flag, `mopWeightTransitive` (300) for a transitive one. It reproduces the E6 mark for an unstamped APK.
- **`distance`** gives an action the weight of its nearest unretired target: with `d` the smallest distance over the lookup's pairs whose target is not retired (D19), the weight is `ape.mopWeightD1` (500) for `d ≤ 1`, `ape.mopWeightD2` (400) for `d = 2`, `ape.mopWeightD3` (300) for `d = 3`, and 0 from `d = 4` or with no pair. Ties are by weight level, not by exact `d`: `d = 0` and `d = 1` are one level (weight `mopWeightD1`), and `d = 2` and `d = 3` are one level each. The tied actions are those whose undivided weight equals the state's best level, the largest undivided weight among the state's actions. When there are `n > 1` of them, each receives `max(W / n, W_next)`: the level's weight `W` divided by their number (integer division; plan §6.7, "o reforço se divide pelo número de empatados"), but never less than the weight of the next level (`mopWeightD2` for the `d ≤ 1` level, `mopWeightD3` for `d = 2`, 0 for `d = 3`). Two actions at `d = 0` and one at `d = 1` are three tied actions, and each gets `max(500 / 3, 400) = 400`. The floor is the author's decision of 2026-10-07: with the plain split, two widgets at `d ≤ 1` get 250 each and fall below one widget at `d = 2` (400), so the explorer would prefer the farther one. Actions at a worse level keep their undivided weight. The split weight is added to the priority, as today (INV-MOP-03), and stored as the action's `mopBoost`. The undivided weight is stored beside it, as the action's `mopWeight`, for the shortcut's ranking (D21); under `flag` the two are equal.

The default stays `flag` because the presets `mop` and `llm_mop` define the E6 arms, and the distance arm is a new arm the harness selects explicitly. `mopWeightDirect`/`mopWeightTransitive` are inert under `distance`, and `mopWeightD1..3` under `flag`; `RUN_START.inert` reports them. The menu-gateway, WTG and frontier passes are unchanged in both modes. WTG and frontier read `activityHasMop`; the menu gateway reads `activityHasMopOptionsMenu`, which load computes from `optionsMenus` and the WTG edges into the selected MOP set (`MopData.recomputeMopOptionsMenus`, `:441-458`). Both keep their format-1 meaning apart from the source-3 constructor exclusion (D15), which reaches the gateway through that set.

### D19. Targets retire after N first interactions

Under `distance`, the agent keeps one counter per target, `int[targets]`. It counts at selection, in `StatefulAgent.moveForward` (`:1495-1504`), where the coverage tracker records the interaction: `updateStateInternal` calls `resolveNewAction()` (`StatefulAgent.java:850`), then `markVisited`, `recordActionHistory` and `moveForward()` (`:852-854`). Before `recordInteraction` marks the selected action as seen, the agent checks whether this is the action's first interaction in its activity (`UICoverageTracker.hasActivityInteraction(activity, widgetId(action))` false). Novelty is per (widget, action type): `widgetId` is `xpath|ActionType` (`UICoverageTracker.java:240-250`), so a long-click on a widget already clicked is a first interaction. If it is, the agent increments the counter of every target in the **weight's lookup** for the action's event: among the action's containment lookups (D17), the one holding its smallest unretired distance, with ties going to the earlier node in containment order (the node itself, its ancestors, then its descendants). It is the same lookup behind `dec.mopd`/`dec.mops` (D23) and the shortcut's target count (D21), so what gives an action its weight is what retires. The rule was the selected node's own lookup until the author's decision of 2026-10-07 (night): under it, an action weighted only through containment never retired its targets and kept its weight for the whole run. When no containment lookup has an unretired pair, nothing is counted. A target with a counter of at least `ape.mopRetireAfter` (3) is retired: it no longer counts toward `d` in D18, the marker (D22) or the launcher (D20). The plan requires the record to be made "a partir da ação executada" (§6.6 `:816-818`); counting at selection meets that, because the counted action is the one dispatched next. It is not rebuilt afterwards from xpath and shortId.

*Not done here* (plan review R12, `20261006_revisao_analise_plano_guia_mop.md:758-778`, and the analysis `20261006_analise_rigorosa_plano_guia_mop.md` §6.2, `:281-287`):
- an off-tree LLM tap (`MODEL_LLM_TAP`) has no node, so it never counts (R12);
- `widgetId` comes from `Name.toXPath()`, so a refinement that renames a widget makes its next interaction a "first" one again (R12; analysis `:281`);
- counting only the targets at the widget's own minimum distance was proposed as a mitigation (analysis `:287`) and is not adopted.

### D20. The launcher orders activities by distance

Under `distance`, `MopLauncherStage.selectTriggerCandidate` (`:163-186`) keeps its eligibility filter (in the MOP activity set, not a framework class, no permission, not the main activity, not yet visited) and replaces the round-robin over manifest order with an ordering: ascending smallest unretired distance of the activity's `activityDist`, then manifest order. Only distances up to `ape.mopLauncherDmax` (6) count; an activity with none sorts after every activity with one, in manifest order. The stage walks the order as a rotation: the next candidate is the first eligible activity after the one selected last, wrapping at the end, whatever the launch result. This is new behavior, not today's. Today's cursor is a firing counter (`roundRobinIndex`, `MopLauncherStage.java:76`) that advances by one at every firing, candidate or not (`:109`), and the walk starts at that offset (`:171`); so an activity whose launch is refused is selected again on consecutive firings until the offset passes it (eligible manifest indices {2, 4}: offsets 0, 1 and 2 all pick 2). Under `distance` the stage keeps the same field, owned by the stage as INV-DP-07 requires, but holds in it the sort position `(key, manifest index)` of the last selection instead of a counter. A launched activity that opens becomes visited and leaves the eligible set; one whose launch is refused stays eligible, and the rotation moves past it instead of selecting it again at the next firing (the analysis's "rodízio dentro de faixas", `20261006_analise_rigorosa_plano_guia_mop.md:288`). The rotation never reads the launch result, so INV-CT-14 holds. Under `flag` the stage is unchanged. Not retiring a target whose launch fails is the author's standing decision (INV-CT-14 stays, verification §1).

### D21. The MOP shortcut prefers widgets new to the activity (decision 9)

Both deterministic MOP sites, `selectUnvisitedMopTarget` (`SataAgent.java:621-628`, epsilon path) and the 0-step probe of `findGreedyActionForward` (`:1443`), go through `pickCappedMopTarget` → `pickBestMopTarget` (`:642-660`). The ranking becomes:

1. actions whose widget has no interaction yet in this activity (`hasActivityInteraction` false) before the others. Novelty is per (widget, action type), because `UICoverageTracker.widgetId` is `xpath|ActionType` (`UICoverageTracker.java:240-250`): a long-click on a widget already clicked is new;
2. then the larger **undivided** weight, `mopWeight` (D18), not the split `mopBoost`. Ranking by the split value would bring back the inversion the floor of D18 removes: three actions at `d ≤ 1` get 400 each and tie with one at `d = 2` (400), and key 3 could then pick the farther one. The split stays where the plan puts it, on the priority the roulette reads (author, 2026-10-07 night). Under `flag` `mopWeight` equals `mopBoost`;
3. under `distance`, then the larger number of distinct unretired targets within `d + 1` (plan §6.7, "só como desempate");
4. then the larger priority.

Candidates still need `mopBoost > 0`, and the cap of 3 picks per `(widget, type, activity)` is unchanged. The ordering applies in both modes, because both campaign arms carry it (author, 2026-10-05, verification §1 decision 9). It does not reproduce the E6 shortcut; E6 is reproduced by its own jar.

### D22. The prompt marker is the scoring's verdict, per line

`buildMopMarker` (`ApePromptBuilder.java:456-469`) marks a line from the node's id-key aggregate flags, while the score uses the per-event flag with containment. Review R9 lists three disagreements: containment, a widget direct only for long-click that shows `[DM]` while its click gets +300 or nothing, and the event `none`. With distances, and with D10's lines joining several actions, a per-node rule cannot hold.

The marker becomes a function of the line's actions: `[DM]` when one of them is direct under the active mode (`flag`: a direct flag; `distance`: an unretired target at `d = 0`) and has a positive MOP weight, else `[M]` when one of them has a positive MOP weight under the active mode, else nothing. The weight is the one before the tie split of D18, so a line's marker does not depend on the other actions of the state. An arm that zeroes the weights shows no marker. The test is the same D17 lookup, with containment, that the scoring pass uses. A line therefore carries a marker if and only if the score treats one of its actions as MOP, and under `distance` the marker disappears when its targets retire. `buildExplorationContext`'s `N/M MOP.` counts actions with the same test. The vocabulary (`[DM]`, `[M]`) and the system messages are unchanged.

This changes the `v13` prompt beyond Part A even under `flag`: the event-aware and containment-aware test adds and removes markers. It is the rule D10 asked for ("a rule per line, not per node").

### D23. Telemetry of the lookup, the distance and retirement

- `dec.mopd`: the `d` behind the chosen action's MOP weight, under `distance`; absent when the weight is 0. `d = 0` (a direct target) is a value, not a default, so it is written explicitly, an exemption from the omission rule like `dec.patched` and `dec.cf` (INV-SNK-05, amended in the `event-sink` delta). `dec.mops`: `s` when the chosen action's lookup came from a stamp, `i` from the id key; written whenever the source is `STAMP` or `ID`, in both modes and even when the weight is 0; absent when `NONE`. Both follow the event sink's omission rule (an absent field means "none"), so steps whose chosen action has no MOP entry pay no bytes.
- `dec.ret`: the indices of targets that retired as a consequence of this step's action (D19), absent when none. They are not known when `sink.decision(...)` runs: that call is inside `resolveNewAction` (`StatefulAgent.java:1581-1590`, reached from `:850`), and retirement is counted later in the same step, in `moveForward` (`:854`). So they reach the open record through a sink entry of their own, `EventSink.mopRetired(int[] indices)`, called from `moveForward` when `onFirstInteraction` returns a non-empty array. It follows the pattern of `mopExposure` (`EventSink.java:114`) and `componentLaunch` (`:128`), which also add a member to the step's `dec` after the decision call. `StepRecord` writes `dec` when the record closes (`StepRecord.java:214ff`), so the indices are in `dec` and survive a record closed without an `out` member.
- `MOP_DATA` gains `targets`, `handlers` and `droppedPairs` (counts) beside the `formatVersion` it already carries.
- `RUN_END` counters gain `stampNodes` (nodes with at least one stamp seen in captured trees), `stampHits` (lookups of the chosen action, at most one per step, whose source was `STAMP`) and `retired` (targets retired by the end).

The `ACT` dictionary bit `mop` (`NdjsonSink:540-551`) keeps reading `activityHasMop`. INV-SNK-13 is defined in the archived `2026-08-07-rearch-04-step-ndjson-telemetry` `event-sink` delta (`:52`), as a throughput gate (steps per minute against the pre-change jar), not a byte budget; the new fields are absent on most steps.

## API Design

### `static int[] CoordinateNormalizer.toNormalized(int pixelX, int pixelY, int width, int height)`

- **Pre:** `width > 0`, `height > 0` (the caller passes `deviceDimensions`, positive by contract).
- **Post:** returns `{clamp((int)(pixelX * 1000.0 / width), 0, 999), clamp((int)(pixelY * 1000.0 / height), 0, 999)}`. For `0 <= p < d < 2000`, `normalize(toNormalized(p)) ∈ [p − 2, p]` per axis (INV-LLM-13).
- **Errors:** none thrown for positive dimensions. Pixels outside `[0, d)` are clamped.

### `List<SglangClient.Message> ApePromptBuilder.build(int deviceWidth, int deviceHeight, State state, List<ModelAction> actions, MopData mopData, String base64Image, List<ActionHistoryEntry> recentActions)`

- **Pre:** `deviceWidth > 0`, `deviceHeight > 0`. Every other argument may be null, as today.
- **Post:** INV-PRM-01..06 hold. No coordinate is computed from any other divisor.
- **Errors:** never throws (unchanged contract).
- **Part B:** the `MopData mopData` parameter becomes `MopContext mop` (D17, Plumbing); nothing else in the signature changes.

### `ApePromptBuilder.ActionHistoryEntry(String actionType, String widgetClass, String widgetText, Rect bounds, String typedText, String result)`

- `bounds` is in screen pixels.
  - For a node action, it is a copy of the executed widget's bounds.
  - For an LLM tap, it is `[x,y][x+1,y+1]` at the tap's own pixel.
  - For `back` and `menu`, it is null and nothing is rendered.
- An entry whose bounds do not intersect the display renders without coordinates (D11).
- `actionType` takes one of `click`, `long_click`, `type_text`, `back`, `menu`.
- The fields `normX`/`normY` are replaced, not kept alongside (SDD-P3).

### `public static String ApePromptBuilder.simpleClassName(GUITreeNode node)` and `public static String ApePromptBuilder.textOrContentDescription(GUITreeNode node)`

- **Post:** `simpleClassName` behaves exactly as `safeGetSimpleClassName` does at `e93dea86` (the part after the last `.`; `"View"` for a null node, a null or empty class, or a throwing read). `textOrContentDescription` returns the node's flattened text, else its flattened content-description, else `""`; it never returns `id=…`.
- **Use:** the element list (its identifier is `textOrContentDescription`, then `id=<shortId>` when that is empty) and `StatefulAgent.recordActionHistory` (D13).
- **Errors:** never throw.

### `private static int[] ApePromptBuilder.visibleCentre(Rect bounds, int width, int height)`

- **Post:** null when `bounds` is null or when `Rect.intersect` of a copy with `[0,width)×[0,height)` returns false. Otherwise it returns `{c.centerX(), c.centerY()}` of the intersection `c`, a pixel inside the display. A zero-width or zero-height rect inside the display does intersect and gets its centre. Does not modify `bounds`.
- **Use:** every list site and `formatHistoryEntry`; its result goes to `CoordinateNormalizer.toNormalized`.

### `static boolean CoordinateMapper.inAnswerSpace(int x, int y)`

- **Post:** `0 <= x <= 999 && 0 <= y <= 999`.
- **Use:** `LlmEngine` step 8 skips `map` for a non-`back` answer that fails it. `toPixels` still runs, because it is pure and the sub-event's `px` and the `nearest` fields need a pixel. The clamped pixel is recorded for telemetry and is never matched or tapped.

### `CoordinateMapper.map` (boundary)

- **Pre:** unchanged.
- **Post:** returns null without matching when `pixelY < deviceHeight * topPct` (`ape.llmBoundaryTopPct`, default `0.035`). There is no lower-edge reject.

### Part B: `MopData.Lookup MopData.lookup(String activity, GUITreeNode node, String eventType)`

- **Post:** returns the D17 result for one node and one event: `direct`, `transitive`, `int[][] dist` (pairs `[i, d]`, possibly empty) and `source ∈ {STAMP, ID, NONE}`. Never null; `NONE` carries no flag and no pair. Pure: it does not read retirement.
- **Use:** `MopScorer` (both modes, over the containment nodes), `SataAgent.pickBestMopTarget` (the target count of D21), `ApePromptBuilder` (marker and `MOP NAVIGATION`), `MopRetirement` (the weight's lookup among the selected action's containment lookups, D19).

### Part B: `MopScorer.score(MopData, State, ModelAction, ScoringParams, MopRetirement)`

- **Post:** under `flag`, `weightDirect`/`weightTransitive`/0 from the best containment lookup; under `distance`, the D18 weight of the smallest unretired `d` over the containment lookups, before the tie split. The tie split is applied by `MopWidgetPass`, which sees every action of the state.

### Part B: `MopContext`

- **Post:** an immutable value holding the loaded `MopData`, the scoring mode, the weights (`mopWeightDirect`/`Transitive`, `mopWeightD1..3`, `mopLauncherDmax`) and the run's `MopRetirement` (null under `flag`). Built once by `StatefulAgent` after the artifact loads; null when no artifact is loaded.
- **Use:** `ScoringContext` (`MopWidgetPass`), the decision `StepContext` (`MopLauncherStage`, INV-DP-12), `SataAgent.pickBestMopTarget`, and `ApePromptBuilder.build`, whose `MopData mopData` parameter becomes `MopContext mop` (D17, Plumbing).

### Part B: `MopRetirement`

- One instance per run, owned by `StatefulAgent`, sized `targets` from the artifact; null when no artifact is loaded or the mode is `flag`.
- `int[] onFirstInteraction(Lookup weightLookup)`: +1 on each target index of `weightLookup.dist`, each index once; returns the indices that reached `ape.mopRetireAfter` on this call, which `moveForward` passes to `EventSink.mopRetired` (D23 `dec.ret`). `weightLookup` is the containment lookup holding the selected action's smallest unretired distance (D19).
- `boolean retired(int i)`; `int nearest(int[][] dist, int dmax)`: the smallest `d` over unretired pairs with `d ≤ dmax`, or `Integer.MAX_VALUE`.

### Part B: `GUITreeNode.getHandlerClick()` / `getHandlerLongClick()`

- **Post:** the binary class name gh121 wrote under `rvsec.click` / `rvsec.longClick`, or null.

## Data Flow

1. Step N selects a model action. `StatefulAgent.recordActionHistory` stores a copy of its resolved node's bounds in the ring buffer (max 5), with an empty result. Step N+1 resolves its state and fills that entry's result before any LLM stage runs (D12).
2. Step M > N reaches an LLM stage. `LlmEngine.selectAction` computes `{W, H}` once and captures the image at `W × H`.
3. `ApePromptBuilder.build(W, H, …)` renders every listed widget and every history entry at `visibleCentre(bounds, W, H)` through `toNormalized(·, W, H)`, or without coordinates when that is null.
4. The model answers `(x, y)`. `CoordinateMapper.toPixels(x, y, W, H)` inverts it. A copied coordinate returns within 2 px of the visible centre, toward the origin, inside the widget.

Part B, per step of a MOP arm:

5. The tree capture copies each node's stamp (D16).
6. `MopWidgetPass` computes each target action's weight through `MopData.lookup` over its containment nodes and the active mode, and splits the weight among the actions tied at the state's best weight level (D17, D18). The shortcut ranks by activity novelty first (D21); the launcher, when it fires, orders eligible activities by their nearest unretired target (D20).
7. The prompt, if the LLM stage runs, marks each line by the same lookup (D22).
8. At selection, `moveForward` counts a first interaction of the selected widget in its activity against the targets of the weight's lookup, before the action is dispatched, and retires those at N (D19); the step record carries `mopd` and `mops` in `dec` from the decision call, and `ret` from `EventSink.mopRetired` (D23).

## Error Handling

| Error | Source | Strategy | Recovery |
|---|---|---|---|
| Display throws | `AndroidDevice.getDisplayBounds` in `deviceDimensions` | unchanged: root node, then constants | the chosen pair is still used by all three steps, so they stay consistent |
| Node unresolved or bounds unreadable | `getResolvedNode` / `getBoundsInScreen` in builder / history | list: `@(x,y)` omitted at all three list sites (a change: at `e93dea86` they wrote `@(0,0)`); history: a node action whose bounds throw is recorded with null bounds and renders without coordinates (at `e93dea86` it rendered `@(0,0)`) | prompt still valid (INV-PRM-01) |
| Selected model action replaced by a restart | `ApeAgent.checkRestart` returns a start action in place of the action `updateStateInternal` recorded | `StatefulAgent.checkRestart` removes the last history entry when its result is still empty (D12) | the buffer holds one entry fewer until the next model action |
| Widget partly or wholly outside the display | node behind the navigation bar, off-screen scroll child, off-canvas menu | centre of the visible part; no coordinate when nothing is visible (D11) | line still listed; a copy lands inside the visible part |
| Answer outside [0,999] | model points past the image | `inAnswerSpace` false → `no_match` / `boundary` | SATA fallback, as for any `no_match` |
| Old properties file sets `ape.llmBoundaryBottomPct` | hand-written or stale config | `RETIRED_KEY` abort with the reason | edit the file |
| Artifact in format 1 | a `.mop.json` derived before the bump, or a stale host cache | `version-mismatch`, and a MOP arm aborts (INV-MOP-22, INV-MOP-34) | re-derive from a gh120 `.apk.json`; the host cache keys on the format (rv-android change) |
| Stamp naming a class `handlers` does not list | XML `android:onClick`, toolbar, menu, library listeners | id-key lookup (D17) | none needed |
| Extras unreadable | `getExtras()` throws or holds a non-string | stamp fields null | id-key lookup |
| Distance index outside `[0, targets)` | malformed artifact | the pair is dropped at load and counted in the load census | run continues on the remaining pairs |
| Format-2 artifact missing `targets` or `handlers`, or with a non-integer `targets` | generator↔jar skew or a hand-edited file | `parse-error`, and a MOP arm aborts (INV-MOP-22) | re-derive with the rv-android generator |
| Format-2 artifact with `targets: 0` | derived from a September `.apk.json` (no `distanceTargets`; gh120 has no guard, D15), or an empty artifact from a GATOR timeout | loads (`MOP_DATA.targets: 0`); the run continues in both modes with no distance data (author, 2026-10-08) | none in the run; the analysis can exclude it by `MOP_DATA.targets` |

## Risks / Trade-offs

- **[Arm comparability]** → Prompts differ from E5b/E6 on nearly every screen, so any cross-study comparison must treat this as a new arm version. Mitigation: proposal and the `RUN_START` build stamp identify the jar. No flag (D6).
- **[Fine-tuned models trained on old-base prompts]** (branch `laya`, on hold) → a model fine-tuned to copy old-base coordinates may systematically mis-copy under the new base. Mitigation: out of scope here. Regenerate training prompts with a jar carrying this change before resuming that line.
- **[Off-screen widgets listed without a coordinate]** → 2.0 % of E6 lines (21,938) lose their `@(x,y)` and stay listed. The model can name them only visually, and they are not on the image. Mitigation: whether to list them is the open curation question (Open Questions). Of the 200 copies they drew in E6, the band rejected 191 as run. Under the display base with the clamped centre, 95 would act on another widget, 64 would tap off-tree, 24 would be dropped and 17 rejected (`evidence/coordinate_counterfactual.out` §7, D11).
- **[UiAutomation fallback image is not in W × H]** → dead in `app_process` today (needs `androidx.test`). If it ever becomes reachable, the image base would diverge. Mitigation: recorded here and in Non-Goals; the device smoke would show it as visual answers drifting.
- **[The screenshot is captured with rotation 0]** → `ScreenshotCapture.java:80-81` passes rotation 0 to the screenshot call whatever the display's rotation. On a landscape display (328 E6 answers, 31 APKs; Context) the base is right, because `deviceDimensions` reports 1794 × 1080, but whether the image the model sees is oriented as the screen is not verified. A copied coordinate does not depend on the image; a visual answer does. In landscape the top band is also 0.035 × 1080 = 38 px, against a 63 px status bar if it keeps its portrait height (54 px at the old `0.05`, so not new). Mitigation: out of scope, since the screenshot path is unchanged (proposal, "Not changed"); recorded so that a landscape analysis does not attribute it to this change. The device smoke runs in portrait.
- **[Top band is AVD-specific]** → `0.035` is the campaign AVD's status bar (420 dpi). Another density makes it slightly too wide or too narrow. Mitigation: the key stays (D7), and the open question below records the runtime alternative.
- **[Answers that the old bottom band absorbed now act]** → 4,879 E6 answers that were not list copies were rejected, and under this change they match (2,995) or tap off-tree (1,865); the 19 outside [0,999] stay rejected (D8). Some of those taps hit empty space near the bottom edge. They are subject to the dead-pair ban like any other tap. The device check (task 8.2) counts them per height band, so the shift is visible rather than assumed.
- **[The keyboard's bottom row is no longer behind a band]** → With the soft keyboard open, its bottom row (space, enter, symbol keys) lies below y = 1686 on the campaign AVD, where the retired band rejected every answer. The keyboard is another window, outside the tree the jar builds from `getRootInActiveWindow()` (`MonkeySourceApe.java:487-488`), so an answer there matches no widget and becomes an off-tree tap on the keyboard. The rows above y = 1686 were already reachable. Mitigation: none; the device check (task 8.2) counts off-tree taps below y = 1686, which shows them but does not attribute them to the keyboard, since the trace does not record whether it was open.
- **[Offline analyses assume one line per action]** → `coordinate_base.py` (Study 03) and `evidence/coordinate_counterfactual.py` pair the *i*-th line of the prompt with the *i*-th target-requiring action of the trace's listing, which gives each listed `@(x,y)` its pixel bounds. They require ordinals `1..N` (`parse_prompt`, `coordinate_counterfactual.py:224`) and equal counts (`:491`), so on traces of this jar they align no call. Mitigation: on this jar's traces, parse the lines by indentation and group the listing's target-requiring actions by (target `Name`, resolved bounds) before pairing, which is exactly the jar's grouping (D10). Traces of earlier jars keep the 1:1 alignment, and the Study 03 offline prompt replica (`rvsec_study03/grounding/prompt/builder.py`) reproduces `e93dea86` and does not change. The device check (task 8.2) is written against the new alignment; task 9.3 tells the study side.
- **[The prompt's MOP marker is also the subject of the MOP-guidance work]** → resolved inside this change: Part B redefines `<MOP_MARKER>` per line (D22), in the same "Widget List Generation" requirement this change MODIFIES, so no later change has to rewrite it.
- **[The live gain depends on the dead-pair ban, which was calibrated under the defect]** → `CoordinateMapper.java:36-41` sets k = 5 from the cal_a1 runs, where it refused 27.5 % of decisions against a stated 30 % ceiling. In E6 it already refused 40.7 % (53,007 of 130,199, `evidence/coordinate_counterfactual.out` §1). A matched widget's ban key is `state|xpath|type`, while an off-tree tap's key carries its exact pixel (`banKey`). Imprecise copies spread their strikes over neighbours and pixels; correct ones concentrate them on the intended widget's key, which reaches five sooner. The counterfactual holds the strike record fixed, so its live gain (+7,085) is an upper figure; with the as-run refusal share it is about +4,400 (proposal). Mitigation: none in this change, which does not touch the ban; the device check (task 8.2) reports the `dead_pair` share, so the shift is measured. It also reports the share of executed actions that came from the LLM, because a refused decision falls through to the launcher and SATA, and that share, not the refusal share, is what reaches the MOP guidance of the `llm_mop` arm.
- **[A copied off-tree tap moves its ban key at each copy]** → Today an off-tree tap's history entry reads `click @(0,0)`, so the model cannot copy its point. Under this change the entry shows the tap's own point (D9), and a copy maps back up to 2 px toward the origin (INV-LLM-13). Over every pixel of an axis, the round trip of D3 and `normalize` moves 1,040 of 1,080 x values (96.3 %) and 1,792 of 1,794 y values (99.9 %). An off-tree tap's ban key carries its exact pixel (`CoordinateMapper.banKey`, `tap|<state>|<x>,<y>`), so almost every copy gets a new key, a copy of that copy another one, and a chain of copies never collects the five strikes the ban needs. Matched widgets are not affected: their key is `state|xpath|type`. Mitigation: none in this change, which does not touch the ban; the device check (task 8.2) counts off-tree taps in one state that land within 2 px of an earlier off-tree tap in that state.
- **[No bottom band on the dimension fallback path]** → when `Display.getSize` throws, `deviceDimensions` uses the root's edges or the 1080 × 1920 constants, the screenshot then includes the navigation bar, and the retired `0.94` band rejected most of it (y > 1804.8). After this change nothing rejects a tap on the navigation bar's home or recents keys on that path. It was not observed in E6 or E5b. Mitigation: recorded; the band is not reinstated for a path the campaign never took.
- **[Retiring a key breaks stale configs]** → an `ape.properties` that sets `ape.llmBoundaryBottomPct` aborts. rv-android does not set it. Mitigation: the abort names the reason, which is the point of `RETIRED`.
- **[Part B needs the gh120 analysis of every APK]** → the jar reads only format 2, and a format-2 artifact is meaningful only from a gh120 `.apk.json`. Deriving from a September one is ruled out by the corpus hand-off, not by a guard (gh120 D9, `:110`): it yields `targets: 0` and wrong wrapper flags. Mitigation: the night analysis of the 163 APKs (rv-android `docs/handoff/20261007_sa163_gh120_handoff.md`, untracked there). The jar does not check it (author, 2026-10-08): such an artifact runs, unguided under `distance` and on the September flags under `flag`, and `MOP_DATA.targets: 0` shows it on the trace.
- **[Two repositories, one format]** → the derive (rv-android) and the reader (this jar) bump to 2 together, or every MOP arm aborts. Mitigation: the rv-android Fast-Forward change `gh122` implements D15 and is merged in the same window as this change; INV-MOP-34 fails fast instead of running unguided.
- **[Decision 10 values are provisional]** → K = 3, D_max = 6, N = 3 and 500/400/300 come from the plan, not from a measurement. d = 4–6 gets no weight, the author's decision of 2026-10-08: the plan gives 0 only beyond D_max (§6.7 `:888`) and is silent on 4–6. Mitigation: every value except K is a plan key (`mopWeightD1..3`, `mopRetireAfter`, `mopLauncherDmax`); K is the derive's.
- **[Boundary targets weigh as direct ones]** → B includes activity constructors (gh120 D12: `<init>`/`<clinit>` kept), so a widget that only reaches a constructor-side target gets the same weight as one reaching an app call site. Mitigation: none in this change; the trace's `dec.mopd` plus the `.apk.json` join measure it.
- **[Retirement inherits xpath re-keying, misses off-tree LLM taps]** (D19, *Not done here*).
- **[The stamp needs the dexlib2 instrumenter]** → `--stamp-handlers` is accepted only with `--instrumentation-variant dexlib2`; the default `ajc` variant aborts the experiment (rv-android `rv_experiment/__main__.py:835-842`, INV-EXP-40). Mitigation: task R.5 and the device smoke (17.2) name both flags.
- **[Compose coverage is unmeasured]** → whether the distances reach the stamped Compose lambdas, and whether GATOR finishes on Compose-only APKs (openbible hit 1,800 s), is not known. Mitigation: `stampNodes`/`stampHits` (D23) measure it in the first run.
- **[The parity goldens change twice]** → `goldens/mop/baseline.ndjson`, `goldens/llm_mop/*.ndjson` load `cryptoapp.apk.mop.json` (`OracleScaffold.java:271`) and depend on MOP scoring and the shortcut (D21 applies under `flag` too). Task 10.1 replaces that fixture with one derived from the gh120 `.apk.json`, whose flags, widgets, WTG edges and augmented set (D15, source 3) differ from the September one. The goldens were expected to move already in group 10; they did not: their scenarios script their own widgets and read only the activity list and the MOP-activity set from the fixture, which name the same activities, so the regeneration of task 10.4 changed only the header's `capturedAt`, and the goldens were kept unchanged (`verification.md`, task 10.4; author, 2026-10-08). Mitigation: compare them at the end of group 10, where every difference must trace to the fixture, and regenerate and review in 17.1, where, against the group-10 goldens, the decision sequences change only where the shortcut ordering (D21) or the marker (D22) does.
- **[The change is large]** → Part A (prompt repairs) and Part B (MOP guidance) share one archive. Mitigation: the tasks keep them in separate groups, Part B's groups run after Part A's, and each group ends with the full suite.
- **[INV-RTR-21 has no JVM test]** → `ScreenshotStep` is `final` and its first call reaches Android reflection. Mitigation: the property is one local variable flowing to three calls inside one method, checked in review. The device smoke trace check verifies the outcome (list base equals mapping base).

## Testing Strategy

| Layer | What to test | How | Count |
|---|---|---|---|
| Unit | `toNormalized` scenarios, clamp, exhaustive round trip | `CoordinateNormalizerTest`, plain JVM | ~5 |
| Unit | band: `(540,40)` rejected, `(540,80)` and `(540,1750)` proceed, `(0,0)` rejected; `inAnswerSpace` edges | `CoordinateMapperBoundaryTest` (new), `CoordinateMapperOffTreeTapTest` (two cases rewritten) | ~6 |
| Unit | config: top default 0.035, bottom key absent and retired | `ConfigTest`, `KeyOwnershipTotalityTest`, `RunSpecAbortTest` | ~3 |
| Unit | history: LLM tap entry at its pixel, menu entry without coordinates, `v17` through the formatter, an entry cut by the display edge at its visible centre and one wholly outside it without coordinates | `ApePromptBuilderCoordinateBaseTest` | ~4 |
| Unit | element list: one line per (target `Name`, resolved bounds), alias nodes with the same bounds collapse, distinct `Name`s or bounds do not, no ordinal, order, `v17` visit sum, `v17` screen line in widgets | `ApePromptBuilderElementListTest` | ~8 |
| Unit | history class and text: simple class name, content-description fallback, same as the list's line for the same widget (D13) | `ApePromptBuilderCoordinateBaseTest`; agent history fixture | ~3 |
| Unit | `v13` system message: the `PRIORITY` line without the visit ranks; the other lines unchanged (D14) | `ApePromptBuilderTest` | ~2 |
| Unit | history result: each entry carries its own action's outcome; a step without a model action does not shift it; a model action a restart replaces leaves no entry, and a restart that replaces no model action, or none at all, removes nothing; an empty result renders without the arrow (D12) | agent history fixture; `ApePromptBuilderCoordinateBaseTest` for the rendering | ~6 |
| Unit | list coordinates per variant on full-screen and dialog geometry; edge-cut widget at its visible centre; off-screen and unresolved nodes render no `@(` per variant; list/history agreement; history bounds → normalized | `ApePromptBuilderCoordinateBaseTest` (new), real `GUITreeNode`/`ModelAction` built as in `ApePromptBuilderIdentifierTest` | ~8 |
| Unit (updated) | existing history and integration tests switched to bounds inputs and production output | `ApePromptBuilderTest`, `PromptIntegrationTest` | existing |
| Regression | decision sequences unchanged under a scripted LLM | `rearch-01` parity golden suite | existing |
| Device | Run on the RVSec AVD against the campaign's server (vLLM v0.29.0, `--enable-auto-tool-choice --tool-call-parser hermes`, no `tool_choice` in the request). It checks the mechanism, not the effect: at about 27 LLM calls per minute (E6: 130,248 answers over 162 runs of 30 min), a 5-minute run yields about 130 calls and 55 copies, too few to measure a change in the copy hit rate. The effect is measured by an arm comparison in the study that adopts the jar. Checks: (a) the list coordinate of each executed widget equals `toNormalized(visibleCentre(bounds, W, H))` with the call's own display, and no widget whose bounds `Rect.intersect` finds disjoint from the display carries one; (b) copied answers land inside the widget; (c) `boundary` outcomes of `click`/`long_click` answers occur only at y < 63 or for out-of-space answers. `classify` also labels a `type_text` with no input widget as `boundary`, so those are excluded from this check; (d) no history entry shows a coordinate outside [0,999]; (e) every history entry's result agrees with its own step's recorded transition (D12); (f) the D9, D10, D13 and D14 checks of task 8.2 (no `@(0,0)` history entry, no repeated (target `Name`, bounds) pair and no ordinal in the `v13`/`v17` lists with container/child lines kept, no full class name in the history, no `unvisited` in the `v13` system message) | smoke run; trace check adapted from `evidence/coordinate_counterfactual.py` (same alignment) | 1 run |
| Unit (Part B) | format-2 load, lookup precedence, both scoring modes, tie split, retirement, launcher order, shortcut order, marker per line, new telemetry fields, new keys | `MopDataTest`, `MopDataLookupTest`, `MopScorerDistanceTest`, `MopRetirementTest`, `MopLauncherStageTest`, `SataAgentMopShortCircuitTest`, `ApePromptBuilderMopMarkerTest`, `NdjsonSinkTest`, run-spec tests | ~40 |
| Regression (Part B) | MOP parity goldens regenerated and diffed | `ParityOracleMopTest`, `ParityOracleLlmMopTest`, `PreemptionGoldenTest` | existing |
| Device (Part B) | a stamped APK (`cryptoapp` instrumented with `--instrumentation-variant dexlib2 --stamp-handlers`) under `ape.mopScoring=distance`: `stampNodes > 0`, `dec.mopd` present on MOP picks, at least one `dec.ret`, launcher order by `activityDist` | smoke run with a format-2 artifact | 1 run |

## Open Questions

- **What becomes of the remaining pending improvements?** On 2026-10-07 the author confirmed P1 (D10) and kept P2 (the `id=` step in the history identifier) and P3 (a `type_text` entry naming its field) pending in `pending.md`. Neither is needed by the repairs, and each would widen the distance from the prompt Study 03 calibrated (proposal, "Relation to Study 03").

- **Should `ScreenshotStep` become substitutable so the pipeline is JVM-testable?** That would let INV-RTR-21 be pinned by a unit test instead of review plus smoke. Recommendation: not in this change. It touches the unit-ownership design (`llm-routing` "LLM Unit Lifecycle and Ownership") for a property that is one local variable.
- **Should the top band be derived at runtime** from the status bar height instead of a fraction? Recommendation: not here (D7). A study on another density sets the key.
- **Should list curation be its own change?** The residual misses (Non-Goals) point to it: 5,066 of 6,874 are copies of scroll-only entries, and 21,938 E6 lines (2.0 %) belong to off-screen widgets that this change lists without a coordinate. Index selection is excluded by the author's decision of 2026-07-29, so the remedy stays inside the coordinate design. Recommendation: a separate proposal that drops from the prompt the entries no tool can act on. `evidence/coordinate_counterfactual.py` already classifies these misses, so it can count how many decisions such a change would touch. What the model would choose instead cannot be estimated offline.
- **Should a study that adopts this jar re-derive the copy/visual split?** With one base, `coordinate_base.py`'s "copied" classification becomes exact, which makes the split a cleaner metric than it was in E6. That is a decision for the study, not the jar. The rv-android calibration report's split (14.89 % yield for "exact copies", answers within 3 px of a listed centre, against 31.61 % for visual hits; `rvsec/rv-android/docs/20260724_relatorio_calibracao_aperv.md` §12-bis.9) was measured with this defect active. Under it, a copy of a full-screen widget lands more than 3 px from the centre for every centre at y ≥ 21 px, so that bin held almost no copies of full-screen widgets. This change does not rely on that figure.
- **The rv-android issue number.** Resolved 2026-10-08: issue PAMunb/rvsec#122, change `gh122-aperv-mop-artifact-format2` (Fast-Forward). It also adds the arms `mopd_on_llm_off` (the reference plus `mop_scoring: distance`) and `mopd_on_llm_90` (Study 03's E6 arm 4 plus `mop_scoring: distance`). The campaign's instrumentation is out of it: the Study 03 replication-package side runs it.
- **Should a widget count toward retirement only for its nearest targets?** Proposed by the analysis `20261006_analise_rigorosa_plano_guia_mop.md:287` (not by R12) to soften retirement; not adopted (D19).

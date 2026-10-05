## Why

The LLM arm uses the [0,1000) coordinate space in three places, and they disagree about what 1000 means. The element list in the prompt divides a widget's centre by the right and bottom edges of the **active window's root node**. The action history, in the same prompt, divides by the **display** (`Display.getSize`). The model's answer is turned back into a pixel by multiplying by the display, the same rectangle the screenshot is captured at. An answer the model reads off the image lands where the model meant. An answer it **copies from the element list** lands somewhere else.

The bases differ on almost every screen of the campaign emulator, in two ways:

- **Full-screen activity windows.** The DecorView extends behind the navigation bar, so the root's raw bounds end at y=1920. The display reports 1794. A copied coordinate lands above the widget's centre by 6.56 % (1 − 1794/1920) of the centre's distance from the top of the screen: about 12 px near the top, about 106 px near the bottom.
- **Dialogs, popups, and windows the keyboard resized.** The root is the window itself, e.g. `[28,690][1052,1166]`. The list then divides by 1052 × 1166. Both axes are off, and the error reaches hundreds of pixels: a widget `[745,1040][897,1140]`, centred at `(821,1090)`, is listed as `@(780,934)`, and a copy is tapped at `(842,1675)`, 585 px below it.

**The boundary band has the same root cause.** `CoordinateMapper` rejects an answer whose pixel y is greater than `0.94 × deviceHeight` or less than `0.05 × deviceHeight`. The gh6 design (`openspec/changes/archive/2026-03-17-gh6-aperv-llm-integration/design.md:294`) wrote the bottom band as "navigation bar (bottom 6% of screen)", a fraction that only holds on a 1920-high screen (126/1920 = 6.56 %). At runtime `deviceHeight` is the display, 1794, which already excludes the navigation bar. The band therefore rejects y ∈ (1686, 1794): app content, where 32.8 % of E6 states have a target (bottom-navigation items, `next`/`settings` buttons, snackbar actions). The defect's upward shift kept copied answers out of that band. Fixing the list alone moves copies of bottom widgets back into it.

The defects date from `b2852ddf` (gh6, 2026-03-17). That change's design (`:277`) planned one base for the prompt and the answer. The router read the display under a comment saying "same as ApePromptBuilder", while the builder read the root. `llm-tap-display-bounds` (2026-07-21) moved the off-tree tap's validity domain to the display, but kept the root as correct for node actions and did not touch the prompt or the bands. `llm-native-toolcall-repair` (J1b, 2026-07-23) exposed the bands as keys whose defaults reproduce the hard-coded values, and did not revisit what they were fractions of.

**Measured effect.**

- **Settings audit.** The Study 03 settings audit (`rvsec-study03-replication-package/experiments/E6-campaign/settings-audit.md` §4, script `settings-audit/coordinate_base.py`) quantified the list defect on E6 arm 4 (jar `e93dea86`, 130,986 LLM decisions):
  - 41.1 % of answers are copied from the list.
  - Decisions sent to a different target than the model chose: lower bound 1.3 %, central estimate 4.1 %, ceiling 6.0 %. For E5b v13_A, the arm E6 inherits, the figures are 2.3 % / 7.1 % / 9.3 %.
- **Counterfactual replay.** A replay of the mapper over the same traces (`evidence/coordinate_counterfactual.py`, 2026-10-05) reproduces every recorded outcome: 130,199 of 130,199 aligned calls. Holding the model's choice of list entry fixed, the share of copied answers that map to the widget they name is shown below. A mapped answer is either executed or refused by the dead-pair ban; as run, 18,837 of the 37,780 E6 copies that mapped to their widget were refused by the ban.

  | Scenario | E6 (53,352 copies) | E5b (4,106 copies) | Copies rejected at the boundary (E6) |
  |---|---|---|---|
  | As run | 70.8 % | 69.9 % | 1.0 % |
  | Display base only, bands unchanged | 83.6 % | 85.3 % | 4.1 % |
  | Display base with the bands re-derived | 87.2 % | 89.4 % | 0.1 % |
  | The same, listing each widget at the centre of its visible part (this change) | 87.1 % | 89.4 % | 0.1 % |

  - In dialogs and popups, copies map to the named widget 21.0 % of the time as run and 97.4 % with this change.
  - Beyond copies, the bottom band also rejects 4,879 E6 answers that are not list copies (3.7 % of all answers). The list fix does not move these. With this change, 2,995 of them land on a tree widget and 1,865 become off-tree taps. The other 19 pointed outside the image (`qwen` y ≥ 1000) and stay rejected (D8). These figures come from the replay without the bottom band; the 19 are its 12 matches and 7 taps at the clamped edge.

**Scope.** Any fix changes what the LLM arm measures. It applies to future studies only, and E6 will not be re-run. The defect enters the Study 03 paper as one found in the analysis of the results. Its effect on decisions is measured; its effect on misuses is not, and is never stated as null (author's decision, `rvsec-study03-replication-package/HISTORY.md:693-697`, 2026-10-03, which reverses the same day's decision to keep it out).

The defect went unnoticed for three reasons:

- `ApePromptBuilderTest` always calls `build(null, …)`, so the root read never runs. `ApePromptBuilderIdentifierTest` passes real actions with a null tree and asserts only that a coordinate is present (`@(`), never its value.
- `PromptIntegrationTest` re-implements the normalization formula in the test instead of calling production code.
- `ScreenshotStep.deviceDimensions` and the history normalization in `StatefulAgent` have no tests. The `llm-prompt` spec writes `deviceWidth`/`deviceHeight` without naming their source and asserts "the SAME coordinate space the LLM responds in". Nothing checks that assertion. The band scenarios in `llm-routing` are written against a 1080 × 1920 device that the jar never sees at runtime.

## What Changes

- **One base, carried from one source.** The `{width, height}` that `ScreenshotStep.deviceDimensions` returns is already used to capture the screenshot and to map the answer. It becomes the only base for the element list and the action history too. `LlmEngine` passes it to `ApePromptBuilder.build`. The builder no longer reads the tree's root node for dimensions. The `GUITree` parameter is removed from `build`, because dimensions were its only use (P3).
- **One normalization primitive.** `CoordinateNormalizer` gains the forward direction, pixel → [0,1000), next to the existing inverse `normalize`. These copies are deleted, and every [0,1000) value the prompt shows comes from that one primitive:
  - the inline copies in `ApePromptBuilder`: the shared line formatter used by `ape_current`, `ape_reasoning` and `compact_v1`, and the `v13` and `v17` list builders;
  - the history computation in `StatefulAgent.recordActionHistory`.
- **History carries pixels, the builder normalizes.**
  - `ActionHistoryEntry` stores the executed widget's pixel bounds instead of a pre-normalized pair. The same widget gets the same `@(x,y)` in the list and in the history of one prompt, by construction.
  - Every history renderer goes through `formatHistoryEntry`: the default branch, `v13`, `visual_only`, and `v17`, whose compact `Recent:` line was rendered inline from the raw fields.
  - `StatefulAgent` stops calling `AndroidDevice.getDisplayBounds()` for the prompt.
- **History entries for actions without a resolved node say what ran.** Today an off-tree `MODEL_LLM_TAP` and a `MODEL_MENU` are both recorded as `click @(0,0)` with no class. Such an entry appears in 24.2 % of E6 arm-4 prompts (31,466 of 130,266) and 22.5 % of E5b `v13_A` prompts (2,157 of 9,586).
  - An LLM tap now records its own pixel and its `long_click` flag.
  - A menu press records `menu`, rendered without coordinates, as `back` already is.
  - `v17` no longer renders `back@(0,0)`.
  - `ActionHistoryEntry` is also specified in `exploration` ("StatefulAgent — Action History Ring Buffer"), whose field table changes with it.
- **A list line with no resolved node omits its coordinate.** The main spec has required this since gh6 ("Unresolved action node"), but the three list sites write `@(0,0)` instead, and no test covers it. They now omit `@(x,y)`. No E6 or E5b prompt contains such a line, so the repair changes no measured prompt.
- **A widget is listed at the centre of its visible part.** The list used the centre of the node's whole bounds, clamped to the display edge. The jar taps a node action at the centre of the node's part inside the visible window, and drops the action when nothing is visible (INV-EXPL-19). The list now shows that point: the bounds intersected with the display, then the centre. A widget entirely off screen keeps its line with no `@(x,y)`, like an unresolved node; whether to list it at all stays the open curation question.
  - In E6, 23,080 list lines (2.1 %, in 3.9 % of prompts) belong to a node entirely off screen, and 58,765 (5.3 %) to a node cut by the display edge.
  - 227 copied answers copied an off-screen entry. With the clamped centre they hit another widget (103), tapped off-tree at the edge (83), hit the band (17), or snapped to a node the dispatcher then drops (24).
  - Copies mapping to their widget: 87.1 % against 87.2 % with the clamped centre, the difference being those 24 snaps and one edge-cut copy (`evidence/coordinate_counterfactual.out`, S3 versus S4). E5b is unchanged.
- **The boundary band is re-derived in the display base.**
  - **BREAKING (configuration):** `ape.llmBoundaryBottomPct` is retired. On the display base every pixel below 1794 is app content, and an answer cannot reach y ≥ 1794 (`normalize` clamps). Any value below 1.0 therefore rejects app content, and any value from 1.0 up never fires. A properties file that still sets the key aborts resolution with a reason naming this change.
  - `ape.llmBoundaryTopPct` changes its default from `0.05` to `0.035`. That is the status bar, 63 px on the campaign AVD (`statusBarBackground [0,0][1080,63]` in 210 UIAutomator dumps), divided by 1794. It still rejects a degenerate `(0,0)`. It stops rejecting y ∈ [63, 90), which is the top of the app's toolbar.
  - An answer outside [0,999] on either axis is rejected as `boundary` before it is mapped, for every tool except `back`; its clamped pixel is computed for telemetry only. Today 19 such E6 answers were rejected only because `normalize` clamped them into the bottom band. Without the band, they would act on a widget at the screen's edge that the model never named.
- **The `v13` and `v17` element lists show each widget once, with no ordinal.** Both lists write one line per target-requiring **action**, numbered `1.`, `2.`, …. A widget with several actions (click and long-click, two scroll directions) therefore appears as several lines. Each line carries only per-widget content (class, identifier, coordinate, MOP marker, static metadata), so the copies are identical apart from the number. In E6:
  - 25.3 % of list lines share their node's bounds with an earlier line, and 74.2 % of prompts contain at least one such repeat. Grouping by node identity collapses 24.9 % of lines: 4,633 of the bounds repeats are distinct nodes with identical bounds (a container and its single child) and stay.
  - The number is read by nothing: no tool, the parser and the mapper all ignore it. The answer is a coordinate, and the mapper matches it against the state's actions, never against list lines.
  - The list therefore becomes one line per widget (its resolved node), in order of first appearance, with no ordinal prefix. `v17`'s status tag counts the visits of all the widget's actions, and its screen-info line counts widgets on both sides of its ratio (`N/M elements tested`).
  - The `ape_current`, `ape_reasoning` and `compact_v1` lists keep one line per action and their `[<index>]` prefix.
  - `ModelAction`s, the mapper, SATA and the exploration model are untouched: they never read the prompt text.
- **BREAKING (experimental comparability):** prompts and routing differ from E5b and E6.
  - The list's coordinates change on every screen where the root and the display disagree, which on the campaign emulator is nearly every screen.
  - The boundary decisions change for answers in the old bottom band and in y ∈ [63, 90).
  - The `v13`/`v17` lists lose their ordinals and their repeated lines.
  - Widgets cut by the display edge are listed at a different point, and wholly off-screen ones without a coordinate.
  - Prompts are not byte-comparable with E5b/E6, and an arm run with this jar is a new arm version, not a re-run of the inherited one.

Not changed:
- the system message;
- the variants' layouts, apart from the `v13`/`v17` element lists (one line per widget, no ordinal), the `v17` screen-info line (counted in widgets), the `v17` history entry, the history entries of LLM taps and menu presses, the omission of `@(x,y)` for an unresolved or wholly off-screen node, and the listed point of a widget cut by the display edge;
- the screenshot path;
- containment, snap tolerance and off-tree tap synthesis in `CoordinateMapper`;
- the off-tree tap validity domain;
- node-action dispatch;
- the `no_match` reason set (`degenerate`, `boundary`, `dead_pair`).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `llm-prompt`:
  - "Widget List Generation" names the source of `deviceWidth`/`deviceHeight`: the display dimensions `ScreenshotStep` reported for the same decision, never the root node.
  - "Action History" receives pixel bounds and renders them with the same visible-centre rule, primitive and dimensions as the list. It records LLM taps and menu presses for what they were, and covers every variant's history renderer.
  - New invariant INV-PRM-06: one base per prompt. INV-PRM-03 ("all actions … no actions filtered") is scoped by hand at archive to the default-branch list.
  - New requirement "Single Coordinate Base": the builder receives the decision's dimensions, every coordinate goes through one primitive, a widget is listed at the centre of its visible part, and an unresolved or wholly off-screen node renders no coordinate.
  - New requirement "Element List of the v13 and v17 Variants": one line per widget, no ordinal, `v17` counts in widgets.
  - "Widget List Generation" is scoped to the `ape_current`/`ape_reasoning`/`compact_v1` list.
- `llm-infrastructure`:
  - "CoordinateNormalizer — Qwen Coordinates to Device Pixels" gains the forward conversion, and a round-trip property that ties the two directions together.
  - "LLM Configuration Keys" drops the bottom band key and moves the top band's default.
- `llm-routing`:
  - "Action Selection Pipeline" states that the dimensions determined in step 2 are the ones the prompt in step 4 and the mapping in step 8 use, and that an answer outside [0,999] never becomes a pixel.
  - "Coordinate-to-ModelAction Mapping" restates the boundary reject in the display base.
  - "LLM Telemetry Logging" moves one scenario off the retired navigation band.
  - "LLM Unit Lifecycle and Ownership": `CoordinateMapper` owns one band and the answer-space check.
  - New invariants INV-RTR-21 (one dimension pair per decision) and INV-RTR-22 (the band in the display base; supersedes INV-RTR-14's band-default clause).
- `exploration`: "StatefulAgent — Action History Ring Buffer": `ActionHistoryEntry` carries pixel bounds, `menu` joins the action types, an LLM tap is recorded as a one-pixel rectangle at its pixel, and the agent no longer normalizes.
- `run-spec`: "Total Fail-Fast Validation" lists `ape.llmBoundaryBottomPct` among the retired keys.

## Impact

- **Java:**
  - `ape/llm/ApePromptBuilder.java`: the `build` signature, three list sites, every history renderer, and `ActionHistoryEntry`, whose `normX`/`normY` become `Rect bounds`; one new private helper, `visibleCentre`.
  - `ape/llm/CoordinateNormalizer.java`: the new forward method.
  - `ape/llm/LlmEngine.java`: passes the dimensions, and adds the answer-range check.
  - `ape/llm/CoordinateMapper.java`: one band instead of two, and the range predicate.
  - `ape/agent/StatefulAgent.java`: `recordActionHistory` stores pixels, the tap pixel and `menu`.
  - `ape/utils/Config.java`, `ape/runtime/KeyOwnership.java` (`feature` line and `RETIRED` entry), `ape/runtime/Feature.java`.
  - Untouched: `ScreenshotStep`, `ScreenshotCapture` and `MonkeySourceApe`.
- **Tests:**
  - `ApePromptBuilderTest` (its `build(null, …)` calls and its reads of `entry.normX`/`normY`), `ApePromptBuilderIdentifierTest`, `PromptIntegrationTest`, `CoordinateNormalizerTest`, `LlmTelemetryTest`, `LlmClientTest`.
  - `CoordinateMapperOffTreeTapTest`: its two band tests assume `0.94` and `0.05`.
  - `ConfigTest` and `KeyOwnershipTotalityTest`.
  - `CoordinateMapIntegrationTest`: stale constants, since it never calls the mapper.
  - The test code that passes `ActionHistoryEntry` lists through without building values: `LlmNewStateStageTest`, `LlmStagnationStageTest`, `LlmRandomStageTest`, `BudgetStageTest`, `DecisionPipelineFromSpecTest`, `PipelineFixture`, `FakeStepContext` and `oracle/ScriptedLlm`. They need no value change.
  - The `rearch-01` parity golden suite uses a scripted engine that overrides `selectAction` and constructs no `CoordinateMapper`, so its decision sequences must compare identical.
- **rv-android / experiments:**
  - No interface change. rv-android sets neither band key.
  - Any study that compares an arm run with this jar against E5b/E6 must treat it as a different arm version.
  - The plan digest in `RUN_START` changes for every LLM arm, because it hashes defaults. Non-LLM arms keep theirs.
  - Fine-tuning data generated from prompts of earlier jars (branch `laya`, on hold) uses the old bases.
- **Telemetry:**
  - No new field. The `llm[].user` dump changes only in the coordinate values and history entries it shows.
  - Analyses that infer the list's base from that dump (`coordinate_base.py`, `evidence/coordinate_counterfactual.py`) will see the display base everywhere.
  - The same analyses align prompt lines 1:1 with the state's target-requiring actions. With one line per widget they must align by widget instead: one line per distinct (target `Name`, resolved-node bounds) pair of the listing.
- **Evidence:** `evidence/` holds the counterfactual script and its E6/E5b outputs (scenario S4, the visible-centre rule, added after the consistency review; S0–S3 are unchanged), a rerun of the audit's `coordinate_base.py`, the list-composition analysis (`curation_analysis.py`, `curation_e6.out`, `curation_e5b.out`), the `@(0,0)` prompt scan (`prompt_zero_scan.py`, `prompt_zero_scan_e6.out`, `prompt_zero_scan_e5b.out`), and the node-identity breakdown of repeated lines (`dup_identity.py`, `dup_identity_e6.out`).
  - All of it reads the replication package's raw traces. It cannot be regenerated from this repository.
  - The E6 arm-4 trace of `com.ds.avare_404` was overwritten on 2026-10-05 by a run with no LLM answers (`data/raw/e6-campaign/e6_07/e6_07/com.ds.avare_404.apk/`, files dated 13:01–13:14). That leaves 162 traces and 130,248 answers, and the audit's 130,986 no longer reproduces from the raw data.
- **Out of scope, measured so a later change can start from it:** after this change, 6,874 E6 copies still miss their widget. About 86 % of these misses (5,891) copy an entry no tool can act on as listed:
  - 5,066 are a `click` on an entry that offers only a scroll;
  - 576 are a `click` on a long-click-only widget;
  - 227 copy an entry whose node is entirely off screen, which this change lists without a coordinate (128 of them are actions APE had already marked invalid);
  - 22 are a `type_text` on a non-input entry.

  The list advertises actions that no tool can select. Scroll is excluded from the LLM by design since gh6, because SATA scrolls mechanically (`docs/analise_prompts_sota.md:198,213,540`). Yet `v13` still lists scroll-only entries, and their lines carry no action type. Selection by list index is not the remedy: the author discarded it on 2026-07-29 and it will not be re-attempted (`rvsec/rv-android/docs/20260724_relatorio_calibracao_aperv.md` §[rev2b]). The consistent direction is curating the list: no entry the tools cannot act on. That is a prompt change for its own proposal, not a defect repair. Landscape screens (328 E6 answers in 31 APKs) stay at 47 % after this change, because capture assumes rotation 0. Not addressed here.
- **Depends on:** nothing pending. Implementation is not scheduled.

# Session prompt — rigorous consistency review of OpenSpec change `llm-coordinate-single-base`

You are continuing work on **APE-RV** (`/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape`, branch `master`). Read `CLAUDE.md` of the repo and your memory index first. Answer the author in Portuguese (pt-BR); the artifacts are written in English.

## 1. Goal of this session

Perform a **rigorous consistency analysis** of the OpenSpec change `openspec/changes/llm-coordinate-single-base`. The change is **artifacts only**. Nothing is implemented and nothing is committed. Your job is to find every inconsistency before anyone implements it:

- between the artifacts: `proposal.md`, `design.md`, `tasks.md`, `specs/*/spec.md`;
- between the artifacts and the code at HEAD;
- between the artifacts and the evidence in `evidence/`;
- between the deltas and the main specs in `openspec/specs/`.

Deliver a findings report to the author first. Each finding gets a severity, `file:line` and the evidence. **Do not edit anything until the author approves the specific fixes.**

## 2. Mandatory workflow rules (follow rigorously)

1. **Artifacts before code; no unapproved edits.** The order is: present findings → author approves → edit artifacts. Never touch `src/`, not even to "measure". Probe scripts go in your session scratchpad, never in the project tree.
2. **Stay on `master`.** Do not check out, modify, merge or archive anything on other branches (`llm-opaque-screen`, `laya`, `mop-fairtest`, `rearch`). Never propose merging `llm-opaque-screen`: it was abandoned on 2026-09-28.
3. **No commit** unless the author explicitly says so. When committing, end messages with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
4. **Verify before concluding.** One empty search is not proof of absence. Check claims against the code and the evidence files, not against memory.
5. **Never run `find`/`grep -r` rooted at `/` or `~`.** Use bounded paths.
6. **Do not re-litigate settled decisions.** They are listed in §6.
7. Use subagents for parallel, independent checks, and the `sequential-thinking` MCP for the synthesis if helpful. Verify any subagent claim you relay, and say which claims you verified yourself.
8. Use `openspec validate llm-coordinate-single-base --strict` after any approved artifact edit. It must print `Change 'llm-coordinate-single-base' is valid`.
9. Be concrete and organized when reporting to the author. They have explicitly complained about loose, vague explanations. Give one clear recommendation, not a menu.

## 3. The defect and the fix (what the change is about)

**Pipeline** (`LlmEngine.selectAction`):

1. `ScreenshotStep.deviceDimensions` → `Display.getSize` = 1080 × 1794 on the campaign AVD. The navigation bar is excluded; the status bar of 63 px is included.
2. `ScreenshotCapture` crops `(0,0)–(W,H)`, and `ImageProcessor` resizes to 602 × 1000 JPEG, keeping the aspect ratio with no padding. Qwen3-VL answers in relative [0,1000).
3. `ApePromptBuilder` writes the element list with `@(x,y)` in [0,1000). **Defect:** it divides by the **active window's root node bounds**, which are 1080 × 1920 for a full-screen activity (the DecorView extends behind the navigation bar) and the dialog's own rectangle for dialogs and popups.
4. The answer is converted to a pixel with the display (`CoordinateNormalizer.normalize`).
5. `CoordinateMapper.map` applies the boundary band, containment, a snap of up to 150 px (the campaign used `ape.llmSnapTolerancePx=150`), and otherwise synthesizes an off-tree `LlmTapAction`.

A matched node action is tapped on its own bounds. So the error shows up as a wrong widget, a tap in empty space, or a rejection.

**Consequence.** When the model copies a coordinate from the list (41 % of answers), the tap lands about 12–106 px too high on full-screen windows, and hundreds of px off on dialogs, usually outside the dialog. Visual answers are fine. The defect dates from `b2852ddf` (gh6, 2026-03-17).

**Sibling defect.** The bottom boundary band `pixelY > 0.94 × H` was written in gh6 as "navigation bar, bottom 6 %" for a 1920-high screen (`openspec/changes/archive/2026-03-17-gh6-aperv-llm-integration/design.md:294`). With H = 1794 it rejects y ∈ (1686, 1794), which is app content: bottom navigation and footer buttons.

**What the change does** (decisions D1–D10 in `design.md`):

- **D1–D5:** one coordinate base, the display pair from `ScreenshotStep.deviceDimensions`, used for list, history, capture and mapping.
  - New forward primitive `CoordinateNormalizer.toNormalized`, next to the inverse `normalize`.
  - `ApePromptBuilder.build(int W, int H, …)` loses its `GUITree` parameter.
  - History entries store pixel centres; the builder normalizes them.
- **D6:** no flag.
- **D7:** retire `ape.llmBoundaryBottomPct` through `KeyOwnership.RETIRED`. The top band default goes from 0.05 to **0.035** (63/1794).
- **D8:** an answer outside [0,999] (tool ≠ `back`) is refused as `boundary` before mapping. `toPixels` still runs for telemetry `px`/nearest only.
- **D9:** history entries record what actually ran.
  - An LLM tap records its own pixel (`LlmTapAction.getPixelX/Y`).
  - A menu press records `menu`, rendered without coordinates.
  - `v17` history goes through `formatHistoryEntry`.
- **D10:** the `v13`/`v17` element lists render **one line per widget** (resolved node, by identity, first-appearance order), with **no ordinal prefix** (`1. `, `2. ` is read by nothing). The `v17` status tag sums the widget's actions' visits.

**Spec deltas.**

- `llm-prompt`:
  - INV-PRM-06;
  - ADDED "Single Coordinate Base";
  - ADDED "Element List of the v13 and v17 Variants";
  - MODIFIED "Widget List Generation" and "Action History".
- `llm-infrastructure`:
  - INV-LLM-13;
  - MODIFIED "CoordinateNormalizer…" and "LLM Configuration Keys".
- `llm-routing`:
  - INV-RTR-21 and INV-RTR-22 (INV-RTR-22 supersedes INV-RTR-14's band-default clause);
  - MODIFIED "Action Selection Pipeline", "Coordinate-to-ModelAction Mapping", "LLM Telemetry Logging" and "LLM Unit Lifecycle and Ownership".
- `run-spec`: MODIFIED "Total Fail-Fast Validation" (retired key).

**Scope.** The change applies to future studies only. E6 is not re-run. The author decided (rvsec-study03-replication-package `HISTORY.md:693-697`, 2026-10-03) that the defect **enters** the Study 03 paper as found in the analysis of results; its effect on misuses is not measured and is never stated as null.

## 4. Measured evidence (in `evidence/`, E6 arm 4, 162 traces, avare excluded)

**Mapper replica.** It reproduces 130,199 of 130,199 aligned calls (result and reason), and the executed widget in every matched call.

**Copied answers mapping to the intended widget** (same-entry assumption):

| Scenario | E6 | E5b |
|---|---|---|
| As run | 70.8 % | 69.9 % |
| Display base only | 83.6 % | 85.3 % |
| Display base with the bands re-derived | 87.2 % | 89.4 % |

- **Dialogs and popups** (narrow windows): 21.0 % → 97.4 %.
- **"Mapped" includes dead_pair-refused answers.** As run, 18,837 of the 37,780 "hits" were refused by the ban.
- **Bottom band, non-copied answers.** It rejected 4,879 non-copied answers (3.7 %). Under the change, 2,995 match, 1,865 tap off-tree, and 19 (qwen y ≥ 1000) stay rejected (D8).
- **List composition.** 25.3 % of list lines repeat a widget, and 74.2 % of prompts contain a repeat. The ordinal prefixes cost about 13 tokens per call.
- **Residual misses after the change: 6,849.** These are not addressed by this change.
  - 5,066 are a click on a scroll-only entry;
  - 577 are a click on a long-click-only widget;
  - 128 copy an entry whose action is invalid;
  - 22 are type_text on a non-input entry;
  - 771 are containment picking a smaller child.

**Evidence files.** These scripts read raw traces in the replication package and cannot be regenerated from this repo:

- `coordinate_counterfactual.py` with `coordinate_counterfactual.out` and `coordinate_counterfactual_e5b.out`;
- `audit_rerun.out`, a rerun of the audit's `coordinate_base.py`;
- `curation_analysis.py` with `curation_e6.out` and `curation_e5b.out`.

## 5. History of this work (2026-10-03 → 2026-10-05)

- **2026-10-03:** the change was opened, artifacts only (commit `0b2db6e2`).
- **2026-10-05, investigation.** Subagents covered the code audit, data/counterfactual, the thesis impact and the literature. The investigation found:
  - (a) the bottom-band sibling defect;
  - (b) the v17 history rendered inline at `ApePromptBuilder.java:686`, bypassing `formatHistoryEntry`;
  - (c) LLM_TAP/MENU recorded as `click @(0,0)` in history;
  - (d) duplicated widget lines and unused ordinals;
  - (e) that Study 02 is unaffected, since it has no LLM and its `ape` arm is the upstream 2021 jar.
- **2026-10-05, review fix.** An adversarial review found 15 defects in the amendment. All were fixed:
  - numbers;
  - the dialog example, now widget `[745,1040][897,1140]` → `@(760,607)`;
  - D8 versus telemetry `px`;
  - the device gate excluding type_text `boundary`;
  - missing call sites (`LlmTelemetryTest:543,567,584`, `LlmClientTest:198,214`, `KeyOwnershipTotalityTest:176-181`);
  - the `Feature.own` neutral-value gap;
  - the stale `Config.java:186-195` comment.
- **2026-10-05, retraction.** An "element-reference / index tool" suggestion was **retracted**, because it contradicts the author's decision (§6). D10 was added after the author's explicit request.
- **2026-10-05, validation.** `openspec validate --strict` passes at the end of the session.

**Uncommitted working tree** (`git status`):

- `M` on `proposal.md`, `design.md`, `tasks.md`, and `specs/{llm-infrastructure,llm-prompt,llm-routing}/spec.md`;
- new `specs/run-spec/` and `evidence/`.

The untracked `docs/20260927_prompt_*opaque_screen*.md` and `docs/analise_claude_fable5 (Cópia).md` predate this work. Do not touch them.

## 6. Settled decisions — do not re-open

- **No LLM action selection by list index or option list.** The author discarded it on 2026-07-29 (`rvsec/rv-android/docs/20260724_relatorio_calibracao_aperv.md` §[rev2b]). Keep the LLM in coordinate space.
- **No scroll tool for the LLM.** It was excluded since gh6 (`archive/2026-03-17-gh6-aperv-llm-integration/design.md:155`: no semantic benefit, ~3–5 s per call, SATA scrolls mechanically). A 2026-07-29 proposal to add it was rejected "for now". No document measures LLM scroll over-use; do not claim one exists.
- **Hiding scroll-only widgets from the list is NOT in this change.** The author has not decided it. It is recorded in the design as open.
- **One line per widget and no ordinal ARE in this change (D10).** The author was emphatic.
- **No flag to reproduce the old base (D6).** E6 is reproduced with its jar `e93dea86`.
- **Top band = status bar fraction 0.035; bottom band key retired.**
- **The change is not scheduled for implementation.** Future studies only.

## 7. What the consistency review must check (checklist)

1. **Cross-artifact coherence.**
   - Every D1–D10 in `design.md` has a matching spec requirement, scenario or invariant, and a task.
   - Every task traces to a requirement.
   - Proposal "What Changes", "Not changed", "Capabilities" and "Impact" agree with design Goals, Non-Goals and Decisions.
   - No artifact contradicts another, for example about whether `toPixels` runs for out-of-space answers, the band values, `menu` and `llm_tap` history, or the v17 format.
2. **Numbers.** Every figure in the proposal and design traces to `evidence/*.out` or to the cited audit at `rvsec-study03-replication-package@d8ec828`. Verify percentages and derived numbers: for example 2,995 = 3,007 − 12 and 1,865 = 1,872 − 7, and 5,793 = 5,066 + 577 + 128 + 22.
3. **Scenario arithmetic.** Recompute every coordinate in the scenarios with the exact formulas:
   - forward: `(int)(p*1000.0/d)` clamped to [0,999];
   - inverse: `(int)(q/1000.0*d)` clamped to [0,d−1];
   - the band: `0.035×1794 = 62.79`.

   Cases include `(540,178)→(500,99)`, `(821,1090)→(760,607)`, `(600,900)→(555,501)`, `(540,538)→(500,299)`, `v17` `Recent: click@(500,99), back`, and `[TESTED-3x]`.
4. **Code references.** Every `file:line` cited in `design.md` and `tasks.md` exists at HEAD and says what is claimed. In particular check:
   - `ApePromptBuilder.java:125-140, 371-430, 615-665, 679-733`;
   - `CoordinateMapper.java:44-59, 85, 128-135, 244-245`;
   - `LlmEngine.java:95-97, 124, 157-176, 255-256`;
   - `StatefulAgent.java:1796-1864`;
   - `Config.java:186-197`;
   - `KeyOwnership.java:103, 216-217, 254-287`;
   - `Feature.java:177-178`;
   - `ScreenshotStep.java:50-68`, `ScreenshotCapture.java:80-81`;
   - `LlmRandomStage.java:85-86`, `LlmClient.java:158-167`, `LlmTapAction.java:59,63`;
   - the test files and lines cited in tasks.
5. **OpenSpec delta rules.**
   - A MODIFIED requirement reproduces the full main-spec block, changing only intended lines. Diff each MODIFIED block against `openspec/specs/<cap>/spec.md`.
   - openspec 1.14 rejects a MODIFIED block that drops a scenario title, so retired behavior keeps its title with a rewritten body.
   - `openspec archive` syncs requirements only. Invariants, Purpose and Data Contracts must be hand-carried (task 9.2). Check that the task lists all of them, including INV-PRM-06, INV-LLM-13, INV-RTR-21, INV-RTR-22, the llm-prompt Data Contracts, and the INV-RTR-14 supersession note.
6. **Completeness of tasks.** Could an implementer follow the tasks and still ship the defect? Look for gates that cannot fail, greps that miss a renderer, call sites not listed, and test sets pinned by exact contents.
7. **D10 impact.**
   - Confirm nothing reads the prompt's ordinal or relies on one line per action. That covers telemetry, parser, mapper, the `hasInputField` predicate, and dead-pair keys.
   - Confirm `ape_current`/`compact_v1` are untouched and the spec says so.
   - Check the `(no elements)` fallback and unresolved-node lines.
8. **Comparability and Impact claims.** The plan digest changes for LLM arms (defaults are hashed; `RunSpec.java:338-354, 410-431`). `RUN_START.params` omits defaults. The parity goldens use a scripted engine that constructs no `CoordinateMapper` (`oracle/ScriptedLlm.java:188-215`).
9. **Residual risks recorded:**
   - landscape (328 answers, capture rotation 0);
   - the UiAutomation fallback;
   - the AVD-specific 0.035;
   - offline analyses needing widget alignment on new traces.

Report format: a table with severity (blocker / major / minor / nit), `file:line` in the change, the claim, the evidence, and the proposed fix. Then **wait for approval**.

## 8. Related files

- **Change:** `openspec/changes/llm-coordinate-single-base/{proposal.md,design.md,tasks.md,specs/llm-prompt/spec.md,specs/llm-routing/spec.md,specs/llm-infrastructure/spec.md,specs/run-spec/spec.md,evidence/}`.
- **Main specs:** `openspec/specs/{llm-prompt,llm-routing,llm-infrastructure,run-spec,event-sink}/spec.md`; principles P1–P4 in `openspec/config.yaml:9-13`.
- **Code:** `src/main/java/com/android/commands/monkey/ape/llm/{ApePromptBuilder,CoordinateNormalizer,CoordinateMapper,LlmEngine,ScreenshotStep,ScreenshotCapture,ImageProcessor,LlmClient,ToolCallParser}.java`, `ape/agent/StatefulAgent.java`, `ape/agent/pipeline/Llm*Stage.java`, `ape/utils/Config.java`, `ape/runtime/{KeyOwnership,Feature,RunSpec,RunSpecEcho}.java`, `ape/model/LlmTapAction.java`, `MonkeySourceApe.java` (node tap at `:299-333`, `:368-371`; llm_tap at `:936-941`).
- **Historical design:** `openspec/changes/archive/2026-03-17-gh6-aperv-llm-integration/design.md` (`:155` scroll, `:277` single-base intent, `:294` bands), `archive/2026-07-23-llm-native-toolcall-repair/` (J1b, INV-RTR-14), and `docs/analise_prompts_sota.md`.
- **Study 03:**
  - `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/experiments/E6-campaign/settings-audit.md` §4 and `settings-audit/coordinate_base.py`;
  - `HISTORY.md:671-697`;
  - raw traces `data/raw/e6-campaign/e6_0?/e6_0?/<apk>/*__aperv:e6_mop_on_llm_90.trace{,.ndjson.gz}`;
  - E5b traces `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/data/results/e5bm1_*/`.
- **rv-android decisions:** `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/docs/20260724_relatorio_calibracao_aperv.md` (§[rev2b] index selection discarded; the 14.89 % vs 31.61 % copy-vs-visual figure was measured **with this defect active**), and `docs/20260729_propostas_melhorias_e3.md:32,45` (scroll rejected "for now").
- **Thesis skeleton (read only, never edit):** `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/doutorado-tese/docs/estudo-03/escrita/esqueleto.md` (`:430-444`, `:622-627` treat the defect).

## 9. Commands

```bash
cd /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape
git branch --show-current            # must be master
git status --short
git diff -- openspec/changes/llm-coordinate-single-base
openspec list
openspec validate llm-coordinate-single-base --strict
openspec show llm-coordinate-single-base --json --deltas-only
# diff a MODIFIED block against main spec, e.g.:
sed -n '/### Requirement: Coordinate-to-ModelAction Mapping/,/### Requirement: Server Model Acknowledgement/p' openspec/specs/llm-routing/spec.md
# rerun evidence (read-only over the replication package; ~seconds, 32 procs):
python3 openspec/changes/llm-coordinate-single-base/evidence/coordinate_counterfactual.py        # E6
python3 openspec/changes/llm-coordinate-single-base/evidence/coordinate_counterfactual.py e5b    # E5b
python3 openspec/changes/llm-coordinate-single-base/evidence/curation_analysis.py
```

Before rerunning the evidence scripts, read their headers for paths and arguments, and write outputs to the scratchpad, not to `evidence/`.

## 10. Learnings / pitfalls from the previous session

- **E6 raw data was overwritten.** On 2026-10-05, between 13:01 and 13:12, a container `e6_07` (`phtcosta/rvandroid:0.9.4`) rewrote the aperv-arm files of `com.ds.avare_404` in E6 raw data. The new arm-4 trace has 0 LLM answers. The audit's 130,986 answers now reproduce as 130,248. Exclude avare, or check its file dates, before recounting. This was reported to the author, and it is unknown whether it was intentional.
- **`uiautomator dump` clips nodes to `Display.getSize`.** That is why the 1920 root was invisible offline. APE reads `AccessibilityNodeInfo` directly. The dump has no `inputType`/`hint` attributes, only `password`. `AccessibilityNodeInfo.getInputType()` exists at runtime but APE does not capture it.
- **The prompt's element list is text only.** `LlmEngine` passes `state.getActions()` separately to the builder (rendering) and to the mapper (matching), and nothing reads list lines back.
- **The model almost never answers `long_click`.** It did once in 130k calls. It uses `type_text` in 8.3 % of calls where it is offered.
- **openspec 1.14 MODIFIED blocks must keep every main-spec scenario title.**
- **Subagent reports were sometimes slightly off.** Examples: snap tolerance 150 vs 50, "lower bound 1.3 %" versus the rerun's 1.2 %, and the 85 % residual share needing the 128 invalid entries. Recompute before stating.
- **Before proposing any action-space redesign,** search rv-android docs for prior author decisions.

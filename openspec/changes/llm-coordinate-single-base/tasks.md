# Tasks: llm-coordinate-single-base

Implementation is not scheduled. These tasks are written so the change can be picked up later without re-running the investigation. Before starting, re-check the line numbers cited in `design.md` §Context against the HEAD of that day.

## 1. Forward primitive in CoordinateNormalizer (TDD RED→GREEN)

- [ ] 1.1 RED: extend `CoordinateNormalizerTest` with the llm-infrastructure scenarios: `toNormalized(540, 178, 1080, 1794)` → `[500, 99]`; `toNormalized(1080, 1857, 1080, 1794)` → `[999, 999]`; negative pixel → 0; and the exhaustive round trip (INV-LLM-13): for every `d ∈ {1080, 1794, 1920, 1999}` and every `0 <= p < d`, `normalize(toNormalized(p))` on one axis lies in `[p − 2, p]`
- [ ] 1.2 GREEN: implement `CoordinateNormalizer.toNormalized(int pixelX, int pixelY, int width, int height)` per design D3 (`(int)(p * 1000.0 / d)`, clamp [0,999]); update the class javadoc to describe both directions
- [ ] 1.3 Run `/sdd-test-run` for the `ape.llm` tests

## 2. ApePromptBuilder takes the decision's dimensions (TDD RED→GREEN)

- [ ] 2.1 RED: create `ApePromptBuilderCoordinateBaseTest` building real `GUITreeNode`/`ModelAction` fixtures as `ApePromptBuilderIdentifierTest` does, covering the llm-prompt scenarios for each coordinate-bearing variant (`ape_current`, `compact_v1`, `v13`, `v17`):
      full-screen widget `[0,105][1080,252]` with `1080 × 1794` → `@(500,99)`;
      dialog widget `[745,1081][897,1207]` → `@(760,637)`;
      centre at y=1857 → y=999, action still listed;
      the list and the history render the same coordinate for the same widget.
      The test calls the new `build(int, int, …)` signature, so it fails to compile until 2.2
- [ ] 2.2 GREEN: change `ApePromptBuilder.build` to `build(int deviceWidth, int deviceHeight, State, List<ModelAction>, MopData, String, List<ActionHistoryEntry>)`. Delete the root-node dimension block (`:131-140`) and the `GUITree` parameter of `build` and `buildUserText` (P3)
- [ ] 2.3 GREEN: replace the three inline normalizations (shared line formatter `:400-409`, `buildRvsmartV13UserText` `:635-640`, `buildRvsmartV17UserText` `:714-719`) with `CoordinateNormalizer.toNormalized(bounds.centerX(), bounds.centerY(), deviceWidth, deviceHeight)` (design D3/D4). Keep each site's existing omission behavior for unresolved nodes
- [ ] 2.4 Update the class javadoc (`:24-25`) and the `build` javadoc: dimensions are the display pair the caller determined, never the root node
- [ ] 2.5 Update `ApePromptBuilderTest` and `ApePromptBuilderIdentifierTest` call sites to the new signature (dimensions `1080, 1920`, matching the values their assertions already assume)
- [ ] 2.6 Rewrite `PromptIntegrationTest:245-300` to assert on `build` output instead of re-implementing the formula in the test
- [ ] 2.7 Run `/sdd-test-run` for the `ape.llm` tests

## 3. History carries pixels, normalized at build time (TDD RED→GREEN)

- [ ] 3.1 RED: add to `ApePromptBuilderCoordinateBaseTest` the scenario "history entry normalized at build time": an `ActionHistoryEntry` with `centerX=540, centerY=178` renders `- click @(500,99) ...` under `1080 × 1794` and `@(500,92)` under `1080 × 1920`
- [ ] 3.2 GREEN: rename `ActionHistoryEntry.normX/normY` to `centerX/centerY` (pixels); `formatHistoryEntry` normalizes them with `CoordinateNormalizer.toNormalized` and the dimensions `build` received (pass them through to the formatter); `back` entries render without coordinates, as today
- [ ] 3.3 GREEN: `StatefulAgent.recordActionHistory` (`:1828-1839`) stores `bounds.centerX()`, `bounds.centerY()` and drops the `AndroidDevice.getDisplayBounds()` call and the inline normalization
- [ ] 3.4 Update the remaining `ActionHistoryEntry` constructions (`ApePromptBuilderTest:42,56,68,81,198`) to pixel inputs. Confirm by grep that `LlmNewStateStageTest`, `LlmStagnationStageTest`, `BudgetStageTest`, `DecisionPipelineFromSpecTest` and `oracle/ScriptedLlm` only pass entries through and need no value change
- [ ] 3.5 Run `/sdd-test-run` for the full suite (`mvn test`): baseline 1123 tests / 19 skipped, plus the new cases, 0 failures

## 4. Engine hands the pair to the builder

- [ ] 4.1 `LlmEngine.selectAction` (`:124`): call `promptBuilder.build(deviceWidth, deviceHeight, state, actions, mopData, base64, recentActions)` with the `dimensions` determined at `:95-97`; update the `@param tree` javadoc (`:77`): the tree is a dimension fallback for `ScreenshotStep`, not a prompt input
- [ ] 4.2 Review INV-RTR-21 by inspection: in `selectAction`, the only dimension values reaching `screenshot.capture`, `promptBuilder.build`, `mapper.toPixels` and `mapper.map` are the `deviceWidth`/`deviceHeight` locals from `screenshot.deviceDimensions(tree)`. Record the check in `verification.md`
- [ ] 4.3 Grep gate (INV-PRM-06 / INV-LLM-13 "only implementation"): no `* 1000` normalization arithmetic outside `CoordinateNormalizer` in `src/main/java/.../ape/llm/` and `StatefulAgent`; no `getRootNode().getBoundsInScreen()` in `ApePromptBuilder`. Record the grep output in `verification.md`
- [ ] 4.4 Run `/sdd-verify` for the module (intermediate checkpoint)

## 5. Regression floor

- [ ] 5.1 Run the `rearch-01` parity golden suite: the per-preset decision sequences compare identical (its LLM is scripted and ignores prompt text, so any difference is a regression)
- [ ] 5.2 `mvn package` produces `target/ape-rv.jar`; `RUN_START` carries the new build stamp

## 6. Verification

- [ ] 6.1 Device smoke on the RVSec AVD (display 1080 × 1794) against the campaign's server configuration, for comparability: vLLM v0.29.0 serving `Qwen/Qwen3-VL-4B-Instruct-FP8` with `--enable-auto-tool-choice --tool-call-parser hermes` and no `tool_choice` in the request (`rvsec-study03-replication-package/experiments/E6-campaign/config/docker-compose.e6.yml:31,48-64`). If another server is used (e.g. SGLang), record the difference in `verification.md`. Use `ape.preset=llm`, `llmPercentage=0.7`, prompt variant `v13`, 2–5 min on an APK with dialogs (e.g. `app.eduroam.geteduroam`, the E6 example). If no LLM server is available, mark this task skipped with that reason rather than substituting a scripted run
- [ ] 6.2 Trace check (script in the scratchpad, same join as `coordinate_base.py`): for every matched LLM decision, the executed widget's listed `@(x,y)` equals `toNormalized(centre, 1080, 1794)`, including decisions in dialog states. Copied answers' tap y minus widget centre y has median within [−2, 0] px in every height band (E6 baseline: −12 to −103 px)
- [ ] 6.3 Record the gate evidence (trace excerpts, check output) in `verification.md`
- [ ] 6.4 Run `/sdd-qa-lint-fix` for the module
- [ ] 6.5 Run `/sdd-verify` for the module
- [ ] 6.6 Invoke `/sdd-code-reviewer` via the Skill tool; report findings to the author before applying any fix
- [ ] 6.7 Run `/sdd-docs-sync`. Update CLAUDE.md's LLM notes only if they describe the coordinate base
- [ ] 6.8 `openspec validate llm-coordinate-single-base --strict`

## 7. Close-out (after the author's approval)

- [ ] 7.1 Commit only when the author says so
- [ ] 7.2 Archive. `openspec archive` syncs requirements only, so add by hand to the main specs: INV-PRM-06 to `llm-prompt` `## Invariants`, INV-LLM-13 to `llm-infrastructure` `## Invariants`, INV-RTR-21 to `llm-routing` `## Invariants`. Also update `llm-prompt` `## Data Contracts` (`recentActions` entries carry pixel centres; `build` takes `deviceWidth`/`deviceHeight` and no `GUITree`) and its `## Purpose` coordinate paragraph
- [ ] 7.3 Tell the study side (rv-android / replication packages) that arms run with this jar are a new arm version, and that `coordinate_base.py`'s base inference will report the display base everywhere

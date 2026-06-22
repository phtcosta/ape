## Why

GitHub Issue: phtcosta/ape#15.

The June 169-APK comparison between APE-RV arms (`sata`, `sata_mop`, `sata_mop_llm`) is methodologically invalid, and several APE-RV signals are unfaithful. The dominant defect: `Config.componentPercentage` derives its default from `mopDataPath`, so the `sata` and `sata_mop` arms differ in **two** variables (the MOP scorer *and* component triggering) instead of one. This is proven in the June traces — `[APE-RV] Triggering` fires in `sata_mop` (502/507 traces) and `sata_mop_llm` (390/507) but **never** in `sata`/`ape` (0/507). Four further defects degrade the MOP scorer, the UI-coverage metric, per-action telemetry, and LLM routing under secure windows.

These five defects are bundled in one change (rather than five micro-changes) because they share one repo, one validation pass (device run), and one experiment-fidelity goal. Master plan (Phase 0, authoritative): `rvsec/rv-android/docs/20260621_plano_correcao_aperv_e_modulos_relacionados.md` §3. Each anchor below was confirmed against the current `master` source.

## What Changes

- **A-3 — Decouple component triggering.** `Config.componentPercentage` SHALL default to `0.0` regardless of `mopDataPath`; triggering fires only when `ape.componentPercentage` is set explicitly. **BREAKING** for experiment defaults (intentional): a `sata_mop` run no longer triggers components unless configured to. Anchor: `Config.java:169-170`; sole consumer `SataAgent.java:351-354`.
- **A-2 — MOP scorer correctness (residual).** Reorder `MopScorer.score()` so the activity fallback (`+mopWeightActivity`) is reachable for a resolved-but-unflagged widget (B4, `MopScorer.java:48` vs `:50-51`); reconcile parent/child widget granularity via tree containment before declaring no-match (B3 — requires the GUI node, which `score()` does not currently receive); normalize `eventType` snake_case⇄camelCase in the consumer (B6, `MopScorer.java:137-141`).
- **A-4 — Faithful UI coverage.** Include action type in the coverage key for target actions too (`UICoverageTracker.java:195-198`); aggregate coverage reporting per Activity, collapsing `naming` fragments (`StateKey.java:47,57`); bound `stateData` growth (`StatefulAgent.java:163`, currently never pruned). **BREAKING** for the "coverage gap" metric semantics (intentional: replace, do not keep the old metric in parallel).
- **A-5 — Step decision logging.** Emit one `[APE-STEP]` line per finally-selected action attributing it to a decision source, covering the LLM early-returns that today bypass `logActionSelected` (`SataAgent.java:317,328,339,348`). Requires adding a provenance field to `ModelAction` (model change, not just a log line).
- **A-6 — LLM throughput and secure windows.** On null screenshot, record a breaker failure and short-circuit instead of retrying every step (`LlmRouter.java:245-249`); clamp `llmPercentage` to `[0,1]` (`Config.java:153`); remove the stale `llmMaxCalls` line from `CLAUDE.md:133` (zero occurrences in source; removed in gh12, never returns).

## Capabilities

### New Capabilities
<!-- None. All affected behavior is governed by existing specs. -->

### Modified Capabilities
- `component-triggering`: `Config.componentPercentage` default no longer derived from `mopDataPath` (now `0.0`); INV-CT-01 and the "Config — componentPercentage" requirement change.
- `mop-guidance`: activity fallback ordering; parent/child containment in widget resolution; `eventType` normalization in the consumer.
- `ui-coverage`: coverage key includes action type; reporting aggregates per Activity across naming abstraction; `stateData` is bounded.
- `action-selection`: a `[APE-STEP]` decision-attribution line is emitted per selected action across all selection paths (including LLM early-returns).
- `llm-routing`: null-screenshot trips the breaker and short-circuits; `llmPercentage` is clamped to `[0,1]`.

## Impact

- **Components**: `Config`, `SataAgent`, `StatefulAgent`, `MopScorer`, `MopData`, `ModelAction`, `UICoverageTracker`, `StateKey`, `LlmRouter`, `ScreenshotCapture` (read-only confirm), `CLAUDE.md`.
- **Public API**: B3 parent/child containment is resolved caller-side in `StatefulAgent.adjustActionsByGUITree()` using the GUI node it already holds — `MopScorer.score()` keeps its current signature (design D2, no public-API change). `ModelAction` gains a `decisionSource` provenance field (new public enum field).
- **Experiment configs / properties**: A-3 changes the effective default for `sata_mop`. Updating experiment property files to set `ape.componentPercentage` explicitly is the experiment owner's domain and is **out of scope** of this change; this change only updates APE-RV source + APE-RV docs.
- **Out of scope**: G-1 (gator handler-reachability, repo `PAMunb/rvsec`); A-1 (discarded, #14); B-1 (delivered, `rvsec#71`); B-9 (strict package match — descoped: the `MopData` 3-arg `load` overload, the guard at `MopData.java:225-238`, and `Config.mopStrictPackageMatch` already exist but are unwired and off by default; the guard is irrelevant to the `sata`/`sata_mop` comparison and was never validated, so it stays as dormant gh13 code untouched); calibration of `llmPercentage` magnitude (experiment-config domain).
- **Validation**: manual — JUnit where it exists (`MopScorer`/`MopData` tests) plus a real-device run. No rv-* skills (ape repo convention).

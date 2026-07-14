# mop-reach-strategies

## Why

The MOP-guidance investigation (`rvsec/rv-android/docs/20260708_investigacao_formas_guiar_mop.md`) re-characterised where the MOP signal actually fires and where the guidance mechanism leaks. Three facts reorder the whole strategy:

- **The MOP operation fires on arrival, not on the tapped widget.** The method that invokes a monitored crypto/TLS API is a business-crypto utility 86.8% of the time; at the component level the trigger is `onCreate` (84% of apps), `onReceive` (60%), `onStartCommand` (46%). Only **0.4%** of directly-reaching methods are UI handlers. A boost anchored on a clickable widget is structurally mis-placed; the correct objective is **maximising distinct MOP-bearing screens visited and MOP components awoken**.
- **The activity-level predicate is fed from the wrong source.** `MopData` populates `mopActivities` only from widget listeners that carry a resource id (`MopData.java:385-389`); the fully-parsed component/reachability data is consumed by no scoring path. As a result `activityHasMop` is true for only **17.8%** of apps. Device evidence (cryptoapp, 2026-07-08) sharpened the source question: `components.activities[].reachesTarget` is itself a **false-negative on lambda-triggered activities** — it is computed through the producer call graph, which does not traverse D8-desugared lambda/callback edges, so cryptoapp reports `reachesTarget=false` for all 4 activities even though `CryptographyActivity` provably runs `Cipher`. The robust source is the `reachability[]` **method-level** flags (`CryptographyActivity` has 13 `reachesTarget=true` methods). A′ therefore reads the union of widget-derived + `components.activities[].reachesTarget` + reachability-method-level. Every downstream consumer (`scoreWtg`, the OPTIONSMENU gateway, `stateMopDensity`) is starved of substrate today.
- **The widget→MOP handler join drops desugared-lambda listeners.** `MopData.deriveWidgetMopFlags` (`MopData.java:488`) joins a widget's listener `handler` to reachability by **exact signature match**. A widget's runtime click handler is the D8 synthetic wrapper `<X$$ExternalSyntheticLambdaN: onClick>` (reachability `reachesTarget=false`, because the producer call graph does not traverse wrapper→`lambda$…`), while the underlying `<X: lambda$…$K>` it delegates to IS `reachesTarget=true`. Exact match never bridges the two. On cryptoapp this drops the **Execute button** — the one widget that triggers all crypto — so only 2 of 7 handlers join (`flagged=2`, `mop=0` across a 60-step run). This is the concrete mechanism behind the "MOP inert / substrate = producer" nulls, and it bites **non-obfuscated** apps (it is lambda desugaring, not R8 rename).
- **Reach, not granularity, is the bottleneck.** Only **47.3%** of static MOP-bearing screens are visited in 300 s. Of the 657 missed screens, **52.5%** are navigable on the static WTG (the agent simply never walked there) and **14.2%** are WTG-orphan but `exported=true` (a direct launch reaches them). The combined ceiling is **82.4%**.

The widget-level boost is 83.7% redundant with "the current screen is already a MOP screen" and degenerate in 48% of apps, so it stays a fine-grained tiebreaker only. The leverage is upstream (fix the predicate source) and in navigation (steer toward unvisited MOP screens, order stagnation launches MOP-first).

## What Changes

Four reach levers plus one hygiene fix, all consumer-side, all default-off or behaviour-preserving so the standalone `aperv` default is unchanged:

- **A′ — activity-level `activityHasMop` source (3-source union)** (`Config.mopActivitySourceComponents`, default false). When true, `MopData` populates `mopActivities` from the **union** of today's widget-derived source, every `components.activities[]` whose JSON `reachesTarget` is true, AND every `reachability[]` activity-class with ≥1 `reachesTarget=true` method (the lambda-gap-immune source; the one that flags cryptoapp's 3 crypto activities). Zero scorer change; this is the axis that separates the `sata_mop_widget` and `sata_mop_activity` experiment arms.
- **FIX 2 — widget MOP-flag recovery for desugared-lambda handlers** (always-on when MOP data is loaded; no flag — a correctness fix). On exact-join miss, if the handler class matches `<X$$ExternalSyntheticLambdaN: …>`, recover the flag from the enclosing class `X`'s `lambda$…` reachability methods. Consumer-side, reads only data already in the JSON — **no GATOR / static-analysis re-run**. Inactive when `mopDataPath` is null (standalone default and `apePureMode` unchanged). Recovers the Execute button on cryptoapp.
- **FIX 3 — handler-join diagnostics** on the `[APE-MOP-DATA] status=loaded` line (`handlersUnmatched`/`syntheticLambda`/`recovered`), so a silent join collapse becomes visible in every run.
- **B — `MopFrontierPass`** (`Config.mopFrontierWeight`, default 0 = off), a new `ScoringPass` in the scoring pipeline introduced by the sibling change `rv-scoring-pipeline`. It adds `mopFrontierWeight` to a WTG-matched action when ALL of: the widget matches a WTG transition (`shortId == WtgTransition.widgetName`), the transition target has MOP (`activityHasMop(targetActivity)`), and the target is unvisited (`Graph.getActivityNode(targetActivity) == null`). Additive to and independent of the generic `frontierBoostWeight` from `activity-frontier`.
- **E-mín — MOP-first stagnation-launch ordering** (`Config.triggerMopFirst`, default false). The stagnation activity launcher's candidate selection (`activity-frontier`'s `selectTriggerCandidate`) keeps its manifest filters (exported ∧ permission==null ∧ !isMain ∧ unvisited) but, when the flag is on, considers **MOP-reaching** candidates (`MopData.activityHasMop(className)` — the reachability-augmented truth A′/`MopFrontierPass` use, **not** the lambda-false-negative `ComponentInfo.reachesTarget`) before the rest, deterministically. Extension to receivers/services (E-ext) is an explicit NON-GOAL.
- **F′ seams (no behaviour change)**: `Config.llmPercentageNoSubstrate` (default −1 = inherit `llmPercentage`; **no consumer** in this change) and `MopData.isWidgetlessSubstrate()` (true when the sum of `windows[].widgets` sizes is 0 — the 65 widgetless Compose/GL/game apps). `LlmRouter` is untouched; these expose the classifier that round-2 adaptive routing will consume.
- **G-2 — too-large reject unit consistency (regression guard, no code fix).** The premised decimal-MB vs binary-MiB unit bug **does not exist**: `MopData.load`'s pre-read check has compared `File.length()` (bytes) against `maxMemory()/factor` (bytes) since it was introduced (`c6c5d1f`, `mop-data-load-oom`); no decimal-MB conversion is present on either operand at any commit (verified by blame + `git log -S`). G-2 therefore adds only a regression-guard test that pins the byte-unit boundary (INV-MOP-29). It does **not** recover redreader-scale runs: a 48.3 MiB file's rejection is a genuine heap-budget decision (`48.3 MiB × factor` vs device `maxMemory()`), not a unit artifact.

## Capabilities

### New Capabilities

(none authored here — B's requirement is ADDED to the `scoring-pipeline` capability, which is **created by the sibling change `rv-scoring-pipeline`**; see Dependencies.)

### Modified Capabilities

- `mop-guidance`: A′ 3-source union for `mopActivities`; FIX 2 desugared-lambda widget-flag recovery; FIX 3 handler-join diagnostics; `isWidgetlessSubstrate()` predicate; G-2 regression guard on the (already byte-consistent) load-size budget check.
- `scoring-pipeline`: `MopFrontierPass` requirement (ADDED; capability owned by `rv-scoring-pipeline`).
- `component-triggering`: MOP-first ordering of the stagnation launcher's candidate selection (ADDED; the launcher itself is owned by `activity-frontier`).
- `llm-routing`: `llmPercentageNoSubstrate` seam flag (ADDED; loaded, documented, unconsumed).

## Impact

- **Code**: `MopData.java` (A′ 3-source union population incl. reachability-method-level; FIX 2 desugared-lambda widget-flag recovery in `deriveWidgetMopFlags` + a `className→hasReachingLambda` index in `parseReachability`; FIX 3 join diagnostics on the load line; `isWidgetlessSubstrate()`; G-2 adds a regression test only, no `MopData` code change), a new `MopFrontierPass` in `com.android.commands.monkey.ape.agent.scoring`, `SataAgent.selectTriggerCandidate` (ordering branch), `Config.java` (4 new flags + P4 comments). FIX 2/FIX 3 are consumer-side only — no producer/GATOR change.
- **Behavior**: with A′ on, `activityHasMop` widens 17.8%→83.6%, feeding `scoreWtg`/OPTIONSMENU/`stateMopDensity` for free; with `mopFrontierWeight>0`, widgets that open **unvisited MOP** screens win roulette weight; with `triggerMopFirst`, stagnation launches prefer MOP activities; the F′ flag/predicate change nothing at runtime.
- **Telemetry**: `MopFrontierPass` boosts are recorded in the action's existing `wtgBoost` telemetry field (accumulating with `mopWeightWtg`/`frontierBoostWeight`), so they remain attributable via the `[APE-STEP] ... wtg=` field and the pipeline's per-pass assembly line — no new `decision_source` value is introduced.
- **Vocabulary boundary (gh13 D7)**: A′ reads the JSON `reachesTarget` field (`Target` vocabulary, JSON side) and populates `mopActivities` (`MOP` vocabulary, Java side); the `*Target` name appears only at the JSON read.

## Dependencies

- **`rv-scoring-pipeline` (sibling, in progress)** — B is authored as a `ScoringPass`; its requirement is ADDED to the `scoring-pipeline` capability that the sibling introduces (interface `ScoringPass`, `ScoringPipeline.fromConfig`). This change is self-contained for `openspec validate` (it creates the capability delta as ADDED), but at implementation/archive time it MUST land after `rv-scoring-pipeline`.
- **The 5 open changes** (`activity-frontier`, `back-menu-pick-cap`, `foreign-activity-guard`, `tree-package-guard`, `idle-timeout-cap`) are assumed **archived** first. In particular:
  - E-mín is additive to `activity-frontier`'s stagnation launcher and `selectTriggerCandidate`; it does not redefine them.
  - B's `mopFrontierWeight` is additive to `activity-frontier`'s generic `frontierBoostWeight` (different predicate: B requires the target to be MOP-bearing).

## Non-Goals

- **E-ext** — triggering receivers/services via `am broadcast`/`am startservice` ordered MOP-first. Leaves GUI-testing semantics; a separate arm or later round.
- **F / F′ behaviour** — no adaptive LLM routing, no per-activity LLM prompt. `LlmRouter` is unchanged; only the classifier seam ships. Round 2.
- **Weight calibration** — `mopFrontierWeight` vs `frontierBoostWeight` interaction and the E-mín ordering are validated in a device smoke before the large experiment; this change ships the mechanism with defaults, not tuned values.
- **A-boost** (uniform per-screen boost) — refuted by the data (83.7% redundant, degenerate in 48% of apps); not restored.
- **Producer-side work** (C, Compose slot-table) — out of scope. **D (handler-signature) is NO LONGER a non-goal**: the cryptoapp evidence showed the desugared-lambda mismatch is recoverable **consumer-side** from data already in the JSON (FIX 2), without re-running the static analysis, so it is now in scope as a consumer fix. The remaining producer-side ideal (emit per-listener `handlerReachesTarget`, gh60-C3) stays out of scope — FIX 2 subsumes its effect for the lambda case without a GATOR re-run.

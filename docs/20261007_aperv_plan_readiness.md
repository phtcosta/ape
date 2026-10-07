# APE-RV side of the MOP-guidance plan: scope, readiness, and sync with GATOR

Date: 2026-10-07. Session: `aperv`. Code read at `ape@a695709e`; `src/` is identical to the E6 jar
`e93dea86` (`git diff --stat e93dea86 HEAD -- src` is empty). rv-android read at `modules@4bd7b11a`.

Sources: rv-android `docs/20261005_gator_fragments_compose_guia_teste.md` (the plan: §3.4,
§6.5–§6.10, §8, §9), `docs/20261005_verificacao_plano_gator_compose.md` (§1, §3, §4, §7, §8) and
`docs/20261006_revisao_analise_plano_guia_mop.md` at `4bd7b11a` (§0, R1, R2, R4–R7, R9, R12, §3,
§5).

Nothing here changes code, specs or the decided order (C0 → 7.5 → 7.1 expanded → GATOR change).
It records the state of the APE-RV work and the facts verified on 2026-10-07. Every fact is tagged
**[verified]** (checked in code, specs, fixture or trace by this session) or **[relayed]** (taken
from the documents or from session memory, not rechecked).

## 1. Candidate changes

| # | Change | Depends on | Specs |
|---|---|---|---|
| W1 | Ordered MOP shortcut (decision 9): at both deterministic MOP sites, rank candidates first by "not yet interacted in this activity" (`UICoverageTracker.hasActivityInteraction`), then by the MOP boost; keep the cap of 3 | nothing on the GATOR side | `action-selection`; `event-sink` only if a field is added |
| W2 | `llm-coordinate-single-base` (D1–D12) | nothing on the GATOR side | `llm-prompt`, `llm-infrastructure`, `llm-routing`, `exploration`, `run-spec` |
| W3 | Distance consumer (plan §6.7): `formatVersion` 1 → 2, per-widget/per-activity `[[i,d],…]`, distance scoring replacing +500/+300, retirement counters, launcher ordered by distance | C0 → 7.5; decision 10; the INV-DRV-06 amendment | `mop-guidance`, `static-analysis-entrypoints`, `action-selection`, `component-triggering`, `event-sink`, `llm-prompt` |
| W4 | Compose / widget-bind channel on device | C0 → E2; bind direction after 7.5; INV-RUN-06 | not designed |

Proposed jar order (verification §7.2): W2 → W1 → W3. Which change goes into which jar, and which
comparisons are primary, is still open (verification §8, open item 3).

### 1.1 W1: what the code does today

- Deterministic sites [verified]:
  - epsilon path: `selectUnvisitedMopTarget` (`agent/SataAgent.java:621-628`), called at `:571`,
    over `ActionFilter.ENABLED_VALID_UNVISITED`;
  - 0-step probe in `findGreedyActionForward` (`:1443`), over `getGreedyActions`, which with
    `ape.useActionDiffer=true` (default, `runtime/KeyOwnership.java:184`) is
    `StateActionDiffer.getUnsaturated` — **unsaturated**, not unvisited;
  - both go through `pickCappedMopTarget` (`:836-857`), keyed by `mopPickKey` (`:697`,
    `toXPath()|type|activity`), cap default 3 (`KeyOwnership.java:192`).
  - Note: `SataAgent.java:569` is the BACK/MENU capped filter (`cappedBackMenuFilter`), not the MOP
    cap.
- **Ranking already favours activity novelty within a boost level** [verified]:
  `pickBestMopTarget` (`:642`) orders by `mopBoost`, then by priority. Priority already carries the
  coverage boost (`agent/scoring/CoveragePass.java:47-53`): `weight / (1 + stateVisits / 5)`,
  applied only when `hasActivityInteraction(activity, widgetId)` is false. In the E6 MOP arm the
  feature was on with weight 100 (`RUN_START` of an arm-3 trace: `COVERAGE_BOOST` in `features`,
  `ape.coverageBoostWeight: 100`). So W1's real behavioural delta is:
  - across boost levels (an activity-novel +300 target vs a repeated +500 target);
  - after the coverage boost decays to 0 (integer division: state visit count ≥ 500);
  - where other priority terms outweigh the coverage boost.
- **The size of that delta is not measured.** The E6 trace prints each candidate's priority but not
  its `mopBoost` or `cov`; an offline replay would need the activity-interaction set (reconstructable
  from `dec.a`, plan §6.6) and per-candidate boosts (recomputable from the artifact).
- Telemetry: `dec.cov > 0` on a shortcut pick already means "activity-novel", except when the
  coverage boost has decayed to 0 [verified from code; not checked in data].

### 1.2 W1 open questions (for the author)

1. Should a capped key also lose its boost in the **least-visited tiebreak**? The roulette keeps it
   by spec (`openspec/specs/action-selection/spec.md:252`, "with X's `mopBoost` still in its
   priority") [verified]; the spec is silent on the least-visited tiebreak
   (`model/State.java:158-190`, `beatsLeastVisited`) [verified]. Measurement in memory
   `mop-cap-leak-measured` [relayed]: 2,782 boost-decided post-cap picks in arm 3, 56 % from
   dev.spiegl.flyingcarpet; mechanism is ties at equal positive visit counts.
2. Spec drift on step 0 [verified]: `action-selection/spec.md:217-221` says `getGreedyActions`
   "collects precisely the enabled/valid/unvisited actions"; the code uses unsaturated. The same
   requirement cites stale lines (`SataAgent.java:456-488`, `:1066-1102`, `:409-439`).
3. `ape` spec-text repairs (see §3.2).
4. Activity novelty, the pick cap and the §6.7 retirement counters all inherit refinement re-keying,
   because `widgetId` is built from `Name.toXPath()` (`utils/UICoverageTracker.java:240-250`)
   [verified for the key; relayed that action refinement changes the XPath].

### 1.3 W2 state

`openspec validate llm-coordinate-single-base --strict` passes; 0/48 tasks [verified, 2026-10-07].
Not scheduled. Whether the four repairs of verification §7.1 all landed in `a695709e` was not
rechecked.

## 2. Out of scope

- W4 (blocked, above).
- Refused or deferred by the author: list-index / option-list selection for the LLM; a scroll tool;
  retiring launch targets that fail (INV-CT-14 stays); Intent-extras inference (deferred).
- Micro-randomization: an option only; it changes the jar.
- Branches: `llm-opaque-screen` abandoned; `laya` on hold.
- The instrumenter, including the `invoke-super` defect (handed to gator-compose).

## 3. Sync with the GATOR plan

### 3.1 What the GATOR side changes, and what APE-RV must do

- **F1/F2 fragments: no jar change** [verified]. Correction to plan §3.4's phrasing: the jar does not
  "accept `Host#Fragment` keys by exact match". The generator folds `Host#X` into `Host`
  (`derive_mop_artifact.py` `_base_activity`, `BASE_ACTIVITY_SEPARATOR = "#"`, line 110), and the jar
  looks up the exact runtime activity (`utils/MopData.java:627`, `getWidget`). The jar never sees
  `#`. No `ape` spec says that a fragment window is folded into its host
  (`static-analysis-entrypoints` and `mop-guidance` say only "base activity").
- **Dialogs, DataBinding, adapters (host emitted pre-WTG): no APE-RV change** [verified]. The I-17
  hazard is real, derive-side: in `_rekey_dialogs`, a DIALOG window named `Host#…` whose resolved host
  is another activity takes `widget_map[Host]` — the host's whole bucket — moves it to that activity
  and deletes `Host`. Same-host resolution is a no-op; an orphan keeps its key.
- **Wrapper → body link (D1): no code change, but an `ape` scenario changes** [verified on the
  fixture `modules/aperv-tool/tests/fixtures/cryptoapp.apk.json`]: the wrapper
  `CryptographyActivity$$ExternalSyntheticLambda0.onClick` **is present** in `reachability[]` with
  `reachesTarget=false`; the body `lambda$setupExecuteButton$0$…` has `true`. The scenario at
  `static-analysis-entrypoints/spec.md:193` says "has no exact `reachability[]` signature" (wrong
  today), and it requires `executeButton` to be flagged "**only** through the D8 recovery" — which
  breaks once the GATOR change links the wrapper to its body.
- **Distance (W3): two separate parities** [verified]. INV-ANA-32 covers GATOR `JsonSchema.Keys` ↔
  `_JK` (in `rv-static-analysis/.../static_analysis_parser.py`), i.e. the full JSON. The generator ↔
  jar contract is `FORMAT_VERSION = 1` (`derive_mop_artifact.py:69`) ↔
  `SUPPORTED_FORMAT_VERSION = 1` (`MopData.java:149`), gated by INV-MOP-34; on a MOP arm a mismatch
  aborts the run (`StatefulAgent.requireMopArm`, `:233`). W3 must move both.
- **Compose:** W4, blocked. 97.4 % of only-Compose clicks have no `resource-id` [relayed, plan §6.10].

### 3.2 Already in sync

The `(base activity, shortId)` key; the collision policy (INV-MOP-19); the deep-link URI chosen
host-side and launched by `MonkeySourceApe.java:956-967` (`ACTION_VIEW` when a URI exists, explicit
component otherwise) [verified]; fail-fast when a MOP arm's artifact does not load [verified].

### 3.3 Gaps neither plan covers yet

1. **Prompt marker vs scoring (review R9).** `ApePromptBuilder.buildMopMarker` (`:456`) uses the
   exact shortId and aggregate flags; scoring uses ±2-level containment and the per-event flag
   (`MopWidgetPass.mopBoostWithContainment`, `MopScorer.containmentShortIds`) [verified for the
   containment case; the long-click and `none`-event cases relayed]. No change owns it; it matters if
   the LLM arm stays in the campaign.
2. **`complete` / partial artifacts** [verified for the text]: `static-analysis-entrypoints/spec.md:189`
   makes `complete == true` a derivation precondition (scenario `:201-204`); the generator
   deliberately does not read it (`derive_mop_artifact.py:41`, INV-DRV-08). "Complete by
   construction" is stated both in the comment at `MopData.java:204-206` **and** in
   `mop-guidance/spec.md:19`. 44 of 163 artifacts lack `complete` [relayed].
3. **Undefined invariants** [verified]: INV-DRV-05, INV-DRV-06, INV-SNK-13 and INV-MOP-14 are cited
   in the main specs but defined only in archived changes (`rearch-07`, `rearch-04`, `gh13`).
4. **Deep-link counts are not reconciled**: analysis 50/57; gator-compose ~94 activities with a URI,
   ~49 likely failing (review I-14, relayed); an earlier aperv session 79/43. None verified here.
5. **Telemetry**: W1 is partly covered by `dec.cov` (§1.1); distance and retirement have none.
6. **Refinement re-keying** affects W1 novelty, the pick cap and the §6.7 counters alike (§1.2, 4).

## 4. Readiness and recommendation

| Change | Can open now? | What blocks it |
|---|---|---|
| W2 | yes; artifacts complete and valid | the author's scheduling and the jar-composition decision |
| W1 | yes; no machine needed | the author's release (06/10: no change opened) and open question 1 |
| W3 | no | C0 → 7.5; decision 10; INV-DRV-06 amendment |
| W4 | no | C0 → E2; bind direction after 7.5; INV-RUN-06 |

**Recommendation:** release only the opening of W1, artifacts only. It would carry the cap-leak
analysis in detail (as decided), the answer to open question 1, the step-0 spec repair, and a
comparator whose secondary key can move from `mopBoost` to the distance score. Its first task: an
offline E6 replay that sizes how often the new order changes the pick (§1.1).

## 5. Corrections to the session prompt

- `SataAgent.java:569` is the BACK/MENU cap filter; the MOP cap is `pickCappedMopTarget` (`:836-857`).
- The roulette clause is at `action-selection/spec.md:252`, not `:240`.
- INV-ANA-32 is not the generator ↔ jar parity (§3.1).
- The fragment key is folded by the generator, not matched by the jar (§3.1).

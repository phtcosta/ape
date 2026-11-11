## MODIFIED Requirements

### Requirement: Run-Level Records

The sink SHALL carry the out-of-step records in the same NDJSON stream: `RUN_START` (emitted by the run-spec capability as the first record; carries `run_id`, the epoch base `t0`, and the effective plan — including the LLM parameters previously on the retired `[APE-LLM-CONFIG]` manifest), `MOP_DATA` (the load census, below), `PIPELINE` (assembly provenance, below), `LLM_ACK` (`server_model`, at most once, on the first successful chat response), and `RUN_END`.

**`MOP_DATA` — the load census, and the field that has been misread.** The record is written by `StaticArtifactLoader` on every load, which happens if and only if the plan mounts `STATIC` or `MOP` (design D-B14.20); a path given with neither group mounted is inert and produces no record. It describes the input, not a decision, so it is written in the cells `as` and `as_llm` as well, and the clean-control gate excludes it by name (design D-B14.9). The record SHALL carry `status`, optional `reason` on rejection, `formatVersion` (the format of the loaded artifact, `2`; `0` on a rejection, where a version read is by definition not one the jar can vouch for), `sourceDigest` when the load reached one, and on success `package`, `windows`, `widgets`, `flagged`, `droppedNoId`, `wtgEdges`, `handlersUnmatched`, `syntheticLambda`, `recovered`, `mopActivities`, `mopActsAugmented`, `components`, `targets`, `handlers` `droppedPairs` (malformed distance pairs dropped at load, `mop-guidance` loader) and `cutPairs` (well-formed widget and handler pairs dropped by the cut at load, `mop-guidance` "Distance-Pair Cut at Load"). Apart from the three provenance fields, the three Part B counts and `cutPairs`, this is the set the retired `[APE-MOP-DATA]` line carried, with one deliberate substitution: the line's `transitions` field is **not** restored, and `wtgEdges` replaces it.

The three counts come with wire format 2 of the compact artifact (`mop-guidance`, `static-analysis-entrypoints`). `targets` is the artifact's `targets` value, the length of the target list every distance pair indexes, and therefore the bound of every index in `dec.ret`. `handlers` is the number of classes the artifact's `handlers` table lists. `droppedPairs` is the number of malformed distance pairs the loader dropped; a conforming generator emits none, so a non-zero value is a generator↔jar skew signal. `cutPairs` is the number of well-formed widget and handler pairs the loader dropped because their distance exceeds the cut c, the length of `ape.mopWeights`. The cut applies only under `ape.mopScoring=distance`, so `cutPairs` is 0 under `flag` and in a cell without `MOP`. It is 0 at the default ladder with an artifact derived at the generator's default depth 3. A positive value means the artifact is deeper than the ladder. That is not an error, and the field keeps it apart from a malformed artifact. No pair is counted in both fields. Together with `RUN_END.counters.stampNodes` and `stampHits` they say whether the stamp-first lookup had anything to work with.

That substitution is the point of the requirement, so it is stated rather than left to the field list. The three WTG-family passes — `WtgPass` (MOP), `NavFrontierPass` (STATIC, mounted by `NAV_FRONTIER`) and `MopFrontierPass` (MOP) — gate on `StaticGuiModel.hasWtgData()` as well as on their member feature, and `hasWtgData()` is `!wtgTransitions.isEmpty()` — a **click-only** convenience view, keyed by base activity. What the retired line logged as `transitions=N` was the **flat** list of the producer's transitions — a different structure, and not the one the gate reads. Over the decisive campaign's 40 applications, 11 have zero transitions, **14 have transitions but no click event at all**, and 15 have at least one click edge. Restoring `transitions` would restore the misreading; `wtgEdges` is the number the gate actually reads.

A separate boolean `has_wtg_data` SHALL NOT be emitted: `hasWtgData()` is exactly `wtgEdges > 0`, and a second field would be a second place the same truth is written.

**`PIPELINE` — assembly provenance including what was *not* assembled.** The record SHALL carry `passes` (the enabled scoring passes, in pipeline order, as `ScoringPipeline.passNames()` returns them) and, beside it, `candidates`: every one of the seven candidate passes — `MopWidgetPass`, `MenuGatewayPass`, `WtgPass`, `NavFrontierPass`, `MopFrontierPass`, `CoveragePass`, `FormCompletionPass` (`scoring-pipeline`) — with `{name, enabled}`. It SHALL also carry the decision pipeline's side (`decision-pipeline`, design D-B23): `stages`, the names of the stages as assembled, in pipeline order (the list the `[APE-ARCH] stages=[…]` Logger line echoes); and `stageCandidates`, every one of the nine candidate stages in the fixed order `Budget`, `Restart`, `LlmNewState`, `LlmStagnation`, `LlmCoin`, `MopLauncher`, `ComponentTrigger`, `MopCoin`, `SataChain`, each as `{name, enabled}` — the shape of the pass census. `candidates` SHALL keep its meaning, the scoring-pass census; the stage census is the separate, additive member `stageCandidates`, so a reader of `candidates` is unaffected. The stage side SHALL be a static declaration of candidate stage names, not constructed objects — a feature absent from the plan has no stage (decision-pipeline INV-DP-03), and the census SHALL NOT reintroduce a disabled-stage object to enumerate.

A `reason` field on the pass entries SHALL NOT be emitted. Each gate is the member feature (`RUN_START.features`) and, for the WTG family, `hasWtgData()` (`MOP_DATA.wtgEdges`), so every conjunct is already recoverable from the same trace, and a reason field would encode the order in which each constructor happens to evaluate its conjuncts rather than the cause.

The census is what makes the pass list readable as a data-dependent outcome instead of a configuration echo: in the decisive campaign the WTG family was never constructed in 25 of the 40 applications, with nothing in the trace saying so except the absence of names from a list. Without `candidates`, "the cell turned this off" and "this application's data could not support it" remain indistinguishable.

#### Scenario: MOP data load recorded

- **WHEN** in a cell that mounts `STATIC` or `MOP` the format-2 artifact loads successfully with 12 windows, 340 widgets, 9 targets and 57 handler classes
- **THEN** the trace SHALL contain `{"type":"MOP_DATA","status":"loaded","formatVersion":2,...,"windows":12,"widgets":340,...,"targets":9,"handlers":57}` as its own line
- **AND WHEN** the same path is given in the `base` plan with neither group mounted
- **THEN** no `MOP_DATA` record SHALL be written

#### Scenario: transitions present, click edges absent

- **WHEN** in the `as_mop` cell an application's static JSON yields 29 transitions, none of which carries a `"click"` event
- **THEN** the `MOP_DATA` record SHALL carry `wtgEdges:0` and SHALL carry no `transitions` field
- **AND** the `PIPELINE` record's `candidates` SHALL list `WtgPass`, `NavFrontierPass` and `MopFrontierPass` with `enabled:false`, while `passes` lists only the four that were constructed

#### Scenario: a pass disabled by weight rather than by data

- **WHEN** the application has 7 click edges and the `as_mop` cell sets `ape.mopFrontierWeight=0`
- **THEN** `MOP_DATA.wtgEdges` SHALL be `7`, `PIPELINE.candidates` SHALL carry `MopFrontierPass` with `enabled:false`, and `RUN_START.features` SHALL lack `MOP_FRONTIER`
- **AND** the three facts together SHALL identify the cause without any `reason` field on the record

#### Scenario: the stage census lists every candidate stage

- **WHEN** a run of the `as` cell assembles its decision pipeline (no `LLM`, no `MOP`)
- **THEN** the `PIPELINE` record's `stageCandidates` SHALL list, in this order, `Budget`, `Restart`, `LlmNewState`, `LlmStagnation`, `LlmCoin`, `MopLauncher`, `ComponentTrigger`, `MopCoin`, `SataChain`, each with `{name, enabled}`, with `enabled:false` on `LlmNewState`, `LlmStagnation`, `LlmCoin`, `MopLauncher` and `MopCoin`
- **AND** `stages` SHALL list exactly the names whose `stageCandidates` entry is `enabled:true`, in the same order
- **AND** `candidates` SHALL still be the seven-entry scoring-pass census

#### Scenario: Server model acknowledged once

- **WHEN** the first successful LLM response reports model `qwen3-vl-8b` and 500 further calls succeed
- **THEN** exactly one `LLM_ACK` record with `server_model:"qwen3-vl-8b"` SHALL appear in the trace

#### Scenario: a format-1 artifact is rejected with its format named

- **WHEN** the pushed `.mop.json` declares `formatVersion: 1` in a cell that mounts `STATIC` or `MOP`
- **THEN** the `MOP_DATA` record SHALL carry `status:"rejected"`, `reason:"version-mismatch"` and `formatVersion:0`
- **AND** its census counts, `targets` and `handlers` among them, SHALL be absent or `0`

#### Scenario: cut pairs recorded apart from malformed ones
- **WHEN** a `mop` run with `ape.mopWeights=500,400` (c = 2) loads an artifact whose widget and handler lists hold 40 well-formed pairs at `d = 3` and no malformed pair
- **THEN** its `MOP_DATA` record SHALL carry `"droppedPairs":0` and `"cutPairs":40`
- **AND WHEN** the same artifact is loaded at the default ladder
- **THEN** the record SHALL carry `"cutPairs":0`

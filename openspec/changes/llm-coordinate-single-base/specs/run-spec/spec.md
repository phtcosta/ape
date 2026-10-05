# Delta: Run Spec (llm-coordinate-single-base)

## Purpose

`llm-coordinate-single-base` retires one configuration key, `ape.llmBoundaryBottomPct`. The key named a band of the screen's bottom 6 % meant to keep the LLM off the navigation bar. The fraction was written for a 1920-high screen, but the band is applied to the display, whose 1794 px already exclude the navigation bar. On the display, every value of the key below 1.0 rejects app content, and every value from 1.0 up never fires (`llm-routing` INV-RTR-22). A key that is simply deleted aborts as `unknown_key`. Listing it among the retired keys gives the abort a reason a researcher holding an old properties file can act on, which is what the retired class exists for.

## MODIFIED Requirements

### Requirement: Total Fail-Fast Validation

`RunSpec.resolve` SHALL abort (nonzero exit, `[APE-RUNSPEC-ABORT] reason=<class> key=<key> detail=<message>` to stderr and stdout, before step 1) on every one of the following input classes — and the abort SHALL happen before any agent, event source, or device interaction is constructed:

1. **Unknown key**: an `ape.*` key in a properties file that no owner declares.
2. **Foreign key**: any non-`ape.` key in a properties file.
3. **Retired key** (dedicated message naming the replacement): `ape.apePureMode` (purity is structural — owner decision D3), `ape.modelFile`, `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, `ape.saveStates` (persistence protocol removed), `ape.enableXPathAction` (injection channel removed), `ape.mopWeightActivity` (dead key), `ape.llmBoundaryBottomPct` (on the display base the jar maps answers in, the navigation bar is outside every reachable pixel, so the band only rejected app content — `llm-coordinate-single-base`), `ape.agentType` and `ape.replayLog` when present in a **file** (CLI-only values — closes the `/sdcard` agent-swap hole).
4. **Invalid type**: a non-numeric value for a numeric key; a boolean key whose value is not literally `true`/`false` (case-insensitive). Documented value-semantics clamps (cadence `<= 0`, launch cap `< 0`, percentage clamps, `-1` sentinel) remain clamps, not aborts.
5. **Missing dependency / invalid combination**: per the Feature Model requirement; plus `--ape replay` without `--ape-replay`.
6. **Unknown `--ape` value**: any value outside `{sata, random, replay}` — including `bfs`, `dfs`, and `ape` — aborts with a diagnostic naming the valid set. The silent `SataAgent` fallback SHALL NOT exist.

#### Scenario: unknown key aborts before step 1

- **WHEN** `ape.properties` contains `ape.graphStableRestartThreshld=200` (typo)
- **THEN** the process SHALL exit nonzero with `[APE-RUNSPEC-ABORT] reason=unknown_key key=ape.graphStableRestartThreshld`
- **AND** no exploration step SHALL have run and no agent SHALL have been constructed

#### Scenario: invalid type aborts instead of degrading

- **WHEN** `ape.properties` contains `ape.doFuzzing=ture`
- **THEN** the process SHALL abort with `reason=invalid_type`, instead of silently reading `false` as today

#### Scenario: missing dependency aborts

- **WHEN** `ape.properties` contains `ape.activityTriggerEnabled=true` and no `ape.mopDataPath`
- **THEN** the process SHALL abort with `reason=missing_dependency` naming `ACTIVITY_TRIGGER` and `MOP`

#### Scenario: invalid combination aborts

- **WHEN** the process is launched with `--ape replay` and no `--ape-replay` argument
- **THEN** the process SHALL abort through the same `[APE-RUNSPEC-ABORT]` path (no ad-hoc `System.exit`)

#### Scenario: unknown agent type aborts loudly

- **WHEN** the process is launched with `--ape bfs`
- **THEN** the process SHALL exit nonzero with a diagnostic naming the valid set `{sata, random, replay}`
- **AND** no `SataAgent` SHALL be silently constructed

#### Scenario: retired kill-switch key aborts with its decision

- **WHEN** `ape.properties` contains `ape.apePureMode=true` (a hand-written file — after the stage-2 Python edit no arm pushes the key, at either value)
- **THEN** the process SHALL abort with `reason=retired_key key=ape.apePureMode` and a detail referencing structural purity (a feature absent from the plan does not exist)

#### Scenario: retired bottom boundary band aborts with its reason

- **WHEN** `ape.properties` contains `ape.llmBoundaryBottomPct=0.94`
- **THEN** the process SHALL abort with `reason=retired_key key=ape.llmBoundaryBottomPct`
- **AND** the detail SHALL state that the display excludes the navigation bar and that the status-bar band `ape.llmBoundaryTopPct` is the only boundary band

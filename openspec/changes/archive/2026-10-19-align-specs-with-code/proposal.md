## Why

The main specs in `openspec/specs/` drifted from the code on `master` (`23c772c1`) across several changes. A
four-part audit (2026-10-19) found about 150 claims the code no longer bears out:
- log lines the jar stopped writing when the NDJSON `EventSink` replaced them;
- `Config` fields deleted by D-B24 and classes or methods that were renamed or removed;
- jar-side parsing that moved to the host-side generator (format 2);
- duplicate invariant ids, and 58 ids cited but never defined in a main spec;
- requirement titles that no longer match their bodies.

The code is the reference. This change makes the specs describe it, and changes no code.

## What Changes

- Requirement blocks: MODIFIED (stale names, log lines, defaults and behaviour descriptions), RENAMED (titles that
  name a retired mechanism), REMOVED (requirements for mechanisms that do not exist, e.g. "BFS and DFS Traversal
  Modes"). No requirement asks for new behaviour.
- Preambles (Purpose / Data Contracts / Invariants): stale invariants restated against the code; duplicate ids
  renumbered; invariants cited but defined only in archived deltas restored where they still hold, and their
  citations removed where the mechanism is gone. `openspec archive` does not apply preambles, so they are
  hand-synced at archive.
- `openspec/specs/README.md`: capability list and descriptions brought up to the 24 specs.
- No change to `src/`, tests, `CLAUDE.md`, or rvsec. Every drift item whose resolution would need a code change is
  recorded as open in `design.md` (Open Questions), and the spec describes the code as it is.

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
Every one of the 24 main specs except where the audit found nothing to change:
- `action-selection`, `decision-pipeline`, `scoring-pipeline`, `exploration`, `parity-oracle`, `guidance-groups`:
  `[APE-STEP]` invariants, priority-tiebreak title, epsilon bounds as plan keys, undefined INV-DP ids, RNG streams,
  fuzz-toss timing, Budget channel, RandomAgent roulette, BFS/DFS removal, `ScriptedLlm` stub.
- `mop-guidance`, `wtg-navigation`, `static-analysis-entrypoints`, `component-triggering`, `activity-budget`,
  `form-completion`: host-side parsing (INV-WTG-01/03, INV-MOP-20/25), `StaticGuiModel` accessor names,
  `StaticArtifactLoader.load`, MOP density distance branch, component-trigger signatures, INV-CT-15/18 duplicates,
  "logged" clamps, `widgetCount`.
- `llm-routing`, `llm-infrastructure`, `llm-prompt`, `event-sink`, `run-spec`, `aperv-tool`: `[APE-LLM-*]` lines,
  `LlmRouter` → `CoordinateMapper`/`LlmTelemetry`, mode labels as written, `RUN_END` counters, `RunContext`
  ownership, INV-RUN-07/08 duplicates, system-properties wording.
- `model`, `naming`, `ui-tree`, `ui-coverage`, `build`: `ActionType` count, `evolveModel` as a plan key and its gate
  site, `ScrollType` values, flags cleared before the `resetActions` throw, coverage dump boundary, source count and
  `pom.xml` facts.
- `heuristic-input`: none beyond invariant ordering (no delta unless a citation fix requires one).

## Impact

- Files: `openspec/specs/*/spec.md` (at archive), `openspec/specs/README.md`, this change's directory.
- Code, tests, jar, presets, telemetry: unchanged. `mvn clean test` stays at 1453 / 24 skipped.
- Cross-repo: rv-android specs and code are not touched. Several ids cited in these specs (INV-APV-*, INV-INS-*,
  INV-ANA-*, INV-PLT-*) belong to other repositories and stay as cross-references.
- Source comments in `src/` that cite renumbered or restated ids (e.g. `MonkeySourceApe` citing INV-CT-18 for the
  null-tree relaunch) become stale; they are listed in Open Questions and left for a code change.

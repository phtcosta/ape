<!-- Subagent dispatch hints:
     - Group 1 (delta specs) was written by four parallel writers, one per capability group (A–D).
     - Groups 2–5 are sequential and run by the lead.
     - This change touches ≈ 24 spec files and no source file. -->

## 1. Delta specs (one writer per capability group)

- [x] 1.1 Group A deltas: action-selection, decision-pipeline, scoring-pipeline, exploration, parity-oracle, guidance-groups (INV-DP restore, INV-SEL-04 dedup, INV-ORA-02/07, fuzz-toss timing, Budget channel, BFS/DFS removal, RandomAgent, titles)
- [x] 1.2 Group B deltas: mop-guidance, wtg-navigation, static-analysis-entrypoints, component-triggering, activity-budget, form-completion (host-side parsing, accessor names, INV-CT-15/18→19, INV-MOP/CT restore, titles)
- [x] 1.3 Group C deltas: llm-routing, llm-infrastructure, llm-prompt, event-sink, run-spec, aperv-tool (`[APE-LLM-*]` lines, `LlmRouter`, mode labels as written, `RUN_END` counters, INV-SNK/RTR/LLM/APERV restore, INV-RUN-07/08 dedup)
- [x] 1.4 Group D deltas: model, naming, ui-tree, ui-coverage, build (ActionType count, `evolveModel`, `ScrollType`, `resetActions` flags, coverage boundary, build facts, INV-MODEL-18…20 and INV-TREE-13 restore)
- [x] 1.5 Lead review: re-check a sample of each writer's restatements against the code; `openspec validate align-specs-with-code --strict` → 0 errors
- [x] 1.6 Present the deltas to the author; wait for approval before group 2

## 2. Non-spec spec-tree file

- [x] 2.1 Update `openspec/specs/README.md`: list the 24 capabilities with current one-line descriptions (D8)

## 3. Archive

- [x] 3.1 Snapshot `openspec/specs/` to the scratchpad (`specs_before`)
- [x] 3.2 `openspec archive align-specs-with-code -y`; record the +/~/−/→ counts
- [x] 3.3 Snapshot the archive output (`specs_archived`)

## 4. Preamble hand-sync (D4)

- [x] 4.1 Apply each delta's `## Purpose` / `## Data Contracts` / `## Invariants` edits to its main spec (amended/restored/new in full; retired deleted)
- [x] 4.2 Requirement-block identity check: `reqs.py specs_archived/<cap> openspec/specs/<cap>` reports `same` for all 24
- [x] 4.3 Id check: no `- **INV-…**` id defined twice; every local id cited in a main spec is defined in one

## 5. Verification

- [x] 5.1 `openspec validate --specs --strict --json`: 0 errors; report the warning count by message (baseline 203 long-text warnings)
- [x] 5.2 `git diff --stat -- src/ CLAUDE.md` is empty
- [x] 5.3 Drift greps re-run: no `[APE-STEP|LLM-TEL|LLM-ERROR|LLM-CONFIG-ACK|MOP-DATA]` requirement (historical mentions only); no `Config.<x>` that `Config.java` lacks
- [ ] 5.4 Ask the author before committing (`docs(openspec): …`, explicit paths, no trailer) and again before pushing

## Archive record (2026-10-19)

- 3.2: `openspec archive` totals + 0, ~ 92, − 2, → 8.
- 4.1: preambles synced by `sync.py` (scratchpad) plus three hand edits: event-sink's INV-SNK intro sentence, run-spec's coverage paragraph (D5), naming's CEGAR sequence diagram (`evolveModel` gate in `StatefulAgent`).
- 4.2: requirement blocks identical to the archive output in 24/24 specs.
- 4.3: no id defined twice. Cited and undefined: only foreign ids (INV-ANA, INV-APV, INV-DRV, INV-INS, INV-PLT, owned by rv-android/GATOR) and INV-ARCH-06, cited only historically as dissolved.
- 5.1: `openspec validate --specs --strict`: 0 errors, 205 "Requirement text is very long" warnings (baseline 203).
- 5.2: `git status -- src CLAUDE.md` clean.
- 5.3: remaining `[APE-*]` and `Config.<x>` mentions are historical or negative ("SHALL NOT read", "no longer exists").

# APE-RV — Synthesis Analysis Report

**Date:** 2026-03-11
**Author:** Claude Code (orchestrated analysis with parallel subagents + MCP sequential thinking)
**Method:** Ground-truth verification against actual source code + synthesis of 5 LLM analyses

---

## 1. Executive Summary

The artifact set (PRD + 6 OpenSpec specs) is high quality overall but has three categories of issues that must be resolved before implementation begins:

1. **Strategy exposure gap** — The PRD and exploration spec claim 5 strategies (sata, ape, bfs, dfs, random). Only `sata` and `random` are accessible via the `--ape` CLI flag. The `ApeAgent` class exists but is not wired into `createAgent()`. BFS and DFS have no agent implementations at all. This is the most critical documentation error and was only identified by Codex.

2. **Phase 2 features absent from specs** — MODEL_MENU and AndroidX ViewPager detection are planned (Phase 2) but absent from exploration/spec.md, model/spec.md, and ui-tree/spec.md. An implementer reading only those specs would not know what to add.

3. **sataModel.obj type inconsistency** — exploration/spec.md says the serialized file contains a `Graph`. The code serializes a `Model`. model/spec.md correctly says `Model`. This is a confirmed cross-spec inconsistency.

The "CURRENT STATE" question: most specs (exploration, model, naming, ui-tree) accurately describe what is implemented today. The build/spec.md is correct for the current Ant+dx system. The PRD is a mix of current state and planned phases — which is correct for a PRD, but needs clearer phase status markers.

---

## 2. Ground Truth: Current State Snapshot

Verified by reading source code directly (2026-03-11, commit `8f51b99`).

| Dimension | Current State | PRD Claims |
|-----------|--------------|------------|
| Build system | Ant + `dx` + Java 1.7 → `ape.jar` | Maven + `d8` + Java 11 → `ape-rv.jar` (Phase 1, unimplemented) |
| Total Java files | **140** (confirmed) | 140 ✓ |
| Strategy via `--ape` | **sata, random** only | 5 strategies: sata, ape, bfs, dfs, random ✗ |
| `ApeAgent` class | Exists but not wired into `createAgent()` | Claimed as accessible strategy ✗ |
| BFS/DFS agents | No implementation found | Claimed as Phase 2 variants ✗ |
| `MODEL_MENU` | **Not in ActionType enum** | Phase 2 requirement |
| AndroidX ViewPager | **Not detected** (only `android.support.v4.view.ViewPager`) | Phase 2 requirement |
| `MopData` / `MopScorer` | **Do not exist** | Phase 3 requirement |
| `aperv-tool` Python plugin | **Does not exist** | Phase 4 requirement |

### ActionType enum (current, 14 values):
```
PHANTOM_CRASH, FUZZ, EVENT_START, EVENT_RESTART, EVENT_CLEAN_RESTART, EVENT_NOP,
EVENT_ACTIVATE, MODEL_BACK, MODEL_CLICK, MODEL_LONG_CLICK, MODEL_SCROLL_TOP_DOWN,
MODEL_SCROLL_BOTTOM_UP, MODEL_SCROLL_LEFT_RIGHT, MODEL_SCROLL_RIGHT_LEFT
```

### Package file counts (actual vs PRD):
| Package | Actual | PRD says |
|---------|--------|---------|
| `com.android.commands.monkey` | **33** | 28 |
| `ape` | **13** | 14 |
| `ape.agent` | **5** | 5 ✓ |
| `ape.model` | **21** | 22 |
| `ape.model.xpathaction` | **6** | 6 ✓ |
| `ape.naming` | **38** | 28 |
| `ape.tree` | **6** | 7 |
| `ape.events` | **11** | 10 |
| `ape.utils` | **7** | 7 ✓ |

The `ape.naming` count discrepancy (38 vs 28 claimed) is the largest. Total = 140 confirmed.

---

## 3. What the 5 LLM Analyses Got Right (Convergent Findings)

All five analyses independently identified:
- build/spec.md documents Ant+dx (current), not Maven+d8 (target)
- MODEL_MENU missing from specs
- AndroidX ViewPager missing from ui-tree/spec.md
- Phase 1 Gantt `:done` marker is wrong (no pom.xml exists)
- aperv-tool/spec.md and mop-guidance/spec.md are empty placeholders
- INV-EXPL-03 numbering skip (confirmed: numbering goes 02→04)

These are settled facts. No further verification needed.

---

## 4. What Our Analysis Adds (Beyond the 5 LLM Analyses)

### 4.1 Strategy Exposure Gap (Critical — from Codex, not in others)

Only Codex identified this, and our subagent confirmed it with exact code:

```java
// Monkey.java line 1580:
usage.append("              [--ape [AGENT_TYPE(random,sata)]]\n");

// ApeAgent.createAgent() handles only:
// "sata" → SataAgent
// "random" → RandomAgent
// "replay" → ReplayAgent
// null/default → SataAgent
```

**BFS and DFS**: No agent implementations found. The PRD claims `bfs` maps to `StatefulAgent with BFS queue` and `dfs` to `StatefulAgent with DFS stack`. These do not exist in the codebase.

**ApeAgent class**: Exists as a class but is NOT wired into `createAgent()`. It is never returned from the factory method.

**Impact**: PRD FR05 describes 5 strategies as implemented. Three are fictional. This affects:
- exploration/spec.md (lists all 5 strategies in requirements)
- PRD FR05 requirement heading
- CLAUDE.md ("5 exploration strategies")

**Correct statement for current state**: APE-RV currently supports 3 strategies:
- `sata` (SataAgent) — primary
- `random` (RandomAgent) — priority-weighted, NOT pure random
- `replay` (ReplayAgent) — via `ape.replayLog` config, not `--ape` flag

### 4.2 RandomAgent Is Not Pure Random (Codex finding, confirmed)

The PRD describes RandomAgent as "uniform random action selection without state tracking." This is incorrect:
- `RandomAgent` extends `StatefulAgent`
- It uses priority-weighted sampling via `State.randomlyPickAction()`
- `adjustActionsByGUITree()` applies unvisited bonuses and transition heuristics

**Impact**: Experimental comparisons against "random baseline" in rv-android Experiment 01 are not pure random. This is a research validity concern for RQ-APE-1 and RQ-APE-2.

### 4.3 sataModel.obj Type Inconsistency (Codex found, our subagent confirmed)

- `exploration/spec.md` line 26: serialized `Graph` object
- `model/spec.md`: serialized `Model` object
- Code (`StatefulAgent.saveGraph()`): `oos.writeObject(model)` — serializes `Model`

The exploration spec is wrong; model/spec and code agree. This needs a one-line fix in exploration/spec.md.

### 4.4 Package Count Discrepancy (new finding)

PRD Section 4.2 package table is inaccurate. Most egregious: `ape.naming` has 38 files, not 28. The total of 140 is correct but the per-package breakdown is wrong.

### 4.5 Namelet Is Already Defined (corrects Claude analysis)

The Claude LLM analysis identified "Namelet definition missing from naming/spec.md" as a gap. This is INCORRECT. naming/spec.md lines 8-9 contain:

> "Each `Namelet` binds an XPath selector (identifying which widgets in the tree to match) to a `Namer` strategy (defining how to compute an abstract name for each matched widget)."

This is a clear formal definition. The Claude analysis was wrong on this point.

### 4.6 StatefulAgent MOP Injection Point Exists (verified)

`StatefulAgent.adjustActionsByGUITree()` calls `action.setPriority()` at 3 points. There is no MOP code yet, but the structure is the correct injection point. The PRD's description of FR12 (inject at `adjustActionsByGUITree()`) matches the code structure.

---

## 5. Artifact-by-Artifact Status

| Artifact | Describes Current State? | Phase 2+ Gaps | Unique Issues |
|----------|--------------------------|---------------|---------------|
| **PRD.md** | Mix: current + planned | MODEL_MENU, ViewPager2 absent from text as "not yet" | File counts wrong; strategy claim wrong; Gantt `:done` wrong |
| **build/spec.md** | YES — describes Ant+dx correctly | N/A (Phase 1 will need new spec) | Does not describe Phase 1 target |
| **exploration/spec.md** | Mostly yes | MODEL_MENU not in flowchart; strategy list incomplete | sataModel.obj wrongly called "Graph"; INV-EXPL-03 missing |
| **model/spec.md** | YES — accurate | MODEL_MENU not in INV-MODEL-01/03 | INV-MODEL-03 "seven" will be wrong after Phase 2 |
| **naming/spec.md** | YES — most accurate spec | None | Namelet IS defined (Claude analysis error) |
| **ui-tree/spec.md** | YES | AndroidX ViewPager absent; MODEL_MENU absent from blocklist | No diagrams |
| **aperv-tool/spec.md** | YES (placeholder = Phase 4 unimplemented) | Full spec needed before Phase 4 | — |
| **mop-guidance/spec.md** | YES (placeholder = Phase 3 unimplemented) | Full spec needed before Phase 3 | — |

---

## 6. Prioritized Fix Plan

Ordered by blocking impact on the phased implementation.

### Priority 0 — Correctness fixes (before any implementation)

These are factual errors in the current documentation that mislead about the current state:

| Fix | Artifact | Issue |
|-----|----------|-------|
| F-01 | PRD.md | Remove Phase 1 `:done` Gantt marker |
| F-02 | PRD.md | Update FR05: BFS/DFS are Phase 2 planned, not current. "ape" strategy needs investigation. |
| F-03 | PRD.md | Fix package file counts (especially ape.naming: 28→38) |
| F-04 | PRD.md | Clarify RandomAgent as priority-weighted, not uniform random |
| F-05 | exploration/spec.md | Fix sataModel.obj: change "Graph" to "Model" |
| F-06 | exploration/spec.md | Renumber: add INV-EXPL-03 or note it was intentionally removed |

### Priority 1 — Must fix before Phase 1 implementation

| Fix | Artifact | Issue |
|-----|----------|-------|
| F-07 | build/spec.md | Add Maven+d8 target spec (either new spec or replace existing). The current Ant spec is accurate as "legacy baseline" but there's no Phase 1 target spec. |

### Priority 2 — Must fix before Phase 2 implementation

| Fix | Artifact | Issue |
|-----|----------|-------|
| F-08 | exploration/spec.md | Add MODEL_MENU to ActionType table and SataAgent flowchart |
| F-09 | model/spec.md | Update INV-MODEL-01 (add menuAction) and INV-MODEL-03 (eight, not seven) |
| F-10 | ui-tree/spec.md | Add INV-TREE-08/09 for AndroidX ViewPager classes; add MODEL_MENU to blocklist |
| F-11 | PRD.md | Add BFS/DFS as explicit Phase 2 deliverables (if planned) |

### Priority 3 — Must fix before Phase 3 and 4

| Fix | Artifact | Issue |
|-----|----------|-------|
| F-12 | mop-guidance/spec.md | Write full spec from PRD FR11-FR13 |
| F-13 | aperv-tool/spec.md | Write full spec from PRD FR14-FR17 |

### Priority 4 — Quality improvements (non-blocking)

| Fix | Artifact | Issue |
|-----|----------|-------|
| F-14 | model/spec.md | Add class/component diagram |
| F-15 | ui-tree/spec.md | Add GUITree class diagram |
| F-16 | PRD.md | Fix FR15 title ("Four Supported Variants" → document alias clearly) |

---

## 7. SDD Track Recommendations per Fix Group

| Fix Group | Changes | SDD Track | Entry Point |
|-----------|---------|-----------|-------------|
| F-01 to F-06 | Documentation corrections (no behavior change) | Quick Path | `/opsx:new --schema sdd-quick-path` |
| F-07 | New build spec (Maven+d8 target) | Fast-Forward | `/opsx:ff` |
| F-08 to F-11 | Pre-implementation spec updates for Phase 2 | Quick Path | `/opsx:new --schema sdd-quick-path` |
| F-12 to F-13 | Write Phase 3+4 specs | Fast-Forward | `/opsx:ff` per spec |
| F-14 to F-16 | Diagram additions | Quick Path | `/opsx:new --schema sdd-quick-path` |

Phases F-01 to F-06 (Priority 0) can be batched into a single Quick Path change. They are purely mechanical corrections with clear evidence.

---

## 8. The Strategy Question — Recommended Resolution

The BFS/DFS/ApeAgent strategy discrepancy needs a decision before PRD can be fixed:

**Option A — BFS/DFS are Phase 2 features (not yet implemented)**
Update PRD FR05 to say: "Current: sata, random. Phase 2 adds: bfs, dfs, ape." This is the most honest representation of current state and aligns with the phased roadmap.

**Option B — ApeAgent is wired via config override**
If `ApeAgent` can be activated by setting `ape.agentType=ape` via `ape.properties` (even though `createAgent()` doesn't have a case for it), then it's partially accessible. Needs code verification of the full config-to-agent path. If true, PR should note it's undocumented.

**Recommendation**: Option A. The `createAgent()` code is definitive — no case exists for "ape", "bfs", or "dfs". The PRD should reflect this. The SataAgent-based exploration (which uses activity navigation heuristics) is the primary tested mode. BFS/DFS can be added in Phase 2.

---

## 9. Conclusion

The APE-RV artifact set is well-structured and largely accurate for the **current system state**. The naming, model, and ui-tree specs correctly document the existing APE codebase. The main gaps are:

1. **Strategy overclaiming** (3 strategies described that don't exist)
2. **Phase 2 features missing from specs** (expected — but must be added before Phase 2 starts)
3. **sataModel.obj type error** in exploration/spec.md
4. **Package counts wrong** in PRD

All Priority 0 fixes (F-01 to F-06) can be done immediately in a single Quick Path change. The Phase 1 target build spec (F-07) should be written as a Fast-Forward change using the PRD FR01-FR03 as input.

The instruction "DEVEMOS DOCUMENTAR O ESTADO ATUAL DO SISTEMA" is largely satisfied by the existing specs — they do document the current state. The action needed is to **clearly mark what is current vs. planned** and **fix the factual errors** identified above.

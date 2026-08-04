# Tasks: rearch-06-memory-surgical

**Worktree** (decided 2026-08-03): all 7 stages are implemented in a single git worktree on branch `rearch` (`git worktree add ../ape-rearch -b rearch`), merged into `master` only after stage 7. Setup, what the worktree inherits, and the `mvn install` caveat: `docs/20260803_procedimento_worktree_rearch.md`. The parity runs that close each fix group below use the goldens committed by `rearch-01` on that branch (procedure doc §5).

Group order is the risk order fixed by the proposal and design: the caller audit gates everything (report Sec. 11: "checagem de callers antes; paridade de sequência de ações depois"), then the three fixes land smallest-blast-radius first (V12 → V11 → V24), each closed by its own unit tests — the evidence — plus a `rearch-01` golden run as a ladder regression floor before the next one starts (design D4: the goldens do not execute the changed paths, so they are a floor, never the proof). **Every fix group is conditional on its audit task**: if re-verification finds a consumer the design's tables do not classify, the group STOPS and `design.md` is amended first — no fix is applied over an unmapped semantic path.

## 1. Preconditions and caller-audit ratification

The design's audit tables (design.md, "Caller Audit" sections) were produced at HEAD `5dcf225`. Stages 2–5 will have moved the code by apply time; each row must be re-verified against the implementation HEAD, producing a confirmed (or amended) audit row per consumer class.

- [x] 1.1 **Ordering precondition (gate for group 3)**: verify `rearch-02-runspec` and `rearch-04-step-ndjson-telemetry` are applied — `saveGraph`/`readGraph`/`sataModel.obj` and `saveActionHistory`/`action-history.log` absent from `src/main/java` (grep). If either survives, group 3 (V11) is **blocked** — record the finding and re-sequence; groups 2 and 4 may proceed
- [x] 1.2 **Audit row — `actionHistory`/`ActionRecord` consumers (gate for group 3)**: re-run the enumeration (`grep -rn "actionHistory\|ActionRecord\|getActionHistory\|appendToActionHistory\|updateActionHistory" src/main/java reducer/`); confirm design table A is exhaustive at implementation HEAD — expected residue after 1.1: writers, `updateModel` remap loop, `recoverCurrentState`, and the unrelated LLM `_actionHistory` window. Any NEW reader of deep records → classify diagnostic vs semantic; a semantic reader beyond the depth-1 recovery tail STOPS group 3
- [x] 1.3 **Audit row — resolved-object consumers (gate for group 4)**: re-run the enumeration (`grep -rn "getResolvedGUITreeAction\|getResolvedGUITree\|getResolvedNode\|getResolvedNodes\|resolvedTree\|isResolvedAt\|resolveAt" src/main/java`); confirm design table B classes still hold: guarded same-step (B1), unguarded same-step (B2/B3 with the `:732`-before-`:733` ordering proof re-checked), cross-step scalar `resolvedSaturation` only (B4), dead code (B5 — `Model.update(ModelAction)` single-arg and `isOverAbstracted` still caller-free). Any reader of resolved **references** across steps or into released trees STOPS group 4
- [x] 1.4 **Audit row — replay/reducer**: confirm `reducer/` remains outside the Maven build and `ReplayAgent` remains the sole `refreshNewState` caller; record both rows as ratified in the change notes

## 2. V12 — release the naming caches with the tree (conditional on 1.4 only; no stage-2/4 dependency)

- [x] 2.1 `GUITreeBuilder`: re-key `namingToGUITreeNodeCache` to `Naming → GUITree → (GUITreeNode → Name)`; `getNodeName(naming, tree, node)` (which already receives the tree) does the two-level lookup — no signature change
- [x] 2.2 `GUITreeBuilder.release(removed)`: sweep all **three** static caches, removing the `removed` slice under **every** naming (iterate the outer maps), unconditionally and before the `currentNaming == null` early return; keep `naming.release(removed)` under its current guard (INV-TREE-13)
- [x] 2.3 `StatefulAgent.checkAndRefreshNewState`: compute `isTopNamingEquivalent(removed, last)` **before** invoking the release cycle (kills the re-insertion of a released tree into the caches at the HEAD `:689`/`:692` ordering); pure computation, decision-neutral by construction
- [ ] 2.4 JVM unit tests (rearch-01 fixture kit): release empties all three cache slices for the tree; entries under a non-current naming are also removed; the recheck sequence never re-caches a released tree; a live tree's evicted entry recomputes to an equal value
- [ ] 2.5 `mvn test` green (the group's release-cycle unit tests are the neutrality evidence); **ladder regression floor**: re-run the rearch-01 golden suite for every target preset — byte-identical, and expected green by construction since the oracle builds and releases no GUITrees (design D4)

## 3. V11 — action history to identifiers + minimal snapshot (conditional on 1.1 AND 1.2)

**BLOCKED at 2026-08-04 by task 1.1** (finding recorded in `design.md`, "Audit Ratification at Implementation HEAD"): `rearch-04-step-ndjson-telemetry` has not landed, so consumer A1 (`Model.saveActionHistory` `Model.java:97` / `StatefulAgent:1908-1910`, teardown call `:1863`) is still live and still re-resolves every deep record through the rich `GUITreeAction`. Task 1.2's gate is satisfied — table A is exhaustive and holds — so this group unblocks the moment stage 4 deletes A1, with no further audit work. Groups 2 and 4 proceed meanwhile, as 1.1 provides.

- [ ] 3.1 `Model.ActionRecord` → snapshot shape (design D2): `clockTimestamp`, `agentTimestamp`, action type name, state id (null for non-model), target XPath (null for targetless), tree id + tree timestamp, throttle — primitives/strings only, no `Action`/`GUITreeAction`/`GUITree`/`GUITreeNode` references (INV-MODEL-18); drop `Serializable` and delete `resolveModelAction` with its last consumer already gone (P3; precondition 1.1)
- [ ] 3.2 `Model.appendToActionHistory` (same signature): build the snapshot from the action's still-valid resolved objects at append time; maintain the depth-1 recovery point by the three D2 rules (`canStartApp` → blocked; model action → set + unblock; other → no-op), checked in the HEAD scan's precedence (`canStartApp` before `isModelAction`)
- [ ] 3.3 `StatefulAgent.recoverCurrentState`: read the recovery point (recover iff present and not blocked) instead of scanning the list; `StatefulAgent.updateModel`: delete the per-record remap loop and `Model.updateActionHistory` (P3); remap the recovery point via the existing `model.update(ModelAction, GUITreeAction)` discipline, **carrying the deleted loop's `requireTarget()` condition** (`:308`) so a targetless recovery point stays un-remapped exactly as at HEAD (design D2 — the unconditional remap is a behavior change this stage does not make)
- [ ] 3.4 JVM unit tests: snapshot record holds no object references; recovery-point equivalence with the HEAD backward scan over the append-sequence classes — `[model]`, `[model, start, fuzz]`, `[start, model, fuzz]`, `[fuzz-only]`, empty, and a crash record (non-model, null guiAction → rule-3 no-op); **the remap contrast pair**: a targeted model action as recovery point IS remapped across a rebuild, a targetless one (`MODEL_BACK`) is NOT and still resolves to the pre-rebuild object. The pair is what makes a future change of that guard a visible edit
- [ ] 3.5 `mvn test` green (the group's unit tests are the neutrality evidence); **ladder regression floor**: rearch-01 golden suite byte-identical — expected green by construction, since the oracle never runs the history or recovery paths (design D4), so a green run here confirms nothing about this group beyond the ladder

## 4. V24 — release resolved references with their tree (conditional on 1.3)

- [x] 4.1 `ModelAction.releaseResolved(GUITree released)`: iff `resolvedTree == released` (reference identity) null `resolvedTree`/`resolvedGUITreeAction`/`resolvedNode`/`resolvedNodes` and set the resolve timestamp to `-1` (`isResolvedAt` false for every timestamp); MUST NOT touch `resolvedSaturation` (cross-step semantic scalar, audit B4) nor priority/boost/provenance fields; no-op for a different tree (INV-MODEL-19)
- [x] 4.2 `Model.release(removed)`: after `namingManager.release(removed)`, sweep `removed.getCurrentState().getActions()` (null-guarded) calling `releaseResolved(removed)` — both existing release call sites (`checkAndRefreshNewState`, replay's `refreshNewState`) are covered with no new wiring, in the same cycle as the V12 cache sweep
- [x] 4.3 JVM unit tests: refs into the released tree cleared + `isResolvedAt` false; saturation preserved; refs into the surviving latest tree untouched; unresolved action is a safe no-op; a guarded reader skips the cleared action exactly as it skips any stale resolve
- [x] 4.4 `mvn test` green (the group's unit tests are the neutrality evidence); **ladder regression floor**: rearch-01 golden suite byte-identical — expected green by construction (design D4)

## 5. Heap observation — before/after on device (measurement only, no gate)

**Owner-executed** standalone validation path (`scripts/run_emulator.sh` + `test-apks/cryptoapp.apk`), per design D5: heap sampling needs `dumpsys meminfo` against a live run, which the rv-platform tool path does not expose, so this group is run by the owner. The assistant never starts, stops or manages an emulator. This produces the observation the change is judged by and the baseline any future profiling-gated bound would need; it is not a CI gate and promises no threshold.

- [ ] 5.1 Build the **pre-fix** jar (commit before group 2) and run 600 s standalone SATA (`--running-minutes 10`), sampling `adb shell dumpsys meminfo <monkey pid>` every 60 s; record the Dalvik Heap Alloc/Size series
- [ ] 5.2 Build the **post-fix** jar (after group 4) and repeat with the same seed/configuration; best-effort `am dumpheap` at 600 s on both runs for retention-root inspection (may be unavailable for a shell-uid `app_process` — the meminfo series is the primary observation)
- [ ] 5.3 Record both series, the end-of-run delta, and the retention-root notes in the change's verification notes; no threshold asserted — the deliverable is the comparison
- [ ] 5.4 Update the `CLAUDE.md` known-issue note to the narrowed current state (trees still retained by `treeHistory` by design; the V11/V12/V24 retainers closed) — current state only, no history (P4)

## 6. Verification

- [ ] 6.1 `mvn package` succeeds; jar builds clean with no vendored-JAR leakage (build contract unchanged)
- [ ] 6.2 Full `mvn test` green, including all new unit tests from groups 2–4
- [ ] 6.3 Assemble the acceptance evidence for INV-MODEL-20 (report Sec. 9 test 10) as what it is: the ratified caller audits plus the unit tests of groups 2–4, which execute the three changed paths, and — recorded beside them, not as the proof — a final rearch-01 golden run across all target presets, byte-identical, confirming the decision ladder is unchanged. State the reach of each in the verification notes so a later reader cannot mistake the golden run for retention evidence (design D4)
- [ ] 6.4 Confirm the audit trail: tasks 1.1–1.4 outcomes recorded; every conditional group either applied or stopped-with-reason — no fix landed without its ratified audit row
- [ ] 6.5 Run `/sdd-verify` (jar module) — tests/lint/complexity checkpoint
- [ ] 6.6 Invoke `/sdd-code-reviewer` via Skill tool over the change's diff
- [ ] 6.7 `openspec validate rearch-06-memory-surgical` clean; artifacts coherent with the implemented state

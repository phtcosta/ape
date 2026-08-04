# Tasks — rearch-05-thin-python-arms

**Worktree** (decided 2026-08-03): all 7 stages are implemented in a git worktree on branch `rearch` (`git worktree add ../ape-rearch -b rearch`), merged into `master` only after stage 7. Setup, what the worktree inherits, and the `mvn install` caveat: `docs/20260803_procedimento_worktree_rearch.md`.

**Rewritten 2026-08-04.** The predecessor carried 52 tasks, 47 of which drove rv-android work from this repository against a stale picture of it — 27 surviving arms where `gh95-thin-python-arms` had already shipped 8, a 52-pair mapping where the module has 50, and deletion tasks for constants (`ARM_DEFINING_KEYS`, `LLM_ARM_KEYS`, `_CAL_LLM_COMMON`, `_APE_PURE_ARM_FLAGS`) that were already gone. Nineteen arms it instructed an implementer to *re-express* had been deleted. It also minted `INV-APV-40`…`44` in rv-android's invariant namespace, conflicting with `gh95`'s definitions of the same IDs. See `proposal.md` for the divergence table and `design.md` D1/D2 for why the fix is structural rather than arithmetic.

**Scope of this change, stated rather than discovered**: the rv-android side belongs to `gh95` and is done — no task below edits that repository. The jar is not modified; no `src/main` file is touched and the deployed binary is byte-identical. What lands here is a **test-tree deletion** plus two spec de-framings. Deleting a test edits the ape repo without changing the jar.

## 1. Confirm the counterpart before editing (read-only, no rv-android edits)

- [ ] 1.1 Re-read the module source rather than this change's snapshot — the discipline whose absence produced the rewrite. In `rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py`, confirm: `get_variants()` returns the 8 names (`default`→`sata`, `sata_mop`, `sata_llm`, `sata_mop_llm`, `mop_on_llm_off`, `mop_off_llm_off`, `mop_on_llm_70`); `APERV_PROPERTY_MAPPING` has 50 pairs; `ARM_DEFINING_KEYS`/`LLM_ARM_KEYS`/`_CAL_LLM_COMMON`/`ape_pure`/`sata_mop_widget`/`cal_a*` are absent. If any of this has moved, correct `design.md`'s Context block **before** touching a test — the numbers there are a snapshot with a date on it, not a contract
- [ ] 1.2 Confirm `gh95`'s state and its remaining gates: it was 37/44 on 2026-08-04 with only group 7 pending (final regeneration diff 7.1, lint, verify, review, docs-sync, owner sign-off 7.6, counterpart closure 7.7). **Task 2.x is gated on `gh95` task 7.1 having run green** — the regeneration diff is what replaces the fixtures this change deletes (design D3), so deleting them while the diff is unproven removes a net before its replacement is demonstrated, not after
- [ ] 1.3 Note for whoever runs this: `gh95` task 7.7 marks the *predecessor's* task 8.5a satisfied. That task number no longer exists in this file — the cross-repo OpenSpec instrument it described is `gh95` itself, which exists. Confirm with the owner that `gh95` 7.7 is understood to close against this change as rewritten, and record the answer here rather than leaving the two task lists pointing at different numbers

## 2. Retire stage 2's transitional Python-contract scaffolding

Stage 2 pinned the jar against the *pre-change* Python output so it could deploy without touching `tool.py` (`rearch-02-runspec` group 6). `gh95` rewrote that output, so the pins now assert a contract that no longer exists — two of the five fixtures (`sata_mop_widget.properties`, `ape_pure.properties`) pin arms that have been retired outright. They are deleted here, in the stage that invalidates them, not left to rot as a frozen copy of a superseded deployment (P3). Stage 2 declared this death itself: its design says stage 5 replaces the fixtures with the real contract, and its task 6.3 says the pin holds "until stage 5".

- [ ] 2.1 Delete `RunSpecCompatTest.java` (301 lines) and the five per-arm fixtures it pins — `src/test/resources/compat/{sata,sata_llm,sata_mop_llm,sata_mop_widget,ape_pure}.properties` — together with the directory's `README.md`, which documents the byte-for-byte capture and the named `tool.py` it was captured from
- [ ] 2.2 Move the retired-key coverage the fixtures also carried into `RunSpecAbortTest`, whose subject is the retired-key list itself and which was never transitional: `src/test/resources/compat/negative/{ape_pure_mode,mop_weight_activity}.properties`. Either re-home the two fixtures or restate them inline — whichever leaves `RunSpecAbortTest` self-contained. **Do this before 2.1 lands**, so the coverage is never absent from the suite even transiently
- [ ] 2.3 Replace `PresetsTest`'s fixture-equivalence assertions (`:205-250`, every `CompatFixtures.resolve(...)` call site) with tests of the contract this stage establishes: `Presets.resolve(name)` returns the declared base vector, explicit keys override it, and the merged result passes the same validation as an explicit plan — asserted against the preset definitions, **never** against a captured copy of `tool.py`'s output. Keep every assertion in the class that does not read a fixture
- [ ] 2.4 Delete `CompatFixtures.java` once 2.1 and 2.3 leave it unread (it is the fixture loader, and those two are its only callers). Its constants `MOP_DATA_PATH` and `LLM_URL` are still wanted by the rewritten `PresetsTest` — re-home them rather than inlining two magic strings, and check for other readers before deleting rather than trusting this note
- [ ] 2.5 **Confirm, concretely, that nothing is lost.** Before declaring 2.1–2.4 done, enumerate what `RunSpecCompatTest` asserted and show for each item either (a) `gh95`'s typed regeneration diff covers it, or (b) the rewritten `PresetsTest`/`RunSpecAbortTest` covers it. If some assertion falls in neither, **do not delete it** — record the gap and route it to `gh95` (design: Error Handling, row 3). This task is the reason the deletion is safe; do not tick it by assertion
- [ ] 2.6 Delete `rearch-02` task 6.4's verification note ("zero Python changes needed") wherever it is still cited as a live property. After this stage it is a historical fact about stage 2, not a standing guarantee
- [ ] 2.7 Run `/sdd-test-run ape.runtime` (the whole surface of this group lives there)

## 3. Record the correction so stages 6 and 7 do not repeat it

- [ ] 3.1 Append to `docs/plans/20260802_rearchitecture_roadmap.md`: stage 5's ape side is a test-tree deletion, the Python side was delivered by `gh95`, and the numbers that moved (27→8 arms, 2→21 retirements, 52→50 pairs). Current-state wording (P4) — the roadmap is a status log, so the entry is dated, not written as history
- [ ] 3.2 Record the failure mode itself, not just its instance: **an artifact that plans another repository's work from this one will drift, and nothing detects it.** `rearch-07`'s counterpart `gh96` is at 49/55 with the ape side at 0/45 — the same asymmetry at an earlier point, and the next place this can happen. Name it where the stage-7 implementer will read it
- [ ] 3.3 Check whether `rearch-06-memory-surgical` (11/29) carries any task written against the predecessor's arm picture; if so, file the correction there rather than fixing it silently here

## 4. Verification

- [ ] 4.1 `mvn test` — full suite green with the transitional tests gone and the preset-contract tests in their place. Baseline before this change: **1088 tests, 0 failures, 19 skipped**; expect the count to fall by what `RunSpecCompatTest` contributed and to hold otherwise
- [ ] 4.2 Confirm the jar is untouched: `git diff --stat` shows no `src/main` path. A red suite in this stage cannot be a jar regression, and this task is what makes that statement checkable
- [ ] 4.3 Run `/sdd-qa-lint-fix src/test/java`
- [ ] 4.4 Run `/sdd-verify ape`
- [ ] 4.5 Run `/sdd-code-reviewer` over the ape-side diff
- [ ] 4.6 Run `/opsx:verify rearch-05-thin-python-arms` before archiving

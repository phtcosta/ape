# isolate-guidance-groups — evidence

## 11.7 Fixture re-derive with the sister derive (2026-10-17)

- Derive: rv-android worktree `worktrees-aperv/isolate-guidance-groups/rv-android`, HEAD `c38cd624`,
  which contains `482c7cfb`; `derive_mop_artifact.py` is unchanged between the two
  (`git diff --stat 482c7cfb HEAD` empty). Generator `aperv-derive/2`.
- Procedure: the library calls of
  `openspec/changes/archive/2026-10-09-llm-coordinate-single-base/evidence/derive_fixtures.py`
  (`derive`, `serialize_canonical`, `digest_of_file`), run with the worktree's `.venv`
  (`python -I -B`), output to a scratch directory. The archived script itself no longer runs: it
  imports `digest_of_file` from `derive_mop_artifact`, which no longer re-exports it; the wrapper
  imports it from `rv_android_core.util.analysis_document` (where `tool.py:61-63` takes it).
- Result: byte-identical to the committed fixtures, so nothing was rewritten.

| Fixture | Bytes | sha256 (first 16) | vs committed |
|---|---|---|---|
| `test-apks/cryptoapp.apk.mop.json` | 4657 | `8a4103615ce6ca49` | identical |
| `src/test/resources/cryptoapp.apk.mop.json` | 4657 | `8a4103615ce6ca49` | identical |
| `src/test/resources/synthetic-activity-selection.mop.json` | 975 | `413407ff431250e1` | identical |

## Wave 2 notes accepted as-is (recorded, not changed)

- F1 writes `dec.rot` after `updateState`; on the bad-state path it can overwrite the previous
  record's `rot`.
- F1 draws the fuzz toss after the step's events (single consumer, same sequence).
- F3 `dec.nav` may stay stale across an aborted ABA attempt (the spec says MAY).
- F4 `own.g` is cleared when a later LLM stage of the same step calls.

## 11.8 / 11.9 Package and install (2026-10-17)

- `mvn clean test` on HEAD `f588eb61` plus the uncommitted wave-2 tree: 1439 tests, 0 failures, 0 errors, 24 skipped.
  [verified]
- `mvn package`: `target/ape-rv.jar` sha256 `7b334d26451dda07f63909ef7e2025070b563568133a424d00c097769a69b5c4`,
  `BuildInfo.GIT_SHA = f588eb6`. [verified]
- `mvn install -Drvsec_home=<worktrees-aperv/isolate-guidance-groups>`: the installed jar (rebuilt, so `JAR_BUILT`
  differs) has sha256 `2d3f14a68bad02e1650285512ac46be8dec9b5d41b933d547067cd61eebc048c`, `GIT_SHA = f588eb6`, and is
  byte-identical to `target/ape-rv.jar`. [verified]
- F7 module tests against that jar (run by `aperv-tool`): aperv-tool suite 791 passed, 72 skipped; migration with
  `APE_REPO` on this working tree 24 passed + 1 XPASS(strict) `test_preset_names_are_exactly_the_six_cells` (expected:
  the tree has only the six cells; the marker flips against the wave-2 SHA); M.4 against `src/test/resources/cells/`
  50 passed + 2 XPASS(strict); no difference against contract v3.2. [reported]
- Both jars predate the wave-2 commit; the jar handed out for 12.1 is rebuilt after it.

## 12.1 / 12.2 (device smoke, `mini-experimento` short round)

Pending.

# Verification (tasks 6.1, 6.3, 6.4)

## 6.1 Cell fixtures re-captured (2026-11-11 13:49)

```
nice -n 19 ionice -c3 mvn -o test -Dtest=CellRunStartFixtureTest -Dape.oracle.regenerate=true
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

`git diff --stat -- src/test/resources/cells src/test/resources/goldens`: three files, one line each
(`as_mop`, `as_mop_llm`, `mop`); goldens untouched. With `"digest"` masked, each new line equals the
committed one (checked per file), so only the plan digest moved (design D8):

| Cell | Old `digest` | New `digest` |
|---|---|---|
| `mop` | `5e0068112e42a1bc` | `356216783902d09d` |
| `as_mop` | `54ec26904defdb2e` | `d7fb89b0f3dd0dd4` |
| `as_mop_llm` | `b3f5dafc39331ff8` | `d385131f550c9811` |

`params` and `inert` are unchanged: the F1 values are the defaults, so the MOP cells' records carry none of
the four keys, as before. The other three cells (`base`, `as`, `as_llm`) do not mount a feature owning the keys
and did not move.

Log: `/tmp/claude-1000/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-ape/6ee40789-762c-4207-a4ae-a0c26eb4631e/scratchpad/task_6_1_regen.log`

## 6.3 Full suite (2026-11-11 13:51)

```
nice -n 19 ionice -c3 mvn -o clean test
Tests run: 1551, Failures: 0, Errors: 0, Skipped: 28   BUILD SUCCESS
[E7-EQUIV] distanceWeight=12 splitTies=440 nextLevelWeight=5 mismatches=0
```

1551 = 1537 (baseline `a0fb5c16`, task 0.2) + 14 new `@Test` (task 2.3). Skipped 28 as at the baseline.
`git diff --exit-code -- src/test/resources/goldens` is empty.

Log: `/tmp/claude-1000/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-ape/6ee40789-762c-4207-a4ae-a0c26eb4631e/scratchpad/suite_6_3.log`

## 6.4 OpenSpec validation (2026-11-11 13:52)

- `openspec validate gh-tbd-mop-defaults-f1 --strict` → `Change 'gh-tbd-mop-defaults-f1' is valid`.
- `openspec validate --specs --strict` in the worktree → `Totals: 1 passed, 23 failed (24 items)`, exit 1: the
  pre-existing "Requirement text is very long (>500 characters)" warnings, which strict mode counts as failures.
  The output is byte-identical to the same command on the master checkout at `a0fb5c16`, so this change adds no
  warning. (The main specs move only at archive; task 7.5 validates them again after the sync.)

## 6.5 / 6.6 Lint and verify (2026-11-11 13:53–13:56)

- `/sdd-qa-lint-fix`: Checkstyle 8.39 with `/google_checks.xml` (the project has no checkstyle config and no
  lint plugin; jar run offline from the local Maven repository). No auto-fix exists for Checkstyle. 0 errors; the
  warnings on changed lines are the Google defaults (2-space indent, 100-column lines, method-name pattern)
  against the project's own style, all already present at `a0fb5c16`. The two new comment lines over 100
  columns in `src/main` (`KeyOwnership`, `RunSpec`) were re-wrapped.
- `/sdd-verify`: tests `nice -n 19 ionice -c3 mvn -o clean test` → 1551 run, 0 failures, 0 errors, 28 skipped;
  lint 0 errors (5298 style-default warnings over the 25 changed files); complexity: no tool for Java.
  `git diff --check` clean. **overall: pass**.

## After the review fixes (task 6.7, 2026-11-11 14:2x)

`mvn -o clean test` → **1552 run, 0 failures, 0 errors, 28 skipped** (1537 + 15 new `@Test`), BUILD SUCCESS;
goldens unchanged; fixtures still differ only in `digest`. This supersedes the 1551 of 6.3 as the count for task
7.2. Details in `evidence/review.md`.

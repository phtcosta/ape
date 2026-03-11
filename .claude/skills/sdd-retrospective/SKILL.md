---
name: sdd-retrospective
description: "Analyze development patterns from git history and produce a data-driven retrospective report. Use WHEN completing a development cycle, sprint, or milestone. WHEN NOT: for risk assessment (use sdd-risk)."
context: fork
argument-hint: "[time-window]"
---

# Retrospective: $ARGUMENTS

Analyze git history within a time window and produce a structured retrospective report with quantitative metrics, pattern observations, and action items. This skill extracts data from commits, file changes, and test coverage patterns to surface what went well, what went wrong, and what to improve.

This skill is a Tier 3 support component. It runs as a forked subagent and is read-only.

**Invariants:**

- **INV-RETRO-01:** This skill SHALL NOT modify any files or git state. All analysis is read-only.
- **INV-RETRO-02:** Every observation in wentWell and wentWrong MUST cite specific data (commit SHAs, file names, or metric values) as evidence.
- **INV-RETRO-03:** Action items MUST be actionable and specific. Each action item SHALL reference the specific observation it addresses.
- **INV-RETRO-04:** Leaf node constraint. This skill SHALL NOT use the Skill tool or Task tool.

---

## Input

`$ARGUMENTS` contains an optional time window specification. If empty, the skill determines the default window automatically.

**Supported formats:**

| Format | Example | Meaning |
|--------|---------|---------|
| `last N weeks` | `last 2 weeks` | N calendar weeks before today |
| `last N days` | `last 10 days` | N calendar days before today |
| `since vX.Y.Z` | `since v1.0.0` | All commits after the specified tag |
| `since YYYY-MM-DD` | `since 2026-02-01` | All commits after the specified date |
| `(empty)` | | Default: since last tag or 2 weeks, whichever is more recent |

---

## Workflow

### Step 1: Parse Time Window

Determine the `--since` or commit range for git commands based on `$ARGUMENTS`.

**If `$ARGUMENTS` is empty or not provided:**

1. Find the most recent tag:
   ```bash
   git describe --tags --abbrev=0 2>/dev/null
   ```
2. If a tag exists, get its date:
   ```bash
   git log -1 --format=%aI <tag>
   ```
3. Compute the date 2 weeks ago.
4. Use whichever date is more recent (tag date or 2-weeks-ago date) as the `--since` value.
5. If no tags exist, use 2 weeks ago.

**If `$ARGUMENTS` matches `last N weeks` or `last N days`:**

Compute the calendar date N weeks/days before today and use it as `--since`.

**If `$ARGUMENTS` matches `since vX.Y.Z` (a tag):**

1. Verify the tag exists:
   ```bash
   git rev-parse "vX.Y.Z" 2>/dev/null
   ```
2. If the tag exists, use the range `vX.Y.Z..HEAD` for git log commands.
3. If the tag does not exist, report the error and STOP.

**If `$ARGUMENTS` matches `since YYYY-MM-DD`:**

Use the date directly as the `--since` value.

Record the resolved period as `period.from` (date or tag) and `period.to` (today's date or HEAD).

### Step 2: Validate History

Count the commits in the resolved time window:

```bash
git rev-list --count <range-or-since-args> HEAD
```

WHEN the count is fewer than 3
THEN report `InsufficientHistory` (see Error Handling) and STOP.

### Step 3: Collect Metrics

Run the following git commands to extract quantitative data. All commands are read-only.

#### 3.1 Commit Count and List

```bash
git log <range-or-since-args> --oneline --format="%H %s"
```

Store the total commit count as `metrics.commitCount`.

#### 3.2 Files Changed (Unique)

```bash
git log <range-or-since-args> --name-only --format="" | sort -u | wc -l
```

Store as `metrics.filesChanged`.

#### 3.3 Average Commit Size (Files per Commit)

```bash
git log <range-or-since-args> --format="" --name-only | awk 'BEGIN{c=0; f=0} /^$/{c++} /./{f++} END{if(c>0) print f/c; else print 0}'
```

Alternatively, compute: `filesChanged / commitCount` is a rough proxy, but the per-commit count is more accurate. For each commit, count the files it touched:

```bash
git log <range-or-since-args> --format="%H" | while read sha; do
  count=$(git diff-tree --no-commit-id --name-only -r "$sha" | wc -l)
  echo "$count"
done
```

Compute the average of these counts. Store as `metrics.avgCommitSize`.

#### 3.4 Largest Commit

From the per-commit file counts collected above, identify the commit with the highest file count. Store as `metrics.largestCommit` with fields: `sha`, `message`, `fileCount`.

If there is a tie, pick the most recent one.

#### 3.5 Top Churn Files

Find the 5 files changed most frequently across all commits in the window:

```bash
git log <range-or-since-args> --name-only --format="" | sort | uniq -c | sort -rn | head -5
```

Store as `metrics.topChurnFiles` (list of objects with `file` and `changeCount`).

#### 3.6 Test Commit Ratio

Count how many commits include changes to test files. A "test file" is any file whose path matches common test patterns:

- `**/test_*`, `**/*_test.*`, `**/*Test.*`, `**/*Spec.*`
- `**/tests/**`, `**/test/**`, `**/__tests__/**`
- `**/*.test.*`, `**/*.spec.*`

```bash
git log <range-or-since-args> --format="%H" | while read sha; do
  if git diff-tree --no-commit-id --name-only -r "$sha" | grep -qE '(test_|_test\.|Test\.|Spec\.|\.test\.|\.spec\.|/tests/|/test/|/__tests__/)'; then
    echo "has_test"
  fi
done | wc -l
```

Compute: `testCommitRatio = commits_with_tests / commitCount`. Store as `metrics.testCommitRatio`.

#### 3.7 Commit Frequency Distribution

Compute commits per day to identify patterns (bursts vs. steady pace):

```bash
git log <range-or-since-args> --format="%ad" --date=short | sort | uniq -c | sort -rn
```

This data supports analysis in Step 4 but is not a top-level metric.

### Step 4: Analyze Patterns

Using the collected metrics, identify patterns for the three-section report. Each observation MUST cite specific evidence (INV-RETRO-02).

#### 4.1 What Went Well

Evaluate the following positive indicators. Include an observation only when the data supports it.

| Signal | Condition | Example Observation |
|--------|-----------|---------------------|
| Consistent test discipline | `testCommitRatio >= 0.5` | "N of M commits included test changes (ratio: X)" |
| Small, incremental commits | `avgCommitSize <= 5` | "Average commit touched N files, indicating incremental delivery" |
| Steady commit pace | Low variance in daily commit counts | "Commits distributed across N days with no multi-day gaps" |
| Focused changes | No file in `topChurnFiles` exceeds 40% of commits | "Change distribution is spread across the codebase" |
| Active development | `commitCount` is proportional to the time window | "N commits over M days (avg X commits/day)" |

#### 4.2 What Went Wrong

Evaluate the following concern indicators. Include an observation only when the data supports it.

| Signal | Condition | Example Observation |
|--------|-----------|---------------------|
| Low test discipline | `testCommitRatio < 0.3` | "Only N of M commits included test changes (ratio: X)" |
| Large commits | `largestCommit.fileCount > 15` | "Commit <SHA> touched N files: '<message>'" |
| High file churn | A file appears in >40% of commits | "<file> changed in N of M commits (X%), possible hotspot" |
| Bursty development | >50% of commits concentrated in <20% of days | "N commits concentrated on M days, with long gaps" |
| Low avg commit size with many commits | Could indicate excessive micro-commits | Assess contextually |

#### 4.3 Action Items

For each concern identified in 4.2, generate a specific, actionable improvement.

Each action item SHALL have:
- **Priority**: `high`, `medium`, or `low`
  - `high`: The concern affects code quality or delivery predictability directly.
  - `medium`: The concern is a process smell worth addressing.
  - `low`: The concern is minor or contextual.
- **Rationale**: Reference the specific observation from 4.2 that this action addresses.
- **Suggestion**: A concrete next step (not generic advice).

**Examples of specific action items** (use as patterns, not verbatim):
- "Investigate decomposing `src/parser.py` (changed in 15/25 commits) into smaller modules to reduce churn concentration."
- "Consider adding a pre-commit check or workflow step to encourage test changes alongside code changes (test ratio: 0.2)."
- "Review commit `abc1234` (touched 22 files) to determine if it could have been split into smaller changes."

**Do NOT generate generic advice** such as "write more tests" or "make smaller commits" without citing the specific data that motivates it.

---

## Output Format

Return the following structured report.

```markdown
# Retrospective Report

## Period

- **From**: <date or tag>
- **To**: <date or tag>
- **Commits analyzed**: <N>

## Metrics

| Metric | Value |
|--------|-------|
| Total commits | <commitCount> |
| Unique files changed | <filesChanged> |
| Avg files per commit | <avgCommitSize> |
| Largest commit | <sha-short> (<fileCount> files): "<message>" |
| Test commit ratio | <testCommitRatio> (<N>/<M> commits with tests) |

### Top Churn Files

| # | File | Changes |
|---|------|---------|
| 1 | <file> | <count> |
| 2 | <file> | <count> |
| 3 | <file> | <count> |
| 4 | <file> | <count> |
| 5 | <file> | <count> |

## What Went Well

1. **<Title>**: <Observation with specific evidence>
2. **<Title>**: <Observation with specific evidence>

## What Went Wrong

1. **<Title>**: <Observation with specific evidence>
2. **<Title>**: <Observation with specific evidence>

## Action Items

| # | Priority | Action | Addresses |
|---|----------|--------|-----------|
| 1 | high | <Specific action> | <Reference to What Went Wrong item> |
| 2 | medium | <Specific action> | <Reference to What Went Wrong item> |

## Summary

<One paragraph summarizing the key findings: highlight the strongest positive pattern,
the most pressing concern, and the highest-priority action item.>
```

### Field Definitions

| Field | Description |
|-------|-------------|
| `period.from` | Start of the analysis window (date string or tag name) |
| `period.to` | End of the analysis window (date string or "HEAD") |
| `metrics.commitCount` | Total commits in the window |
| `metrics.filesChanged` | Count of unique files changed across all commits |
| `metrics.avgCommitSize` | Mean number of files changed per commit |
| `metrics.largestCommit` | The commit with the most files changed (SHA, message, count) |
| `metrics.topChurnFiles` | The 5 files with the highest change frequency |
| `metrics.testCommitRatio` | Fraction of commits that include test file changes |
| `wentWell` | Positive patterns, each backed by evidence |
| `wentWrong` | Concerns, each backed by evidence |
| `actionItems` | Prioritized improvements, each referencing a specific concern |

---

## Error Handling

### InsufficientHistory

WHEN the specified time window contains fewer than 3 commits
THEN return:

```markdown
# Retrospective Report

## Error: InsufficientHistory

The specified time window contains only **<N>** commit(s). A retrospective requires
at least 3 commits to identify patterns.

**Suggestion**: Expand the time window. Try:
- `last 4 weeks` (instead of `last 2 weeks`)
- `since <earlier-tag>` (instead of the current tag)
- Remove the time window argument to use the default
```

AND STOP. Do not attempt partial analysis.

### InvalidTag

WHEN `$ARGUMENTS` specifies a tag (e.g., `since v1.0.0`) that does not exist
THEN return:

```markdown
# Retrospective Report

## Error: InvalidTag

Tag `<tag>` not found in this repository.

**Available recent tags**:
<list up to 5 most recent tags using: git tag --sort=-creatordate | head -5>
```

AND STOP.

### NoGitRepository

WHEN the current directory is not inside a git repository
THEN return:

```markdown
# Retrospective Report

## Error: NoGitRepository

The current directory is not inside a git repository. This skill requires git history to analyze.
```

AND STOP.

---

## Constraints

- This skill is read-only. It SHALL NOT create, write, modify, or delete any file or git state (INV-RETRO-01).
- This skill is a leaf node. It SHALL NOT invoke the Skill tool or Task tool (INV-RETRO-04).
- Do not prompt the user for input. Run non-interactively and produce the full report.
- Do not install dependencies or run package managers.
- All git commands used MUST be read-only (`git log`, `git rev-list`, `git diff-tree`, `git describe`, `git tag`, `git rev-parse`, `git shortlog`, `git diff --stat`). Do not use `git checkout`, `git reset`, `git commit`, `git stash`, or any state-modifying command.
- Test file detection patterns are language-agnostic. The patterns listed in Step 3.6 cover common conventions across languages.
- When the time window yields more than 500 commits, note this in the summary and analyze all commits (git commands handle large histories efficiently).

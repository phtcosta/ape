---
name: sdd-release
description: "Prepare a release: determine version, generate changelog, update version files, create git tag. Use WHEN preparing a new version for release. WHEN NOT: for deployment or CI/CD operations."
context: fork
argument-hint: "[version|major|minor|patch]"
---

# Release: $ARGUMENTS

Prepare a release by analyzing commits since the last tag, determining the appropriate semantic version bump, generating a changelog, updating version files, and creating a git tag. The developer approves the release before any changes are made.

This skill is a Tier 3 support component. It runs as a forked subagent and writes to version files, CHANGELOG.md, and git tags. It does NOT push to the remote repository.

**Invariants:**

- **INV-REL-01:** The skill MUST follow semantic versioning. A breaking change results in a major bump, a new feature in a minor bump, a bug fix in a patch bump (unless overridden by the developer).
- **INV-REL-02:** The skill MUST NOT push to the remote repository. It creates the tag locally; the developer decides when to push.
- **INV-REL-03:** The skill MUST present the proposed changelog and version for developer approval before making any changes.
- **INV-REL-04:** The generated changelog MUST list all commits since the last tag, grouped by category (Features, Bug Fixes, Breaking Changes, Other).
- **INV-REL-05:** This skill SHALL NOT use the Skill tool or Task tool. It is a leaf skill that operates only via Bash (git commands), Read, Write, and Edit tools.

---

## Input

`$ARGUMENTS` contains an optional version override. Accepted formats:

| Value | Meaning |
|-------|---------|
| `major` | Force a major version bump |
| `minor` | Force a minor version bump |
| `patch` | Force a patch version bump |
| `X.Y.Z` (e.g., `1.2.0`) | Use this exact version |
| _(empty)_ | Determine bump type automatically from commit analysis |

---

## Phase 1: Version Discovery

Find the current version from the most recent git tag.

### Step 1: Find Last Tag

```bash
git describe --tags --abbrev=0 2>/dev/null
```

**If no tags exist:**
- Set `currentVersion` to `0.0.0`.
- All commits on the current branch are included in the release.
- Use `git log --oneline --no-merges` for the full commit list.

**If a tag exists:**
- Parse the tag name. Strip a leading `v` if present (e.g., `v1.2.3` becomes `1.2.3`).
- Set `currentVersion` to the parsed version.
- Validate it matches semver format `X.Y.Z` where X, Y, Z are non-negative integers.

### Step 2: Count Commits Since Last Tag

```bash
# If a tag exists:
git log <last_tag>..HEAD --oneline --no-merges

# If no tags exist:
git log --oneline --no-merges
```

**WHEN** the commit count is zero
**THEN** report `NoCommitsSinceLastTag` error and STOP.

---

## Phase 2: Commit Analysis

Determine the appropriate version bump from commit messages.

### Step 1: Collect Commit Messages

```bash
# If a tag exists:
git log <last_tag>..HEAD --format="%H %s" --no-merges

# If no tags exist:
git log --format="%H %s" --no-merges
```

Capture the full SHA and subject line for each commit.

### Step 2: Classify Commits

For each commit, check its subject line against conventional commit prefixes:

| Prefix / Pattern | Category | Bump Signal |
|------------------|----------|-------------|
| `feat:` or `feature:` | Features | minor |
| `fix:` | Bug Fixes | patch |
| `BREAKING CHANGE` in subject or body | Breaking Changes | major |
| `!:` after type (e.g., `feat!:`) | Breaking Changes | major |
| `docs:` | Other | patch |
| `refactor:` | Other | patch |
| `test:` | Other | patch |
| `chore:` | Other | patch |
| Any other prefix or no prefix | Other | patch |

### Step 3: Determine Bump Type

**If `$ARGUMENTS` specifies a bump type** (`major`, `minor`, `patch`):
- Use the specified type. Note the override in the output.

**If `$ARGUMENTS` specifies an exact version** (matches `X.Y.Z`):
- Validate the version is greater than `currentVersion`. If not, report `InvalidVersionOverride` and STOP.
- Use the exact version. Skip bump calculation.

**If `$ARGUMENTS` is empty** (automatic determination):
- If any commit is classified as Breaking Changes: bump = `major`.
- Else if any commit is classified as Features: bump = `minor`.
- Else: bump = `patch`.

### Step 4: Calculate New Version

Apply the bump type to `currentVersion`:

| Bump | Calculation | Example (from 1.2.3) |
|------|-------------|----------------------|
| major | X+1.0.0 | 2.0.0 |
| minor | X.Y+1.0 | 1.3.0 |
| patch | X.Y.Z+1 | 1.2.4 |

If an exact version was provided in `$ARGUMENTS`, use it directly.

---

## Phase 3: Version File Detection

Detect which version files exist in the project root.

### Step 1: Check Known Version Files

Check for each of the following files using the Read tool. Record which files exist and their current version values.

| Language | File | Version Location |
|----------|------|------------------|
| Python | `pyproject.toml` | `[project].version` or `[tool.poetry].version` |
| Node.js | `package.json` | `"version"` field |
| Java (Gradle) | `build.gradle` or `build.gradle.kts` | `version` property (e.g., `version = "X.Y.Z"`) |
| Java (Maven) | `pom.xml` | Top-level `<version>` element |
| Go | _(none)_ | Go uses git tags only |

### Step 2: Handle Results

**WHEN** one or more version files are found:
- Record each file path and the current version string found in it.
- Include all detected files in the release checklist (Phase 5).

**WHEN** no version files are found:
- Report `NoVersionFile` as a warning (not a fatal error).
- The release can still proceed with changelog and tag creation only.
- Note in the checklist that no version files will be updated.

---

## Phase 4: Changelog Generation

Build the changelog content from the classified commits.

### Step 1: Format Changelog Entry

Generate a changelog section using the following structure:

```markdown
## [X.Y.Z] - YYYY-MM-DD

### Breaking Changes
- Description of change (SHA_SHORT)

### Features
- Description of feature (SHA_SHORT)

### Bug Fixes
- Description of fix (SHA_SHORT)

### Other
- Description of other change (SHA_SHORT)
```

Rules:
- Use the new version number and today's date.
- SHA_SHORT is the first 7 characters of the commit SHA.
- Omit empty sections (e.g., if no breaking changes, omit that heading).
- Strip the conventional commit prefix from the description (e.g., `feat: add parser` becomes `Add parser`).
- Capitalize the first letter of each description.

### Step 2: Prepare CHANGELOG.md Content

**WHEN** `CHANGELOG.md` exists in the project root:
- Read the existing content.
- Prepend the new entry after the file's title line (first `# Changelog` heading). If there is no title heading, prepend the new entry with a `# Changelog` heading.

**WHEN** `CHANGELOG.md` does not exist:
- Create the file with:
  ```markdown
  # Changelog

  <new entry>
  ```

---

## Phase 5: Release Checklist

Present the release proposal to the developer for approval (INV-REL-03).

### Step 1: Display Checklist

Present the following information:

```markdown
## Release Checklist

**Current version**: <currentVersion>
**Proposed version**: <newVersion>
**Bump type**: <major|minor|patch> <(override)|(automatic)>
**Commits included**: <count>
**Tag to create**: v<newVersion>

### Changelog Preview

<formatted changelog from Phase 4>

### Files to Modify

| File | Action |
|------|--------|
| CHANGELOG.md | <Create / Update> |
| <version_file_1> | Update version from X.Y.Z to A.B.C |
| <version_file_2> | Update version from X.Y.Z to A.B.C |

### Git Operations

- Create annotated tag: `v<newVersion>`
- **Will NOT push to remote** (you push when ready)

---

**Proceed with release?** (approve / reject)
```

### Step 2: Wait for Approval

Wait for the developer's response.

**WHEN** the developer approves:
- Proceed to Phase 6.

**WHEN** the developer rejects:
- Set `status` to `"aborted"`.
- Report the output with status "aborted" and STOP. Make no changes.

**WHEN** the developer requests modifications (e.g., different version, exclude commits):
- Apply the requested changes and re-display the checklist.

---

## Phase 6: Release Execution

Apply the release changes. This phase modifies files and creates a git tag.

### Step 1: Update Version Files

For each version file detected in Phase 3, update the version string.

**Python (`pyproject.toml`):**
```
Read the file, find the version line under [project] or [tool.poetry], replace the old version with the new version using Edit.
```

**Node.js (`package.json`):**
```
Read the file, update the "version" field value using Edit.
```

**Java Gradle (`build.gradle` / `build.gradle.kts`):**
```
Read the file, find the version property line, replace the old version with the new version using Edit.
```

**Java Maven (`pom.xml`):**
```
Read the file, find the top-level <version> element, replace the old version with the new version using Edit.
```

### Step 2: Update CHANGELOG.md

Write or update `CHANGELOG.md` with the content prepared in Phase 4. Use Write (for new files) or Edit (for existing files).

### Step 3: Create Git Tag

```bash
git tag -a "v<newVersion>" -m "Release v<newVersion>"
```

The tag message is `Release v<newVersion>`. The tag is annotated (not lightweight).

Do NOT run `git push` (INV-REL-02).

---

## Output Format

After completing (or aborting) the release, return the following structured report:

```markdown
# Release Report

**currentVersion**: <version before release>
**newVersion**: <version after release>
**bumpType**: <major|minor|patch>
**commitCount**: <number of commits included>
**tag**: v<newVersion>
**status**: <completed|aborted|error>

## Changelog

<changelog content>

## Files Modified

| File | Change |
|------|--------|
| CHANGELOG.md | <Created / Updated> |
| <version_file> | Version updated to <newVersion> |

## Next Steps

- Review the changes: `git diff`
- Push the commit and tag: `git push origin <branch> && git push origin v<newVersion>`
```

For aborted releases, only include `currentVersion`, `newVersion` (proposed), `bumpType`, `commitCount`, `tag` (proposed), and `status: aborted`. Omit Files Modified and Next Steps.

For error cases, include the error type and message. Omit changelog, files modified, and next steps.

---

## Error Handling

### NoCommitsSinceLastTag

WHEN there are no commits since the last tag
THEN return:
```
Error: NoCommitsSinceLastTag
Message: "No commits found since tag '<tag>'. Nothing to release."
status: error
```
AND STOP

### InvalidVersionOverride

WHEN `$ARGUMENTS` specifies an exact version that is not valid semver or is not greater than the current version
THEN return:
```
Error: InvalidVersionOverride
Message: "Version '<provided>' is not valid or is not greater than current version '<current>'."
status: error
```
AND STOP

### NoVersionFile

WHEN no version files are detected in the project
THEN include a warning in the release checklist:
```
Warning: No version file detected. Only CHANGELOG.md and git tag will be created.
```
This is NOT a fatal error. The release proceeds with changelog and tag only.

---

## Constraints

- This skill SHALL NOT use the Skill tool or Task tool (INV-REL-05).
- This skill SHALL NOT push to the remote repository (INV-REL-02).
- This skill SHALL NOT run tests, builds, or CI/CD pipelines. It handles version management and changelog only.
- This skill MUST wait for developer approval before making any changes (INV-REL-03).
- Do not install dependencies or run package managers.
- When there are more than 200 commits since the last tag, include all commits in the analysis but note the count in the checklist for the developer's awareness.

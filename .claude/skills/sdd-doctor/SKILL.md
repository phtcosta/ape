---
name: sdd-doctor
description: "Diagnose SDD Toolkit deployment environment: checks CLI, Docker, Neo4j, Python, MCP, and mode."
context: inline
user-invocable: true
---

# SDD Doctor

> **Scope**: Diagnose the SDD Toolkit deployment environment. Checks 6 components and reports pass/fail status.
> Strictly read-only. SHALL NOT modify any file, configuration, or service.

## Invariants

- **INV-DR-01**: sdd-doctor SHALL run all 6 checks regardless of individual check failures. No check failure SHALL abort the skill.
- **INV-DR-02**: sdd-doctor SHALL NOT modify any file, configuration, or service.
- **INV-DR-03**: sdd-doctor SHALL use `context: inline` and `user-invocable: true`.

## Steps

Run all 6 checks below in order. Record each result as PASS, FAIL, SKIP, or INFO. Then output the report.

### Check 1: Claude CLI

Run via Bash tool:
```bash
claude --version 2>&1
```

- **Success** (outputs version string): Status = **PASS**, Details = version number
- **Failure** (command not found or error): Status = **FAIL**, Details = `Not installed. Install: https://docs.anthropic.com/en/docs/claude-code`

### Check 2: Docker (sdd-graphdb container)

Run via Bash tool:
```bash
docker ps --filter name=sdd-graphdb --format '{{.Status}}' 2>&1
```

- **Docker not installed** (command not found): Status = **FAIL**, Details = `Docker not installed`
- **Container running** (output contains "Up"): Status = **PASS**, Details = `sdd-graphdb: <status>`
- **Container not running** (empty output): Status = **FAIL**, Details = `Container not running. Run: docker compose -f infrastructure/docker-compose.yml up -d`

Store the Docker result for dependency checks below.

### Check 3: Neo4j Connectivity

**If Docker check was FAIL**: Status = **SKIP**, Details = `Skipped (Docker not available)`

**Otherwise**: Call `mcp__sdd__query_cypher` with query `RETURN 1 AS ok`.

- **Success**: Status = **PASS**, Details = `Connected (bolt://localhost:7687)`
- **Failure** (connection error or MCP unavailable): Status = **FAIL**, Details = error message

### Check 4: Python / uv

Run via Bash tool:
```bash
python3 --version 2>&1 && uv --version 2>&1
```

- **Both available**: Status = **PASS**, Details = `Python <version>, uv <version>`
- **Python available, uv missing**: Status = **FAIL**, Details = `Python <version>, uv: not found`
- **Python missing**: Status = **FAIL**, Details = `Python not found`

### Check 5: MCP Server

**If Docker check was FAIL**: Status = **SKIP**, Details = `Skipped (Docker not available)`

**Otherwise**: Attempt to call `mcp__sdd__query_cypher` with query `RETURN 1 AS ok`.

- **Success**: Status = **PASS**, Details = `sdd-mcp server responding`
- **MCP tools not available**: Status = **FAIL**, Details = `MCP server not configured. Check .mcp.json`

Note: If Check 3 already succeeded with the same query, reuse that result — no need to query again.

### Check 6: Operating Mode

Read `.sdd/sdd-config.yaml` using the Read tool.

- **File exists with `sdd.infrastructure.mode` field**: Status = **INFO**, Details = mode value (`full`, `lite`, or `minimal`)
- **File does not exist**: Status = **INFO**, Details = `minimal (no config file)`
- **File exists but mode field missing**: Status = **INFO**, Details = `minimal (mode not configured)`

## Output Format

Output a markdown report with the following structure:

```markdown
# SDD Doctor Report

| Check | Status | Details |
|-------|--------|---------|
| Claude CLI | PASS/FAIL | <details> |
| Docker | PASS/FAIL | <details> |
| Neo4j | PASS/FAIL/SKIP | <details> |
| Python/uv | PASS/FAIL | <details> |
| MCP Server | PASS/FAIL/SKIP | <details> |
| Mode | INFO | <details> |

## Summary
N/M checks passed. <assessment>
```

For the summary count:
- PASS counts as passed
- INFO counts as passed (informational, not a failure)
- FAIL counts as not passed
- SKIP counts as not passed

Assessment text:
- All passed: `Environment is ready.`
- Some failed: `Review failed checks above.`

## Error Handling

| Condition | Behavior |
|-----------|----------|
| Any check throws an exception | Catch it, mark that check as FAIL with error message, continue to next check |
| MCP tools not available at all | Mark Neo4j and MCP Server as FAIL, continue with remaining checks |
| Bash tool unavailable | Mark Claude CLI, Docker, Python/uv as FAIL, continue with remaining checks |

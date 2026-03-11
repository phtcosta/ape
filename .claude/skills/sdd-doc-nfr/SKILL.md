---
name: sdd-doc-nfr
description: "Map non-functional requirements to architectural support using Sommerville/Bass frameworks. Use WHEN documenting NFR coverage and identifying quality attribute gaps. WHEN NOT: for architecture views use sdd-doc-architecture."
argument-hint: "[scope or category: 'performance', 'security', or module path]"
context: fork
---

# NFR Mapping: $ARGUMENTS

Map non-functional requirements to their architectural support in the target project. Identify stated and inferred NFRs, match them to architectural tactics, locate code-level evidence, classify support levels, and highlight gaps.

This skill is a Tier 3 support component. It runs as a forked subagent. It writes the NFR mapping document to `docs/nfr-mapping.md`. It follows the Sommerville (Software Engineering) and Bass (Software Architecture in Practice) frameworks for quality attribute analysis.

**Invariants:**

- **INV-NFR-01:** The skill MUST cover all 5 quality attribute categories (Performance, Security, Availability, Maintainability, Scalability). Categories with no detected NFRs SHALL be listed with "No NFRs detected" rather than omitted.
- **INV-NFR-02:** Every NFR mapping MUST include at least: NFR statement, category, and either supporting evidence or an explicit gap notation.
- **INV-NFR-03:** The skill MUST NOT fabricate evidence. If no code-level evidence supports an NFR, the mapping SHALL mark it as a gap.
- **INV-NFR-04:** The output MUST be language-agnostic in structure. Language-specific patterns appear only as evidence references, not in the mapping framework itself.
- **INV-NFR-05:** This skill MUST NOT invoke other skills via the Skill tool. It MUST NOT use the Task tool. It reads `.sdd/sdd-config.yaml` directly and uses Read, Glob, Grep, and MCP tools for data sourcing.

---

## Input

`$ARGUMENTS` contains an optional scope or category filter. Accepted formats:

| Value | Meaning |
|-------|---------|
| `performance` | Analyze only the Performance category |
| `security` | Analyze only the Security category |
| `availability` | Analyze only the Availability category |
| `maintainability` | Analyze only the Maintainability category |
| `scalability` | Analyze only the Scalability category |
| `src/module` | Scope evidence search to a specific path |
| _(empty)_ | Analyze all 5 categories across the entire project |

When a single category is specified, still produce the full summary matrix (INV-NFR-01) but only generate detailed mappings for the selected category.

---

## Mode Detection

Determine the operating mode before sourcing data.

### Step 1: Read Config

Read `.sdd/sdd-config.yaml` from the project root using the Read tool.

**If the file does not exist** or cannot be parsed:
- Set `mode: "minimal"`.
- Proceed to Minimal mode.

**If the file exists**, extract `sdd.infrastructure.mode`:

| Value | Action |
|-------|--------|
| `"full"` | Proceed to Full mode |
| `"lite"` | Proceed to Lite mode |
| `"minimal"` | Proceed to Minimal mode |
| Missing or unrecognized | Set `mode: "minimal"`, proceed to Minimal mode |

---

## NFR Discovery

Discover NFRs from project documentation and code before sourcing evidence. This phase runs in all modes.

### Step 1: Search Documentation for Stated NFRs

Read the following files (if they exist) and search for NFR statements:

- `docs/PRD.md` (product requirements)
- `README.md`
- `CLAUDE.md`
- `docs/*.md` (any documentation files)
- Configuration files: `docker-compose.yml`, `docker-compose.yaml`, `Dockerfile`, `*.config.*`, `.env.example`

Search for keywords that indicate NFR statements:

| Category | Keywords |
|----------|----------|
| Performance | performance, latency, throughput, response time, cache, SLA, p99, p95, milliseconds, batch |
| Security | security, authentication, authorization, encryption, TLS, SSL, RBAC, CORS, OWASP, sanitize |
| Availability | availability, uptime, failover, redundancy, health check, circuit breaker, retry, SLA, disaster recovery |
| Maintainability | maintainability, modularity, test coverage, documentation, code quality, separation of concerns |
| Scalability | scalability, horizontal scaling, vertical scaling, sharding, partitioning, stateless, queue, load balancing |

For each match, extract the surrounding sentence or paragraph as the NFR statement. Record the source file and line number.

### Step 2: Classify Stated NFRs

Assign each discovered NFR to one of the 5 categories based on the matched keywords. If an NFR spans multiple categories, assign it to the primary category (the one with the strongest keyword match) and note the secondary categories.

### Step 3: Identify Inferred NFRs

Search for code patterns and configurations that indicate architectural tactics without a corresponding stated NFR. These become inferred NFRs.

For each tactic indicator found (see Tactic Catalog below) that has no matching stated NFR:
- Create an inferred NFR entry.
- Mark the source as "inferred from code/configuration" rather than "stated in documentation".
- Example: Redis configuration with TTL settings but no performance NFR in docs produces: "Inferred: Response caching with TTL-based invalidation".

---

## Tactic Catalog

The following catalog maps quality attribute categories to architectural tactics and their language-agnostic code indicators. Use this catalog to search for evidence in the codebase.

### Performance Tactics

| Tactic | Code Indicators |
|--------|----------------|
| Caching | Cache configuration files (Redis, Memcached), TTL settings, cache key patterns, in-memory cache structures, cache invalidation logic |
| Async Processing | Async/await patterns, message queue configurations (RabbitMQ, Kafka, SQS), worker process definitions, thread pool configurations, background job frameworks |
| Connection Pooling | Database pool size settings, HTTP connection pool configurations, gRPC channel reuse, connection factory patterns |
| Batch Operations | Bulk insert/update queries, batch processing loops, chunk-based file processing, pagination implementations |
| Lazy Loading | Deferred initialization patterns, proxy objects, on-demand resource loading, lazy property accessors |

### Security Tactics

| Tactic | Code Indicators |
|--------|----------------|
| Authentication | JWT configuration and token handling, OAuth/OIDC setup, session management, login endpoints, authentication middleware or filters |
| Input Validation | Schema validation libraries (Zod, Pydantic, Bean Validation), sanitization functions, request validation middleware, parameterized queries |
| Encryption | TLS/SSL configuration, encryption library usage, key management files, encrypted storage settings, hashing utilities (bcrypt, argon2) |
| Authorization | Role-based access control definitions, permission checks, policy files, access control lists, authorization middleware |
| Audit Logging | Security event logging, audit trail implementations, access log configurations, compliance logging |

### Availability Tactics

| Tactic | Code Indicators |
|--------|----------------|
| Health Checks | Health check endpoints (`/health`, `/ready`, `/live`), liveness and readiness probe configurations, health check libraries |
| Circuit Breakers | Circuit breaker library usage (Resilience4j, Polly, Hystrix), fallback method definitions, failure threshold configurations, retry policies with backoff |
| Redundancy | Replica configuration, load balancer setup files, failover configuration, multi-instance deployment definitions, database replication settings |
| Graceful Degradation | Fallback responses, feature flags, degraded mode handlers, timeout configurations with fallback |
| Retry Policies | Retry configuration with max attempts and backoff, exponential backoff implementations, idempotency keys |

### Maintainability Tactics

| Tactic | Code Indicators |
|--------|----------------|
| Modularity | Clear module/package boundaries, dependency injection configuration, interface definitions with multiple implementations, plugin architectures |
| Test Coverage | Test directories and files, coverage configuration (`.nycrc`, `pytest.ini`, `jacoco`), CI pipeline test steps, test utility modules |
| Separation of Concerns | Layered directory structures (controllers/services/repositories), MVC/MVVM patterns, clear API boundaries between modules |
| Documentation Coverage | Inline documentation patterns, API documentation generation configuration (Swagger/OpenAPI), doc comment conventions |

### Scalability Tactics

| Tactic | Code Indicators |
|--------|----------------|
| Stateless Services | No server-side session storage, external state stores (Redis, database), token-based authentication, shared-nothing architecture indicators |
| Queue Processing | Message queue consumer/producer configurations, worker queue definitions, event-driven processing patterns, job scheduling configurations |
| Horizontal Scaling | Container orchestration files (Kubernetes manifests, Docker Compose with replicas), auto-scaling configurations, load balancer definitions |
| Data Partitioning | Sharding configuration, database partitioning schemes, tenant isolation patterns, data routing logic |

---

## Data Sourcing by Mode

After NFR discovery, source evidence for each NFR using the appropriate mode.

### Full Mode

Query the MCP server for pre-indexed NFR data.

1. **Query NFR aggregation**: Call `mcp__sdd__aggregate_nfrs` with the target scope. The query returns NFR nodes with linked evidence chains (component IMPLEMENTS tactic relationships).

2. **Handle MCP failure**: If the MCP server is unavailable or returns an error, fall back to Minimal mode. Note the fallback in the report.

3. **Merge with discovery**: Combine MCP results with the NFR discovery from the previous phase. MCP may provide evidence for stated NFRs and may reveal additional inferred NFRs.

### Lite Mode

Use MCP Memory for cached results.

1. **Get scope hash**:
   - If `<scope>` is a file path: compute file hash:
     ```bash
     sha256sum <scope> | cut -d' ' -f1
     ```
     If `sha256sum` is not available, fall back to `shasum -a 256 <scope> | cut -d' ' -f1`.
   - If `<scope>` is a directory or project-wide: compute directory hash:
     ```bash
     find <scope> -type f | sort | xargs wc -c | sha256sum | cut -d' ' -f1
     ```
     If `sha256sum` is not available, fall back to `shasum -a 256` in the pipeline.
   Store the result as `<scope_hash>`.

2. **Check cache**: Search MCP Memory with query `sdd-doc-nfr:<scope>:<scope_hash>`.

   - **Cache hit**: Extract the cached NFR mapping. Return it directly. STOP.
   - **Cache miss**: Proceed to Minimal mode analysis.

3. **Cache results**: After Minimal mode analysis completes, store the result:
   ```
   Entity name: "sdd-doc-nfr:<scope>:<scope_hash>"
   Entity type: "sdd-analysis-cache"
   Observations:
     - "target: <scope>"
     - "scope_hash: <scope_hash>"
     - "timestamp: <ISO 8601>"
     - "report: <full NFR mapping report>"
   ```
   If caching fails, return the report without caching.

### Minimal Mode

Read source files directly to find evidence for each NFR.

1. **Locate source and config files**: Use Glob to find source files and configuration files under the target scope.

2. **Search for tactic indicators**: For each tactic in the Tactic Catalog, use Grep to search for the code indicators listed. Record each match with:
   - File path
   - Line number
   - Matched pattern
   - Corresponding tactic and category

3. **Match evidence to NFRs**: For each stated NFR, check if any found tactic indicators support it. For each inferred NFR, the evidence is the tactic indicator that triggered the inference.

4. **Handle scope filtering**: If `$ARGUMENTS` specifies a path, restrict the Grep/Read searches to that path. If `$ARGUMENTS` specifies a category, search for indicators from that category only but still report all 5 categories in the summary matrix (INV-NFR-01).

---

## Mapping Generation

For each NFR (stated or inferred), produce a mapping entry containing the following fields.

### Required Fields per NFR

| Field | Description | Source |
|-------|-------------|--------|
| **NFR Statement** | The requirement as stated in documentation, or the inferred statement | NFR Discovery phase |
| **Source** | "stated in `<file>:<line>`" or "inferred from `<file>:<line>`" | NFR Discovery phase |
| **Category** | One of: Performance, Security, Availability, Maintainability, Scalability | Classification |
| **Architectural Tactics** | List of tactics from the catalog that support this NFR | Tactic Catalog matching |
| **Code Evidence** | Specific files, configurations, or patterns that implement the tactics | Data Sourcing phase |
| **Support Level** | One of: strong, partial, weak, none (see classification below) | Evidence assessment |
| **Gaps** | What is missing to fully support the NFR. Empty only if Support Level is "strong" | Gap analysis |
| **Verification** | How to test that the NFR is met (benchmarks, test scenarios, fitness functions) | Derived from NFR type |

Every mapping MUST have a statement, a category, and either code evidence or a gap notation (INV-NFR-02). The skill MUST NOT invent evidence that does not exist in the codebase (INV-NFR-03).

### Support Level Classification

| Level | Criteria |
|-------|----------|
| **strong** | Tactic is fully implemented with concrete code evidence. Multiple indicators confirm the implementation. |
| **partial** | Tactic is partially implemented, or evidence is incomplete. Some indicators are present but others are missing. |
| **weak** | Minimal support found. Only one indicator or a tangential implementation. |
| **none** | No supporting implementation found in the codebase. This is a gap. |

### Verification Approaches by Category

| Category | Verification Methods |
|----------|---------------------|
| Performance | Load testing, benchmark suites, profiling, response time monitoring |
| Security | Penetration testing, dependency vulnerability scanning, security audit checklists |
| Availability | Chaos testing, failover drills, uptime monitoring, SLA tracking |
| Maintainability | Code coverage reports, linting scores, documentation coverage metrics |
| Scalability | Load testing with scaling, capacity planning reviews, stress testing |

---

## Summary Matrix

After all NFR mappings are generated, produce a summary matrix showing the aggregate support level per category.

### Aggregation Rules

For each category, determine the aggregate support level:

1. Collect all NFR mappings in the category.
2. If the category has no NFRs (stated or inferred): aggregate = `none`.
3. If all NFRs in the category are `strong`: aggregate = `strong`.
4. If any NFR is `none`: aggregate = `weak` (a gap pulls the category down).
5. Otherwise: aggregate = `partial`.

All 5 categories MUST appear in the matrix (INV-NFR-01). Categories with no detected NFRs show "No NFRs detected" in the details column.

### Recommendations

After the matrix, provide recommendations for categories with `weak` or `none` aggregate support:

- Identify which NFRs are missing or need strengthening.
- Suggest architectural tactics from the catalog that could address the gaps.
- Prioritize categories with `none` support (these need NFR definition first).
- For categories with `weak` support, suggest specific tactic implementations.

---

## Output Format

Write the NFR mapping document to `docs/nfr-mapping.md`. Use Write (for new files) or Edit (for existing files).

### Document Template

```markdown
# NFR Mapping

**Scope**: <analyzed scope or "full project">
**Mode**: <full|lite|minimal>
**Date**: <YYYY-MM-DD>

## NFR Inventory

| # | NFR Statement | Category | Source | Support Level |
|---|---------------|----------|--------|---------------|
| 1 | <statement> | <category> | <stated/inferred> | <strong/partial/weak/none> |

## Detailed Mappings

### <Category Name>

#### NFR-<N>: <NFR Statement>

- **Source**: <stated in `file:line` | inferred from `file:line`>
- **Category**: <category>
- **Architectural Tactics**: <tactic1, tactic2>
- **Code Evidence**:
  - `path/to/file.ext:line` - <description of what this implements>
  - `path/to/config.yaml` - <description of configuration>
- **Support Level**: <strong|partial|weak|none>
- **Gaps**: <what is missing, or "None" if strong>
- **Verification**: <how to verify this NFR is met>

_(Repeat for each NFR in the category)_

_(Repeat for each of the 5 categories. Categories with no NFRs show:)_

### <Category Name>

No NFRs detected for this category.

## Summary Matrix

| Category | Aggregate Support | NFR Count | Details |
|----------|-------------------|-----------|---------|
| Performance | <level> | <N> | <brief summary> |
| Security | <level> | <N> | <brief summary> |
| Availability | <level> | <N> | <brief summary> |
| Maintainability | <level> | <N> | <brief summary> |
| Scalability | <level> | <N> | <brief summary> |

## Recommendations

<prioritized list of recommendations for gaps>

## Sources Searched

- <list of files and directories that were searched for NFR statements and evidence>
```

---

## Error Handling

### NoNFRsDetected

WHEN the project has no identifiable NFRs in documentation, configuration, or code patterns (neither stated nor inferred)
THEN produce a report that:
- Lists all 5 categories with "No NFRs detected" (INV-NFR-01)
- Shows aggregate support as `none` for all categories
- Recommends where to define NFRs (PRD, README, or a dedicated requirements document)
- Lists the files and directories that were searched
AND do NOT create an empty mapping document

---

## Constraints

- This skill SHALL NOT invoke other skills via the Skill tool (INV-NFR-05).
- This skill SHALL NOT use the Task tool.
- This skill writes only to `docs/nfr-mapping.md`. It does not modify source files.
- Do not prompt the user for input. This skill runs non-interactively as a subroutine for orchestrator skills or agents.
- Do not install dependencies or run package managers.
- All tactic indicators in the catalog are language-agnostic descriptors. Language-specific examples appear only in evidence references when found in the actual codebase (INV-NFR-04).
- When the codebase is large (100+ source files), prioritize searching configuration files and well-known framework files first, then sample source directories for tactic indicators. Note any sampling in the Sources Searched section.

---
name: sdd-doc-architecture
description: "Generate architecture documentation using Clements Views and Beyond. Use WHEN documenting a project's architecture with standardized views. WHEN NOT: for code-level documentation use sdd-doc-code."
argument-hint: "[scope: module path, component name, or 'full']"
context: fork
---

# Generate Architecture Documentation: $ARGUMENTS

You are an architecture documentation generator. You analyze a project's structure, select relevant architectural views based on evidence, and produce documentation following the "Views and Beyond" methodology (Clements et al.). Each view follows a 5-section structure. You generate only views that have supporting evidence in the project.

**Scope**: `$ARGUMENTS` specifies what to document — a module path, component name, or `full` for the entire project. If empty, default to `full`.

**Language**: English only. No promotional language ("modern", "elegant", "sophisticated"). Document current state only (P4).

**Diagrams**: Use Mermaid with `%%{init: {'theme': 'neutral'}}%%`. Avoid reserved words (`graph`, `end`, `class`, `style`, `default`) as node IDs.

## Step 1: Mode Detection

Read `.sdd/sdd-config.yaml` directly (do NOT invoke sdd-config-reader via Skill tool). Extract the `mode` field.

```
Read .sdd/sdd-config.yaml
  → mode: "full" | "lite" | "minimal"
```

If the file is missing or unreadable, default to `minimal`. The three modes affect only data sourcing (Step 4). The output structure is identical in all modes (INV-ARCH-04).

## Step 2: View Selection

Analyze the project to determine which views to generate. Do NOT generate all views blindly (INV-ARCH-02). Use the decision tree below.

### Module Views

| View | Indicators | How to detect |
|------|-----------|---------------|
| Decomposition | Directory hierarchy with clear module boundaries | Glob for `src/*/`, check for package/module files |
| Uses | Import/dependency chains across modules | Grep for import statements, require/include patterns |
| Generalization | Inheritance hierarchies (extends, implements, mixins) | Grep for class inheritance patterns across languages |
| Layered | Layered structure (controllers/services/repositories or equivalent) | Glob for layer-named directories, check call direction |
| Aspects | Cross-cutting concerns applied across layers (logging, auth, metrics) | Grep for decorators, annotations, middleware, interceptors |
| Data Model | Database models, ORM entities, schema definitions | Grep for model/entity definitions, migration files, schema files |

### C&C Views

| View | Indicators | How to detect |
|------|-----------|---------------|
| Pipe-Filter | Data transformation pipelines, stream processing | Grep for pipe/filter/transform/stream patterns |
| Client-Server | API endpoints + external service calls | Grep for route/endpoint definitions, HTTP client usage |
| SOA | Service discovery, registry config, service mesh | Grep for service registry, consul, eureka, mesh config |
| Pub-Sub | Message queue config (RabbitMQ, Kafka, SQS, NATS) | Grep for queue/topic/subscription config files |
| Shared-Data | Shared database accessed by multiple components | Check for shared DB config, multiple connection strings |

### Allocation Views

| View | Indicators | How to detect |
|------|-----------|---------------|
| Deployment | Container/orchestration config | Glob for Dockerfile, docker-compose*, kubernetes/, helm/, CI/CD files |
| Install | Install scripts, package manager config | Glob for setup scripts, package manifests (package.json, pyproject.toml, pom.xml, etc.) |
| Work Assignment | Team ownership markers | Glob for CODEOWNERS, check for team-scoped directories |

### Beyond Views (always generated)

- **System Overview**: Always generated.
- **Documentation Roadmap**: Always generated. Lists generated views with evidence and omitted views with justification.
- **View Mapping**: Generated if 2+ views exist. Maps relationships between views.
- **Rationale**: Always generated. Cross-cutting architectural decisions.
- **Directory**: Always generated. Index of all documentation artifacts.

Record each selected view with the evidence that triggered it. Record each omitted view with the reason for omission.

## Step 3: Analysis Checklists

Apply these 5 checklists during analysis to ensure systematic coverage. Each checklist guides what to look for regardless of operating mode. Mark items as found/not-found.

### Checklist 1: Context Modeling

1. System boundary — what is inside vs outside the project
2. External systems the project communicates with (APIs, databases, services)
3. External libraries and frameworks the project depends on
4. User-facing entry points (CLI commands, API endpoints, UI routes)
5. Configuration sources (env vars, config files, command-line args)
6. Deployment targets (where the system runs)
7. Data inputs and outputs (files, streams, network)
8. Authentication/authorization boundaries
9. Variation points (feature flags, plugins, environment-specific behavior)
10. Operational constraints (memory limits, latency requirements, availability targets)
11. Regulatory or compliance boundaries (data residency, audit logging)
12. Integration contracts (message formats, API schemas, shared protocols)
13. Build and CI/CD pipeline dependencies
14. Third-party service dependencies (cloud services, SaaS integrations)
15. Data flow across system boundary (ingress/egress points)
16. Security perimeter (TLS termination, network boundaries, trust zones)
17. Monitoring and observability integration points
18. Error propagation across boundaries (how external failures are handled)
19. Versioning strategy for external interfaces
20. Temporal dependencies (scheduling, cron jobs, time-sensitive operations)

### Checklist 2: Structural Modeling

1. Top-level module/package decomposition
2. Module responsibilities and single-responsibility adherence
3. Module dependencies (which modules depend on which)
4. Dependency direction (do dependencies follow a consistent direction?)
5. Circular dependency detection
6. Public vs internal interfaces per module
7. Shared types or contracts between modules
8. Layer assignment per module (if layered)
9. Layer violation detection (does any module skip layers?)
10. Namespace/package organization conventions
11. Module size distribution (lines of code, number of files)
12. Coupling between modules (tight vs loose)
13. Cohesion within modules (related functionality grouped together)
14. Utility/shared modules and their usage patterns
15. Configuration module structure
16. Test organization (mirrors source structure?)
17. Build module boundaries (what constitutes a build unit)
18. Plugin or extension module boundaries
19. Data access module patterns (repository, DAO, data mapper)
20. Entry point modules (main, app, index)

### Checklist 3: Behavioral Modeling

1. Request processing flow (from entry point to response)
2. Concurrency model (threads, processes, async, event loop)
3. State management strategy (stateless, stateful, external state store)
4. Error handling flow (how errors propagate through the system)
5. Transaction boundaries (database transactions, distributed transactions)
6. Caching strategy and cache invalidation
7. Background processing (workers, jobs, scheduled tasks)
8. Event processing flow (event emission, handling, ordering)
9. Authentication/authorization flow
10. Data validation flow (where and how input is validated)
11. Logging and audit trail generation
12. Retry and circuit breaker behavior
13. Graceful shutdown and startup sequences
14. Health check and readiness probe behavior
15. Rate limiting and throttling behavior
16. Data migration and schema evolution
17. Feature flag evaluation flow
18. Session management lifecycle
19. File upload/download processing
20. Webhook processing flow
21. Batch processing patterns
22. Idempotency handling

### Checklist 4: Interaction Modeling

1. Synchronous communication patterns (function calls, HTTP, gRPC)
2. Asynchronous communication patterns (message queues, events, callbacks)
3. Data serialization formats (JSON, protobuf, XML, binary)
4. API versioning strategy
5. Service discovery mechanism
6. Load balancing strategy
7. Communication security (TLS, mTLS, API keys, tokens)
8. Request routing patterns (gateway, proxy, direct)
9. Response caching at communication boundaries
10. Timeout and deadline propagation
11. Correlation ID / tracing context propagation
12. Bulk/batch communication patterns
13. Streaming communication (WebSocket, SSE, gRPC streaming)
14. Inter-process communication (IPC, shared memory, pipes)
15. Database connection patterns (pooling, per-request, singleton)
16. File system interaction patterns (read/write, watch, lock)
17. External API client patterns (retry, fallback, circuit breaker)
18. Notification delivery patterns (push, pull, poll)
19. Configuration distribution (push config, pull config, config service)
20. Cross-cutting communication concerns (logging, metrics, tracing interceptors)

### Checklist 5: Interface Modeling

1. Public API surface (what is exposed to external consumers)
2. Internal API surface (what modules expose to each other)
3. API documentation format (OpenAPI, GraphQL schema, protobuf definitions)
4. Interface contracts (pre-conditions, post-conditions, invariants)
5. Error response format and error code taxonomy
6. Pagination patterns for collection endpoints
7. Filtering, sorting, and search patterns
8. Authentication interface (how clients authenticate)
9. Authorization interface (how permissions are checked)
10. Webhook interface (how external systems subscribe to events)
11. Configuration interface (how the system is configured)
12. Extension points (how the system is extended by plugins/addons)
13. CLI interface (command structure, flags, arguments)
14. Database schema interface (tables, views, stored procedures)
15. Event/message interface (event types, message schemas)
16. File format interface (import/export formats)
17. Monitoring interface (metrics endpoints, log format, trace format)
18. Admin/management interface (admin endpoints, management commands)
19. Migration interface (how schema changes are applied)
20. Deprecation policy (how interfaces are retired)

## Step 4: Data Sourcing by Mode

### Full Mode

Query Neo4j via MCP tools for graph-backed data:

| MCP Tool | Purpose | Use for |
|----------|---------|---------|
| `mcp__sdd__get_layer_components` | Component layers and boundaries | Module Views (Decomposition, Layered) |
| `mcp__sdd__find_patterns` | Design patterns detected in code | C&C Views, Rationale |
| `mcp__sdd__get_dependencies` | Dependency graph between components | Module Views (Uses), C&C Views |
| `mcp__sdd__aggregate_extension_points` | Extension points and plugin boundaries | Variability Guide sections |

Supplement MCP results with checklist items that MCP does not cover (e.g., deployment config, external systems). Use Read/Glob/Grep for those.

### Lite Mode

1. Search MCP Memory for cached project analysis: `mcp__memory__search_nodes` with query matching the project/scope.
2. If cache hit: use cached data, supplement with checklists for gaps.
3. If cache miss: fall through to Minimal mode analysis, then cache results via `mcp__memory__create_entities`.

### Minimal Mode

Use checklist-driven analysis with direct code reading:

1. **Glob** for project structure (directories, config files, source files).
2. **Read** key files: entry points, config files, package manifests.
3. **Grep** for architectural indicators: import patterns, class hierarchies, endpoint definitions, queue config, deployment config.
4. Walk through each checklist (Step 3), marking items found/not-found.
5. Build the architectural model from checklist findings.

## Step 5: View Generation

For each selected view, generate a markdown file with exactly 5 sections (INV-ARCH-01):

```markdown
# [View Type]: [View Name]

## Primary Presentation

[Main diagram (Mermaid) or table showing elements and relationships in this view.
This is the central artifact — a reader should understand the view's structure from
this section alone.]

## Element Catalog

[Detailed description of each element in the Primary Presentation:
- Name
- Responsibility
- Interfaces (what it exposes and consumes)
- Properties (language-specific details appear here, not in view structure — INV-ARCH-03)
- Dependencies (incoming and outgoing)]

## Context Diagram

[How this view's elements relate to the broader system and external entities
not shown in the Primary Presentation. Shows the boundary between what is
documented in this view and what lies outside.]

## Variability Guide

[How the architecture accommodates variation:
- Configuration options that change structure or behavior
- Plugin/extension points
- Feature flags
- Environment-specific variations
- Conditional compilation or conditional inclusion]

## Rationale

[Why this structure was chosen:
- Design decisions that led to this structure
- Alternatives that were considered and rejected
- Trade-offs accepted
- Quality attribute implications]
```

**Per-view naming**: Save each view as a separate file using kebab-case naming:
- Module Views: `module-decomposition.md`, `module-uses.md`, `module-generalization.md`, `module-layered.md`, `module-aspects.md`, `module-data-model.md`
- C&C Views: `cc-pipe-filter.md`, `cc-client-server.md`, `cc-soa.md`, `cc-pub-sub.md`, `cc-shared-data.md`
- Allocation Views: `alloc-deployment.md`, `alloc-install.md`, `alloc-work-assignment.md`

## Step 6: Beyond Views Generation

Generate cross-cutting documentation that ties views together.

### System Overview (`architecture-overview.md`)

- Project purpose (1-2 paragraphs)
- High-level component diagram (Mermaid) showing major subsystems
- Technology stack summary (languages, frameworks, infrastructure)
- Key architectural drivers (quality attributes, constraints, stakeholder concerns)

### Documentation Roadmap (`documentation-roadmap.md`)

- Table of generated views with: view name, file path, what it shows, which checklists were applied
- Table of omitted views with: view name, reason for omission (e.g., "No Dockerfile or CI/CD config detected")
- Reading guide: suggested order for different audiences (developer, architect, operator)

### View Mapping (`view-mapping.md`) — only if 2+ views

- Element correspondence table: how elements in one view relate to elements in another
- Cross-view consistency notes: where views must agree and how to verify

### Rationale (`architecture-rationale.md`)

- Cross-cutting architectural decisions that span multiple views
- Quality attribute trade-offs
- Constraints that shaped the architecture

### Directory (`architecture-directory.md`)

- Alphabetical index of all architectural elements mentioned across views
- For each element: name, type, which views contain it, file path to definition

## Step 7: Output

Write all files to `docs/architecture/` in the target project. Create the directory if it does not exist.

```bash
mkdir -p docs/architecture
```

### Output Report

After generation, report what was produced:

```
## Generated: Architecture Documentation

### Views Generated
- [View Name]: docs/architecture/[filename].md (evidence: [what triggered selection])
- ...

### Views Omitted
- [View Name]: [reason for omission]
- ...

### Beyond Views
- System Overview: docs/architecture/architecture-overview.md
- Documentation Roadmap: docs/architecture/documentation-roadmap.md
- View Mapping: docs/architecture/view-mapping.md (if applicable)
- Rationale: docs/architecture/architecture-rationale.md
- Directory: docs/architecture/architecture-directory.md

### Checklists Applied
- Context Modeling: [N] items found / 20
- Structural Modeling: [N] items found / 20
- Behavioral Modeling: [N] items found / 22
- Interaction Modeling: [N] items found / 20
- Interface Modeling: [N] items found / 20
```

### Error: NoProjectStructure

If the scope argument matches no recognizable source files:
1. Report what directories and patterns were searched.
2. Suggest correcting the scope argument.
3. Do NOT generate empty views.

## Constraints

1. **Leaf node**: This skill MUST NOT invoke other skills via the Skill tool (INV-ARCH-05). MUST NOT use the Task tool.
2. **Read-only analysis**: Steps 1-4 are read-only (Read, Glob, Grep, MCP queries). Only Step 5-7 write files.
3. **Evidence-based views**: Never generate a view without supporting evidence (INV-ARCH-02). Omitted views go in the Documentation Roadmap.
4. **5-section structure**: Every generated view has exactly 5 sections (INV-ARCH-01). No exceptions.
5. **Language-agnostic structure**: View structure uses generic terms (modules, components, connectors). Language-specific details appear only in Element Catalog entries (INV-ARCH-03).
6. **Mode-independent output**: The documentation structure is identical regardless of operating mode (INV-ARCH-04). Mode affects data richness only.
7. **P4 compliance**: No promotional language. No migration history. Current state only.

# Skills, Agents, and Subagents: Architecture Analysis

> **Status**: Reference document for architectural decisions
> **Date**: 2026-02-26
> **Scope**: SDD Toolkit skill/agent design, Claude Code constraints, industry practices
>
> **See also**: [`.claude/SKILLS.md`](../.claude/SKILLS.md) for the complete skill catalog with invocation chains, nesting graph, and decision framework. This document covers the *design rationale*; SKILLS.md covers the *current inventory*.

---

## 1. Problem Statement

The SDD Toolkit (`agentes-claude`) migrated 45+ skills from rv-android into a generic, language-agnostic toolkit. The current implementation has **2 agents** and **39 skills** organized in a 4-tier architecture. Before implementation, we resolved fundamental questions about how skills and agents work in Claude Code — particularly regarding **nesting constraints**, **orchestration patterns**, and **portability** to other code assistants.

**Key constraint discovered empirically**: Claude Code subagents (spawned via Task tool) **cannot spawn other subagents**. The Task tool is silently unavailable inside subagent contexts. This constraint directly affects our orchestrator and agent design.

---

## 2. Official Claude Code Definitions

Source: https://code.claude.com/docs/en/skills, https://code.claude.com/docs/en/sub-agents

### 2.1 Skills

A **skill** is a directory containing a `SKILL.md` file with YAML frontmatter and markdown instructions. Skills extend Claude's capabilities by providing domain knowledge, step-by-step procedures, or task templates.

**Key characteristics**:
- Follow the [Agent Skills](https://agentskills.io) open standard (cross-platform portable)
- Loaded on demand — only description loaded at startup (~100 tokens), full content on invocation
- Can run **inline** (shares main context) or **forked** (`context: fork`, gets isolated subagent)
- Available via `/skill-name` (user) or Skill tool (Claude, programmatic)
- Support arguments via `$ARGUMENTS`, dynamic context via `` !`command` ``

**Frontmatter fields** (from official docs):

| Field | Purpose |
|-------|---------|
| `name` | Unique identifier, becomes `/slash-command` |
| `description` | When Claude should use this skill (WHEN/WHEN NOT pattern recommended) |
| `argument-hint` | Hint for expected arguments |
| `context` | `fork` = run in isolated subagent context |
| `agent` | Which subagent type when `context: fork` (default: `general-purpose`) |
| `allowed-tools` | Tools allowed without per-use approval |
| `model` | Model override |
| `disable-model-invocation` | `true` = only user can invoke (not Claude) |
| `user-invocable` | `false` = hidden from `/` menu (Claude-only) |
| `hooks` | Lifecycle hooks scoped to this skill |

### 2.2 Subagents

A **subagent** is a specialized AI assistant running in its **own isolated context window** with a custom system prompt, specific tool access, and independent permissions. Defined as `.md` files in `.claude/agents/`.

**Key characteristics**:
- Own context window (isolation from main conversation)
- Configured via YAML frontmatter: `name`, `description`, `tools`, `model`, `permissionMode`, `skills`, `memory`, `hooks`, `mcpServers`, `maxTurns`, `isolation`
- Scopes: session (`--agents` flag), project (`.claude/agents/`), user (`~/.claude/agents/`), plugin
- Can preload skills via `skills` field (full content injected at startup)
- Support persistent memory (`memory: user|project|local`)

### 2.3 Agent Teams (experimental)

Source: https://code.claude.com/docs/en/agent-teams

A **third orchestration mechanism** beyond subagents and skills. Agent Teams coordinate **multiple independent Claude Code sessions** working together on a shared project.

**Key characteristics**:
- Experimental (disabled by default, requires `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS`)
- One session acts as **team lead**, others as **teammates**
- Each teammate has its own full context window (separate Claude Code instance)
- Teammates can **message each other directly** (unlike subagents which only report back)
- Shared task list with self-coordination
- Supports split-pane display (tmux/iTerm2)

**Comparison with subagents**:

| Aspect | Subagents | Agent Teams |
|--------|-----------|-------------|
| Context | Own window; results return to caller | Own window; fully independent |
| Communication | Report results back to main only | Teammates message each other directly |
| Coordination | Main agent manages all work | Shared task list with self-coordination |
| Best for | Focused tasks where only result matters | Complex work requiring discussion/collaboration |
| Token cost | Lower (results summarized) | Higher (each teammate = separate Claude instance) |
| Nesting | Cannot spawn subagents | Cannot spawn teams (no nested teams) |

**Relevance for SDD Toolkit**: Agent Teams could serve as an advanced orchestration mode for large features where multiple specialists (architect, tester, documenter) need to coordinate. However, being experimental and high-cost, it should not be the primary mechanism.

### 2.4 The Critical Constraint

From the official documentation (emphasis added):

> **"Subagents cannot spawn other subagents.** If your workflow requires nested delegation, use Skills or chain subagents from the main conversation."

And specifically about the Task tool:

> "If Task is omitted from the tools list entirely, the agent cannot spawn any subagents. **This restriction only applies to agents running as the main thread with `claude --agent`. Subagents cannot spawn other subagents, so `Task(agent_type)` has no effect in subagent definitions.**"

### 2.5 Known Issue: `context: fork` via Skill Tool

GitHub Issues [#17283](https://github.com/anthropics/claude-code/issues/17283) and [#16803](https://github.com/anthropics/claude-code/issues/16803) reported that when a skill with `context: fork` is invoked via the **Skill tool** (programmatically by Claude), the fork/agent frontmatter was **ignored** — the skill ran inline in the main context instead.

**Status**: #17283 was closed as duplicate of #16803 (Jan 10, 2026). Our hello-claude-code validation (Feb 17, 2026) showed fork behavior working correctly via Skill tool, suggesting this was fixed between those dates. **However, this is an area of active development** — behavior may vary across Claude Code versions.

**Implication for SDD Toolkit**: Test skill forking behavior on each Claude Code update. Have a fallback plan (inline execution) if forking regresses.

---

## 3. Empirical Validation (hello-claude-code)

Source: `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/hello-claude-code/`

The hello-claude-code project ran 11 controlled tests to validate nesting behavior. Key findings:

### 3.1 Task Tool vs Skill Tool

| Mechanism | In main context | In subagent | In forked skill |
|-----------|----------------|-------------|-----------------|
| **Task tool** | Available | **Silently absent** | **Silently absent** |
| **Skill tool** | Available | Available | **Available** |

**This distinction is architecturally significant**: while subagents cannot spawn other subagents (Task), they CAN invoke skills (Skill tool), which may themselves fork into isolated contexts.

### 3.2 Fork Depth Testing

```
Test T11: 5-level nested fork via Skill tool
──────────────────────────────────────────────
Level 1: fork created (+0s)
  Level 2: fork created (+4.35s)
    Level 3: fork created (+3.45s)
      Level 4: fork created (+3.46s)
        Level 5: fork created (+3.25s)
          Leaf skill executed (inline)
        Level 5: returned
      Level 4: returned
    Level 3: returned
  Level 2: returned
Level 1: returned
Total: ~38s for 5 levels
```

**Finding**: No depth limit observed for Skill-based nesting. Latency: ~3-4 seconds per fork level. Practical limit for interactive workflows: 3-4 levels.

### 3.3 Two Delegation Patterns in Claude Code

```
Pattern A: Task tool (FLAT, single-level only)
  Main → Task(agent-A)     OK
  Main → Task(agent-B)     OK
  agent-A → Task(agent-C)  FAILS (Task absent from subagents)

Pattern B: Skill tool with fork (DEEP, multi-level)
  Main → Skill(fork-A)                          OK
  fork-A → Skill(fork-B)                        OK
  fork-B → Skill(fork-C)                        OK
  fork-C → Skill(inline-D)                      OK
  ... (no observed limit)
```

---

## 4. Existing Implementations

### 4.1 rv-android (46 skills, Claude Code)

Source: `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/`

**Architecture**:
- 46 skills in `.claude/skills/`, **no agent files** in `.claude/agents/` (empty directory)
- Agents are defined via `agent` field in skill frontmatter, not as separate entities
- All skills use `context: fork` for isolation
- Skills invoke other skills via Skill tool (requires `allowed-tools: Skill`)

**Skill categories**:

| Category | Count | Pattern |
|----------|-------|---------|
| Orchestrators | 4 | Multi-phase workflows with user checkpoints. Invoke analysis skills and code reviewer |
| Quality Gate | 1 | `rv-code-reviewer` — final gate, invokes analysis sub-skills |
| Analysis | 7 | Single-responsibility, MCP Memory caching via git hash |
| Refactoring | 4 | Code modification, no chaining |
| Testing/QA | 5 | Run tests, lint, verify |
| Documentation | 6 | Generate docs, ADRs |
| Planning/Process | 5 | Planning, impact analysis, retrospective |
| OpenSpec | 10 | Process management layer |

**Orchestrator invocation chain** (example: rv-refactor):
```
/rv-refactor [module]
  ├── Skill: rv-impact-analyzer [module]
  ├── Skill: rv-analyze-complexity [module]
  ├── Skill: rv-analyze-dependencies [module]
  ├── [CHECKPOINT #1: User approval]
  ├── [EXECUTION PHASE]
  ├── Skill: rv-verify [module]
  ├── Skill: rv-code-reviewer [module]
  │   ├── Skill: rv-analyze-file-complexity [path] (per changed file)
  │   ├── Skill: rv-analyze-file-dead-code [path]
  │   └── Skill: rv-analyze-dependencies [module]
  └── [CHECKPOINT #2: Final approval]
```

This works because **Skill tool is available in forked contexts** — each orchestrator invokes analysis skills via Skill tool, even though the orchestrator itself runs as a forked skill (subagent).

**Key insight**: rv-android treats everything as skills. "Agents" are just orchestrator skills with `context: fork` that chain other skills. There is no functional distinction between "agent" and "orchestrator skill".

### 4.2 agente-documentador (41 skills, Claude Code)

Source: `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/agente-documentador/`

Uses three orchestration patterns to handle the nesting constraint:

**Pattern 1: External Script Orchestration**
```
Skill → Bash(python orchestrate.py) → Parallel workers → MCP tools → Neo4j
```
- Python script manages 4-worker parallel pool
- Avoids nesting because workers are OS processes, not Task calls
- Skill just calls Bash and waits for completion

**Pattern 2: Multi-Phase Task Orchestration**
```
Orchestrator Skill (docgen-generate):
  PHASE 1: 7 Task calls in parallel (Beyond Views generation)
  PHASE 2: 3 Task calls in parallel (View generation)
  PHASE 3: 2 Task calls in parallel (Appendices)
  PHASE 4: 2 Task calls in parallel (Output)
  PHASE 5: 1 sequential (Index)
```
- Maximum depth: 2 levels (orchestrator → Task → skill)
- Sequential between phases, parallel within phases
- **This pattern works because the orchestrator runs in main context** (not inside another subagent)

**Pattern 3: Direct MCP Tool Orchestration**
```
Skill → MCP tools (Neo4j queries/updates) → Done
```
- Skills call MCP tools directly (not subagent calls)
- Stateless from LLM perspective (state persisted in Neo4j)
- Can be parallelized by the orchestrator

**"Bombadas" (supercharged skills)**: Skills that perform complex multi-step work via:
1. External process management (Bash/Python scripts)
2. Intra-phase Task parallelism (safe — only 1 level of Task from main)
3. Persistent state in Neo4j + SQLite (avoids passing state between nested subagents)
4. Checklist-driven deterministic workflows

### 4.3 hello-opencode (OpenCode framework)

Source: `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/hello-opencode/`

Demonstrates an **alternative approach** using the OpenCode framework with Ollama models:

**Architecture**: Commands → Agents → Skills
```
User → Command (bib-review.md)
         → OrchestratorAgent (primary mode)
              ├── SearchAgent (uses AcademicSearchSkill)
              ├── FilterAgent (uses FilterByMetadataSkill)
              │    └── SemanticFilterAgent (per article)
              ├── AnalysisAgent (uses SummarizeAndExtractSkill, per article)
              └── SynthesisAgent (uses LiteratureSynthesisSkill)
```

**Key differences from Claude Code**:
- Agents are `.md` files with persona, model, and tool config
- Skills are instruction documents loaded BY agents
- **Supports hierarchical nesting** (Orchestrator → Sub-agent → Sub-sub-agent)
- JSON-based structured communication between agents
- Configuration via `opencode.jsonc` (model, provider, context size)

**Portability insight**: The skill format (SKILL.md with YAML frontmatter) is compatible with Claude Code's Agent Skills standard. The agent `.md` files are framework-specific but the concepts map directly.

---

## 5. Industry Consensus

Sources: agentskills.io specification, Anthropic engineering blog, multiple practitioner blogs

### 5.1 Definitions Convergence

| Concept | Industry Definition | Claude Code Implementation |
|---------|-------------------|--------------------------|
| **Skill** | Directory with SKILL.md — knowledge/instructions, not autonomous | `.claude/skills/*/SKILL.md` — identical |
| **Subagent** | Isolated AI worker with own context window | `.claude/agents/*.md` or forked skills |
| **Agent** | Top-level reasoning system that orchestrates | Main Claude Code session or `claude --agent` |
| **Command** | User entry point for invoking a workflow | `.claude/commands/*.md` (merged into skills) |

### 5.2 Decision Heuristics

- **"I want Claude to remember X automatically"** → Skill
- **"I want to automate Y workflow step-by-step"** → Skill with `context: fork` (or subagent)
- **"I need isolated context for complex analysis"** → Subagent
- **"I need parallel workers"** → Multiple Task calls from main context
- **"I need to access external data"** → MCP server (not a skill)

### 5.3 Progressive Disclosure (universal pattern)

```
Level 1: Metadata (~100 tokens) — name + description, loaded for ALL skills at startup
Level 2: Instructions (<5000 tokens) — full SKILL.md body, loaded on invocation
Level 3: Resources (as needed) — supporting files in skill directory, loaded on demand
```

### 5.4 Orchestration Consensus

The industry consensus avoids deep nesting:
- **Maximum 2 levels deep** for skill structures (flat hierarchy)
- **Horizontal composition** preferred: one agent loads multiple skills as needed
- **Skills and MCP serve complementary roles**: skills encode expertise (how), MCP provides access (what)

### 5.5 agentskills.io Open Standard

Anthropic published Agent Skills as an open standard on December 18, 2025. The specification and SDK are at [agentskills.io](https://agentskills.io). Claude Code explicitly follows this standard.

**Structure**:
```
skill-name/
├── SKILL.md          # Required — frontmatter + instructions
├── scripts/          # Optional — executable helpers
├── references/       # Optional — detailed docs
└── assets/           # Optional — images, data
```

**Adoption (as of Feb 2026)**: **26+ platforms** have adopted the standard, including:
- **Claude Code** (Anthropic) — original creator
- **OpenAI Codex** — full agentskills.io support
- **GitHub Copilot** (CLI, VS Code, coding agent) — full support
- **Google Antigravity** / **Gemini CLI** — adopted Jan 2026
- **Cursor** — full support
- **VS Code** — native integration
- **OpenCode** — compatible format

A skill built for Claude Code works unchanged in all these platforms. There is even a universal CLI ([agent-skills-cli](https://github.com/Karanjot786/agent-skills-cli)) that syncs skills across platforms, and a marketplace (SkillsMP) with 40,000+ skills.

**Portability**: This is the single most important finding for the SDD Toolkit. Our 39 skills, if written following agentskills.io, are **portable to any major code assistant tool** — not just Claude Code. This validates the user's observation: "na verdade eh ate facil migrar para outra ferramenta (assistente de codigo)".

### 5.6 Community Workarounds for Nesting Limitation

The community has developed several workarounds for the subagent nesting constraint (GitHub Issue [#4182](https://github.com/anthropics/claude-code/issues/4182)):

1. **`claude -p` hack**: Subagents call `claude -p` via Bash to spawn nested CLI instances. **Not recommended** — loss of visibility, no progress tracking, complex error handling, no context sharing.

2. **Skill chaining via Skill tool**: The official recommendation. Skills with `context: fork` create isolated contexts that CAN invoke other skills. This is the pattern validated in hello-claude-code and used in rv-android.

3. **External script orchestration**: Python/Bash scripts manage parallel workers as OS processes. Used successfully in agente-documentador for 4-worker parallel analysis.

4. **Agent Teams**: The newest mechanism (experimental). Multiple independent Claude Code sessions coordinate via shared task list and direct messaging.

5. **MCP-based coordination**: Skills interact with MCP servers for state management, avoiding the need for nested context passing entirely.

---

## 6. Implications for SDD Toolkit Architecture

### 6.1 Current Design (from pre-plano.md)

```
sdd/
├── agents/                  # Tier 0 (7 agents)
│   ├── sdd-workflow.md
│   ├── sdd-architect.md
│   ├── sdd-documenter.md
│   ├── sdd-reviewer.md
│   ├── sdd-tester.md
│   ├── sdd-migrator.md
│   └── sdd-onboarder.md
│
└── skills/                  # Tiers 1-3 (32 skills)
    ├── sdd-methodology/SKILL.md
    ├── sdd-feature/SKILL.md
    ...
```

### 6.2 Constraint Analysis

| Design Element | Constraint | Impact |
|---------------|-----------|--------|
| **Agents invoking skills** | Task tool unavailable in subagents | Agents (spawned via Task) CANNOT invoke skills with Skill tool? **WRONG** — they CAN use Skill tool |
| **Agents invoking other agents** | Task tool unavailable in subagents | Agents CANNOT spawn other agents |
| **Skills invoking skills** | Skill tool available everywhere | Skills CAN invoke other skills, even from forked contexts |
| **Parallel execution** | Task tool only from main context | Parallelism via Task requires orchestration from main context or orchestrator skill |

### 6.3 Critical Clarification

Based on the hello-claude-code validation (T6, T7):
- **TRUE agents** (spawned via Task tool) **CAN** use the Skill tool
- **TRUE agents** CANNOT use the Task tool (cannot spawn other subagents)
- **Forked skills** (via `context: fork`) CAN use the Skill tool
- **Forked skills** CANNOT use the Task tool

This means our agents CAN orchestrate skills — they just cannot spawn other agents. This is the same pattern rv-android uses successfully.

### 6.4 Architectural Recommendations

#### Option A: Everything as Skills (rv-android pattern)

```
sdd/skills/
├── sdd-workflow/SKILL.md        # Orchestrator skill (context: fork)
├── sdd-architect/SKILL.md       # Orchestrator skill (context: fork)
├── sdd-feature/SKILL.md         # Orchestrator skill (context: fork)
├── sdd-analyze-file/SKILL.md    # Analysis skill (context: fork)
...
```

**Pros**: Simplest model, proven in rv-android, portable via agentskills.io
**Cons**: No distinction between agents and skills — all use same mechanism

#### Option B: Agents + Skills (current design, validated)

```
sdd/agents/
├── sdd-workflow.md              # Agent with preloaded skills
├── sdd-architect.md             # Agent with preloaded skills
...
sdd/skills/
├── sdd-feature/SKILL.md         # Orchestrator skill
├── sdd-analyze-file/SKILL.md    # Analysis skill
...
```

**Pros**: Clear role distinction, agents have persistent memory, specialized system prompts
**Cons**: Agents cannot spawn other agents (only chain via main), less portable

#### Option C: Hybrid (recommended)

```
sdd/agents/
├── sdd-workflow.md              # Top-level orchestrator (use with claude --agent)
├── sdd-reviewer.md              # Quality gate agent (memory: project)
...
sdd/skills/
├── sdd-feature/SKILL.md         # Orchestrator skill (context: fork, invokes other skills)
├── sdd-architect/SKILL.md       # Design skill (context: fork)
├── sdd-analyze-file/SKILL.md    # Analysis skill (context: fork)
...
```

**Rationale**:
- **Agents** for roles that benefit from persistent memory, specialized system prompts, and that run from main context (sdd-workflow as primary, sdd-reviewer with memory)
- **Skills** for everything else — orchestrators, analysis, support, documentation
- Skills can freely chain via Skill tool
- Agents run from main context and can delegate via both Task (parallel) and Skill (sequential)

### 6.5 What Changes in pre-plano.md

| Current Design | Recommendation | Why |
|---------------|---------------|-----|
| 7 agents in `sdd/agents/` | Keep 2-3 as true agents, rest become skills | Most "agents" don't need persistent memory or custom system prompts |
| Agents orchestrate skills | Validated — works via Skill tool | Agents CAN invoke skills, just not other agents |
| `sdd-workflow` as agent | Keep as agent — benefits from `claude --agent` mode | Can spawn Task-based parallel workers |
| `sdd-reviewer` as agent | Keep as agent — benefits from persistent memory | Remembers patterns across reviews |
| `sdd-architect`, `sdd-documenter`, `sdd-tester`, `sdd-migrator`, `sdd-onboarder` | Convert to skills with `context: fork` | Don't need persistent memory, work better as reusable skills |

### 6.6 Tier Mapping Revised

```
INFRASTRUCTURE: sdd-mcp (Python MCP server) + Neo4j + tree-sitter

AGENTS (2-3 true agents in sdd/agents/):
  sdd-workflow.md     — Primary orchestrator (claude --agent mode)
  sdd-reviewer.md     — Quality gate with persistent memory

SKILLS — Orchestrators (context: fork, invoke other skills):
  sdd-feature, sdd-tdd, sdd-refactor, sdd-cleanup,
  sdd-planning, sdd-code-reviewer, sdd-security, sdd-verify,
  sdd-architect, sdd-documenter, sdd-tester, sdd-migrator, sdd-onboarder

SKILLS — Analysis (context: fork, query MCP/read code):
  sdd-analyze-file, sdd-analyze-module, sdd-analyze-complexity,
  sdd-analyze-dead-code, sdd-analyze-dependencies, sdd-impact-analyzer

SKILLS — Foundation (inline or fork):
  sdd-methodology, sdd-detection, sdd-config-reader

SKILLS — Support (mixed fork/inline):
  sdd-doc-*, sdd-qa-*, sdd-test-*, sdd-debug-*, sdd-release,
  sdd-retrospective, sdd-risk, sdd-extract, sdd-setup
```

---

## 7. Portability Considerations

### 7.1 Cross-Platform Compatibility

With 26+ platforms adopting agentskills.io, the SDD Toolkit has broad portability:

| Platform | Skills Support | Agent Support | MCP Support | Nesting |
|----------|---------------|--------------|-------------|---------|
| **Claude Code** | agentskills.io | `.claude/agents/*.md` | Native | Skills chain freely, agents flat |
| **OpenAI Codex** | agentskills.io | Platform-specific | Native | Platform-specific |
| **GitHub Copilot** | agentskills.io | Platform-specific | Native | Platform-specific |
| **Gemini CLI** | agentskills.io | Platform-specific | Native | Platform-specific |
| **Cursor** | agentskills.io | Platform-specific | Native | Platform-specific |
| **VS Code** | agentskills.io | Platform-specific | Native | Platform-specific |
| **OpenCode** | Compatible format | `.opencode/agents/*.md` | Partial | Hierarchical agents |

### 7.2 Portability Layers

| Layer | Portable? | Notes |
|-------|-----------|-------|
| **Skills** (SKILL.md) | **YES** — 26+ platforms | agentskills.io standard. Zero migration needed |
| **Supporting files** (templates, checklists, scripts) | **YES** | Plain files inside skill directories |
| **MCP servers** (sdd-mcp) | **YES** — protocol standard | Config format varies per platform |
| **Agents** (.md definitions) | **NO** — platform-specific | Only 2-3 agents, easy to translate |
| **OpenSpec skills** | **YES** — 20+ platforms | CLI is standalone; skills generated per-tool via adapters (Claude Code, Cursor, Codex, Copilot, Gemini CLI, Windsurf, etc.) |
| **Hooks** (trace_logger, trace_viewer) | **NO** — Claude Code-specific | Hook APIs differ per platform |

### 7.3 Portability Strategy

1. **Maximize skills, minimize agents**: 39 skills are portable; keep agents to 2-3 (platform-specific)
2. **Follow agentskills.io strictly**: SKILL.md with YAML frontmatter, supporting files in standard directories
3. **MCP as the integration layer**: sdd-mcp works across any MCP-compatible client
4. **Accept platform-specific process layer**: OpenSpec, hooks, and agents are Claude Code-specific — small % of total code

### 7.4 Migration Path

To migrate the SDD Toolkit to another code assistant:
1. **Copy `sdd/skills/`** — works unchanged on any agentskills.io platform (39 skills, 0 changes)
2. **Translate 2-3 agent definitions** — small effort, platform-specific format
3. **Configure MCP server** — same server, different connection config
4. **Run `openspec init --tools <target>`** — regenerates OpenSpec skills for the target platform (20+ supported)
5. **Adapt hooks** — rewrite for target platform's event system (if needed)

### 7.5 Portability Risk Assessment

### 7.6 OpenSpec Portability Detail

OpenSpec deserves special mention because it was initially perceived as Claude Code-specific, but is in fact **platform-agnostic by design**:

- **CLI**: Standalone Node.js tool (`@fission-ai/openspec`), no AI dependency
- **Schemas**: Declarative YAML + Markdown templates, editable without code changes
- **Skills**: Generated per-tool via adapter pattern (`openspec init --tools <target>`)
- **Supported tools (20+)**: Claude Code, Cursor, Windsurf, Codex, GitHub Copilot, Gemini CLI, OpenCode, Amazon Q, Antigravity, Cline, RooCode, Trae, and more
- **Syntax adapters**: `/opsx:new` (Claude Code) → `/opsx-new` (Cursor/Copilot) → `/openspec-new-change` (Trae) — automatic transformation
- **Custom schemas**: Fully customizable (e.g., rv-android has `sdd-full` and `quick-path` schemas)

This means **all 5 portability layers** of the SDD Toolkit are portable:

| Layer | Portable? |
|-------|-----------|
| 39 sdd-* skills | YES — agentskills.io (26+ platforms) |
| OpenSpec skills | YES — built-in adapters (20+ platforms) |
| MCP server | YES — protocol standard |
| Supporting files | YES — plain files |
| 2-3 agents | NO — platform-specific (small effort) |
| Hooks | NO — platform-specific (small effort) |

### 7.7 Portability Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| agentskills.io standard changes | Low | Standard is governed by cross-platform consortium |
| `context: fork` behavior differs per platform | Medium | Write skills that work both inline and forked |
| MCP protocol incompatibilities | Low | Protocol is standardized, widely adopted |
| Platform drops agentskills.io support | Very Low | 26+ platforms adopted, industry standard |
| OpenSpec drops platform support | Very Low | Active development, 20+ platforms, MIT license |

---

## 8. Design Guidelines for SDD Toolkit Skills

Based on all findings, these guidelines apply to skill/agent development:

### 8.1 Skill Structure

```
sdd-{name}/
├── SKILL.md              # Required: frontmatter + instructions (<500 lines)
├── templates/            # Optional: report/output templates
├── checklists/           # Optional: reference checklists
├── examples/             # Optional: sample outputs
└── scripts/              # Optional: executable helpers
```

### 8.2 Frontmatter Conventions

```yaml
# Orchestrator skills (user-invocable, forked)
---
name: sdd-feature
description: "Full feature lifecycle: implement new features with spec-driven phases."
context: fork
agent: general-purpose
argument-hint: "[feature-name]"
---

# Analysis skills (NOT user-invocable, forked)
---
name: sdd-analyze-file
description: "Single file deep analysis with mode-aware behavior."
context: fork
user-invocable: false
---

# Foundation skills (inline, Claude-invocable)
---
name: sdd-methodology
description: "SDD principles, P1-P4, workflow track selection."
user-invocable: false
---
```

**Why no `allowed-tools`**: When `allowed-tools` is specified in frontmatter, ONLY those tools are available — all others (including MCP tools like `mcp__sdd__*` and `mcp__memory__*`) are silently blocked. Since skills need MCP tools for Full and Lite modes, omit `allowed-tools` entirely. Enforce tool restrictions via textual instruction in the skill body (e.g., "SHALL NOT use Skill tool or Task tool") rather than via frontmatter filtering.

### 8.3 YAML Frontmatter Pitfalls

Claude Code parses skill frontmatter as YAML. Several YAML syntax rules cause silent failures where `context: fork` is not recognized and the skill falls back to inline execution in the main context — losing isolation and polluting the parent's context window with the skill's full output.

#### 8.3.1 Brackets in `argument-hint` are parsed as arrays

The `argument-hint` field expects a string. But YAML interprets unquoted `[...]` as an inline sequence (array), not a string:

```yaml
# WRONG — YAML parses this as a list ['module-path'], not a string
argument-hint: [module-path]

# CORRECT — quotes make it a string literal
argument-hint: "[module-path]"
```

Proof:

```python
import yaml
yaml.safe_load("argument-hint: [module-path]")
# → {'argument-hint': ['module-path']}   ← list, not string

yaml.safe_load('argument-hint: "[module-path]"')
# → {'argument-hint': '[module-path]'}   ← string, correct
```

When Claude Code's frontmatter parser encounters an unexpected type for `argument-hint`, it can fail to parse the entire frontmatter block. If the frontmatter fails to parse, `context: fork` is not recognized, and the skill runs inline.

This pattern appears in the official Claude Code documentation examples (`[issue-number]`, `[filename] [format]`), but those examples are shown within a description table — not as actual YAML. In YAML, brackets MUST be quoted.

#### 8.3.2 Colons in `description` can cause ambiguity

YAML uses `: ` (colon-space) as the key-value separator. While standard YAML parsers handle colons within scalar values correctly in most cases, defensive quoting prevents edge cases:

```yaml
# RISKY — colon after "gate" could confuse partial YAML parsers
description: Unified verification gate: runs a 3-stage pipeline

# SAFE — explicit string delimiters
description: "Unified verification gate: runs a 3-stage pipeline"
```

#### 8.3.3 Multiline `>-` blocks with special chars downstream

The YAML folded block scalar (`>-`) is valid but adds parsing complexity. If lines following the `>-` block contain YAML special characters at the same indentation level, some parsers may misinterpret where the block ends:

```yaml
# RISKY — the [caminho] on line 4 could confuse the block boundary
description: >-
  Scan project and extract metadata.
  Use after /docgen-init.
argument-hint: [caminho-do-projeto]   # ← YAML array! double problem
context: fork                         # ← may not be reached if parsing fails above
```

#### 8.3.4 Quoting Rules (summary)

| Field | Rule | Example |
|-------|------|---------|
| `description` | Always quote with `"..."` | `description: "Read config and determine mode."` |
| `argument-hint` | Always quote with `"..."` | `argument-hint: "[module-path]"` |
| `name` | No quotes needed (simple alphanumeric + hyphens) | `name: sdd-verify` |
| `context` | No quotes needed (enum: `fork`) | `context: fork` |
| `user-invocable` | No quotes needed (boolean) | `user-invocable: false` |

**Rule of thumb**: quote any frontmatter value that is a free-form string. Leave unquoted only values that are simple identifiers, booleans, or numbers.

#### 8.3.5 Evidence and origin

This issue was identified during the `agente-documentador` project, where skills with `argument-hint: [caminho-do-projeto]` (unquoted brackets) exhibited fork execution failures — the skill ran inline despite having `context: fork`. The fix was adding quotes: `argument-hint: "[caminho-do-projeto]"`. The `hello-claude-code` validation project (`/home/pedro/.../workspace-rv/hello-claude-code`) confirmed fork behavior using skills with simple unquoted descriptions (no special characters), which is why the issue was not caught in that experiment — all test skills used descriptions without colons, brackets, or pipes.

The SDD Toolkit's implemented skills (`sdd-verify`) already follow the correct pattern: `argument-hint: "[module-path]"` (quoted).

### 8.4 Orchestration Rules

1. **Leaf node enforcement**: Analysis and support skills (Tier 2/3) SHALL NOT invoke the Skill tool or Task tool. Enforce via textual instruction ("SHALL NOT use Skill tool or Task tool"), not via `allowed-tools` (which blocks MCP tools)
2. **Orchestrator chaining**: Orchestrator skills (Tier 1) invoke Tier 2/3 skills via Skill tool. They SHALL NOT invoke other Tier 1 skills
3. **Skill chains** should not exceed 3 levels deep (latency: ~3-4s per fork level)
4. **Parallel execution** requires orchestration from main context (Task tool)
5. **State persistence** via MCP (Neo4j/Memory) preferred over context passing

### 8.5 Skill Testing with Reference Projects

Analysis skills produce meaningful output only when run against real codebases with layers, dependencies, and complexity patterns. Testing against the SDD Toolkit's own files (mostly markdown and a single Python script) validates plumbing (mode detection, frontmatter parsing, forked execution) but not analytical quality.

#### Reference Project

The canonical test project is [Spring PetClinic](https://github.com/spring-projects/spring-petclinic), a standard Java application with clear MVC layers, Spring annotations, service/repository/controller separation, and manageable size (~50 source files).

Clone it to `/tmp` with a session-unique name to avoid conflicts when multiple sessions run in parallel:

```bash
TESTDIR="/tmp/sdd-test-petclinic-$(date +%s)"
git clone --depth 1 https://github.com/spring-projects/spring-petclinic.git "$TESTDIR"
```

The `$(date +%s)` suffix (Unix epoch seconds) ensures uniqueness across parallel sessions. After validation, the directory can be deleted:

```bash
rm -rf "$TESTDIR"
```

#### Why Spring PetClinic

| Criterion | PetClinic |
|-----------|-----------|
| Language | Java (plugin with full `layers` and `analysis` config) |
| Architecture | MVC with controller, service, repository, model layers |
| Size | ~50 source files — small enough for Minimal mode, rich enough for layer/dependency analysis |
| Annotations | `@Controller`, `@Service`, `@Repository`, `@Entity` — exercises plugin `layers.annotations` classification |
| Dependencies | Internal cross-layer imports, Spring framework imports — exercises dependency graph building |
| Complexity | Mix of simple and moderate methods — exercises complexity scoring |
| Availability | Public, stable, well-known — no authentication or setup required |

#### Test Targets per Skill

| Skill | Recommended test targets |
|-------|--------------------------|
| `sdd-analyze-file` | `src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java` |
| `sdd-analyze-module` | `src/main/java/org/springframework/samples/petclinic/owner/` |
| `sdd-analyze-complexity` | `src/main/java/org/springframework/samples/petclinic/` |
| `sdd-analyze-dead-code` | `src/main/java/org/springframework/samples/petclinic/` |
| `sdd-analyze-dependencies` | `src/main/java/org/springframework/samples/petclinic/` |
| `sdd-impact-analyzer` | `OwnerController.java` or `OwnerRepository.java` |

These targets exercise the full analysis pipeline: language detection (Java), plugin config loading (`plugins/java/config.json`), layer classification via `layers` field, and mode-specific behavior.

#### Validation Checklist

For each skill invocation against the reference project:

1. **Mode detection**: Skill defaults to Minimal (no `.sdd/sdd-config.yaml` in PetClinic). Verify report says `Mode: minimal`.
2. **Language detection**: Verify `Language: Java` (for file-level skills) or Java file counts (for module-level skills).
3. **Plugin integration**: Verify the skill reads `plugins/java/config.json` (for skills that use `layers` or `analysis` fields). Since PetClinic has no `.sdd/sdd-config.yaml`, the skill must still attempt to read it and default gracefully.
4. **Output format**: Verify all report sections from the spec are present, even if some are empty.
5. **Leaf node**: Verify the skill did not invoke Skill tool or Task tool (check trace if available).
6. **Analytical quality**: Verify the skill correctly identifies layers (controller, service, repository, model), dependencies between them, and complexity hotspots. A report that produces empty tables for a project with 50+ source files indicates a logic problem.

### 8.6 Description Engineering

The `description` field drives Claude's auto-invocation. Use the WHEN/WHEN NOT pattern:

```yaml
description: >-
  Analyze code complexity of a module. Use WHEN performing refactoring
  pre-analysis, code review, or architecture assessment.
  WHEN NOT: for single-file analysis (use sdd-analyze-file instead).
```

---

## 9. Summary

| Finding | Impact | Source |
|---------|--------|--------|
| Task tool absent from subagents | Agents cannot spawn agents; parallelism from main only | Official docs, Issue #4182 |
| Skill tool available everywhere | Skills can chain freely, even from forked contexts | hello-claude-code T4-T11 |
| `context: fork` via Skill had issues | Was broken (Issues #17283/#16803), appears fixed by Feb 2026 | GitHub, our validation |
| 5-level fork tested successfully | Deep skill chains work but add ~3-4s per level | hello-claude-code T11 |
| agentskills.io standard: 26+ platforms | Skills are portable to Codex, Copilot, Gemini, Cursor, etc. | agentskills.io, industry |
| Agent Teams (experimental) | Third orchestration mechanism: multi-session coordination | Official docs (Feb 2026) |
| rv-android uses skills-only model | Proven: orchestrator skills invoke analysis skills via Skill tool | rv-android .claude/skills/ |
| agente-documentador uses "bombadas" | External scripts + MCP tools for complex orchestration | agente-documentador |
| OpenCode supports hierarchical agents | Alternative platform allows deeper nesting natively | hello-opencode |
| Industry consensus: flat hierarchy | 2 levels max recommended for practical workflows | Multiple sources |
| `claude -p` hack exists but not recommended | Fragile workaround for nesting, no visibility/error handling | Issue #4182 comments |

**Bottom line**: The SDD Toolkit primarily uses **skills** (portable to 26+ platforms, chainable, proven) with a small number of **true agents** for roles that genuinely benefit from persistent memory and custom system prompts. The original 7-agent design in pre-plano.md was revised to 2 agents (sdd-workflow, sdd-reviewer) + 5 additional orchestrator skills. The agentskills.io adoption by 26+ platforms makes portability a first-class concern — maximizing skills and minimizing platform-specific agents is the right strategy.

---

## 10. References

### Internal Sources
- `docs/pre-plano.md` — SDD Toolkit implementation plan
- rv-android `.claude/skills/` — 46 skills, orchestrator-component pattern
- agente-documentador `.claude/skills/` — 41 skills, "bombadas" pattern
- [hello-claude-code](https://github.com/phtcosta/hello-claude-code) — Empirical validation of nesting behavior (11 tests)
- hello-opencode — OpenCode alternative implementation with hierarchical agents

### Official Documentation
- [Claude Code: Sub-agents](https://code.claude.com/docs/en/sub-agents) — Subagent architecture, constraints, configuration
- [Claude Code: Skills](https://code.claude.com/docs/en/skills) — Skill structure, frontmatter, invocation
- [Claude Code: Agent Teams](https://code.claude.com/docs/en/agent-teams) — Multi-session coordination (experimental)
- [Agent Skills Specification](https://agentskills.io/specification) — Open standard, 26+ platforms
- [OpenAI Codex: Agent Skills](https://developers.openai.com/codex/skills/) — Codex adoption of agentskills.io
- [VS Code: Agent Skills](https://code.visualstudio.com/docs/copilot/customization/agent-skills) — GitHub Copilot integration

### GitHub Issues (constraints & workarounds)
- [#4182](https://github.com/anthropics/claude-code/issues/4182) — Task tool not exposed in nested agents
- [#17283](https://github.com/anthropics/claude-code/issues/17283) — Skill tool `context: fork` not honored (closed as dup of #16803)

### Industry Sources
- [Anthropic Engineering: "Equipping agents for the real world with agent skills"](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)
- [Unite.AI: "Anthropic Opens Agent Skills Standard"](https://www.unite.ai/anthropic-opens-agent-skills-standard-continuing-its-pattern-of-building-industry-infrastructure/)
- [Sandeep Satya: "Claude Skills vs Sub-agents"](https://medium.com/@SandeepTnvs/claude-skills-vs-sub-agents-architecture-use-cases-and-effective-patterns-3e535c9e0122)
- [Mario Ottmann: "Claude Code Customization Guide"](https://marioottmann.com/articles/claude-code-customization-guide)
- [eesel.ai: "Claude Code Multiple Agent Systems 2026 Guide"](https://www.eesel.ai/blog/claude-code-multiple-agent-systems-complete-2026-guide)
- [claudefa.st: "Claude Code Agent Teams Complete Guide"](https://claudefa.st/blog/guide/agents/agent-teams)
- [dev.to: "Claude Code Skills vs Subagents"](https://dev.to/nunc/claude-code-skills-vs-subagents-when-to-use-what-4d12)
- [youngleaders.tech: "Skills, Commands, Subagents, Plugins"](https://www.youngleaders.tech/p/claude-skills-commands-subagents-plugins)
- [inference.sh: "Agent Skills: The Open Standard"](https://inference.sh/blog/skills/agent-skills-overview)
- [Strapi: "What Are Agent Skills and How To Use Them"](https://strapi.io/blog/what-are-agent-skills-and-how-to-use-them)
- [VoltAgent: awesome-agent-skills](https://github.com/VoltAgent/awesome-agent-skills) — 380+ community skills
- [awesome-claude-code-subagents](https://github.com/VoltAgent/awesome-claude-code-subagents) — 100+ subagent collection

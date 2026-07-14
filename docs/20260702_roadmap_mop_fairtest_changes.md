# Roadmap — Implementação das 6 changes do fair-test MOP (worktree `ape-mop-fairtest`)

**Data:** 2026-07-02
**Branch/worktree:** `mop-fairtest` @ `ape-mop-fairtest` (base `master` f70f986; tudo não commitado — decisão: alteração completa no worktree, *merge* se funcionar, *descarte* se não; nada no master).
**Objetivo final:** destravar o fair-test §7.5 (`docs/20260622_investigacao_mop.md`) e maximizar as métricas — cobertura de UI, cobertura de classes/métodos/métodos-MOP e violações JavaMOP.

Este roadmap fixa a **ordem correta de aplicação** das 6 changes OpenSpec e as dependências que a determinam. Foi derivado de uma validação de consistência com 8 agentes (6 por-change + 2 cross-change), toda claim verificada contra o código do worktree.

---

## 1. As 6 changes

| # | Change | Capacidades (specs) tocadas | Estado atual |
|---|--------|------------------------------|--------------|
| A | `experiment-validity` | model, exploration, naming, ui-tree, mop-guidance, component-triggering | Artefatos-only (sem código) |
| B | `mop-parser-fidelity` | mop-guidance, wtg-navigation | Grupos 1–3 no código; **grupo 4 pendente** |
| C | `mop-discriminative-boost` | action-selection, mop-guidance | Grupos 1–2 no código; **grupo 4 pendente** |
| D | `form-completion` | form-completion (nova), heuristic-input | Grupos 1–6 no código; **grupo 7 pendente** |
| E | `exploration-observability` | action-selection, llm-routing, ui-coverage | UICOV dump no código; **grupo 5 (atribuição) pendente** |
| F | `exploration-effectiveness` | exploration, heuristic-input, mop-guidance, naming, ui-tree | Artefatos-only (sem código) |

Todas passam `openspec validate <change> --strict`.

---

## 2. Fase 0 — Reconciliação de specs (CONCLUÍDA ✅)

Correções spec-only (reversíveis) aplicadas antes de qualquer implementação. Todas as 6 changes seguem passando `--strict`.

- **B (parser-fidelity) — BLOCKER resolvido:** o requisito `MODIFIED "MopData — Static Analysis JSON Loader"` estava escrito sobre a base *pré-gh60* (vocabulário `reachesMop`) e, ao arquivar, reverteria a base e derrubaria o contrato per-event-type que C consome. **Rebaseado sobre a base gh60 atual** (reproduz o modelo tipado completo + todos os cenários da base; enxerta apenas INV-MOP-19/20 e 3 cenários; referencia só o `load` de 3 args). design.md: reconciliação gh13-arquivado, D2 (empty-`idName`) corrigido para a premissa verdadeira, **D5 + INV-MOP-25** (rationale do DIALOG), citações atualizadas.
- **C (discriminative-boost):** `stateMopDensity` especificado **explicitamente com 3 args** (`State, MopData, timestamp`) + tasks sinalizam os 5 call-sites (`SataAgent:702,714,952,955,964`); nota "ships first" trocada por **co-requisito com D** no mesmo sítio `SataAgent:1072`; citação `:414-435`→`:456-488`.
- **E (observability):** task 1.1 `[x]` anotada **SUPERSEDED por §5.1/5.2** (regra branch-level substituída pela sub-path); precedência do check de device corrigida para incluir `Form`.
- **D (form-completion):** tasks 7.2+7.3 **fundidas num único checkbox atômico** (o predicado convergente e a exclusão do submit não podem ser meio-implementados).
- **F (exploration-effectiveness):** NPE do fuzz especificado (`new PointF[4 + count << 1]` = `8+2·count` alocado vs `6+2·count` escrito → 2 nulls finais; guard antes do deref) em spec + tasks; `Configuration Loading` **MODIFIED** para virar `saveGUITreeToXmlEveryStep` default→`false` (remove a contradição com a tabela-base).
- **A (experiment-validity):** LOW — "setUp" → "constructor" (`StatefulAgent:162`).

### Decisão de ownership fixada
`MopData.load` — a mudança de assinatura (3-arg + fail-fast + **remoção do overload de 1-arg**) pertence a **`experiment-validity` (A)**. `mop-parser-fidelity (B)` referencia apenas a forma de 3 args. Isso resolve os dois BLOCKERs semânticos cruzados (S1 = deleção do 1-arg; S2 = reversão do loader).

---

## 3. Fase 1 — Ordem de implementação

> Regra de ouro: **A antes de B** (contrato do `load`); **C e D juntas** no sítio `SataAgent:1072`; **E depois de C+D** (consome os métodos e o enum delas); **F por último** (independente, mas `INV-MOP-23` reusa a política de contenção de C).

| Passo | Change | O que implementar (grupos pendentes) | Por que nesta posição |
|-------|--------|--------------------------------------|-----------------------|
| **1** | **A** `experiment-validity` | Primeiro o núcleo do `MopData.load`: 3-arg + fail-fast + **deletar overload 1-arg** + linha `[APE-MOP-DATA]` (INV-MOP-21/22). Depois o resto: rebuild idempotente (Graph:1293 + reset ActivityNode, INV-MODEL-11), seed `-s` (RandomHelper, INV-EXPL-14), waitForActivity com contador+relaunch (INV-EXPL-15), tearDown em `finally` (INV-EXPL-16), binarySearch `<0` (GUITree.contains:283, Naming:438), dispatchTrigger via `getPackageName()` (INV-CT-04). | Estabelece o **contrato do `load`** de que B depende. Demais itens são independentes e limpos. |
| **2** | **B** `mop-parser-fidelity` | Grupo 4: re-key de janelas DIALOG para a activity host via arestas WTG (INV-MOP-25); `precomputeMopOptionsMenus` via `baseActivity()`; remover comentário obsoleto; testes DIALOG/tripla-colisão. | Reconstrói o substrato de widgets flagados sobre o `load` já reconciliado; C discrimina sobre esse substrato. |
| **3** | **C + D** (integradas) | **C** g4: probe `pickBestMopTarget` dentro de `findGreedyActionForward` antes do `randomPickWithPriority` (`SataAgent:1072`, INV-SEL-MOP-03, **sem reordenar a cadeia SATA**); `stateMopDensity` 3-arg contando só widgets MOP-flagados (INV-MOP-24, 5 call-sites). **D** g7: **7.1** ler `currentState` (INV-FORM-07); **7.2 (atômico)** predicado convergente via `getText()` **+** exclusão do submit no roulette EARLY_STAGE e no `greedyPickLeastVisited` (INV-FORM-06 estendido); **7.3** unificar `isEditText` no set de 4 classes do GUITreeBuilder. | **Editam o MESMO sítio `SataAgent:1072`** (probe MOP de C + exclusão do submit de D). O predicado convergente (D-7.2) é **pré-requisito rígido**: sem ele a exclusão nunca se levanta e o submit fica bloqueado para sempre. Por isso são co-implementadas. |
| **4** | **E** `exploration-observability` | Grupo 5: atribuição `decision_source` restrita aos sub-caminhos que consomem prioridade (roletas `:487`/`:1072` + short-circuits; Back/Menu/least-visited/navegação ficam `SATA`); `DecisionSource.Form` (precedência MOP>WTG>Menu>Form>Coverage); `clock=` no `[APE-STEP]` (**nunca logcat**); testes de atribuição; `screenshot_failed=` separado do `null=` no LlmRouter. | Referencia `pickBestMopTarget`/`selectUnvisitedMopTarget` (de C) e o `formBoost`/enum `Form` (de C+D) — precisa deles no lugar. |
| **5** | **F** `exploration-effectiveness` | Defaults de artefatos→false (INV-EXPL-17); pinch/zoom enfileirado + **fix do NPE de over-alocação** + guard `<6` antes do deref (INV-EXPL-18); StringCache empty-check (INV-INP-06); `setIsPassword` em fillNode (INV-TREE-09); matchKeywords por token (INV-INP-05); cap `maxGUITreesPerState` (NamingFactory:280/:1180, INV-NAME-14); clearChildren while-remove-first (INV-TREE-10); threshold WebView só nós acionáveis (INV-TREE-11); center-click off-screen deletado + log (INV-EXPL-19); input tipado via contenção ±2 (INV-MOP-23). | Em geral independente. `INV-MOP-23` reusa a política de contenção ±2 níveis de C → aplicar depois de C. |

### Grafo de dependências

```
        contrato MopData.load(3-arg)
A ───────────────────────────────────────► B
(load core + fail-fast + del 1-arg)   (substrato: DIALOG re-key, precompute base-key)
                                             │  substrato de widgets flagados
                                             ▼
                            C ◄───── mesmo sítio SataAgent:1072 ─────► D
                    (probe MOP + density)   (co-implementadas)   (submit-exclusion + convergência)
                                             │
                                             ▼  consome pickBestMopTarget / enum Form / formBoost
                                             E
                                     (decision_source sub-path + clock=)

F (perf/qualidade/tree)  — independente; INV-MOP-23 reusa contenção ±2 de C → aplicar após C.
```

---

## 4. Restrições inegociáveis

- **APE NUNCA toca o logcat** — nem leitura (RVSEC/RVSEC-COV são do rv-platform) **nem escrita** (quebra o parser do rv-platform). O único apoio para *join* step↔violação é o `clock=` no `[APE-STEP]` do `.trace` (E-5.3).
- **Princípios (CLAUDE.md):** P1 simplicidade, P3 sem backward-compat/legacy (deletar, não flaggar), P4 comentários de estado atual. Sem flags novas gratuitas.
- **Itens REJEITADOS (não ressuscitar):** marcador `[APE-STEP-MARK]` no logcat; bridge logcat→trace; prompt LLM text-only para FLAG_SECURE (backlog condicionado).
- **Itens DEFERIDOS de alto risco (ablação própria, não entram agora):** saturação por nó exercitado, theta, `isStrong>=2→>=1`, RefinementResult, re-seleção de alvos MOP visitados.

---

## 5. Gates por change (SDD/OpenSpec)

Para cada passo: `openspec status --change <name>` → `/opsx:apply` (implementar tasks) → `mvn test` → `/sdd-verify ape` → `/sdd-code-reviewer` → `openspec validate <name> --strict`. Não arquivar (`opsx:archive`/`opsx:sync`) até o passo estar verde e o item de device/§7.5 correspondente resolvido ou explicitamente deferido.

### Nota de arquivamento (sync/merge final)
- **A** é dona da assinatura do `load`; ao sincronizar, `A` e `B` ambas escrevem em requisitos de `mop-guidance` — arquivar de forma que o loader (owned por B, MODIFIED) e o sanity-check (owned por A, MODIFIED) fiquem coerentes: loader referencia só 3-arg; sanity-check declara a deleção do 1-arg.
- **INV-MOP-07** (fallback de atividade, removido por C) vive fora de requisito na base — reconciliar a seção `## Invariants` no archive de C.
- Base `component-triggering` tem blocos de requisito duplicados (pré-existente) — qualquer MODIFIED futuro contra ela enfrenta header ambíguo.

---

## 6. Resumo dos INV-* por change (sem colisões)

| Change | INVs introduzidos |
|--------|-------------------|
| A `experiment-validity` | INV-MODEL-11, INV-EXPL-14/15/16, INV-MOP-21/22, INV-CT-04, INV-NAME-13, INV-TREE-08 |
| B `mop-parser-fidelity` | INV-MOP-19/20/25, INV-WTG-04/05 |
| C `mop-discriminative-boost` | INV-SEL-MOP-01/02/03, INV-MOP-24 (remove INV-MOP-07) |
| D `form-completion` | INV-FORM-01..07, INV-INP-04 |
| E `exploration-observability` | INV-SEL-04 (mod), INV-COV-07 |
| F `exploration-effectiveness` | INV-EXPL-17/18/19, INV-INP-05/06, INV-TREE-09/10/11, INV-NAME-14, INV-MOP-23 |

Namespaces disjuntos — nenhuma colisão de numeração entre as changes ou com a base.

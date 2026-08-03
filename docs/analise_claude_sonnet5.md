# Análise independente — código-fonte do APE-RV (master + worktree `mop-fairtest` não testada)

**Autor/modelo:** Claude Sonnet 5 (Claude Code)
**Data:** 2026-07-02
**Método:** 4 agentes independentes, cada um lendo o **código-fonte diretamente** (não os traces/relatórios de execução anteriores), com instrução explícita de não confiar cegamente nos números de `docs/20260622_investigacao_mop.md` e de caçar bugs por conta própria. Um 5º agente (re-executado) aprofundou o diff não-commitado do worktree. Repo: `/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape`. Worktree em auditoria: `/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest` (branch `mop-fairtest`, mesmo commit `f70f986` que `master`, mudanças **não commitadas, não compiladas, não testadas em dispositivo**).

**Objetivo declarado pelo usuário (norteia as prioridades abaixo):** as correções em andamento devem melhorar a **exploração** do aperv — maior **cobertura de UI**, maior **cobertura de métodos/classes/MOP**, e **mais violações MOP** encontradas (lançadas pelos monitores JavaMOP em runtime). Toda avaliação de impacto abaixo é filtrada por esse critério, não por "o boost dispara" isoladamente.

---

## 0. Escopo do que foi revisado

| Camada | Arquivos-chave | Agente |
|---|---|---|
| Scoring MOP (parser + score) | `MopData.java`, `MopScorer.java`, `Config.java` | #1 (MopData/MopScorer) |
| Decisão/seleção de ação | `StatefulAgent.java`, `SataAgent.java`, `ModelAction.java` | #2 |
| Cobertura de UI + preenchimento de formulário | `UICoverageTracker.java`, `ApeAgent.java`, `FormCompletion.java` (novo) | #3 |
| Resto do pacote `ape` (naming, model/grafo, tree, events, llm, Config, MonkeySourceApe) | `Naming*.java`, `Graph.java`, `GUITree*.java`, `ApeFuzzer.java`, `MonkeySourceApe.java` | #4 (varredura ampla, do zero) |

A mudança em worktree implementa as 4+1 propostas do plano anterior (`docs/20260622_investigacao_mop.md` §7): **#0** parser fidelity, **#1** form-fill→submit (`FormCompletion.java`, novo), **#2** boost discriminativo + short-circuit greedy, **#3** telemetria `decision_source`, **#4** dump de `UICoverageTracker`, **W** WTG-KEY.

---

## 1. Bugs confirmados na `master` — independentes da investigação de traces anterior

Achados por leitura direta do código, sem qualquer dependência dos traces de junho. Nenhum destes constava no catálogo anterior.

| id | Local | Defeito | Severidade | Confiança |
|---|---|---|---|---|
| **NAM-01** | `Naming.java:252-254` | `hasChild()` **invertido**: `return children == null \|\| children.isEmpty();` retorna `true` quando **NÃO há** filhos. `AbstractNamingManager.isLeaf()` chama isso diretamente. Hoje mascarado porque o `ape.activityManagerType` default (`"state"`) usa `StateNamingManager`, que tem um `isLeaf()` próprio e correto. Se alguém setar `activityManagerType=activity`, `ActivityNamingManager` herda o método quebrado e corrompe os ramos de refinamento (`NamingFactory.java:207,324,346,684,704,1198,1215`). | **Alta** (dormant) | Alta — rastreado ponta a ponta |
| **TREE-01** | `GUITree.java:284` | Uso de `Arrays.binarySearch` verificando `index == -1` como "não encontrado" — mas `binarySearch` retorna qualquer ponto de inserção negativo (não só `-1`) quando não encontra. Cai em `currentNodes[index]` com índice arbitrário → risco real de `ArrayIndexOutOfBoundsException`, no caminho comum de `Model.contains(node)` (atualização de modelo a cada passo). | **Alta** | Alta — rastreado ponta a ponta; é um crash-bug em código quente |
| **GRAPH-01** | `Graph.java:1287-1290` (`rebuildHistory`) | Auto-atribuição: `edge.firstVisitTimestamp = fv` reatribui o valor antigo que acabou de ler, em vez de `tt.getTimestamp()`. Timestamps ficam silenciosamente obsoletos após todo refinamento de naming — afeta lógica de recência/back-edge. | Média | Alta — rastreado |
| **NAM-02** | `Namelet.java:156-162` + `Naming.java:456-457` (`select`) | `filter()` engole `XPathExpressionException` e retorna `null`; o único chamador (`Naming.select`) não checa null antes de `nodes.getLength()` → NPE não tratada no caminho quente de resolução de naming, a cada passo. | Média-Alta (depende de disparar) | Alta no caminho de código; disparo real não confirmado (há pré-validação de XPath em `XPathBuilder.compileAbortOnError`) |
| **NAM-03** | `IndexNamer.java` (`IndexName`) | Falta `equals()`/`hashCode()` (ao contrário do `TextNamer.TextName`, que os tem). O cache de `NameManager` funciona porque chaveia por `toString()`, mas qualquer `Set<Name>`/`Map<Name,_>` chaveado por `equals()` (candidatos: sets de refinamento em `NamingFactory`) trataria toda `IndexName` como distinta mesmo com mesmo índice. | Média | Média — não confirmado exaustivamente em todos os usos |
| **FUZZ-01** | `ApeFuzzer.java:167-192` | Evento de fuzz pinch/zoom é construído mas **nunca enfileirado** (`events.add(...)` ausente) — feature morta silenciosamente. | Média | Alta — rastreado |
| **FUZZ-02** | `ApeFuzzer.java:173` | Bug de precedência de operador no dimensionamento de array — mascarado por FUZZ-01 hoje. | Baixa | Alta |
| **MSA-01** | `MonkeySourceApe.java:792` | Se `updateState` retornar `null`, gera `NullPointerException` não tratada que mata a thread de eventos do Monkey; o handler em `getNextEvent()` só captura `StopTestingException`. | Alta *se* alcançável | Caminho rastreado; condição de disparo (retorno null) não confirmada |
| **MSA-02** | `MonkeySourceApe.java:959-965` (`stopTopActivity`) | Mata apenas `getRunningAppProcesses().get(0)`, sem garantia de que seja o processo do app-alvo. | Suspeita | Não confirmado contra garantias de ordenação do AOSP na faixa de API suportada |
| **SATA-01** | `SataAgent.java` (`checkBackTrack`, BFS não tocado pelo diff), próx. da linha ~269 | Possível bug de visited-set: a checagem `!visited.contains(state)` antes de enfileirar `target` marca `state` como visitado, não `target` — padrão clássico de BFS incorreto (pode reprocessar/perder nós). Não documentado antes. | Média | Suspeita — pede leitura focada adicional, fora do escopo desta rodada |
| **CFG-01** | `Config.java` | Flag `maxStringPieceLength` definida mas sem nenhum outro uso no código — morta. | Baixa | Alta |

**Observação metodológica:** `NAM-01`, `TREE-01` e `GRAPH-01` são os mais preocupantes — nenhum depende de MOP, todos vivem no núcleo (naming/grafo), e `TREE-01` em particular é um crash latente em um caminho executado a cada passo. Nenhum destes três aparecia em nenhuma investigação anterior — reforça o pedido do usuário de não se prender aos dados da execução passada: a varredura independente encontrou defeitos de maior severidade potencial fora do recorte MOP que os traces nunca cobririam (traces só reproduzem o que o código já faz; um `hasChild()` invertido dormant ou um `binarySearch` mal-usado não geram sinal de trace até o caminho ser exercitado).

---

## 2. Avaliação da mudança em andamento (worktree `mop-fairtest`, não commitada/testada)

### 2.1 `#0` — Parser fidelity (`MopData.parseWindows`)
**Veredito: correção logicamente sólida.** Widgets com `idName` vazio agora são descartados (em vez de colidir em um bucket compartilhado morto); o desempate por `mopRank` (`direct > transitive > unflagged`) é estrito, sem off-by-one, independente da ordem de inserção para casos assimétricos. Os novos casos de `MopDataTest` (inserção em ambas as ordens, drop de id vazio com contador, regressão sem-colisão) não são tautológicos.

- **Gap de teste (baixo, novo):** falta um caso de colisão tripla (direct/transitive/unflagged compartilhando o mesmo `idName`, embaralhado). Risco baixo — a lógica par-a-par deveria generalizar — mas não verificado.
- **P4 (comentário no presente) violado (baixo, novo):** comentário em `MopData.java:329` ainda descreve `activityHasMop` como "o substrato do fallback +100", que não existe mais no `MopScorer` pós-`#2`.
- **Inconsistência latente (baixo, novo):** `precomputeMopOptionsMenus` (`MopData.java:621-659`) deriva a chave de activity via *substring-stripping* ad hoc do `OPTIONS_MENU_SUFFIX`, em vez do helper `baseActivity()` compartilhado — usado, por exemplo, pelo próprio `wtgTransitions` já ajustado nesse mesmo diff (`W`/WTG-KEY). Correto hoje por coincidência; se um nome de janela algum dia contiver um `#` anterior, a busca do gateway de menu quebra silenciosamente. Vale unificar já que a função está sendo tocada mesmo assim.
- **Confirmado por replicação independente (2ª leitura do mesmo agente):** a lógica de desempate por `mopRank` é order-independent nos dois sentidos de inserção; nenhum widget a mais é descartado em relação ao baseline. Achado adicional (baixo, novo): `extractShortId` (`MopData.java` master, ~L689-693) já retornava `""` tanto para "sem resourceId" quanto para "resourceId malformado" — dois modos de falha distintos conflados sob o mesmo sentinela `""`; o novo contador `droppedFlaggedNoId` herda essa conflação (não é uma regressão do diff, é um defeito de design pré-existente que o diff apenas expõe melhor). `baseActivity()` usa `indexOf('#')` sem validação — mistruncamento teórico se um nome de classe de activity contiver literalmente `#` (improvável, severidade baixa).
- **Escopo do diff maior que o anunciado (observação):** o diff também rechaveia `wtgTransitions` de "nome de janela sufixado" para `baseActivity()` (a mudança `W`/WTG-KEY) — uma terceira correção, arquiteturalmente distinta das duas primeiras, empacotada no mesmo patch de `#0`. Bem testada (casos 2.3a/b) e sem consumidores órfãos da chave antiga (confirmado por grep), mas vale registrar para quem revisar o PR que `#0` na verdade entrega 3 fixes, não 1.
- **Questão empírica em aberto (a mais importante desta subseção):** com o fallback +100 removido, uma activity cujos widgets MOP-flagged foram todos descartados no parsing agora pontua **identicamente** a uma activity sem MOP algum — o único sinal sobrevivente é `scoreOpenMenu`/`scoreWtg`/`stateMopDensity` (este último é apenas desempate, não score primário). Logicamente consistente com o objetivo (sinal só onde é discriminativo), mas **nenhum teste verifica se isso deixa sinal suficiente para influenciar a seleção de ação** — ponto que só o experimento §7.5 (device-validated) pode responder.

### 2.2 `#2` — Boost discriminativo + short-circuit greedy
**Veredito: remoção do +100 uniforme está completa no `src/`, mas incompleta no OpenSpec.**

- **`openspec/specs/mop-guidance/spec.md` NÃO foi sincronizado (severidade ALTA, novo):** ainda documenta `mopWeightActivity`, o fallback +100 e o invariante `INV-MOP-07` como comportamento vigente — contradiz diretamente o código novo. Isso vai falhar `openspec verify`/`opsx:verify` e induz quem ler o spec como fonte de verdade a erro. **Precisa de `opsx:sync` antes de fechar a change.**
- **Short-circuit greedy (`SataAgent`) é redundante no caso `egreedy()==true`** (o desempate por prioridade em `State.greedyPickLeastVisited` já favorece a ação MOP-boosted não-visitada), mas é **genuinamente necessário e correto no caso `egreedy()==false`** (roleta uniforme) — que é exatamente o mecanismo de diluição que a investigação anterior apontou como causa-raiz da Camada 2. A ordem de precedência vs. os short-circuits existentes de Back/Menu está correta (não usurpa a saída de telas de navegação).
- **Atribuição de `decision_source` (`#3`, ver 2.4) tem risco de correlação-não-causalidade no ramo `EARLY_STAGE`** (busca em grafo por DFS/caminho mais curto que ignora prioridade em parte dos casos) — o rótulo `MOP`/`WTG`/etc. pode ser atribuído a uma ação cujo boost coincidentemente é o maior, mas que **não** foi escolhida por causa do boost. O docstring já se resguarda ("not a counterfactual decisiveness claim"), mas isso é fácil de sobre-interpretar em análises futuras de log.
- **Zero teste unitário cobre `attributeDecisionSource`** — os novos testes (`SataAgentMopShortCircuitTest`, `ModelActionTest`) validam `pickBestMopTarget` isoladamente e getters/setters, não a lógica de atribuição de causa em si. Gap real (médio).
- **Acoplamento frágil (baixo-médio, novo):** `selectSubmitCandidate` é recomputado duas vezes por passo (uma em `selectUnvisitedMopTarget`, outra no passe de boost de `StatefulAgent`) — hoje seguro porque é puro e nada muta a lista de ações entre as chamadas dentro do mesmo `resolveNewAction()`, mas é um acoplamento silencioso entre dois pontos de código que uma edição futura pode quebrar sem aviso.
- **Magnitude não endereçada (observação):** o boost ainda soma diretamente sobre uma `priority` SATA já alta (unvisited +20, aliased, edges) — as mudanças atacam a *diluição pela seleção* (roleta), não a *proporção* do sinal. Consistente com a decisão documentada de tratar isso depois do teste justo.

### 2.3 `#1` — Form-fill → submit (`FormCompletion.java`, novo)
**Veredito: design correto na mecânica local (sem duplo-preenchimento, sem race), mas repousa sobre uma premissa não verificada em dispositivo.**

- **Risco alto, novo:** a convergência de `hasUnfilledEditText`/`isUnfilledEditText` depende de o GUITree capturado no passo seguinte refletir corretamente o texto digitado E de a identidade do widget (resourceID/xpath) permanecer estável entre capturas. `FormCompletionTest.java` **explicitamente adia todos os caminhos dependentes de nó real para "validado em dispositivo"** — ou seja, **zero cobertura automatizada do laço de preenchimento em si**. Se IDs forem dinâmicos ou a subárvore for reinflada (ex.: `RecyclerView`), o campo pode nunca convergir para "preenchido" e gerar re-boost contínuo sem progresso. Isto é o maior risco de todo o worktree para o objetivo do usuário (cobertura/violações) — se não convergir, a sequência preencher→submeter nunca fecha o loop.
- **Heurística de "candidato a submit" pode errar dos dois lados (médio, novo):** `selectSubmitCandidate` escolhe o único `Button` habilitado entre **todas** as ações clicáveis da tela, sem escopo ao formulário — falso-positivo em telas com um Button não relacionado (ex. "Ajuda"/"Cancelar") ao lado de outros clicáveis não-Button; falso-negativo em UIs Compose/AndroxX onde `getClassName()` raramente contém `"Button"` (`buttonCount=0`), caindo no heurístico de palavra-chave — condizente com a lacuna Compose já registrada em memória (`aperv-obfuscation-resilience-via-signature-reachability`).
- Exclusão do candidato de submit no short-circuit MOP (`INV-MOP-06`) está corretamente implementada e testada para o caso isolado, mas depende do mesmo acoplamento frágil citado em 2.2.

### 2.4 `#3` — Telemetria `decision_source`
Implementação estruturalmente correta (zera todos os boosts a cada passe via `resetBoosts()`, sem acúmulo entre passos; precedência de desempate MOP>WTG>Menu>Coverage é determinística, ainda que arbitrária e não documentada como tal). O risco real é o de interpretação (ver 2.2) e a ausência de testes diretos da função de atribuição.

### 2.5 `#4` — Dump de `UICoverageTracker`
**Sem defeitos encontrados.** `dump()` é somente-leitura sobre `stateData` (não perturba a ordem do `LinkedHashMap` de acesso-ordenado usado como LRU), roda uma vez por execução em `tearDown()` (sem custo de performance no laço por-passo), e a matemática de gap/`byType` bate com `getCoverageGap`. Gap de observabilidade conhecido: estados evictados do LRU (`Config.coverageMaxStates`) não são individualmente dumpados, só entram no rollup por Activity — limitação por design, não bug.

---

## 3. Relação com o objetivo declarado (cobertura UI + métodos/classes/MOP + violações MOP)

Nenhuma das 12 mudanças acima altera a **contagem de operações MOP realmente executadas** de forma garantida — o efeito é indireto, via mais tentativas de alcançar/preencher telas MOP. Ordenando por probabilidade de mover as métricas-alvo do usuário:

1. **`#1` Form-fill→submit** é a mudança com maior potencial de impacto direto em "achar mais violações MOP" (destrava o pré-requisito identificado na Camada 3 da investigação anterior: sem form completo, o handler de submit de cripto nunca executa) — **mas é também a de maior risco de não convergir** (ver 2.3). É a que mais precisa de validação em dispositivo antes de qualquer conclusão.
2. **`#0`+`#2`** juntas restauram e tornam decisivo o sinal discriminativo — mas atuam apenas nos **19/169 APKs com substrato estático discriminativo** (achado da Camada 1, não endereçado por este worktree, e nenhum agente desta rodada encontrou motivo para revisar esse número). Ou seja: mesmo perfeitas, essas duas mudanças têm teto de impacto amostral pequeno até que a Camada 1 (produtor/gator) seja atacada — o próprio plano (`docs/20260622_investigacao_mop.md` §5, item 5) já classifica isso como "adiado".
3. **`#3`/`#4`** são puramente observacionais — não movem cobertura ou violações por si, mas são pré-requisito para *medir* se `#0`/`#1`/`#2` funcionaram no experimento mínimo (§7.5 do plano anterior).
4. Os bugs achados fora do MOP (§1 acima) são **ortogonais** ao objetivo declarado no sentido de que não foram desenhados para ele, mas `TREE-01` (crash potencial em `Model.contains`) e `MSA-01` (thread de eventos morta em NPE) são candidatos a **reduzir** cobertura de UI indiretamente se disparados (uma run que crasha cedo explora menos) — vale checar se algum dos 169 APKs do dataset cmpmop teve runs anormalmente curtas/crashed, o que a investigação de trace anterior não teria atribuído a essa causa.

---

## 4. Catálogo consolidado (todos os achados desta rodada, rankeados)

| Prioridade | id | Onde | Ação recomendada |
|---|---|---|---|
| 1 | spec.md desatualizado | `openspec/specs/mop-guidance/spec.md` | Rodar `opsx:sync` antes de fechar a change `mop-fairtest`; sem isso a change não é verificável |
| 2 | FormCompletion não validado em dispositivo | `FormCompletion.java` + teste | Validar em dispositivo/emulador real antes de rodar o experimento §7.5; adicionar teste de convergência com IDs mutáveis simulados se possível |
| 3 | TREE-01 `binarySearch` | `GUITree.java:284` | Corrigir condição de "não encontrado" (checar `index < 0`, não `index == -1`) — risco de crash em produção, independente do MOP |
| 4 | NAM-01 `hasChild()` invertido | `Naming.java:252-254` | Corrigir; hoje dormant mas é uma armadilha para qualquer configuração futura com `activityManagerType=activity` |
| 5 | Submit-heuristic falso-positivo/negativo | `FormCompletion.java` (`selectSubmitCandidate`) | Escopar a busca de Button ao container do formulário; adicionar fallback não-Button para Compose |
| 6 | Teste ausente para `attributeDecisionSource` | `SataAgent.java` | Adicionar teste que force ambos os ramos (EARLY_STAGE vs EPSILON_GREEDY) e valide a atribuição |
| 7 | SATA-01 BFS visited-set | `SataAgent.java::checkBackTrack` | Investigação focada adicional (fora do escopo desta rodada) |
| 8 | MSA-01 NPE na thread de eventos | `MonkeySourceApe.java:792` | Confirmar se `updateState` pode retornar null; se sim, tratar explicitamente |
| 9 | Demais (GRAPH-01, NAM-02, NAM-03, FUZZ-01/02, MSA-02, CFG-01) | vários | Baixo-médio, não bloqueiam o teste justo do MOP; registrar como débito técnico |

---

## 5. O que esta rodada NÃO cobriu (limitações)

- Não foi executado nenhum build/teste (`mvn test`) nem validação em dispositivo — toda a avaliação do worktree é estática/por leitura.
- Não foi re-medido nada dos datasets `cmpmop`/`comparacao_consolidado` (por instrução explícita do usuário de não se prender à execução anterior); os números da Camada 1/2/3 do documento `20260622_investigacao_mop.md` **não foram re-verificados** aqui, apenas o **código** que os produziria.
- `SATA-01`, `NAM-02`, `NAM-03`, `MSA-01`, `MSA-02` precisam de mais uma passada focada (rastreamento de disparo real) antes de virarem itens de correção — hoje são hipóteses fundamentadas em leitura de código, não bugs confirmados em runtime.
- `ape.llm` (Fase 5) recebeu apenas uma varredura superficial — fora de escopo do objetivo declarado (SATA/MOP), conforme instrução herdada do plano anterior.

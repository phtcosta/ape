# APE-RV — isolar ADDONS, ESTÁTICO, MOP e LLM por construção

**Criado:** 2026-10-17 (sessão de exploração `aperv`, nenhum código alterado) · **Base:** `master` `eea2b1a9`
(o jar do E6 v2; `RUN_START.build` = `eea2b1a`) · **Uso:** entrada da change OpenSpec que vai implementar isto.
**Marca:** esta change é **MEASUREMENT** — muda o que um braço mede; nada dela entra num experimento sem decisão
do autor.

Marcas de evidência (`$P3/docs/WORKFLOW.md` §1): `[verified]` — lido nesta sessão, com `arquivo:linha`;
`[reported]` — relatório de subagente desta sessão, não conferido linha a linha. Um número `[reported]` não
justifica decisão nem entra em texto científico até ser conferido. Estado de cada item: **DECIDIDO** (autor,
2026-10-17), **PROPOSTA** (aguarda o autor), **ABERTO** (pergunta sem resposta).

Raízes: `$APE` = este repositório; `$RVA` = `$WS/rvsec/rv-android`; `$P3` =
`$WS/rvsec-study03-replication-package`; `$T` = `$WS/doutorado-tese`; `$WS` =
`/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv`. Caminhos de código relativos a
`src/main/java/com/android/commands/monkey/ape/`.

---

## 1. Por que

O E6 v2 comparou quatro braços: 1 `ape` (jar original), 2 `aperv:mop_off_llm_off` (o "APE-RV sem orientação"),
3 `aperv:mopd_on_llm_off` (orientação MOP por distância), 4 `aperv:mopd_on_llm_90` (3 + LLM). Resultado: C1
(braço 3 × APE) suportado, IRR 1,16 [1,03; 1,31]; C2 (braço 3 × braço 2) não resolvível, 1,05 [0,95; 1,17]
`[reported: decision.md §1–§3, fatos rq-*]`. O artigo não consegue dizer se o ganho vem da orientação ou do
resto do APE-RV, porque:

1. **O braço 2 não é livre de MOP nem de análise estática** (§2.1). O C2 compara a orientação contra um controle
   parcialmente orientado.
2. **O salto APE → braço 2 mistura** os addons do fork com esses resíduos (§2.2), e nenhum braço os separa.
3. **Os grupos se comunicam por um número compartilhado**, a prioridade da ação: os passes MOP e os addons somam
   nela, e regras do SATA que dependem de addons a leem. Daí: o MOP depende de addons para agir, `dec.src` é "o
   maior reforço" e não a causa, e o contrafactual `cf` cobre só o MOP.
4. **A dose do LLM é decidida por regras escondidas** (§2.5): 0,9 configurado virou 69 % de passos chamados e
   ~45 % de passos decididos.
5. **A orientação MOP decide poucos passos** (§2.6): quando há uma ação ponderada na tela, ela é escolhida só
   10 % acima do acaso.

O objetivo é um APE-RV em que cada grupo de comportamento liga e desliga de forma **independente, explícita e
registrada**, sem `if/else` espalhado, de modo que um experimento possa combinar os grupos com o mínimo de
células e cada contraste meça um grupo só.

---

## 2. O que a investigação encontrou

### 2.0 Documentos de origem (onde os problemas foram detectados)

Todos com caminho absoluto, lidos nesta sessão `[verified]`.

**O braço 2 não é livre de MOP nem de análise estática:**
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/posthoc/e6-fork/report.md`:
  - §8.2 (`:262-272`): a tabela das diferenças ativas no braço 2, com a linha "Static-analysis navigation … +200 …
    (not MOP, but from the same static-analysis artifact)" e a linha "MOP-density tiebreak … on";
  - §8.3 (`:274-281`): o desempate como propriedade de desenho registrada, com a contagem de 10 em 489 tarefas;
  - §8.4 (`:283-288`): a evidência anterior de que "ape_pure ≡ ape", noutro jar.
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/handoff/20261017_e6_article_liaison_prompt.md:169-180`:
  "Two open findings about E6's jar", com "Arm 2 is not MOP-free" e "Screenshot rotation", ambos "not decided by
  the author".
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/handoff/20261015_ape_fork_prompt_1.md`
  (`:26`, `:54`, `:94`, `:222`): o prompt da leitura post hoc do fork, com as chaves do braço 2 e o desempate como
  decisão registrada.
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-10-15-e6-campaign/protocol.md`:
  - `:109-118` (§2 "The arms": o braço 2 mantém o documento MOP, os componentes-fonte e o `frontier_boost_weight`
    200);
  - `:893-897` (§12 "Construct — guidance": admite só o desempate).
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/doutorado-tese/docs/estudo-03/escrita/esqueleto/s4/s4-5.md`
  (Rastreio "Braço 2", "Desempate" e a sede da errata A/A, decisão DF-9): o texto do artigo que hoje descreve o
  braço 2 e o desempate.
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/docs/20260714_cmpma.md:214-265`:
  a comparação anterior `ape` × `aperv:ape_pure` × `aperv:sata`, citada pelo §8.4 acima. **Não foi lida nesta
  sessão.**

**O screenshot do LLM é capturado na rotação 0:**
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-09-28-e5c-opaque-screen/decision.md:164-168`:
  declarado para o E6; também valeu para todas as chamadas do E5 e do E5b.
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/HISTORY.md:474`
  (entrada do E5c) e `:785-797` (o artefato do verificador do gate 15 com `back` em paisagem; não é o bug da
  imagem).
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/openspec/changes/archive/2026-10-15-e6-campaign/protocol.md:610`
  e `:926`: "Landscape screens are captured at rotation 0."
- `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec-study03-replication-package/docs/tmp/e6-fork/dynamic-apks/plan_v3b.md:486-495`
  (mudança 22): onde o smoke do V3b reencontrou o bug no beatgame e excluiu os APKs em paisagem.
- Branch `llm-opaque-screen` do `$APE`: `openspec/changes/llm-opaque-screen/design.md` D13 (`1b41c865`),
  `evidence.md`, a correção `f993081f` + `3b35073a` e o reteste em dispositivo `59d19f0c`.
- A memória da sessão do `$P3`:
  `/home/pedro/.claude/projects/-pedro-desenvolvimento-workspaces-workspaces-doutorado-workspace-rv-rvsec-study03-replication-package/memory/v3b-pending-2026-10-16.md`,
  item 1, aberto. Ela diz que as correções passam pela sessão `$APE` só com a palavra explícita do autor, e essa
  palavra veio em 2026-10-17 (D-A9).

### 2.1 O braço 2 ainda consome dado estático e MOP

Configuração real do braço 2 (`RUN_START` de uma tarefa do E6, `cc.sovellus.vrcaa`): preset `mop`;
`mopDataPath` definido; `mopScoring` = `flag` (default); `mopWeightDirect/Transitive/OpenMenu/Wtg` = 0;
`frontierBoostWeight` = 200; `mopActivitySourceComponents` = true; `activityTriggerEnabled` = false (lançador
desligado); features ativas incluem `MOP`, `FRONTIER`, `MOP_ACTIVITY_SOURCE`, `TYPED_FUZZ` `[reported]`. O
`tool.py` da imagem tem o mesmo sha256 do `$RVA` atual `[reported]`.

| Mecanismo | Onde | Dado | É MOP? | Gate hoje | Dose no braço 2 |
|---|---|---|---|---|---|
| Desempate por densidade MOP — ABA | `agent/SataAgent.java:1080-1098` | conjunto (aumentado) de activities MOP | **sim** | só `getMopData() != null` | não registrado |
| Desempate por densidade MOP — caminhos | `agent/SataAgent.java:1342-1364` | idem | **sim** | idem | ≥ 147 linhas em 10 traces (piso) `[reported]` |
| `FrontierPass` +200 | `agent/scoring/FrontierPass.java:34-36` `[verified]` | WTG do artefato | não | `mopData != null && hasWtgData() && weight > 0` | 12.573 de 496.728 passos (~2,5 %), 76 tarefas `[reported]` |
| Texto tipado | `agent/ApeAgent.java:236-252` | `inputType`/`hint` do artefato | não | `fuzzInputTyped && mopData != null` | não registrado; 69/163 artefatos têm `inputType` `[reported]` |
| `MopWidgetPass`, `MenuGatewayPass` | `agent/scoring/` | artefato | sim | construídos se há dado | no-op (peso 0) `[reported]` |

Detalhes que importam para o desenho `[reported]`:
- Nos dois desempates, os estados comparados nunca são o `newState`, então a "densidade" vale só "a activity
  pertence ao conjunto MOP (0/1)". O ramo "mesma activity" (`:1080-1085`) nunca decide. **O resíduo MOP inteiro do
  braço 2 é o conjunto aumentado de activities MOP usado como critério de desempate.**
- Quando a densidade decide em `:1342-1364`, o `RandomHelper.randomPick` é pulado: o fluxo aleatório semeado,
  compartilhado com a roleta, o `inputRate`, o texto tipado e o restart, se desloca dali em diante.
- `Feature.FRONTIER` depende de `Feature.MOP` (`runtime/Feature.java:194-196`) `[verified]`, embora a fronteira não
  use MOP. O WTG do artefato não carrega MOP: o derive monta todas as arestas de clique, sem filtro
  (`$RVA/modules/aperv-tool/src/aperv_tool/tools/aperv/derive_mop_artifact.py:1257-1319`, `_build_wtg`)
  `[verified]`. O MOP entra nos **consumidores** que cruzam WTG × activities MOP (`WtgPass`, `MopFrontierPass`,
  `MopData.recomputeMopOptionsMenus` em `utils/MopData.java:660-676` `[verified]`).
- O protocolo do E6 admite só o desempate (§12 "Construct — guidance"); o gate 5 ("Clean control") não conta a
  fronteira genérica `[reported]`.
- Sem artefato, `frontierBoostWeight=200` aborta (`MISSING_DEPENDENCY`), e fronteira e texto tipado caem juntos:
  hoje não existe configuração "ferramenta completa sem specs" `[reported]`.

### 2.2 O que o fork muda em relação ao APE original

O braço 1 roda o `ape.jar` upstream (`8f51b99c`, 2021), sem `-s`, sem properties `[reported]`. Diferenças de
comportamento ativas no braço 2 `[reported]`:

| Id | Mudança | Onde | Chave que desliga |
|---|---|---|---|
| A | cap de 3 BACK e 3 MENU discricionários por activity | `agent/SataAgent.java:521-615, 772-870` | `ape.backMenuPickCap=0` |
| B | ação `MODEL_MENU` + atalho "menu não visitado" | `model/State.java:78`, `SataAgent.java:545` | `ape.modelMenuEnabled=false` |
| C | `CoveragePass` +100/(1+visitas/5) | `agent/scoring/CoveragePass.java` | `ape.coverageBoostWeight=0` |
| E | `FormCompletionPass` +150/+100 | `agent/scoring/FormCompletionPass.java` | `ape.formCompletionEnabled=false` |
| F | preenchimento determinístico de formulário e exclusão do submit | `ApeAgent.java:199`, `StatefulAgent.java:305`, `SataAgent.java:558,1477` | **nenhuma** |
| G | desempate do least-visited pela prioridade | `model/State.java:185` | `ape.leastVisitedPriorityTiebreak=false` |
| H | ε dinâmico 0,02–0,15 | `SataAgent.java:1190-1215` | `ape.dynamicEpsilon=false` |
| I | budget por activity | `agent/pipeline/BudgetStage.java` | `ape.activityBudgetEnabled=false` |
| J | restart por activity estável em 200 passos | `utils/Config.java:48` | `ape.activityStableRestartThreshold` — **fora do mapeamento do `aperv-tool`** |
| K | reconhecimento AndroidX/Material, ViewPager, limiar de WebView | `tree/GUITreeNode.java`, `tree/GUITreeBuilder.java` | `ape.treeEnhancementsEnabled=false` |
| L | texto heurístico por tipo de campo | `ApeAgent.java:236-252` | `ape.heuristicInput=false` |
| M, N | guards de activity estrangeira e de pacote da árvore | `MonkeySourceApe.java:818, 837` | `ape.foreignActivityGuard`, `ape.treePackageGuard` |

Sem chave nenhuma, correções de bug que mudam comportamento `[reported]`: `Graph.rebuildHistory` sem
incremento duplo; buscas binárias `< 0`; o guard `maxGUITreesPerState` passa a valer; `clearChildren` remove
todos os filhos; ação fora da tela descartada; `waitForActivity` desiste após ~10 s; pinch/zoom de fuzzing
emitido; `StringCache` vazio tratado; RNG semeado em vez de `ThreadLocalRandom`.

### 2.3 O LLM usa MOP

`[verified]` em `llm/ApePromptBuilder.java`:
- marcador `[DM]`/`[M]` por linha (`:472-503`), com o mesmo `MopScorer.weigh` da pontuação;
- contagem `N/M MOP.` e seção `MOP NAVIGATION:` (`:621-651`, `:829-832`);
- instruções MOP fixas no prompt de sistema, presentes **mesmo sem dado** (`:223`, `:245`, `:262` v13, `:281`,
  `:292` v17);
- metadados de widget do artefato (`:515-520`, `:748`, `:818`).

Além disso, os estágios LLM vêm antes do lançador, e um passo do LLM não avança a cadência dele
(`agent/pipeline/MopLauncherStage.java:123-129` `[verified]`): 2,08 lançamentos por tarefa no braço 4 contra
6,67 no braço 3 `[reported]`.

### 2.4 Bug: o screenshot do LLM é capturado na rotação 0

`llm/ScreenshotCapture.java:79-81` chama `SurfaceControl.screenshot(Rect(0,0,w,h), w, h, 0)`. Com o display em
paisagem, `w×h` já vem girado (1794×1080), e a imagem sai em retrato, girada 90° e cortada. Dimensões, lista
`@(x,y)`, `CoordinateMapper` e bounds da árvore estão certos; só a imagem está errada. Foi verificado em
dispositivo no retrowars em 2026-09-27 (branch `llm-opaque-screen`, `evidence.md`) e continua aberto no master
`[reported]`. No E6, o braço 4 deu 328 respostas em paisagem em 31 APKs; a fração por tarefa não foi calculada, e
a fração pré-registrada "chamadas em paisagem" não pôde sê-lo porque a orientação não é registrada `[reported]`.
`AndroidDevice.getRotation()` (`:122-128`) chama `IWindowManager.getRotation()`, que não existe no API 30
`[reported]`.

A correção existe na branch abandonada `llm-opaque-screen`: `f993081f` (rotação na captura, limitada a 0..3, mais
o teste) e `3b35073a` (rotação lida de `getRealDisplay(DEFAULT).getRotation()`). `ScreenshotCapture.java` e
`AndroidDevice.java` não mudaram entre `e93dea86` e `eea2b1a9` `[verified: git diff --stat vazio]`. Os commits
também tocam `CLAUDE.md` e o `tasks.md` daquela change: trazer **só os arquivos de código**, nunca fazer merge da
branch.

### 2.5 As regras que reduzem a dose do LLM

Funil do braço 4 (`$P3/experiments/E6-campaign/results/mechanism/llm-arm/funil_passos_llm.out`, 294.774
passos) `[verified]`:

| Destino do passo | % |
|---|---|
| LLM chamado | 69,0 |
| buffer do SATA não vazio | 12,2 |
| moeda "não" | 7,1 |
| circuit breaker aberto | 5,5 |
| ≤ 2 ações na tela | 4,5 |
| Budget decidiu antes | 1,4 |

Das 203.132 respostas: 54,8 % casaram com um widget, 10,4 % viraram toque por coordenada e 34,5 % não casaram
(par morto) e foram para o fallback `[reported: fatos mech-llm-answer-*]`. O LLM decidiu cerca de 45 % dos passos.

As regras no código `[verified]`:
- **G1/G2:** `agent/pipeline/LlmGate.java:53`: `actionBufferSize() == 0 && getActions().size() > 2`. O javadoc
  diz "três ações ou menos"; o código bloqueia com ≤ 2.
- **G3:** breaker com 3 falhas → aberto por 60 s (`llm/LlmCircuitBreaker.java:34`). Contam como falha o screenshot
  nulo, o transporte e o parse (`llm/LlmEngine.java:111,135,150`); par morto e `no_match` contam como sucesso
  (`:166-178`).
- **G4:** o Budget vem antes, pela ordem do pipeline.
- **G5:** o gatilho new-state chama o LLM em todo estado novo, sem moeda (`agent/pipeline/LlmNewStateStage.java:73`).
- **G6:** o gatilho stagnation chama uma vez por episódio, na metade do limiar de restart
  (`agent/pipeline/LlmStagnationStage.java:92-115`).
- **G7:** a moeda usa o RNG do agente e só é sorteada depois que o portão passa
  (`agent/pipeline/LlmRandomStage.java:83-86`).

### 2.6 Onde o peso decide no SATA

Parcela dos passos por caminho de seleção, em 200 traces por braço, classificados pelos marcadores do Logger
(conferido com o `dec.ch` numa trace) `[reported]`:

| Caminho | Critério | Peso age? | Braço 1 / 2 / 3 |
|---|---|---|---|
| least-visited (ε-greedy) | menor contagem de visitas; empate: upstream = 1º do array, APE-RV = maior prioridade | **só no desempate** | 49,1 / 55,4 / 54,0 |
| roleta EARLY_STAGE | proporcional à prioridade | **sim** | 26,4 / 23,4 / 23,3 |
| roleta ε | proporcional | sim | 2,5 / 2,4 / 2,6 |
| navegação (buffer, ABA, ida a estado guloso, backtrack, budget) | grafo: caminho mais curto, contagem de visitas dos estados | **não** | ~16 / ~18 / ~17 |
| regras fixas (BACK/MENU não visitado, atalho MOP, lançador) | regra | não / só MOP | ~1 / <1 / ~3 |

Prioridade-base do APE: CLICK 32, SCROLL 16–24, LONG_CLICK 16, BACK/MENU 8; +20 se a ação não foi visitada; ajustes
por aresta `[reported]`. Os empates no least-visited são frequentes (53–69 % desses passos), mas a parcela
decidida **pelos reforços** — e não pela prioridade-base — não é mensurável com o log atual.

O funil da orientação no braço 3: 124.490 passos com uma ação ponderada na tela (103 APKs); nesses passos, a ação
executada foi a ponderada em 37,5 %, contra 34,1 % ao acaso (lift 1,10 [1,06; 1,15]); nos passos do LLM com
marcadores, o lift foi 1,42 `[reported: fatos expl-x54-*]`. Escolha mudada pelo MOP: 2,4 % dos passos
(`rq-sq9-guidance-arm3-share-steps-cf-changed`) `[reported]`.

### 2.7 Telemetria

`[reported]`: `dec` guarda só o vencedor; `cf` (`MopCounterfactual`) zera só `mopBoost` e `mopFrontierBoost`, em
quatro sítios, e é 0 por construção no braço 2. Os sorteios da roleta não são registrados. Não registrados:
- desempate ABA;
- texto tipado;
- exclusão pelo cap BACK/MENU;
- restart, cuja ação substitui o `dec.a` depois do registro;
- o caminho dentro de `sata_other`.

O leitor `trace_ndjson.py` do `$RVA` não lê `mopd`/`mops`/`ret` nem o `RUN_END`, e descreve `dec.src` como "o
estágio que escolheu". O `SinkNeutralityTest` não replica o braço 2 real.

### 2.8 Tentativas anteriores com telas dinâmicas

Duas, ambas descartadas pelo autor `[verified: git e decision.md]`:
- **`llm-opaque-screen`** (branch existe, `e91d48bf`; abandonada em 2026-09-28; nunca fazer merge).
  - O E5c rodou o jar `f828e5e6` (portão "zero ações de widget"), sem o predicado de região dinâmica e sem a
    correção da rotação: nem `8125a651` nem `f993081f` são ancestrais dele.
  - Decisão (`$P3/openspec/changes/archive/2026-09-28-e5c-opaque-screen/decision.md` §1): flag desligada, com
    ganho de `cov_method` de −0,07 [−2,61; +2,12] e nenhum ganho de violação; o E5d nunca rodou (teto: 2 de 60
    APKs).
  - O predicado D8 (folha `android.view.View` sem filhos, sem texto, focável ou nativamente clicável, ≥ 50 % da
    raiz, fora de Compose/WebView) ficou implementado na branch.
- **`llm-random-point`** (V3b; branch apagada em 2026-10-16): o V1 (16 APKs) não superou nenhum braço do E6 e
  excluiu os três jogos em paisagem.

---

## 3. Decisões já tomadas pelo autor (2026-10-17)

- **D-A1.** `Feature.FRONTIER` não pode depender de `Feature.MOP`; a fronteira não é MOP e deve ser renomeada.
- **D-A2.** O APE-RV é uma ferramenta genérica e **o JSON da análise estática é opcional**. Sem ele, desliga-se
  tudo que precisa de análise estática e/ou MOP, mantendo os addons próprios.
- **D-A3.** O LLM usa MOP; essa dependência tem de ser explícita.
- **D-A4.** Os grupos são **scorers**: cada um dá peso aos candidatos; a cadeia dos habilitados ajusta os pesos e
  o SATA seleciona com base neles. O laço é simples: se o LLM está ligado, lança a moeda; se ganha, chama o LLM;
  se der erro, fallback.
- **D-A5.** Os gatilhos do LLM (new-state, stagnation) **ficam**.
- **D-A6.** No buffer (G1), a moeda ganha: o LLM tem precedência.
- **D-A7.** O desempate MOP na navegação vira **componente plugável**: o default é o do SATA e, com o MOP ativo,
  usa o componente MOP.
- **D-A8.** O lançador MOP deve ser encaixado no desenho, mas **não antes do LLM**.
- **D-A9.** O bug da rotação deve ser corrigido, reaproveitando ou adaptando a branch `llm-opaque-screen`.
- **D-A10.** Moeda MOP, nos termos do autor: um percentual como o do LLM, depois dele. O LLM decide em 90 % das
  vezes; se der erro, cai na moeda MOP; nos 10 % restantes, vai à moeda MOP; com o LLM desligado, a moeda MOP
  domina.
- **D-A11.** O sistema deve ser o mais simples e elegante possível, com boas práticas e sem `if/else` espalhado.
- **D-A12.** O experimento deve combinar as variantes com o mínimo de células, provando o uso da orientação MOP.
  O autor quer ao menos uma célula **"MOP puro"** e admite juntar addons + estático numa variante só.
- **D-A13.** As 10 variantes atuais do `aperv-tool` são **removidas** (resolve Q-12).
- **D-A14.** Tudo vai numa **change única**, implementada hoje (2026-10-17), com um mini experimento à noite. A
  implementação é planejada para máximo paralelismo (§10).
- **D-A19 (branch).** Todo o trabalho acontece **fora do `master`**, inclusive a criação da change:
  - no `$APE`, numa branch `isolate-guidance-groups` criada a partir de `master` `eea2b1a9`;
  - no `$RVA`, numa branch homônima.
  
  O merge no `master` só acontece depois de testado e **se os resultados forem bons**, por decisão explícita do
  autor. Nada de push ou merge sem a palavra dele.
- **D-A15 (Q-2, Q-3).** Moeda MOP: elegível quando há na tela uma ação com peso de alvo não aposentado e
  **ainda não tentada nesta activity** (a semântica do atalho atual); escolhe a de maior peso; `p_M`
  configurável, **default 1,0**.
- **D-A16 (Q-7).** Lançador por **cadência global**: o contador avança em todo passo; quando vence, o lançamento
  acontece no primeiro passo que chega ao ramo MOP/SATA.
- **D-A17 (Q-6).** Gatilhos **como hoje**: new-state chama em todo estado novo e stagnation uma vez por episódio,
  sem moeda; a moeda `p_L` vale para os demais passos. A dose se descreve como "todo estado novo + `p_L` nos
  demais"; tudo fica registrado em `dec.own`.
- **D-A18 (defaults aprovados).**
  - Q-1: uma chave por grupo sobre o mesmo JSON, e a ausência do JSON desliga ESTÁTICO e MOP.
  - Q-4: X2 (escolha de caminho MOP) fica **fora** desta change.
  - Q-5: a R2 (X1) é o comportamento do SATA do APE-RV; com zero scorers, ele é o do APE.
  - Q-8: na célula MOP+LLM, o LLM lê o sinal MOP.
  - Q-11: Budget e restart como guards (P-2) e RNG por fluxo próprio (P-3).
  - Q-13: sem vetor completo de scorers.

---

## 4. Arquitetura proposta

### 4.1 Grupos e dependências de dado

| Grupo | O que é | Precisa de dado externo? |
|---|---|---|
| **BASE** | o algoritmo do APE (SATA, Naming, modelo) + correções de bug declaradas | não |
| **ADDONS** | nossos aperfeiçoamentos: A, B, C, E, F, G, H, I, J, K, L, M, N (§2.2) | não |
| **ESTÁTICO** | consumidores do modelo estático da GUI: fronteira de navegação (WTG), texto tipado (`inputType`/`hint`), metadados de widget no prompt | sim: o JSON da análise |
| **MOP** | consumidores dos **alvos das specs**: scorer por distância/flag, aposentadoria, gateway de menu, fronteira MOP, `WtgPass`, desempate de navegação MOP, moeda MOP, lançador, marcadores/contagem/instruções MOP no prompt | sim: o JSON |
| **LLM** | moeda, portões, gatilhos, chamada, mapeamento, fallback | não (servidor) |

Regra de montagem (D-A2): cada feature declara o dado de que precisa. Sem o JSON, as features de ESTÁTICO e MOP
**não são montadas**, por dependência declarada no `Feature`/`RunSpec`, não por `if`. Ligar um grupo sem o dado
continua sendo um erro de plano (`MISSING_DEPENDENCY`), como hoje.

**PROPOSTA P-1 — separar o dado.** O loader do jar divide o artefato (wire format 2, **inalterado**, sem mudança
no derive) em dois provedores só de leitura:
- `StaticGuiModel`: WTG + metadados de widget;
- `MopData`: alvos, distâncias, handlers, activities MOP.

ESTÁTICO lê só o primeiro; MOP lê os dois. `FrontierPass` e `TypedInputGenerator` passam a ler o
`StaticGuiModel`. `FRONTIER` é renomeada (por exemplo `NAV_FRONTIER`) e passa a depender do provedor estático, não
de `MOP` (D-A1). A chave `ape.frontierBoostWeight` permanece.

**ABERTO Q-1 — uma ou duas variáveis de dado?** Com D-A2, "MOP desligado" = sem JSON = sem ESTÁTICO. Para o
experimento, o autor admite juntar ADDONS + ESTÁTICO numa variante (D-A12); então a célula "addons" carrega o
JSON e só o grupo MOP fica desligado. O plano precisa das duas formas: (a) uma chave por grupo (`addons`,
`static`, `mop`, `llm`) sobre o mesmo JSON; (b) a ausência do JSON desligando ESTÁTICO e MOP juntos.

### 4.2 O passo

```
1. captura + guards de segurança (foreign activity, tree/package)          [igual ao atual]
2. GUITree → Naming → estado abstrato; grafo, refinamento, trackers         [igual ao atual]
3. abre o registro do passo                                                 [igual ao atual]
4. GUARDS de exploração (addons): Budget, restart                           [PROPOSTA P-2]
5. MOEDA LLM   (se LLM montado): u_L < p_L ?
       portões declarados (§4.4) → chama o LLM → ok: executa (dono = LLM)
                                              → erro/recusa: segue para 6
6. MOEDA MOP   (se MOP montado e elegível): u_M < p_M ?
       → a ação ponderada de maior peso (dono = MOP)
7. SATA: cadeia de scorers habilitados → pesos → regras do SATA,
         com os pontos de extensão (§4.3)                              (dono = SATA)
8. refinadores da ação escolhida (texto do input), registrados
9. eventos Monkey + fuzzing; desfecho resolvido no passo N+1               [igual ao atual]
```

**A moeda MOP (D-A10), como o autor a formulou:**
- Com o LLM montado: em `p_L` dos passos o dono pretendido é o LLM. Se o LLM falha, recusa ou um portão o
  barra, e nos `1 − p_L` restantes, o passo vai à moeda MOP.
- Com o LLM desligado, a moeda MOP é a primeira camada.
- A moeda MOP só é lançada se o MOP for **elegível** no passo, isto é, há na tela uma ação com peso de alvo não
  aposentado. Senão, o passo vai direto ao SATA.

A definição exata de "elegível" e de "a ação de maior peso" (desempate entre iguais, ações já visitadas,
interação com a aposentadoria e com o cap atual `mopTargetPickCap`) é **ABERTO Q-2**. O atalho atual
(`SataAgent.java:574-700`, `:1500`) é o candidato natural a virar esse decisor; ele hoje exige ação **não
visitada**.

Consequência a declarar: na célula MOP+LLM, o MOP decide só nos passos que o LLM deixa, cerca de
`(1 − p_L) + p_L·(fração barrada ou com falha)` dos passos elegíveis. É o tratamento escolhido, não um vazamento.

**ABERTO Q-3 — o valor de `p_M`.** Nenhum número foi proposto com base. `p_M = 1` é "sempre que puder" (máxima
dose, sem controle interno); `p_M < 1` cria passos elegíveis não orientados na mesma execução, mas a análise por
cena (§6.3) mostra que a comparação passo a passo é contaminada. A decisão é do autor.

**PROPOSTA P-3 — RNG.** A moeda LLM, a moeda MOP e o fuzzing têm **fluxos próprios** (sementes derivadas da
semente da execução) e são sorteadas **em todo passo, antes de qualquer portão**. Ligar, desligar ou mudar um
portão não desloca as escolhas do SATA. Isso muda a sequência aleatória em relação ao E6 (hoje G7 usa o RNG do
agente) e precisa de teste de neutralidade próprio.

**PROPOSTA P-4 — preempção do buffer sem penalizar o grafo (D-A6).** O SATA já recalcula quando o caminho do
buffer não bate (`StatefulAgent.java:597-656` `[verified]`: ação inesperada → `clearBuffer` → nova escolha). Mas
`clearBuffer()` (`:562-567` `[verified]`) chama `weakenStateTransition`, que enfraquece uma aresta que não falhou.
A preempção pelo LLM ou pela moeda MOP usa uma operação distinta, **abandonar sem enfraquecer**, e o registro
marca "buffer abandonado (dono)". A falha real do caminho continua enfraquecendo, como no APE.

### 4.3 Scorers e pontos de extensão do SATA

**Scorers (D-A4).** Um scorer = um termo somado à prioridade de cada candidato. Regras:
- **R1:** cada scorer calcula o seu termo só da tela, do modelo e do seu próprio dado, e **nunca lê o peso atual**.
  A cadeia é comutativa, e ligar B nunca muda o que A faz. O valor de cada scorer é guardado por candidato num
  campo próprio (já existe: `mopBoost`, `coverageBoost`, …), e a soma desses campos é o "reforço".
- Scorers previstos:
  - ADDONS: cobertura, formulário;
  - ESTÁTICO: fronteira de navegação;
  - MOP: widget (distância/flag), fronteira MOP, WTG-MOP, gateway de menu.
- A telemetria separa o reforço da fronteira genérica do `WtgPass`. Hoje os dois somam em `wtgBoost`/`dec.wtg`,
  distinguidos só por `wtgsrc`.

**O SATA consulta pesos só onde o APE já consulta (§2.6):** roletas, proporcionais, e o desempate do
least-visited. A regra de visitas do APE não muda (preferência do autor de não alterar o APE). Pontos de extensão,
todos com o **comportamento do APE como default, inclusive no consumo de RNG** (Strategy):

| # | Ponto | Sítio | Default (APE) | Variante |
|---|---|---|---|---|
| X1 | desempate do least-visited | `model/State.java:185` (`beatsLeastVisited`) | manter o primeiro do array | **PROPOSTA P-5 (R2):** maior soma dos scorers |
| X2 | escolha entre caminhos | `SataAgent.java:1453` (`randomPickShortest`; ABA `:1060`, navegação `:1550`, backtrack `:1593`) | ordenação estável por comprimento, o primeiro | ABERTO Q-4: variante MOP (destino com alvo não aposentado) |
| X3 | desempate do alvo ABA | `SataAgent.java:1080-1098` | **nunca substituir no empate**, sem sorteio | MOP: activity do conjunto MOP (D-A7) |
| X4 | caminho para activity trivial | `SataAgent.java:1342-1366` | **sempre `randomPick`** (como upstream `:755-757`) | MOP: maior densidade (D-A7) |

Sobre X1/P-5: hoje o addon G compara a **prioridade total**, que inclui a prioridade-base por tipo de ação do APE
e por isso muda escolhas mesmo sem scorer nenhum. A R2 compara só a soma dos scorers: com zero scorers, ela
devolve o comportamento upstream nesse caminho; com scorers, o peso desempata. **Não basta** para o peso vencer
uma contagem de visitas menor, o que exigiria mudar o algoritmo do APE. **ABERTO Q-5:** a R2 é o comportamento do
APE-RV com qualquer scorer ligado, ou fica como addon?

### 4.4 Portões do LLM: declarados e plugáveis

Padrão Specification:

```java
interface LlmGate { String name(); boolean admits(StepContext ctx); }
```

Implementações:
- `MinActionsGate(min)` (G2, default 3);
- `BreakerGate` (G3);
- outros, se surgirem: um `DynamicRegionGate` portaria o D8 da branch `llm-opaque-screen`, mas **fora desta
  change** (§2.8).

O G1 deixa de ser portão (D-A6 + P-4), e o G4 sai porque o Budget vira guard (P-2). A raiz de composição monta os
portões a partir do `RunSpec`. Cada portão tem chave e default iguais ao comportamento atual e aparece no
`RUN_START`; um portão com parâmetro neutro não é montado. Composição por `AllOf`/`AnyOf`, sem herança.

**Gatilhos (D-A5):** new-state e stagnation ficam como estão hoje. O desenho só precisa dizer como cada um
interage com a moeda (**ABERTO Q-6**): o new-state chama sem moeda, como hoje (a "dose" passa a ser descrita
como "todo estado novo + `p_L` nos demais passos")? O stagnation consome a moeda?

Correções no LLM: o javadoc do `LlmGate` (G2); a moeda num fluxo próprio (P-3); o registro separa pretendido,
barrado (por qual portão), chamado, agiu e fallback (motivo).

### 4.5 O lançador MOP (D-A8)

Hoje o lançador dispara a cada `cadence` passagens pelo **próprio estágio**, e por isso um passo tomado por um
estágio anterior não avança o relógio (`MopLauncherStage.java:123-129` `[verified]`). Duas formas candidatas,
ambas depois da moeda LLM (**ABERTO Q-7**):
- **(a) cadência global:** o contador avança em **todo** passo; quando vence, o lançamento acontece no primeiro
  passo que chega ao ramo MOP/SATA. Mantém a semântica do E6, sem fome.
- **(b) fuga da estagnação:** um ponto de extensão "como fugir da estagnação" no SATA, com o default do APE
  (restart) e a variante MOP (lançar uma activity MOP não visitada; se não houver, restart). Usa os contadores de
  estabilidade que o APE já mantém. Muda a semântica (não é mais cadência fixa); a frequência pode ser estimada
  offline nas traces do E6 (linhas "is stable for").

Num caso como no outro, cada lançamento é registrado com dono = MOP e canal = lançador.

### 4.6 O prompt do LLM

O prompt é montado a partir das **contribuições dos scorers montados**. O grupo MOP contribui os marcadores
`[DM]`/`[M]`, a contagem, a seção `MOP NAVIGATION` e as frases MOP do prompt de sistema; o grupo ESTÁTICO
contribui os metadados de widget. Sem o grupo, a seção não existe, e o `ApePromptBuilder` não testa `mop != null`.
Isso corrige as instruções MOP presentes sem dado (§2.3). A dependência LLM → MOP fica explícita (D-A3): na célula
MOP+LLM o LLM lê o sinal MOP; na célula LLM sem MOP, não. **ABERTO Q-8:** na célula MOP+LLM, o LLM lê o sinal MOP
(como no E6) ou não?

### 4.7 Mudanças sem chave

**PROPOSTA P-6:**
- F (preenchimento determinístico e exclusão do submit) passa a ser regido por `formCompletionEnabled`;
- J entra no mapeamento do `aperv-tool` (mudança no `$RVA`);
- as correções de bug sem chave ficam na BASE e são listadas no `design.md` e no `RUN_START` (o `build`
  identifica o jar).

Com isso, a BASE não é idêntica ao APE byte a byte, e isso se declara. A equivalência se verifica por um A/A num
subconjunto (§6.4).

---

## 5. Telemetria

O jar registra **decisões**, nunca desfechos. Ele não lê logcat e não pode decidir por cobertura ou violação: a
cena, as janelas R-A2 e a atribuição são reconstruídas offline (`$P3/.../specs/e6-mechanism` "The scene
reading"). Campos propostos, todos puros, sem consumo de RNG, sem `resolveAction` e sem efeito no cache, no molde
de `MopCounterfactual`:

| Campo | O que registra |
|---|---|
| `dec.own` | dono pretendido e executado; moedas (`u_L`, `u_M`); portão que barrou; motivo do fallback |
| `dec.cfc` | contrafactual por scorer, nos sítios que consomem prioridade (roletas, least-visited): "a escolha seria outra sem este scorer?" — generaliza o `cf` atual, que cobre só o MOP |
| `dec.draw` | `[idx,total]` dos sorteios de roleta, para replay offline |
| `dec.nav` | uso de X2–X4: o componente que decidiu, o corte/caminho com e sem a variante, `drew` 0/1 |
| `dec.inp` | origem do texto: tipado / heurístico / aleatório / formulário |
| `dec.restart` | tipo do restart (act / graph / state / periódico) |
| `dec.bmc` | candidato excluído pelo cap BACK/MENU neste pick |
| `dec.strat` | o caminho do SATA (least-visited, roleta, ABA, navegação, backtrack, …), em vez de `sata_other` |
| `dec.rot` | rotação do display em todo passo (torna mensurável a fração em paisagem) |
| `dec.buf` | buffer abandonado e por quem (P-4) |
| `RUN_END.counters` | lançamentos, passos perdidos por portão, guard BACKs |

Regra de completude: **todo mecanismo que pode decidir ou mudar um passo aparece na trace**, com o fato de ter
agido e, onde é barato, o contrafactual:

| Mecanismo do desenho | Onde aparece |
|---|---|
| grupos ligados, doses, portões, modo do lançador, variante X1–X4, nome da variante | `RUN_START` (params, features, inert, digest + variante) |
| moeda LLM, portão que barrou, chamada, agiu, fallback e motivo | `dec.own` + `llm[]` (já existe) |
| moeda MOP, elegibilidade, ação escolhida | `dec.own` + `dec.mopd`/`mops` (já existem) |
| cada scorer: o termo da ação escolhida e se mudou a escolha | campos de reforço (já existem) + `dec.cfc` |
| caminho do SATA e sorteio | `dec.strat` + `dec.draw` |
| desempates e escolha de caminho (X1–X4) | `dec.nav` (e `dec.cfc` para o X1) |
| lançador, Budget, restart | `dec.own` (dono = guard/lançador) + `dec.restart` + `RUN_END.counters` |
| buffer abandonado por preempção | `dec.buf` |
| cap BACK/MENU, origem do texto, rotação | `dec.bmc`, `dec.inp`, `dec.rot` |
| guards de activity estrangeira e de pacote | `RUN_END.counters` (o BACK do guard não é um passo) |

Fica de fora por desenho: o **desfecho** (cobertura, violações, cenas), que é offline. Também fica de fora o
**valor de cada scorer para cada candidato**, porque o volume seria de N candidatos × scorers por passo; o
`dec.cfc` responde à pergunta causal sem esse volume. **ABERTO Q-13:** se o autor quiser o vetor completo, ele
entra atrás de uma chave de diagnóstico, desligada nas campanhas.

Gates de verificação: um `SinkNeutralityTest` estendido com um preset por célula do experimento (§6), incluindo a
célula de controle real (hoje o teste usa o preset `APERV` sem dado); e um **gate de controle limpo** — numa célula
sem MOP, todo campo do grupo MOP é ausente ou 0.

Do lado do `$RVA` (fora deste repositório): o leitor passa a ler os campos novos, mais `mopd`/`mops`/`ret` e o
`RUN_END`, e corrige a descrição de `dec.src`.

---

## 6. O experimento

### 6.1 Fatores

`B` = base (≈ APE) · `AS` = ADDONS + ESTÁTICO numa variante (D-A12) · `M` = MOP · `L` = LLM.

### 6.2 Células em discussão (ABERTO Q-9)

| # | Célula | AS | M | L | Papel |
|---|---|:-:|:-:|:-:|---|
| 1 | `base` | – | – | – | referência ≈ APE |
| 6 | `mop` ("MOP puro", D-A12) | – | ✓ | – | MOP sobre o APE |
| 2 | `as` | ✓ | – | – | controle limpo: tudo menos specs |
| 3 | `as_mop` | ✓ | ✓ | – | orientação MOP sobre a ferramenta completa |
| 4 | `as_llm` | ✓ | – | ✓ | LLM sem MOP |
| 5 | `as_mop_llm` | ✓ | ✓ | ✓ | MOP + LLM |

- As células 1, 6, 2 e 3 formam um 2×2 AS × M sem LLM. Os efeitos simples 6×1 e 3×2 provam o MOP, e a
  interação diz se o MOP precisa dos addons.
- As células 2, 3, 4 e 5 formam um 2×2 M × L sobre AS. Medem o efeito MOP sob LLM e a interação M × L.
- Cortes possíveis: a célula 4 (perde o LLM sem MOP e a interação M × L) e a célula 1 (vira um A/A num
  subconjunto).
- A arquitetura de §4 reduz a interação AS × M por construção, porque o MOP decide pela própria moeda. Mas ela
  não é zero: os scorers MOP e os addons atuam nas mesmas roletas e no mesmo desempate.

### 6.3 O que não funciona como prova por passo

Aleatorizar por **passo** e comparar "o passo seguinte" não serve. O efeito de uma escolha costuma aparecer na
**chegada** à próxima activity, que a R-A2 atribui à janela de chegada da cena seguinte. A exposição é definida
por cena (`cf_changed`, `common`). E as linhas de ocorrência são limitadas a uma por 100 ms. A prova do mecanismo
fica nas células mais a leitura por cena offline, agora sobre um controle sem resíduo.

### 6.4 Validação da base

Um A/A da célula `base` contra o `ape.jar` original num subconjunto de APKs, com as diferenças declaradas
(correções de bug, RNG semeado). **ABERTO Q-10:** o braço `ape` original fica na campanha ou só nesse A/A?

### 6.5 Poder

O E6 estimou ~538 APKs para 80 % de poder conjunto a um IRR verdadeiro de 1,10 no braço 3 `[reported:
decision.md :869-871]`. Mais APKs rendem mais que mais réplicas. Um controle limpo não garante um efeito maior,
só um efeito interpretável.

---

## 7. Perguntas em aberto (resumo)

| Id | Pergunta |
|---|---|
| Q-1 | ~~Chaves por grupo sobre o mesmo JSON + ausência do JSON desligando ESTÁTICO e MOP: as duas formas?~~ — **DECIDIDO** (D-A18) |
| Q-2 | ~~Definição de "MOP elegível" e de "a ação de maior peso" da moeda MOP (visitada?, empate, aposentadoria, cap)~~ — **DECIDIDO** (D-A15) |
| Q-3 | ~~Valor de `p_M`~~ — **DECIDIDO** (D-A15) |
| Q-4 | ~~Variante MOP na escolha entre caminhos de navegação (X2): entra nesta change?~~ — **DECIDIDO** (D-A18: fora) |
| Q-5 | ~~R2 (X1) como comportamento do APE-RV com scorers, ou como addon?~~ — **DECIDIDO** (D-A18) |
| Q-6 | ~~Interação dos gatilhos new-state/stagnation com a moeda LLM~~ — **DECIDIDO** (D-A17) |
| Q-7 | ~~Lançador: cadência global (a) ou fuga da estagnação (b)?~~ — **DECIDIDO** (D-A16: cadência global) |
| Q-8 | ~~Na célula MOP+LLM, o LLM lê o sinal MOP?~~ — **DECIDIDO** (D-A18: sim) |
| Q-9 | Quais células (§6.2) |
| Q-10 | O `ape.jar` original na campanha ou só no A/A |
| Q-11 | ~~P-2 (Budget e restart como guards antes da moeda LLM) e P-3 (fluxos de RNG próprios)~~ — **DECIDIDO** (D-A18: sim) |
| Q-12 | ~~Remover as 10 variantes atuais~~ — **DECIDIDO** (D-A13): remover |
| Q-13 | ~~Vetor completo de scorers por candidato atrás de uma chave de diagnóstico?~~ — **DECIDIDO** (D-A18: não) |

---

## 8. Riscos

- **O efeito MOP pode encolher.** Um MOP "só pesos + moeda + extensões" é diferente do E6 (§2.6). O efeito por
  canal passa a ser medido, e um efeito menor é um resultado, não um defeito.
- **Comparabilidade com o E6.** É uma ferramenta nova: o E6 não é reaproveitado como braço, e o artigo do E3
  continua descrevendo o jar `eea2b1a9`.
- **RNG.** P-3 e os defaults X3/X4 mudam a sequência aleatória em relação ao `eea2b1a9`; o oráculo de paridade
  precisa de goldens novos, capturados de propósito.
- **Escopo.** A change mexe em `SataAgent`, `StatefulAgent`, `State`, `ScoringPipeline`, `DecisionPipeline`, nos
  estágios LLM, `ApePromptBuilder`, `MopData`, `Feature`/`KeyOwnership`/`Presets`/`RunSpec`, `ScreenshotCapture`,
  `AndroidDevice` e na telemetria, além do `aperv-tool` no `$RVA`. Pode valer dividir em changes ordenadas, como no
  roadmap `docs/plans/20260802_rearchitecture_roadmap.md`.
- **P1 Simplicidade** (`openspec/config.yaml`). Cada ponto de extensão precisa de uso real nesta change: X2 só
  entra se Q-4 disser sim.

---

## 9. Mudanças no `aperv-tool` (rv-android)

O `aperv-tool` (`$RVA/modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py`, último commit no módulo `925edbc6`,
2026-10-10) é quem traduz uma variante em `ape.properties` + linha de comando e empurra o artefato para o device.
Esta change exige uma change irmã no `$RVA`, com o workflow e as specs dele (`$RVA/docs/WORKFLOW.md`).

### 9.1 Estado atual

`get_variants()` (`tool.py:354-541`) define 10 variantes `[verified]`:

| Variante | Preset | `mop_data` | Uso conhecido |
|---|---|---|---|
| `default` = `sata` | `aperv` | — | APE-RV sem MOP e sem LLM |
| `sata_mop` | `mop` | `static_analysis` | identidade do corpus congelado: 4.096 traces `aperv:sata_mop` e 1.066 arquivos em `results/` carregam o nome (INV-APV-42, comentário em `:367-370`) |
| `sata_llm` | `llm` | — | identidade de preset |
| `sata_mop_llm` | `llm_mop` | `static_analysis` | identidade de preset |
| `mop_on_llm_off` | `mop` | `static_analysis` | "reference" do gh90 (modo `flag`) |
| `mop_off_llm_off` | `mop` | `static_analysis` | o braço 2 do E6 (§2.1); o comentário em `:424-445` justifica manter o artefato e diz que "frontier and WTG passes keep running", mas o `WtgPass` não roda (`mopWeightWtg=0`) `[reported]` |
| `mop_on_llm_70` | `llm_mop` | `static_analysis` | E5/E5b (modo `flag`, LLM 0,7) |
| `mopd_on_llm_off` | `mop` | `static_analysis` | o braço 3 do E6 |
| `mopd_on_llm_90` | `llm_mop` | `static_analysis` | o braço 4 do E6 |

Comportamento relevante `[reported]`:
- O artefato é derivado e empurrado para `/data/local/tmp/mop-artifact.json` em toda variante com
  `mop_data == "static_analysis"` (`:1313-1342`); o `system-broadcast.json` vai para toda variante (`:1300-1311`).
- O `ape.properties` (`_push_properties`, `:912-968`) recebe `ape.preset`, depois `ape.mopDataPath`, depois cada
  override traduzido por `APERV_PROPERTY_MAPPING` (`:140-230`).
- **Chaves do jar ausentes do mapeamento:** `ape.activityStableRestartThreshold` (o addon J),
  `ape.takeScreenshotForEveryStep`, `ape.saveGUITreeToXmlEveryStep`, `ape.llmBoundaryTopPct`.
- O leitor de análise `$RVA/modules/aperv-tool/src/aperv_tool/analysis/trace_ndjson.py`:
  - não lê `dec.mopd`/`mops`/`ret`;
  - ignora o `RUN_END` de propósito (`:83-88`);
  - descreve `decision_source` como "o estágio que selecionou" (`:417`) e o `Counterfactual` como prova de que "a
    orientação mudou algo" (`:359-366`), sem dizer que ele zera só os reforços MOP.
- Testes: `tests/test_aperv_tool.py:95-170` fixa o conjunto de variantes (INV-APV-05).
- O braço `ape` original é outra ferramenta (`$RVA/modules/rv-tools/src/rv_tools/builtin/ape/`) e não muda.

### 9.2 Proposta

**PROPOSTA P-7 — uma variante por célula, e nada mais.** As variantes são as células de §6.2, com nomes que dizem
os grupos ligados:

| Variante | Grupos | `mop_data` | Overrides próprios |
|---|---|---|---|
| `base` | — | — | — |
| `mop` | MOP (+ o dado) | `static_analysis` | `p_M` |
| `as` | ADDONS + ESTÁTICO | `static_analysis` | — |
| `as_mop` | ADDONS + ESTÁTICO + MOP | `static_analysis` | `p_M` |
| `as_llm` | ADDONS + ESTÁTICO + LLM | `static_analysis` | `llm_url`, `p_L` |
| `as_mop_llm` | todos | `static_analysis` | `llm_url`, `p_L`, `p_M` |

- **Uma variante = grupos + doses + URL do servidor.** O que cada grupo liga mora no jar (presets compostos por
  grupo, Q-1), não em overrides do Python: o Python não repete chave por chave o que um grupo significa. É o
  princípio que o `rearch-05-thin-python-arms` já seguiu ("arms = preset + overrides").
- **O artefato vai ao device se e só se** um grupo que precisa dele está ligado (ESTÁTICO ou MOP). O
  `system-broadcast.json` vai só se o grupo de componentes estiver ligado.
- **O nome da variante entra no `RUN_START`** (hoje entra só o preset), para que a trace diga a célula sem
  depender do nome do arquivo.
- Os comentários de `tool.py` descrevem o que é (P4): sai o comentário desatualizado de `:424-445`.

**Q-12 — DECIDIDO (D-A13): remover as 10 variantes.** O contexto da decisão:
- **Identidade de dados (INV-APV-42).** `sata_mop` nomeia milhares de traces congeladas; resume e consolidação
  de campanhas antigas acham as execuções pelo nome. Mas essas campanhas rodam com a imagem congelada
  (`rvandroid:0.9.5`, cujo `tool.py` está em `$P3/experiments/E6-campaign/config/image/tool.py`), não com o
  `$RVA` atual. Se a regra for "campanha antiga = imagem antiga", remover os nomes do `$RVA` não as quebra.
- **P3 do projeto (sem compatibilidade retroativa).** Com o jar novo, as variantes antigas nem descrevem mais o
  mesmo comportamento: mantê-las criaria nomes que mentem.

Como fazer: remover todas, sem alias, e registrar a remoção na spec do `aperv-tool`, com a correspondência
antigo → célula mais próxima apenas como documentação. Campanhas antigas se retomam com a sua imagem congelada.

**PROPOSTA P-8 — mapeamento.** O `APERV_PROPERTY_MAPPING` passa a cobrir:
- as chaves novas do jar: os grupos (Q-1), `p_M`, os parâmetros dos portões (§4.4), o modo do lançador (Q-7) e a
  variante do X1 (Q-5);
- as chaves já existentes que faltam: `activity_stable_restart_threshold`, `take_screenshot_for_every_step`,
  `save_gui_tree_to_xml_every_step`, `llm_boundary_top_pct`.

Sai toda entrada de chave que o jar novo aposentar; a resolução do `RunSpec` já aborta numa chave desconhecida ou
aposentada, então uma entrada morta é erro, não ruído.

**PROPOSTA P-9 — o leitor.** O `trace_ndjson.py` passa a ler os campos de §5 (`own`, `cfc`, `draw`, `nav`, `inp`,
`restart`, `bmc`, `strat`, `rot`, `buf`), além de `mopd`/`mops`/`ret` e do `RUN_END`. A descrição de
`decision_source` passa a dizer que, no jar novo, o campo é o **dono** do passo (causal), e no jar `eea2b1a9` é o
maior reforço (não causal). O `Counterfactual` documenta o que zera.

**Sem mudança:** o derive (`derive_mop_artifact.py`): o formato 2 continua o mesmo (P-1 separa o dado no loader do
jar).

### 9.3 Ordem entre os repositórios

Como no roadmap `rearch` (AC1): a change do `$RVA` que troca as variantes e o mapeamento entra **junto com o
primeiro jar** que muda as chaves. O `mvn install -Drvsec_home=…` copia o jar para o módulo, e um jar com chaves
novas e um `tool.py` antigo abortam na resolução, o que é o comportamento desejado. Gate da change no `$RVA`: para
cada variante, o `RUN_START` (features, params, inert, digest) bate com a tabela esperada da célula.

---

## 10. Uma change, em ondas paralelas (D-A14)

Uma change OpenSpec só no `$APE` (nome sugerido: `isolate-guidance-groups`) e uma change irmã no `$RVA` para o
`aperv-tool`, implementadas juntas. O paralelismo se planeja por **posse de arquivo**: duas tarefas paralelas
nunca editam o mesmo arquivo. Os arquivos compartilhados por várias frentes vão numa onda 0 curta e serial, que
fixa os **contratos** (chaves, enum, interfaces, setters do sink); depois disso cada frente só os usa.

Tamanho dos arquivos quentes `[verified: wc -l]`: `StatefulAgent` 2.136, `SataAgent` 1.632, `RunSpec` 1.070,
`ApePromptBuilder` 1.043, `MopData` 1.251, `NdjsonSink` 667, `State` 586, `StepRecord` 510, `ApeAgent` 466,
`KeyOwnership` 371, `EventSink` 319, `DecisionPipeline` 302, `Feature` 269, `Presets` 165, `ScoringPipeline` 142.

### Onda 0 — contratos (serial, 1 agente, antes de tudo)

Só declarações e esqueletos; sem mudar comportamento. Os testes existentes continuam verdes.
- `runtime/Feature`, `KeyOwnership`, `Presets`, `RunSpec`:
  - os grupos e as chaves novas (Q-1), com os defaults;
  - `NAV_FRONTIER` no lugar de `FRONTIER`, dependendo do provedor estático (D-A1);
  - a moeda MOP, os portões do LLM, o modo do lançador, o X1;
  - os presets por célula.
- `telemetry/EventSink`, `StepRecord`, `NdjsonSink`: os setters e a serialização dos campos de §5 (`own`, `cfc`,
  `draw`, `nav`, `inp`, `restart`, `bmc`, `strat`, `rot`, `buf`) e dos contadores novos, com os testes de
  serialização.
- Interfaces novas, vazias de lógica:
  - `LlmGate` (Specification);
  - os pontos de extensão do SATA (X1, X3, X4; e X2 se Q-4 = sim);
  - o provedor `StaticGuiModel`;
  - a contribuição de prompt.

### Onda 1 — frentes paralelas (um agente por frente, worktree próprio)

| Frente | Escopo | Arquivos que possui |
|---|---|---|
| **F1 rotação** | §2.4: rotação na captura, `getRotation` no API 30, `dec.rot`; código de `f993081f` e `3b35073a` | `llm/ScreenshotCapture`, `AndroidDevice`, os testes deles |
| **F2 dado** | P-1: o loader separa `StaticGuiModel` de `MopData`; `FrontierPass` e o texto tipado leem o provedor estático | `utils/MopData`, `StaticGuiModel` (novo), `agent/scoring/FrontierPass`, `utils/TypedInputGenerator` |
| **F3 SATA** | X1 (R2), X3, X4 com o default do APE e a variante MOP; `dec.strat`, `dec.draw`, `dec.nav`, `dec.bmc`; os desempates MOP saem do corpo do `SataAgent` | `agent/SataAgent`, `model/State`, as implementações dos pontos de extensão |
| **F4 decisão** | as moedas LLM e MOP (D-A10), os portões declarados (§4.4), os gatilhos (Q-6), o lançador (Q-7), os fluxos de RNG (P-3), o Budget e o restart como guards (P-2), `dec.own` | `agent/pipeline/*`, `MopCoinStage` (novo) |
| **F5 agente** | P-4 (buffer abandonado sem enfraquecer, `dec.buf`); `dec.inp`, `dec.restart`; F regido por `formCompletionEnabled` (P-6); `cfc` generalizado em `recordModelDecision` | `agent/StatefulAgent`, `agent/ApeAgent`, `agent/MopCounterfactual` |
| **F6 prompt** | §4.6: prompt montado pelas contribuições dos grupos; sem instrução MOP sem o grupo | `llm/ApePromptBuilder` |
| **F7 `$RVA`** | §9: as 6 variantes, remoção das 10 antigas (D-A13), o mapeamento (P-8), o leitor (P-9), os testes do módulo | `$RVA/modules/aperv-tool/**` |

Dependências que atravessam frentes:
- F3 e F4 usam o provedor de F2 só pela interface da onda 0, e implementam contra ela.
- O atalho MOP atual (`SataAgent.java:574-700`, `:1500`) sai do `SataAgent` (F3) e vira o decisor da moeda MOP
  (F4). Na onda 0, a interface do decisor MOP fica definida; F3 remove os sítios, e F4 implementa o decisor.
- F5 e F3 tocam o registro da decisão: F5 possui `recordModelDecision`, e F3 chama os setters do sink diretamente
  nos sítios do `SataAgent`.

### Onda 2 — integração (serial)

1. Merge das frentes na branch da change; `mvn clean test` (lembrete: `mvn clean` antes, por causa de classes de
   teste velhas em `target/`).
2. Testes de integração:
   - **paridade da base**: a célula `base` contra o upstream, no oráculo, nos caminhos X1–X4;
   - **neutralidade do sink**: `SinkNeutralityTest` com um preset por célula;
   - **controle limpo**: numa célula sem MOP, todo campo MOP é ausente ou 0;
   - **neutralidade de RNG**: ligar ou desligar um portão não muda as escolhas do SATA.
3. `mvn install -Drvsec_home=…` para o jar chegar ao `aperv-tool`; testes do módulo no `$RVA`.

### Onda 3 — smoke em dispositivo

- Cada uma das 6 variantes no `cryptoapp`, por alguns minutos, conferindo o `RUN_START` contra a tabela esperada
  e os campos novos na trace.
- Um APK em paisagem (retrowars ou beatgame) na variante com LLM: o screenshot do sub-evento sai na orientação
  certa.

### Depois — o mini experimento (fora desta change)

É do `$P3` (track `experiment`): células, APKs, tempo e réplicas definidos pelo autor, protocolo antes dos dados.
Com o tempo de hoje, ele é **exploratório**, não pré-registrado.

---

## 11. Fontes desta sessão

- Relatórios dos subagentes (inventário MOP, chaves por braço e `RUN_START`, diff APE × APE-RV, telemetria,
  caminhos do SATA e pesos, rotação): resumidos em §2, marcados `[reported]`. Material bruto no scratchpad da
  sessão: o upstream extraído (`orig/`), `sata.diff`, `stateful.diff`, `chan.py`, `ties2.py`.
- Artigo: `$T/docs/estudo-03/escrita/esqueleto/` (`esqueleto_compacto.md` Tabelas 1–4; `s4/s4-5.md`, a errata A/A e
  o DF-9).
- E6: `$P3/openspec/changes/archive/2026-10-15-e6-campaign/` (`protocol.md`, `decision.md`, `specs/e6-mechanism`);
  `$P3/docs/posthoc/e6-fork/report.md`; `$P3/experiments/E6-campaign/results/mechanism/llm-arm/`.
- Memória do projeto: `feature-groups-isolation-exploration`, `llm-gate-opaque-screens`,
  `llm-opaque-screen-merge-pending`, `llm-random-point-change`.

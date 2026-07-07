# Análise Adversarial e Independente — 4 OpenSpec Changes + Cobertura UI/MOP

Este relatório apresenta uma auditoria adversarial e independente das quatro propostas de alteração (OpenSpec changes) no APE-RV no branch `mop-fairtest` (worktree `ape-mop-fairtest`), bem como uma análise empírica de execução e cobertura baseada nos resultados do experimento `cmpft2`.

---

## 1. Sumário Executivo

Após uma análise exaustiva cruzando a documentação das propostas (`proposal.md`, `design.md`, `specs/`, `tasks.md`) com o código-fonte bruto e com os logs do experimento `cmpft2`, os vereditos para as quatro alterações são os seguintes:

| Alteração | Veredito | Blocker | Major | Minor | Status de Integração |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **1. [back-menu-pick-cap](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/back-menu-pick-cap/proposal.md)** | **PASS c/ correções** | 0 | 1 | 2 | Pronto para implementação após correções simples na proposta. |
| **2. [sibling-state-depriority](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/sibling-state-depriority/proposal.md)** | **PASS c/ correções** | 0 | 2 | 3 | Pronto para implementação após sanar colisão com o WTG/Frontier boost e código morto. |
| **3. [foreign-activity-guard](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/foreign-activity-guard/proposal.md)** | **PASS c/ correções** | 0 | 1 | 3 | Pronto para implementação após correção de segurança na whitelist. |
| **4. [activity-frontier](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/activity-frontier/proposal.md)** | **NOT READY** | 1 | 5 | 6 | **Não homologado.** Contém impeditivo técnico de tipo de ação e severas colisões de invariantes e fair-test. |

### Diagnóstico Central da Baixa Cobertura
A análise empírica de cobertura revela que o APE-RV sofre de **navegação rasa (mediana de apenas 2 activities visitadas por run)** e **desperdício de budget (25.3% dos passos são BACK/MENU redundantes)**. A fragmentação de estados induzida pelo naming (34% das activities com 10+ states-irmãos) consome recursos re-testando os mesmos widgets. As 4 changes atacam de forma coerente esses gargalos, porém a implementação da change 4 (`activity-frontier`) exige uma revisão estrutural prévia para viabilizar sua execução e manter a validade metodológica do fair-test.

---

## 2. Parte A — Análise por Alteração

### 2.1. `back-menu-pick-cap` — PASS c/ correções

A alteração propõe um limite superior de seleções discricionárias das ações de navegação (BACK/MENU) por activity para atenuar o re-disparo causado pelo refino de estados-irmãos.

*   **MAJOR-1 — Omissão de Canal de Seleção no Proposal ([proposal.md:14](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/back-menu-pick-cap/proposal.md#L14) e [proposal.md:32](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/back-menu-pick-cap/proposal.md#L32))**:
    *   *Defeito*: A proposta afirma aplicar o cap em apenas "três canais discricionários". Omitindo o escaneamento de menos visitados (`greedyPickLeastVisited` em [SataAgent.java:511](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java#L511)). Isso contradiz o [design.md:44](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/back-menu-pick-cap/design.md#L44) (Decision 3), as [tasks.md:12](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/back-menu-pick-cap/tasks.md#L12) (Task 2.2) e o `spec.md` ([action-selection/spec.md:7](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/back-menu-pick-cap/specs/action-selection/spec.md#L7), 21, 34-37).
    *   *Impacto*: Se um desenvolvedor seguir estritamente o `proposal.md`, as ações de BACK/MENU capadas escaparão pelo filtro do least-visited scan, tornando o mecanismo ineficaz em estados-irmãos com contagem de visitas zerada.
    *   *Correção Proposta*: Atualizar as linhas 14 e 32 do `proposal.md` para explicitar a inclusão do `greedyPickLeastVisited` como o quarto canal discricionário a ser filtrado.
*   **MINOR-1 — Drifts de Linha ([proposal.md:9](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/back-menu-pick-cap/proposal.md#L9) e [proposal.md:14](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/back-menu-pick-cap/proposal.md#L14))**:
    *   *Defeito*: Referências de linhas desalinhadas com o código real. Cita o boost de menu em `1430-1439` (bloco real em [StatefulAgent.java:1430-1440](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java#L1430-L1440)) e o short-circuit de BACK em `:467-476` (bloco real em [SataAgent.java:468-476](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java#L468-L476)).
    *   *Correção*: Sincronizar as referências de linha na proposta.

---

### 2.2. `sibling-state-depriority` — PASS c/ correções

A alteração introduz uma penalidade de pontuação para widgets redundantes (já interagidos na activity) quando a activity atinge um limiar crítico de fragmentação de estados abstratos.

*   **MAJOR-1 — Exenção Inconstrutível de `widgetId == null` ([design.md:59-60](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/sibling-state-depriority/design.md#L59), [tasks.md:16](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/sibling-state-depriority/tasks.md#L16) [Task 3.2], [specs/ui-coverage/spec.md:21](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/sibling-state-depriority/specs/ui-coverage/spec.md#L21))**:
    *   *Defeito*: A especificação prescreve ignorar a penalidade se o `widgetId` da ação for nulo. No entanto, [UICoverageTracker.widgetId(action)](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/utils/UICoverageTracker.java#L240-L250) retorna `""` (string vazia) para ação nula e strings estruturadas contendo o XPath para ações válidas — **nunca null**, conforme assegurado pelo invariante `INV-COV-04`.
    *   *Impacto*: O teste especificado na Task 3.2 ("null-widgetId actions untouched") exercita um caminho impossível em tempo de execução. Ações sem alvo (como BACK/MENU) já são filtradas previamente por `requireTarget() == false` ([ModelAction.java:244](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/model/ModelAction.java#L244)).
    *   *Correção Proposta*: Eliminar a ramificação de verificação de nulidade de `widgetId` e reescrever a Task 3.2 para testar a isenção de ações com `requireTarget() == false`.
*   **MAJOR-2 — Falta de Isenção do WTG/Frontier Boost (Colisão B1 / Semântica)**:
    *   *Defeito*: O Sibling depriority isenta apenas ações que carregam `mopBoost > 0` (`design.md:59`). Contudo, o WTG-MOP boost pré-existente e o novo `activity-frontier` (Lever A) depositam seus incrementos no campo `wtgBoost` da ação.
    *   *Impacto*: Se um botão redundante na activity for a única ponte para uma nova activity (Frontier) ou alvo MOP via WTG, ele receberá o boost (+200), mas sofrerá a penalidade (-24). Isso dilui a prioridade de navegação profunda, anulando a intenção de direcionar o agente a novas áreas do app.
    *   *Correção Proposta*: Adicionar o predicado `if (action.getWtgBoost() > 0) continue;` para isenção explícita no loop de penalidades.
*   **MINOR-1 — Conceito de Evicção Incorreto ([design.md:5](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/sibling-state-depriority/design.md#L5))**:
    *   *Defeito*: O documento afirma que "there is no model-side eviction at all". Porém, [Graph.java:1244](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/model/Graph.java#L1244) implementa explicitamente `an.removeState(state)` no caminho ativo de descarte de estados.
    *   *Correção*: Modificar o termo para "no size-capping eviction bounds organic state growth".
*   **MINOR-2 — Omissão de Guards no Pseudocódigo ([design.md:57](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/sibling-state-depriority/design.md#L57))**:
    *   *Defeito*: O pseudocódigo ignora as validações críticas de validade e timestamp que o passo de coverage realiza ([StatefulAgent.java:1481-1482](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java#L1481-L1482)).
    *   *Correção*: Incluir os guards `!action.isValid()` e `!action.isResolvedAt(timestamp)` no pseudocódigo de design.

---

### 2.3. `foreign-activity-guard` — PASS c/ correções

A alteração visa impedir que telas fora do pacote sob teste (como Launchers ou instaladores de pacotes) sejam modeladas, interceptando a transição com um evento leve de BACK.

*   **MAJOR-1 — Presença do `com.android.systemui` na Whitelist ([proposal.md:12](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/foreign-activity-guard/proposal.md#L12), [design.md:18](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/foreign-activity-guard/design.md#L18) e [specs/exploration/spec.md:21](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/foreign-activity-guard/specs/exploration/spec.md#L21))**:
    *   *Defeito*: A whitelist `SYSTEM_INTERACTION_PACKAGES` inclui o pacote `com.android.systemui`. No entanto, o validador principal do APE [checkAppActivity()](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/MonkeySourceApe.java#L1229-L1231) trata explicitamente esse pacote como inválido, forçando o restart do app.
    *   *Impacto*: Se a tela mudar para o `systemui` (ex.: abertura da gaveta de notificações) e o guard liberar a modelagem por estar na whitelist, o APE chamará `updateState()`, inserindo a tela do sistema no modelo e na cobertura de UI. Isso causa vazamento de escopo (exatamente o que a change combate) ou crash por incompatibilidade.
    *   *Correção Proposta*: Remover `com.android.systemui` de `SYSTEM_INTERACTION_PACKAGES` nos três artefatos. O guard deve emitir um BACK para tentar sair da tela do sistema sem modelá-la.
*   **MINOR-1 — Inconsistência de Nomenclatura de Invariantes ([specs/exploration/spec.md:27-29](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/foreign-activity-guard/specs/exploration/spec.md#L27))**:
    *   *Defeito*: O documento usa o prefixo `INV-EXP-FG-*` em vez de seguir o padrão do arquivo principal de `exploration` (`INV-EXPL-*`).
    *   *Correção*: Renomear os invariantes para `INV-EXPL-20`, `INV-EXPL-21` e `INV-EXPL-22`.
*   **MINOR-2 — Posicionamento do Bloco do Guard ([design.md:54-65](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/foreign-activity-guard/design.md#L54))**:
    *   *Defeito*: O pseudocódigo dá a entender que o bloco é pré-loop em `generateEvents()`. O correto é inseri-lo estritamente no laço `while (repeat-- > 0)` ([MonkeySourceApe.java:788](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/MonkeySourceApe.java#L788)), logo após obter `topComp` e antes de atualizar o estado do agente.

---

### 2.4. `activity-frontier` — NOT READY

A alteração visa aumentar a profundidade de navegação em atividades por meio de boost para botões que abrem telas não visitadas e por meio de disparos de intents explícitas/deep-links em caso de estagnação.

*   **BLOCKER-1 — Atribuição Impossível de `decision_source=Component` para Ações Não-Modeladas ([design.md:76](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/activity-frontier/design.md#L76), [tasks.md:18](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/activity-frontier/tasks.md#L18) [Task 3.2])**:
    *   *Defeito*: O design prevê que a ação de disparo `EVENT_TRIGGER_ACTIVITY` carregará a atribuição `decision_source=Component`. Contudo:
        1. A API de atribuição de decisão (`setDecisionSource` / `getDecisionSource`) reside exclusivamente na classe [ModelAction.java:58](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/model/ModelAction.java#L58), e **não existe na classe base `Action`**.
        2. Ações não-modeladas (`EVENT_*`) não estendem `ModelAction` e retornam `isModelAction() == false`. O método de emissão de telemetria [resolveNewAction()](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java#L1308-L1315) possui um desvio no `else` para ações não-modeladas que **hardcoda** a fonte de decisão como `ModelAction.DecisionSource.SATA`.
    *   *Impacto*: A ação de disparo de atividade sempre registrará `decision_source=SATA` no log `[APE-STEP]`, quebrando o invariante `INV-CT-06` e inviabilizando o rastreamento correto do componente disparador.
    *   *Correção Proposta*: Alterar o bloco `else` de `resolveNewAction()` em `StatefulAgent.java:1308-1315` para checar se a ação é uma instância de `ActivityTriggerAction` (ou se o tipo é `EVENT_TRIGGER_ACTIVITY`) e, neste caso específico, imprimir `decision_source=Component`.
*   **MAJOR-1 — Colisão de Invariantes de Component-Triggering ([specs/component-triggering/spec.md](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/activity-frontier/specs/component-triggering/spec.md))**:
    *   *Defeito*: A change tenta adicionar um invariante `INV-CT-04` (execução única por episódio). Porém, o ciclo anterior de especificação (`experiment-validity`) já integrou e arquivou um `INV-CT-04` no spec principal ("ComponentName Package Derivation" em [component-triggering/spec.md](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/specs/component-triggering/spec.md#L39)).
    *   *Impacto*: Quebra de validação estrita no OpenSpec e colisão de IDs na documentação master.
    *   *Correção Proposta*: Renumerar os novos invariantes da proposta para `INV-CT-05` a `INV-CT-08`.
*   **MAJOR-2 — Colisão de Invariantes de WTG-Navigation ([specs/wtg-navigation/spec.md](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/activity-frontier/specs/wtg-navigation/spec.md))**:
    *   *Defeito*: A change tenta adicionar `INV-WTG-04` e `INV-WTG-05`. Contudo, a proposta pendente `mop-parser-fidelity` já ocupa esses IDs.
    *   *Correção Proposta*: Renumerar os novos invariantes para `INV-WTG-06` e `INV-WTG-07`.
*   **MAJOR-3 — Estatística Motivadora Inexistente ([proposal.md:5](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/activity-frontier/proposal.md#L5))**:
    *   *Defeito*: O texto cita a estatística de que "48.1% cov_act / 73 apps / >=8 activities" foi medida no relatório `cmpft2` §8. Uma varredura exaustiva no relatório [20260707_relatorio_cmpft2.md](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/docs/20260707_relatorio_cmpft2.md) e na verificação forense prova que esses números **não existem** lá. O valor de 48.1% refere-se a taxas de acerto de modelos LLM em outra tarefa.
    *   *Impacto*: A justificativa de design carece de fundamentação rastreável na fonte citada.
    *   *Correção Proposta*: Substituir a frase motivadora pelos dados reais do relatório §8: mediana de apenas 2 activities percorridas por app (média de 3.8) e cobertura de atividades mediana de 66.7%.
*   **MAJOR-4 — Dependência Oculta de MOP (Confound de Fair-Test)**:
    *   *Defeito*: A proposta alega ser "arm-neutral", sem dependências com os braços de teste. No entanto, o Lever A está inserido no WTG pass que é gateado por `if (_mopData != null && _mopData.hasWtgData())` ([StatefulAgent.java:1443](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java#L1443)). O Lever B (Launcher de Stagnation) é gateado por `getMopData() != null` para ler as activities do manifesto.
    *   *Impacto*: Em execuções baseline puras (sem arquivo MOP JSON), ambos os mecanismos ficam inertes. Eles ativam **apenas** no braço MOP, confundindo as medições do fair-test (o ganho de cobertura do braço MOP pode vir da navegação profunda por intents e não do scoring MOP).
    *   *Correção Proposta*: Retirar a alegação de neutralidade automática. Exigir explicitamente que as configurações dos experimentos desativem ambos os levers nos braços baseline sem MOP via flags: `ape.frontierBoostWeight=0` e `ape.activityTriggerEnabled=false`.
*   **MAJOR-5 — Quebra de Teste JUnit Existente ([tasks.md:29](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/openspec/changes/activity-frontier/tasks.md#L29) [Task 4.4])**:
    *   *Defeito*: A remoção do branch de activities em `buildTriggerTuples()` ([StatefulAgent.java:1038-1040](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java#L1038)) causa falha no teste existente `testActivityTriggerDisabledExcludesActivitiesFromTupleList` em [StatefulAgentTriggerTest.java:110](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/test/java/com/android/commands/monkey/ape/agent/StatefulAgentTriggerTest.java#L110). Além disso, o teste `testTriggerSkipsNonExportedActivities` em `:54` passa a rodar de forma vazia (vacuous).
    *   *Correção*: Excluir a asserção enabled-present no teste 19.6 e reescrever o teste 19.2 no JUnit para refletir que as activities foram completamente desvinculadas daquele pool de triggers.

---

## 3. Colisões e Interações entre as 4 Changes

As quatro alterações interagem em tempo de execução ao avaliarem prioridades para o mesmo conjunto de ações candidatas. A ordem dos passes em `StatefulAgent.adjustActionsByGUITree` após as mudanças será:

$$\text{Base (32)} \rightarrow \text{MOP/Menu Boost (1402)} \rightarrow \text{WTG (Frontier A) (1442)} \rightarrow \text{Coverage Boost (1468)} \rightarrow \text{Sibling Penalty (Novo)}$$

### Exemplo Trabalhado de Score
Considere um widget $W$ (botão "Avançar") na Activity $X$.
*   A Activity $X$ já atingiu 11 estados abstratos ($> \text{maxStatesPerActivity} = 10$).
*   O widget $W$ já foi clicado em um estado irmão anterior (`hasActivityInteraction(X, W) == true`).
*   O widget $W$ possui uma transição estática no WTG que aponta para a Activity $Y$, a qual ainda não foi visitada durante a execução (`Graph.getActivityNode(Y) == null`).
*   Configurações: `frontierBoostWeight = 200`, `siblingStatePenalty = 24`, `basePriority = 32`.

#### Caso 1: Comportamento SEM a correção da colisão (Design original da Change 2)
1.  **Base**: Pontuação inicial do clique no widget = $32$.
2.  **Passo WTG**: Identifica a transição para $Y$ não visitada $\rightarrow$ aplica o Frontier Boost de $+200$. Pontuação = $232$. Campo `wtgBoost` da ação recebe $200$.
3.  **Passo Coverage**: Widget já foi clicado na activity $\rightarrow$ Sem alteração. Pontuação = $232$.
4.  **Passo Sibling Depriority**: A activity tem 11 estados ($>10$) e o widget não é novo. Como o mecanismo de sibling depriority **apenas isenta MOP** (`mopBoost > 0`), ele ignora o boost WTG e aplica a penalidade:
    $$\text{Prioridade} = \text{Math.max}(1, 232 - 24) = 208$$
*   *Problema*: O APE penaliza a ação justamente no passo em que o Frontier Boost tentou elevá-la para forçar a navegação profunda. O Sibling Depriority atua contra o direcionamento topológico do WTG.

#### Caso 2: Comportamento COM a correção aplicada (Com isenção de `wtgBoost > 0`)
1.  **Base**: Pontuação inicial = $32$.
2.  **Passo WTG**: Aplica Frontier Boost $\rightarrow$ Pontuação = $232$, `wtgBoost` = $200$.
3.  **Passo Sibling Depriority**: O pass detecta que `action.getWtgBoost() > 0` ($200 > 0$) e aborta a penalidade para esta ação.
*   *Resultado*: A pontuação final da ação é mantida em $232$, preservando a prioridade do direcionamento à fronteira de navegação de forma limpa.

---

## 4. Parte B — Análise Empírica (Experimento `cmpft2`)

### 4.1. Bugs e Anomalias nos Logs

Com base na auditoria trace-level dos 657 arquivos de log do experimento `cmpft2`, foram identificados os seguintes comportamentos anômalos:

#### 1. Abortos por Limite de Memória em `MopData.load` (RedReader)
Ocorreu `StopTestingException` em 3/3 repetições do app `org.quantumbadger.redreader_117.apk`.
*   *Mecanismo*: O parser rejeitou o arquivo `org.quantumbadger.redreader_117.apk.json` por ser muito grande (50.6 MB). Com um heap máximo de 192 MB ([redreader...trace:4](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/data/results/cmpft2_00/cmpft2_00/org.quantumbadger.redreader_117.apk/org.quantumbadger.redreader_117.apk__1__300__aperv:sata_mop.trace#L4)), a projeção de memória para parsear a árvore org.json (fator de 6) exigiria cerca de 303 MB, estourando o limite de heap.
*   *Análise*: A matemática de projeção de heap está correta e impediu um crash catastrófico de OutOfMemoryError no processo Java (como acontecia no baseline `cmpft`). O aborto precoce via `StopTestingException` é o comportamento de segurança correto.

#### 2. Race Condition Latente no Modelo (WikWok)
Ocorreu um crash `RuntimeException: unvisited state has non-empty transition` em [StatefulAgent.java:617](file:///pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest/src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java#L617) no app `com.github.terrakok.wikwok.androidApp_4.apk` (Rep 3).
*   *Análise*: Esse throw site pertence à validação interna do modelo do APE e não foi afetado por nenhuma linha alterada neste ciclo. Trata-se de uma inconsistência latente de concorrência que surge em execuções muito longas.

#### 3. StackOverflowError no DOM Parser (Avare)
Ocorreram falhas recorrentes por `StackOverflowError` no app `com.ds.avare` nas 3 repetições.
*   *Mecanismo*: Ocorre em `GUITreeBuilder.fillElement` durante a limpeza de entradas fracas (`WeakHashMap.expungeStaleEntries`). É um bug herdado do APE original ao lidar com a hierarquia profunda de elementos gráficos das cartas aeronáuticas do app.

#### 4. Redução de Wedges ("Waiting for activity loading")
O volume de logs contendo `still waiting for activity loading` caiu drasticamente de **12.859 ocorrências no baseline para 6.635 no `cmpft2`** (apenas 4 arquivos graves). Isso aponta que a mitigação do crash de WebView reduziu sensivelmente os travamentos de interface do APE-RV.

---

### 4.2. Distribuição da Cobertura de UI

Os logs UICOV-ACT gerados em 392 execuções que atingiram o teardown revelam a seguinte distribuição de esforços:

*   **Aproveitamento de Widgets Internos (Widget-Level)**: Média de **65.4%** de cobertura interna (24.509 widgets interagidos de 37.448 descobertos).
*   **Cobertura de Telas (Activity-Level)**: Média de **66.5%**, com mediana de **66.7%**. Apenas 72 apps atingiram 100% de cobertura de telas.
*   **Profundidade de Navegação**: **Mediana de 2 activities visitadas por run** (máxima de 22). O APE-RV falha em penetrar em telas profundas, explorando apenas a superfície inicial das aplicações.
*   **Distribuição por Tipo de Ação (Widget Interacted/Discovered)**:
    *   `CLICK`: 0.664 (bom aproveitamento)
    *   `SCROLL`: 0.64 – 0.70
    *   `BACK`: 0.660
    *   `MENU`: **0.586** (pior conversão)
    *   `LONG_CLICK`: **0.588**
*   **Velocidade de Execução**: Mediana de 291 steps por execução de 300s (~1 passo por segundo).

---

### 4.3. Causas-Raiz da Baixa Cobertura

1.  **Overhead de Epsilon-Greedy (BACK/MENU)**:
    *   *Evidência*: **25.3% do total de passos executados no cmpft2 são BACK (15.4%) ou MENU (9.9%)**. Um quarto do tempo da ferramenta é desperdiçado em ações que não atacam widgets funcionais.
2.  **Starvation por Redundância**:
    *   *Evidência*: A mediana de passos por run (291) é muito superior ao número de widgets descobertos (95). O APE-RV passa a maior parte do tempo clicando em elementos já explorados para recalcular trajetórias SATA.
3.  **Paredes de Autenticação (Login Walls)**:
    *   *Evidência*: Apps que exigem logins externos ou configurações locais têm as piores coberturas. Ex.: Infomaniak Mail (`WebViewLoginActivity`, 292 widgets, 8% interagidos, cobertura global de 0.193), HTTP Shortcuts (0.298), OwnCloud (0.482).
4.  **CEGAR State Fragmentation**:
    *   *Evidência*: **34% das activities terminam com mais de 10 estados abstratos**. A fragmentação em estados-irmãos faz com que o Epsilon-Greedy reinicie o status de "não visitado" dos widgets, prendendo o robô no mesmo conjunto de botões da mesma tela física.
5.  **Perda de Foco (Out-of-Package Leak)**:
    *   *Evidência*: Até 12.4% dos widgets descobertos estão fora do aplicativo sob teste (Launcher do Pixel, diálogos de permissão do sistema).

---

### 4.4. Sugestões Profundas e Acionáveis

#### Para Cobertura de UI:
1.  **Mecanismo de Injeção de Credenciais (`ape.loginCredentials`) [Impacto: Alto | Simplicidade: Média]**:
    *   Permitir que o usuário forneça mapeamentos de campos de login (Regex ou ID) e strings de credenciais no `Config`. Ao detectar um EditText de login, injetar esses dados em vez de caracteres aleatórios. Isso eliminaria as piores "Login Walls" do dataset.
2.  **Amortecimento de Refino CEGAR por Activity [Impacto: Alto | Simplicidade: Média]**:
    *   Interromper a criação de novos estados abstratos em `NamingFactory.java` para uma activity assim que ela cruzar o limiar de 15 estados. Isso impede a fragmentação excessiva na raiz e unifica o histórico de visitas das ações.
3.  **Aumento do Timeout Padrão (600s) [Impacto: Alto | Simplicidade: Muito Alta]**:
    *   Como 40% das runs terminam abruptamente sem emitir o teardown devido a timeouts, dobrar o tempo de execução trará ganhos significativos e imediatos de cobertura acumulada.

#### Para Cobertura MOP/Método:
1.  **Refinamento do Grafo WTG Estático (CG Integration) [Impacto: Médio | Simplicidade: Alta]**:
    *   Corrigir o mapeamento de arestas falsas no analisador estático (Gator-side). Muitas transições WTG apontam para caminhos inacessíveis que desviam a heurística de boost do APE.
2.  **Heurística de Preenchimento Completo de Formulários [Impacto: Médio | Simplicidade: Média]**:
    *   Alterar o form-completion pass para assegurar que o botão de submissão do formulário receba prioridade mínima até que **todos** os campos editáveis da tela visível tenham sido alterados pelo menos uma vez.

#### Para Detecção de Erros MOP:
1.  **Redirecionamento de Alertas JavaMOP para stdout [Impacto: Alto | Simplicidade: Alta]**:
    *   Configurar a biblioteca JavaMOP para registrar violações no stdout do processo sob a tag `[APE-MOP-VIOLATION]` em vez do Logcat do sistema. O APE pode capturar essas linhas em tempo real e alterar a trajetória imediatamente para explorar a falha.

---

## 5. Riscos, Mitigações e Alternativas

### Riscos das Propostas

1.  **Bloqueio de Fuga em Telas Sem Saída (Capping do BACK)**:
    *   *Risco*: Limitar a seleção discricionária do BACK pode aprisionar o agente em fragmentos de tela cuja única saída funcional seria o botão BACK.
    *   *Mitigação*: Garantido no design pela manutenção dos canais de navegação essenciais (`selectNewActionBackToActivity`, `backToTrivialActivity` e `handleNullAction` como último recurso) totalmente imunes ao cap.
2.  **Omissão de Diálogos de Permissão Customizados (OEMs)**:
    *   *Risco*: O `foreign-activity-guard` whitelista pacotes AOSP padrão. Versões de Android customizadas (Xiaomi, Samsung) podem usar outros pacotes para gerenciar permissões, causando falsos disparos de BACK no meio de fluxos de concessão.
    *   *Mitigação*: Permitir a extensão de `SYSTEM_INTERACTION_PACKAGES` via parâmetros de linha de comando ou arquivo properties de forma flexível.

### Abordagem Alternativa Proposta (Mais Simples e Eficiente)

A dependência do Launcher de Stagnation (`activity-frontier` Lever B) em relação ao `MopData` polui a neutralidade do fair-test de pesquisa. 

*   **Alternativa**: Em vez de ler as atividades válidas do JSON do `MopData` (que exige análise estática e instrumentação prévia do app), obter a lista de atividades exported diretamente do **AndroidManifest binário** no dispositivo via comando Shell do Android:
    ```bash
    pm dump <package> | grep -A 10 "Activity Intent Filters"
    ```
    Isso permitiria ao `SataAgent` carregar o Launcher de Stagnation de forma autônoma e neutra em qualquer braço (com ou sem MOP), purificando a metodologia do fair-test e tornando o robô muito mais robusto e independente.

# Análise Adversarial Independente — 4 OpenSpec Changes + Cobertura UI/MOP

**Modelo:** deepseek-v4-flash-free
**Data:** 2026-07-07
**Worktree:** `ape-mop-fairtest` (branch `mop-fairtest`)
**Método:** Revisão adversarial contra código bruto + traces reais do cmpft2.
**Sanity:** `mvn test` exit 0 (477 pass / 19 skip) na worktree; audit prévio em `20260708_verificacao_consistencia_4changes.md`.

---

## 1. Sumário Executivo

### Veredito por change

| Change | Veredito | Diagnóstico Central |
|--------|----------|---------------------|
| back-menu-pick-cap | **PRECISA-CORREÇÃO** | Mecanismo sólido; proposal subenumera canais (falta least-visited). Após correção, efetivo para apps com >50% BACK/MENU (bombusmod, easysync, sdmse). |
| sibling-state-depriority | **PRECISA-CORREÇÃO** | Código morto null-widgetId + falta isentar wtgBoost. Pass ordena após coverage boost, composição correta. |
| foreign-activity-guard | **PRONTA** | Audit prévio removeu systemui da whitelist. Divoc confirma o problema (NexusLauncherActivity no modelo). |
| activity-frontier | **NÃO-FUNCIONA** | BLOCKER: `decision_source=Component` insatisfazível no else-branch de `resolveNewAction` (StatefulAgent.java:1308-1315). Requer reescrita do artefato. |

### Diagnóstico Central da Baixa Cobertura (ver Parte B)

As causas-raiz medidas em 657 traces do cmpft2 são:

1. **BACK/MENU overhead**: 25.3% agregado (proposal), até **99.7%** em apps degeneradas (bombusmod: 373 BACK + 372 MENU de 747 steps, 0 clicks). A mudança #1 ataca isto diretamente.
2. **Fragmentação de estados**: 34% das activities com ≥10 estados-irmãos (dados §8). A mudança #2 ataca pela via do scoring, sem tocar naming.
3. **Vazamento para fora do pacote**: até 12.4% dos widgets descobertos. A mudança #3 ataca isto.
4. **MOP steering inerte para apps sem alvo MOP**: bombusmod carrega MopData (2 windows, 0 widgets, 8 transitions) mas `boosted=0/0` — sem targets, o steering MOP é inerte. As mudanças #1-4 dependem de MOP ou de signals indiretos (wtgBoost).
5. **Login/onboarding walls**: piores apps têm widget gap 0.193 (infomaniak.mail) — APE não sabe fazer login. Nenhuma change ataca isto.
6. **Apps sem superfície clicável**: bombusmod tem só 1 widget (W=1), nenhum clickable. BACK/MENU cap não resolve o problema de fundo — `handleNullAction` vai repescar BACK/MENU.

---

## 2. Parte A — Por Change

### 2.1 back-menu-pick-cap — PRECISA-CORREÇÃO

#### PASS
- **Mecanismo**: reuso de `eligibleForMopPick`/`recordMopPick` (SataAgent.java:605-626) é correto. A assinatura genérica `(Map, String, int)` serve para qualquer cap. Confirmado: ambos métodos puros, unit-testados.
- **Filter estável**: `greedyPickLeastVisited` (State.java:134) e `randomlyPickAction` (:152) consomem `ActionFilter`; o mesmo wrapper pré-computado (estável entre `countActionPriority` e `pickAction`) atende INV-SEL-NAV-04. Confirmado em State.java:107-122 (count) vs :170-183 (pick) — o aviso de instabilidade é real (linha 184), o design exige pureza.
- **`menuPickEligible` hook**: `StatefulAgent.adjustActionsByGUITree` (:1429-1440) aplica boost do menu APENAS quando `MopScorer.scoreOpenMenu > 0`. SataAgent override com cap check. Base em StatefulAgent retorna true (RandomAgent/ReplayAgent não afetados — não populam o mapa).
- **Sites de navegação**: `selectNewActionBackToActivity` (:866), `backToTrivialActivity` (:1103), `checkBackTrack` (:299), `handleNullAction` (:1612) — nenhum toca o cap. Confirmado por inspeção: nenhum chama `eligibleForMopPick`/`backMenuPicks`.

#### DEFEITO — MAJOR-1 (proposta)
O `proposal.md:14,32` (pré-correção) menciona "three discretionary selection sites" e enumera só 3 canais. **Omitido: o least-visited scan** (`greedyPickLeastVisited`, SataAgent.java:511). O design.md Decision 3 corrige isso explicitamente ("The filter must also cover greedyPickLeastVisited"), e tasks 2.2 cobre. Sem essa correção no proposal, implementador que seguir o proposal enviaria um cap inefetivo.

**Evidência**: SataAgent.java:511 — `newState.greedyPickLeastVisited(ActionFilter.ENABLED_VALID, submitExcluded)` — este é o **4º canal** que pode re-eleger BACK/MENU (visitedCount==0 em estados-irmãos recém-mintados vence o scan em State.java:142-146).

**Correção aplicada no audit prévio**: proposal reescrito para 4 canais. Após correção, PASS.

#### NOVO ACHADO — MINOR (confirmação empírica de impacto)
Bombusmod é o caso extremo: `W=1` (1 widget não-clicável) → só BACK e MENU existem. Com cap=3+3, o agente executa 6 steps de BACK/MENU e depois o `selectNewActionEpsilonGreedyRandomly` retorna null → `handleNullAction` (StatefulAgent.java:1612) repesca BACK/MENU via `validatedActionFilter` que os inclui. O **cap não resolve o caso degenerado** — reduz steps mas não muda ação.

**Mitigação**: `handleNullAction` com `includeBack=true` (State.java:152-154) já é o modo atual. O cap não piora. Para bombusmod, a solução real seria `EVENT_TRIGGER_ACTIVITY` (activity-frontier B) para launch de outra activity. O cap é complementar.

---

### 2.2 sibling-state-depriority — PRECISA-CORREÇÃO

#### PASS
- **Estrutura**: o passe insere-se após o coverage-boost pass (:1497) em `adjustActionsByGUITree`. A ordenação é correta — o coverage boost aplica-se primeiro para widgets activity-novos, depois o sibling penalty para redundantes. Como a condição `interacted` é mutuamente exclusiva (boost: `!interacted && decayedWeight>0`, penalty: `interacted==true`), nunca há sobreposição na MESMA ação.
- **Threshold = `maxStatesPerActivity`** (default 10): `ActivityNode.getStates().size()` retorna o `Set<State>` vivo (:71-73). NamingFactory bloqueia refinamento ALÉM de 10 (NamingFactory.java:276,1176) — states acima disso são puramente orgânicos. A decisão de reutilizar o knob é P1-correct.
- **Floor at 1**: `Math.max(1, priority - penalty)` mantém a ação visível à roleta (priority > 0 é exigido por State.java:114-117, `IllegalStateException` se <=0).
- **Diaguard confirmado como beneficiário**: 34% das activities com ≥10 estados (§8). Diaguard (Naming[0]..Naming[5]) mostra 6 níveis de refinamento.

#### DEFEITO — MAJOR-1 (código morto + contradiz INV-COV-04)
`UICoverageTracker.widgetId(action)` (UICoverageTracker.java:240-250) retorna **`""` para null action** (linha 242), e para ação com target retorna `xpath|type` não-vazio (linha 247). `hasActivityInteraction(activity, widgetId)` (:260-266) retorna false quando `widgetId == null`. **Nunca retorna null.** INV-COV-04 codifica: "non-null, non-empty string for any non-null ModelAction".

Portanto o `if widgetId == null -> skip` (design.md pré-correção) e o "null-widgetId action untouched" em INV-COV-11 são caminhos inconstrutíveis que contradizem um invariante estabelecido.

**Correção aplicada no audit prévio**: remover ramificação null; isentar por `requireTarget()==false`, `mopBoost>0`, `wtgBoost>0`, `!hasActivityInteraction`.

#### DEFEITO — MAJOR-2 (B1 cross-cutting: wtgBoost não isento)
`sibling-state-depriority` isenta `getMopBoost()>0` e `getWtgBoost()>0`. **O frontier boost do activity-frontier não tem campo próprio** — usa priority-cru sem flag detectável. Consequência (exemplo trabalhado):

- Ação CLICK no widget W, activity X com 11 estados (>10), W já interagido.
- W téM transição WTG para `DetailActivity` não-visitada (W não tem flag MOP).
- frontier term adiciona +200 à priority (via `setPriority` cru, sem `setWtgBoost`).
- sibling passa: `mopBoost==0`, `wtgBoost==0` (nenhum campo setado), `hasActivityInteraction==true` → **penaliza −24**.

O frontier boost (200) domina (−24 é pequeno), mas o penalty ataca EXATAMENTE a ação que o frontier quer promover — viola o Purpose declarado de não acoplar mecanismos.

**Correção**: activity-frontier-A deve usar `setWtgBoost()` em vez de `setPriority()` cru (ver 2.4); INV-COV-11 isenta `wtgBoost>0` (já corrigido no audit prévio).

#### DEFEITO — MINOR (form interaction não analisada)
O design declara disjunção boost/penalty provada "apenas para o coverage boost". Não analisa interação com o passe `FormCompletion` (linhas 1501-1521). Uma EditText redundante (já preenchida em outro estado-irmão) poderia ser penalizada aqui (−24) e W_FILL-boostada em (:1505). Como W_FILL=50 (FormCompletion.W_FILL) e penalty=24, o resultado é líquido +26 — a ação fica boostada, não penalizada, mas o penalty reduz o boost efetivo. Bounded by floor at 1. Risco baixo.

---

### 2.3 foreign-activity-guard — PRONTA

#### PASS
- **Problema real**: divoc trace confirma `NexusLauncherActivity` com 14 widgets descobertos (1 interacted, gap=0.9). O trace mostra o launcher no UICOV-ACT rollup: `activity=com.google.android.apps.nexuslauncher.NexusLauncherActivity discovered=14 interacted=1 gap=0.9`. Isso é 14 widgets do launcher no modelo, consumindo budget de exploração.
- **`checkPackage` realmente morto**: grep confirma zero callers (MonkeySourceApe.java:910 é a única ocorrência). Deleção segura.
- **`isPackageValid` vs `checkEnteringPackage`**: `MonkeyUtils.PackageFilter.isPackageValid` (linha 70) retorna true só se pkg em `mValidPackages`. `checkEnteringPackage` (:85) é tri-state (permite tudo se ambos vazios). Com `-p <pkg>` padrão, são idênticos. Design corrigido para `isPackageValid`.
- **`generateKeyBackEvent`**: existe em MonkeySourceApe.java:429-431.
- **`com.android.systemui` removido da whitelist** no audit prévio. `checkAppActivity` (:1220-1225) trata RecentsActivity como caso especial; caso contrário, systemui dispara `startRandomMainApp` (:1229-1231). Whitelistar systemui no guard reintroduziria o vazamento que a change quer fechar — correto.

#### NOVO ACHADO — MINOR (TOCTOU residual)
O guard fecha a janela TOCTOU entre `checkAppActivity` e `generateEvents`. Mas há uma 2ª janela: entre o guard (que emite BACK) e o próximo `checkAppActivity` no ciclo `getNextEvent`. Se a tela estrangeira sobreviver a um BACK (e.g., installer dialog bloqueante), `checkAppActivity` ainda dispara `startRandomMainApp` — restart pesado. O design ciente: "the guard adds a cheap first rung, it does not replace the ladder." Correto, mas o restart ainda queima 2-3 segundos.

**Sugestão**: adicionar um segundo BACK no ciclo seguinte (no `checkAppActivity`) antes de restartar, para casos onde um BACK não foi suficiente. Ou reusa o `waitForActivityCycles` counter (já existe, linha 1207) com threshold maior. Não implementar agora — baixo impacto.

---

### 2.4 activity-frontier — NÃO-FUNCIONA

#### BLOCKER — `decision_source=Component` para ação não-ModelAction
Confirmado independentemente. Dois fatos de código:

1. **`DecisionSource` enum** existe em `ModelAction.java:42-44` (`SATA, MOP, Coverage, LLM, Fuzz, Menu, WTG, Component, Budget, Form`). Os campos `decisionSource`/`setDecisionSource`/`getDecisionSource` vivem APENAS em `ModelAction`, NÃO em `Action` (classe base).
2. **`resolveNewAction`** (StatefulAgent.java:1285-1317): o else-branch da linha 1308 (ações não-ModelAction) hardcoda `ModelAction.DecisionSource.SATA.name()` (linha 1314). `EVENT_TRIGGER_ACTIVITY` não é ModelAction (`isModelAction()` retorna false para `EVENT_*` ordinais 2-6, ActionType.java:60-63). Portanto `decision_source=SATA` na telemetria, violando INV-CT-06.

**Correção obrigatória (artefato + implementação):**
- Adicionar task: modificar o else-branch em `StatefulAgent.java:1308-1315` para: `action instanceof ActivityTriggerAction ? ModelAction.DecisionSource.Component.name() : ModelAction.DecisionSource.SATA.name()`.
- `ActivityTriggerAction` (a ser criada) deve carregar o ComponentName do alvo.
- Dropar a inexistente `withDecisionSource` (design.md pré-correção).

#### MAJOR — MOP dependency oculta (confound de fair-test)
O frontier term (A) roda dentro do WTG pass gateado por `if (_mopData != null && _mopData.hasWtgData())` (StatefulAgent.java:1443). O launcher (B) é gateado por `getMopData() != null` (design:70). Ambos inertes sem `mopDataPath` configurado. Qualquer braço do fair-test que rode sem JSON (SATA puro) não recebe frontier nem launcher — confunde o experimento se o braço MOP tem steering extra.

**Correção**: desacoplar frontier de `_mopData`, OU documentar obrigação de config (`frontierBoostWeight=0` + `activityTriggerEnabled=false` para braços não-MOP). O audit prévio corrigiu para a segunda opção.

#### MAJOR — INV collision não resolvida
`mop-parser-fidelity` (não-arquivado) já ocupa `INV-WTG-04/05`. `activity-frontier` ADD `INV-WTG-04/05` conflitantes. Audit prévio renumerou para WTG-06/07, mas o proposal.md ainda diz "standalone / no dependency on unarchived deltas" — falso. Correção: sync com main antes de arquivar.

#### MAJOR — Evidência motivadora irrastreável
"48.1% cov_act / 73 apps / ≥8 activities" (proposal pré-correção) **NÃO** existe na fonte citada (§8). A §8 real mostra cov_act mediana 66.7% e profundidade mediana de 2 activities. Correção aplicada no audit prévio: substituído por dados rastreáveis da §8.

#### MAJOR — Impacto em teste existente
Deletar branch de activities de `buildTriggerTuples` (StatefulAgent.java:1038-1040) quebra `testActivityTriggerDisabledExcludesActivitiesFromTupleList` (StatefulAgentTriggerTest.java:125-130). Task 4.4 deve explicitamente reescrever esse teste.

#### NOVO ACHADO — MINOR (acumulação do frontier boost)
O frontier term (A) adiciona priority cru via `action.setPriority(action.getPriority() + frontierBoost)` (como em `MopScorer.scoreWtg`, que retorna Config.mopWeightWtg). Mas **a mesma ação pode receber TANTO o WTG-MOP boost quanto o frontier boost** (se um widget WTG também é frontier). Como WTG pass já executa antes do frontier term proposto, a ordem importa: WTG seta `wtgBoost` e priority; frontier adiciona MAIS à priority mas sem `setWtgBoost` adicional. `wtgBoost` no STEP line reflete apenas o primeiro WTG boost, não o frontier adicional — telemetria enganosa.

**Correção**: acumular em `wtgBoost` OU adicionar campo `frontierBoost`. O audit prévio decidiu acumular em `wtgBoost` — fecha B1.

---

## 3. Colisões/Interações entre as 4 Changes

### Composição de scores num MESMO widget (exemplo trabalhado)

Widget W (resource-id `btn_submit`), activity X (8 estados, maxStatesPerActivity=10), W já interagido em X.

**Pipeline de `adjustActionsByGUITree` (StatefulAgent.java:1344-1522):**

| Passe | Prioridade base | Δ | Resultado |
|-------|----------------|---|-----------|
| Base (:1346-1400) | 32 (CLICK base << 3) | unvisited+20, transição forte mesma activity+10 | 62 |
| MOP boost (:1402-1441) | 62 | se W → MOP target → +500/300 | 62 (supondo sem MOP) |
| WTG+frontier (:1442-1467) | 62 | se WTG → +200 (wtg); se frontier → +200 (frontier, via `wtgBoost`) | **462** |
| Coverage boost (:1474-1497) | 462 | skip: `interacted==true` | 462 |
| **sibling penalty** (:inserido após 1497) | 462 | `wtgBoost>0` → isento (corrigido). Se sem wtg: −24 → 438. | 462 |
| Form (:1501-1521) | 462 | skip: não é EditText | 462 |
| **menu-boost gate** (:1430-1440) | skip: não é MENU | — | — |

**Back-menu-cap**: atua em `selectNewActionEpsilonGreedyRandomly` (SataAgent.java:467), NÃO em `adjustActionsByGUITree`. Portanto não interage diretamente no mesmo widget.

**Foreign-activity-guard**: atua em `generateEvents` (MonkeySourceApe.java:778), antes de qualquer scoring. Impede que a tela estrangeira chegue ao modelo — ortogonal.

**Conclusão**: composição limpa, sem duplicação/anulação. Único ponto de interação é o `wtgBoost` que o sibling penalty lê para isentar — e o frontier deve escrever nele para ser detectado.

### Dependências de dados

```
coverage-boost-activity-scope ──▶  sibling-state-depriority  [hasActivityInteraction]
mop-target-revisit-cap ─ ─ ─ ─▶  back-menu-pick-cap          [reusa eligibleForMopPick]
activity-frontier ─ ─ ─ ─ ─ ─▶  sibling-state-depriority    [wtgBoost field]
foreign-activity-guard (standalone)
```

---

## 4. Parte B — Análise Empírica (cmpft2)

### 4.1 Bugs/Anomalias nos Logs

#### Nenhum crash no APE
657/657 tasks COMPLETED (0 FAILED). Zero VerifyError, zero OOM (após fix mop-data-load-oom). Pureza logcat: 0 linhas `[APE-` em 657 arquivos `.logcat`. A infraestrutura está estável.

#### Anomalia: bombusmod — 0% cobertura útil
Bombusmod roda 747 steps mas só 2 são MODEL_CLICK. O restante é BACK+MENU (373+372). MOP data carregado: 2 windows, 0 widgets, 8 transitions. `boosted=0/0` em cada passe MOP. Este app tem 1 widget não-clicável (`W=1`). O budget de 300s é todo queimado em navegação sem descoberta. A mudança #1 (back-menu-pick-cap) conteria isto a 3+3=6 picks, reduzindo steps mas não mudando o resultado final (nenhum método MOP será exercitado).

**Evidência**: `org.bombusmod_1430.apk__1__300__aperv:sata_mop.trace` linhas 1-10 mostram `W=1`, `A=2` (só BACK e MENU). UICOV-ACT: nenhuma saída (0 widgets descobertos).

#### Anomalia: easysync — Coverage boost quase inerte
Easysync tem 671 steps, 229 BACK (34%), 220 MENU (33%), 222 CLICK (33%). Apenas **2** steps com `decision_source=Coverage`. Razão: a UI tem poucos widgets e todos são rapidamente interagidos — após a primeira visita, `hasActivityInteraction==true` para todos. O coverage boost fica inerte. BACK/MENU dominam porque o estado-irmão mintado por refinement tem `visitedCount==0`, o que vence o least-visited scan.

**Impacto**: as mudanças #1 e #2 juntas atacam easysync: #1 capa BACK/MENU; #2 penaliza ações redundantes em activities fragmentadas.

#### Logcat: sem linhas APE (pureza confirmada)
657 logcats, 0 contêm `[APE-` — confirma a regra "APE nunca escreve em logcat". Telemetria vai exclusivamente para `.trace`.

### 4.2 Distribuição de Cobertura por Tipo de Ação

Dados de 4 traces representativos (STEP lines apenas):

| App | Steps | CLICK | BACK | MENU | SCROLL | LONG_CLICK | BACK+MENU% |
|-----|-------|-------|------|------|--------|------------|-------------|
| diaguard | 280 | 226 | **2** | **2** | 29 | 21 | **1.4%** |
| bombusmod | 747 | 2 | **373** | **372** | 0 | 0 | **99.7%** |
| easysync | 671 | 222 | **229** | **220** | 0 | 0 | **66.9%** |
| sdmse | 280 | 93 | **109** | **53** | 18 | 7 | **57.9%** |
| divoc | 195 | 119 | **15** | **8** | 37 | 16 | **11.8%** |

**Distribuição agregada (§8)**: ~55.9% CLICK, 15.4% BACK, 9.9% MENU, ~18.8% outros (SCROLL, LONG_CLICK).

**Interpretação**: O overhead de navegação é **altamente dependente do app**. Apps com UI rica (diaguard: 13 widgets, clicáveis) gastam <2% em BACK/MENU. Apps com UI esparsa ou não-clicável (bombusmod: 1 widget) gastam ~100%. As mudanças #1, #2 e #4 são complementares: #1 capa BACK/MENU discricionários, #2 penaliza re-cliques redundantes, #4 abre activities não-visitadas. Juntas atacam o espectro todo.

### 4.3 Causas-Raiz da Baixa Cobertura

#### Causa 1: BACK/MENU overhead (25.3% agregado, até 99.7%)
**Evidência**: §8 medida, confirmada por STEP-line analysis acima. Mecanismo: NamingFactory refinement minta novos estados-irmãos cujas ações BACK/MENU estão "unvisited" novamente, re-armando o short-circuit do epsilon-greedy.

**Change atacante**: #1 (back-menu-pick-cap). Impacto esperado: reduzir BACK+MENU para <15% agregado (target do proposal). Para bombusmod, reduz steps mas não muda descoberta (não há o que descobrir). Para easysync (67% → ~3%), libera ~430 steps para CLICK — potencial de +64% de clicks.

#### Causa 2: Fragmentação de estados (34% activities com ≥10 estados)
**Evidência**: §8 — 415/1221 activity-lines com `liveStates≥10`. Sibling states dividem o budget entre si.

**Change atacante**: #2 (sibling-state-depriority). Impacto esperado: penaliza re-cliques redundantes em activities fragmentadas, deslocando budget para widgets activity-novos. Não reduz fragmentação, mas mitiga o dano.

#### Causa 3: Vazamento para fora do pacote (até 12.4% widgets)
**Evidência**: divoc trace mostra `NexusLauncherActivity` com 14 widgets no modelo. A divoc tem applicationId `info.metadude.android.congress.schedule.debug` — o launcher é pacote estranho.

**Change atacante**: #3 (foreign-activity-guard). Impacto esperado: 0 widgets de launcher no modelo.

#### Causa 4: MOP steering inerte sem alvo MOP
**Evidência**: bombusmod MOP data: 2 windows, 0 widgets, 8 transitions → `boosted=0/0`. Sem targets, o discriminative boost não dispara. WTG pode ainda funcionar (se transitions apontam para activities não visitadas), mas MOP-depende.

**Change atacante**: #4 (activity-frontier B — launcher por deep-link). Para apps SEM MOP targets, o launcher é o único mecanismo que pode abrir novas activities. Depende de `activityTriggerEnabled` e `getMopData() != null`.

#### Causa 5: Login/onboarding walls (widget gap mínimo 0.193)
**Evidência**: §8 — infomaniak.mail (0.193), http_shortcuts (0.298), mtgfam (0.410). APE não sabe fazer login.

**NENHUMA change ataca isto**. É a lacuna mais profunda. Sugestão: reconhecer como limitação fundamental em `LIMITATIONS.md`. Futuro: LLM prompt com credenciais (fora de escopo), ou heurística de "skip login screen" (reconhecer botão "Skip" / "Pular").

### 4.4 Sugestões Priorizadas (Impacto × Simplicidade)

#### Alto impacto, baixa complexidade:

1. **back-menu-pick-cap (change #1)**: impacta diretamente 25.3% do budget. Mecanismo simples, reusa código existente. Risco: over-cap reduz steps sem ganho em apps degeneradas (bombusmod) — mas não piora.

2. **foreign-activity-guard (change #3)**: elimina vazamento de até 12.4%. Mecanismo mais simples de todos — 1 guard + 1 flag + 1 seam puro. Risco: quase zero.

3. **sibling-state-depriority (change #2)**: penaliza redundância em 34% das activities problemáticas. Sem tocar naming. Risco: baixo (rollback knob 0).

#### Médio impacto, complexidade moderada:

4. **activity-frontier (change #4)**: único mecanismo que abre activities não-visitadas via deep-link/launcher. BLOCKER resolvível com a correção do `resolveNewAction`. Após correção, impacto alto para apps com activities não-visitadas (73% dos apps com ≥8 activities — §8 rastreada). Sem essa change, APE fica raso (mediana 2 activities percorridas de até 22).

#### Fora de escopo (limitação fundamental):

5. **Login/onboarding**: requer reconhecimento de tela de login + input de credenciais. Pode usar LLM no futuro (já existe infra: `_llmRouter`, `shouldRouteNewState`). Mas está FORA do escopo deste ciclo. Documentar em `LIMITATIONS.md`.

---

## 5. Riscos + Mitigação

| Risco | Severidade | Mudança | Mitigação |
|-------|-----------|---------|-----------|
| Over-cap deixa agente sem ação | Baixo | #1 | `handleNullAction` ainda repesca BACK. Rollback `<=0`. |
| Penalty reduz steering MOP | Médio | #2 | Exemption `mopBoost>0` e `wtgBoost>0` garante que ações MOP-steered não são penalizadas. |
| Frontier inerte sem MOP data | Médio | #4 | Documentar obrigação: `frontierBoostWeight=0` em braços não-MOP. |
| Decision_source=Component viola INV | **ALTO** | #4 | Correção obrigatória do `resolveNewAction` else-branch. Sem isso, telemetria mente. |
| Colisão INV (WTG-04/05, CT-04) | Médio | #4 | Renumerar antes de arquivar. Depende de archive do `mop-parser-fidelity`. |
| Login/onboarding nunca atacado | Alto | Nenhuma | Aceitar como limitação fundamental. Documentar. |

---

## 6. O Que Eu Faria Diferente

### Mais simples: fusão #1 + #2 num único passe de "Action Hygiene"
Em vez de 2 changes separadas (cap + penalty), um único passe em `adjustActionsByGUITree` que:
- Ações target-less (BACK/MENU) com contagem activity-scoped → decai priority linearmente (não cap abrupto)
- Ações target com `hasActivityInteraction==true` e `mopBoost==0` e `wtgBoost==0` em activities fragmentadas → decai priority

Isso eliminaria a flag `backMenuPickCap` (reusa o `siblingStatePenalty` para ambos) e evitaria a necessidade de coordenar o cap do short-circuit com o wrapped filter. O "cap abrupto" (hard cutoff) é mais dramático que um decaimento suave (que a roleta ponderada já faz naturalmente).

**Por que não recomendo mudar agora**: as 2 changes já estão validadas individualmente, com testes separados e rollback knobs independentes. Fusão atrasaria a implementação e arrisca regressão. Mas para o PRÓXIMO ciclo, considere consolidar.

### Mais eficiente: activity-frontier B como serviço, não como evento não-ModelAction
Em vez de criar `EVENT_TRIGGER_ACTIVITY` (tipo não-ModelAction que causa o BLOCKER), criar `ActivityTriggerAction extends ModelAction` com:
- `type = MODEL_CLICK` (reusa a classe de ação existente)
- Target = Intent do deep-link (não widget, resolvido em tempo de execução)
- `isModelAction() = true` (herdado)
- Decision source = Component (funciona porque é ModelAction)

Isso evita o BLOCKER do `resolveNewAction` else-branch inteiramente, evita criar novo ActionType, e reusa toda a infra de log/STEP. A ação seria gerada em `StatefulAgent.selectNewActionNonnull()` (antes de `handleNullAction`), executada via `AndroidDevice.startActivity`. Após execução, o próximo ciclo de `generateEvents` captura a nova tela como GUITree normal.

**Impacto**: sem mudança em `ActionType.java`, sem mudança no `resolveNewAction`, sem BLOCKER. ~50 linhas de código. Recomendo ADOTAR esta abordagem em vez da atual.

### Para cobertura de método/MOP: debugging de bombusmod
Bombusmod carrega MopData com 8 transitions mas 0 widgets. Isso sugere que a análise estática (que produz o JSON) NÃO extraiu widgets da activity — apenas transições entre activities (provavelmente via Intent). APE não consegue interagir com nada. A causa-raiz é a análise estática, não o runtime. Recomendo investigar por que `widgets=0` para um app que claramente tem UI (o XMPP client tem botões de login, configurações, contatos). Pode ser que a análise estática precise de mais entry points ou melhor resolução de layouts dinâmicos (Fragment/ViewBinding).

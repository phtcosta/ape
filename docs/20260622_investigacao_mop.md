# Investigação — por que o MOP-guidance do APE-RV é inerte (traces cmpmop × fonte)

**Data:** 2026-06-22
**Corrida analisada:** `cmpmop` (169 APKs JCA, 300 s, 3 reps, `aperv:sata` × `aperv:sata_mop`; jar source-build pós gh71+gh15).
**Método:** 4 agentes de mineração de trace + **3 agentes de re-investigação** (E: replicação independente/adversarial das métricas; F: throughput/budget/depth; G: WTG + fidelidade do parser) em paralelo (509 traces sata_mop + 509 sata) + verificação direta contra o fonte (`ape/src/main/java/.../ape/`) e contra os `<apk>.json`. Toda claim de agente foi confrontada com código/dados antes de ser aceita — **vários overclaims corrigidos por medição direta**: Agente A (uniformidade 100% → na verdade 73% uniforme/27% discriminativo, §2), Agente D (divergência "decisiva" → não-robusta, §1/§6), e o parser-drop (G) + a contradição 18-vs-19 (E) foram verificados no fonte/dados antes de entrar.
**Memo de contexto:** `rvsec/rv-android/docs/20260622_cmpmop_analise.md` (concluiu "fix validado como engenharia, mas sata_mop ≈ sata"). Esta investigação responde **por quê**.
**Escopo:** 100% MOP/SATA. LLM ignorado.

> **Veredito em uma linha:** o MOP-guidance é inerte por **duas camadas de falha independentes** — (1) colapso do substrato discriminativo (só 19/169 APKs têm um widget disparável no JSON; e dentro desses, o parser do aperv **descarta 45% dos widgets flagged** antes do scoring — §1/§3 PARSER-DROP) e (2) o mecanismo de boost é fraco demais para guiar mesmo onde dispara. Há ainda um **pré-requisito de UI ortogonal** (forms de cripto raramente preenchidos→submetidos). O resultado nulo é desta **implementação**, não do MOP-guidance em princípio — que nunca foi testado de forma justa.

---

## 0. Princípios de design (OBRIGATÓRIOS — herdados do plano de correção §0)

O sistema deve ser **o mais simples e elegante possível**, sem complexidade desnecessária. Toda proposta abaixo (§7) respeita:
- **P1 Simplicidade** — complexidade mínima para a tarefa atual; sem features especulativas, sem validar cenários impossíveis, sem oráculo de obfuscação (não existe).
- **P3 Sem retrocompatibilidade** — código morto/substituído é deletado inteiro (backup em `backup/` gitignored); um commit = um estado consistente.
- **P4 Comentários no presente** — comentários dizem o que o código faz agora.

Consequência: nenhuma proposta aqui embute maquinaria para medir claims não-verificáveis; o caminho escolhido (§7) é o conjunto mínimo de edições que dá ao MOP um teste justo no subconjunto que **já** tem substrato.

---

## 1. Causa-raiz — duas camadas de falha (+ pré-requisito de UI)

> Replicação independente (Agente E, adversarial): claims de §1 verificadas — 19/169 (exato), join 0,4292%, 99,025% unflagged, split 72,6/23,5/3,9, containment 100%/67,7%/19,8% — todas **PASS**.

### Camada 1 — colapso do substrato discriminativo (majoritariamente produtor/gator; + uma perda aperv-side no parser)
Só **19/169 APKs** têm ≥1 widget estaticamente discriminativo (handler que casa um método `reachesTarget`). O plano de correção previa ~98 — **errado por ~5×**.

- O join `bySignature` (handler-string → método `reachesTarget`) acerta **4.938 / 1.150.487 listeners = 0,43%**. `handlerReachesTarget` é emitido **0×** → o join exato de string é o único caminho.
- **Perda aperv-side, independente do produtor (PARSER-DROP, NOVO):** mesmo nos 19 com substrato, `MopData.parseWindows` (`MopData.java:308-320`) guarda os widgets num `Map<idName, Widget>` por base-activity com **última-escrita-vence** → widgets com `idName==""` colapsam num único bucket e widgets de mesmo `idName` na mesma activity se sobrescrevem. Resultado: **1.165 / 2.578 widgets flagged (45%) descartados antes do scoring** em 12 dos 19 APKs (futon −666, loki −364, fossify −105; `labnex` e `duress` perdem **100%**). Decomposição: **~730 por colisão de `idName` (recuperáveis por #0)** + **~435 por `idName` vazio (inerentemente não-endereçáveis por resource-id em runtime — item à parte, não recuperável por id)**. `mopActivities` ainda é populado (`:317-319`), então o +100 sobrevive — a perda é a **demção de +500/+300 para +100**. Verificado no fonte. Fix isolado em §7-#0.
- **99,03%** dos widgets carregados são *present-but-unflagged* → só podem ganhar o +100 uniforme de activity (ou 0).
- **Causa dominante = obfuscação R8.** 80,6% dos handlers são renomeados para fora do pacote (`<j.e0: onClick>`, `<q4.i: ...>`); o `reachability[]` no JSON só serializa FQNs *in-package* (`org.elnix.dragonlauncher.*`), porque o filtro `isAppClass`/`codePackage` descarta as classes renomeadas na serialização. Verificado em `org.elnix.dragonlauncher_44`: 8 handlers, todos renomeados; `handlers ∩ reachesTarget-sigs = 0`. ~90/169 APKs são >50% handlers obfuscados.
- **Compose é um caso separado, não-corrigível pelo listener-join.** `app.passwordstore.agrahn` tem **0 listeners** (36 widgets); o código alcançante vive em `$$ExternalSyntheticLambda`/`$inlined$onClick` sem `View.OnClickListener` ligado a um id. Steering widget-level via listener é impossível aqui — precisaria de activity-level.
- **Esta camada é problema do PRODUTOR** (gator + filtro de pacote na serialização), não do aperv. Detalhe e direção de fix em `rvsec-testes-jca` / plano de correção (G-1/A-2). Não atacada agora (ver §7, decisão do usuário).

### Camada 2 — o mecanismo de boost é fraco demais para guiar (aperv)
Mesmo nos 19 APKs com substrato, o boost não move a exploração:

- **~73% dos boosts não-zero são o +100 uniforme de activity** (`MopScorer.score` cai em `activityHasMop → +100` aplicado a TODOS os widgets-alvo, `StatefulAgent.java:1383`). Uniforme não re-ranqueia. Só **~27% (1.373 linhas, ≈1% das 133k decisões)** são discriminativos +300/+500.
- Dos discriminativos, **+500 é 100% resgatado por containment** e **+300 68%** — o `resource-id` de runtime quase nunca casa o `idName` estático; o B3 (ancestral/descendente ≤2) resgata **~20% de todos os boosts positivos** → **manter o containment depth≤2**.
- O boost vira um escalar `priority` consumido por **roleta/desempate, não argmax**: `EARLY_STAGE` → `RandomHelper.randomPickWithPriority` (roleta sobre ações não-saturadas); `EPSILON_GREEDY` → `greedyPickLeastVisited` (prioridade só desempata entre ações de **igual contagem de visitas** — visita domina) ou `randomlyPickAction` (roleta). Ramos que leem prioridade disparam em **97,5%** das decisões — o gargalo **não** é o ramo, é o sinal.
- O boost **compete com um coverage boost de igual magnitude (+100)** que premia o oposto (widget não-visitado, `StatefulAgent.java:1447`, decai com visitas). Co-disparam 100/100 em **16%** dos passos boostáveis. Removendo o +100 do MOP, esse empate some.
- **Corroboração (critério-dependente, mas robusta na conclusão):** a identidade sata_mop×sata vs o baseline de RNG do **mesmo braço** fica dentro de ~1–2pp sob qualquer critério de match, e o **sinal do delta inverte** conforme o critério (Agente E, replicação: match frouxo `activity+classe` → cross 8,7% > same 6,8%, mas **dominado por 1 app**, fossify; match estrito `token+coords` → 0,08% < 0,24%; delta pareado mediana ~0, sinal 6/12 entre os 12 APKs). Ou seja: **não há steering robusto acima do ruído de RNG** — ambos os braços divergem quase totalmente em poucos passos por epsilon/roleta. *A conclusão se sustenta pela mecânica (uniformidade do +100, PARSER-DROP, roleta), não por um número único de divergência* — os "2,6%/2,1%/+0,01pp" do Agente D não replicam fora do seu key semântico específico e foram rebaixados de "decisivo".

### Camada 3 (pré-requisito de UI, ortogonal) — a operação monitorada raramente executa
Mesmo alcançando a tela e boostando o widget certo, o alvo MOP (handler de **submit** de cripto) precisa dos campos preenchidos:
- **Não existe `SET_TEXT`.** Texto é efeito colateral de `MODEL_CLICK` em EditText via `ApeAgent.checkInput` → preenche **probabilisticamente** (`RandomHelper.toss(inputRate)`, `ape.inputRate` default 0.8) e **só o EditText sendo clicado**. Observado: **~42% dos toques em EditText injetam texto**; para um form de *k* campos, P(todos preenchidos antes do submit) ≈ `inputRate^k` → ínfimo.
- Não há sequência "preencher todos → submeter". Cobertura intra-tela ~0,67 (telas MOP **piores**, 0,57). `generateInputText` já é type-aware (`TypedInputGenerator` via `inputType`/`hint` do MOP) — a geração por campo é boa; falta a **completude do form**.

---

## 2. Correções aos achados preliminares do prompt (§2 do prompt de investigação)

| Claim preliminar | Verificado | Correção |
|---|---|---|
| `maxBoost>0` em 4,5% | 4,5% era sobre *linhas de boost* | **~3,2% das decisões** (4.282 / 133.192 [APE-STEP]; replicação E: 3,21%) |
| "85% uniforme N/N" | 100% das linhas são N/N | Artefato do +100-floor (`boosted=N/M` conta boost>0, não boost==max). **Correto: +100 = uniforme (73% dos boosts); +300/+500 = discriminativos, M>1 em 99,3% → re-ranqueáveis (27%)** |
| "boost raramente chega a ramo de prioridade" | ramos que leem prioridade = **97,5%** das decisões | **Refutado** — o gargalo é a uniformidade/realização, não o ramo |
| Níveis (prompt): +100=62,6% / +300=19,5% / +200(wtg)=14,7% / +500=3,2% | denominador **inclui WTG** | MOP-only (Agente E): **72,6 / 23,5 / 3,9%** ≈ §1 (73/27). Como % de **decisões**: 100→2,68%, 300→0,47%, 500→0,14% |
| Anomalia "boosted=8/8 maxBoost=0" | 0 linhas com numerador≥1 e maxBoost=0 | **Não existe** — leitura errada de `boosted=0/8` (guarda única em `StatefulAgent.java:1382-1387`) |
| A-5 `decision_source` sempre SATA | **132.552 SATA + 410 Budget, 0 MOP** (230 linhas truncadas; replicação E) | **Confirmado** (estrutural: `logActionSelected` sempre seta SATA) |

---

## 3. Catálogo de anomalias / bugs

| id | o quê | evidência (trace + fonte) | bug real? | fix (uma linha) |
|---|---|---|---|---|
| **A-5** | `decision_source` nunca = MOP/Coverage/WTG | 0/132.552 passos; enum já tem os valores (`ModelAction.java:43`), default SATA (:58), `logActionSelected` sobrescreve p/ SATA (`SataAgent.java:224`) | **Sim (telemetria)** | atribuir fonte ao mecanismo de maior boost na ação escolhida **quando o ramo leu prioridade** (§7-#3) |
| **PARSER-DROP** | parser descarta 45% dos widgets flagged (idName-keyed, última-escrita-vence) | 1.165/2.578 flagged perdidos em 12/19 APKs; `MopData.java:308-320` (`labnex`/`duress` perdem 100%) | **Sim (eficácia, aperv-side, isolado)** | colisão: vence o flag mais forte; `idName==""`: tratar à parte (§7-#0) |
| **MEC-UNIF** | +100 uniforme não re-ranqueia | 73% dos boosts; `MopScorer.score` retorna +100 p/ todo widget quando `activityHasMop` | **Sim (eficácia)** | remover o fallback +100 → boost discriminativo-only (§7-#2) |
| **MEC-TIE** | coverage +100 == mop +100 competem | co-disparo 100/100 em 16% dos passos boostáveis | **Sim (eficácia)** | resolvido por MEC-UNIF (MOP só 300/500 passa a dominar coverage 100) |
| **WTG-KEY** | WTG keyed por nome-de-janela `#`-sufixado, query por base-activity | ~34 edges válidos silenciados em keepitup/sambalite/syncthingfork; `MopData.java:468` vs `MopScorer.java:84` | **Sim (eficácia, aperv-side)** | keyar `wtgTransitions` por `baseActivity()` (§7) |
| **UI-FORM** | EditText preenchido só ~42%, sem submit-after | `checkInput` toss `inputRate`; `doInput` só digita se `getInputText()!=null` | **Sim (eficácia)** | preencher todos EditText na tela + priorizar submit (§7-#1) |
| **OBS-UICOV** | sem dump de cobertura UI no trace | `UICoverageTracker` não tem método de dump; só `coverage=` por ação | **Não (lacuna de observabilidade)** | 1 linha/state no teardown (§7-#4) |
| A-3 | component-trigger | **0** linhas `Triggering` (`componentPercentage=0.0`, `Config.java:178`) | Não (gated de propósito) | nenhum |
| DATA0 | 12 APKs carregam 0 widgets | `windows[]` populado, `widgets[]` genuinamente vazio no JSON | Não (saída vazia do analisador estático) | upstream, não aperv |
| OBF | widgets carregados mas boost 0 (passwordstore) | Compose, 0 listeners; ou handler renomeado fora do pacote | Não (Camada 1) | produtor/gator (G-1/A-2), fora do escopo atual |

---

## 4. Estatísticas de cobertura de UI (sata vs sata_mop)

- **Mix de tipo de ação** quase idêntico entre braços (MOP-on não muda): CLICK 53,4% · BACK 15,8% · MENU 9,9% · LONG_CLICK 6,4% · SCROLL 14,5%. **Não existe SET_TEXT** como tipo.
- **Classe do widget acionado:** View(genérico) 36,7% · **EditText 11,5% · Button 10,4%** · TextView 9,8% · resto pulverizado. **Tipos relevantes a MOP NÃO estão sub-representados por share** — o problema é **sequência** (form-fill→submit) e **profundidade**, não seleção de tipo.
- **Preenchimento de EditText:** 41,9% (sata_mop) / 42,6% (sata); ~9,5 fills por run de ~265 passos.
- **Cobertura intra-tela** (widgets acionados ÷ descobertos): mediana **0,667**; telas MOP-bearing **piores (0,567)**. MOP-on não melhora.
- **Alcance de tela MOP:** **19/169** expõem uma activity MOP (coincide com os 19 com widget discriminativo da §1 — replicação E; o "18" de um mapeamento mais frouxo do Agente C foi descartado). Das alcançáveis, **~32,5% alcançadas** em 300 s; gap-mediana 0,55. Profundidade ao 1º boost: mediana **1 passo** quando a tela MOP é a inicial; telas profundas (loki @31–324, tubular @115) tardias ou perdidas.
- **Desperdício** idêntico entre braços (~26% BACK+MENU). `mop>0` só nos 12 APKs boostáveis; **nunca** no braço sata.

---

## 5. Propostas de melhoria — rankeadas (impacto × esforço)

**Núcleo do teste justo (escolhido) — atacam as causas-raiz verificadas, compõem entre si:**

| # | Mudança | Camada | Esforço | Impacto | Status |
|---|---|---|---|---|---|
| **0** | **PARSER fidelity**: não colapsar widgets flagged no `Map<idName>` (colisão → vence o flag mais forte; `idName==""` à parte) | aperv (1) | baixo | **ALTO** — recupera as colisões (~730 widgets, a metade endereçável); empty-id (~435) é item à parte; **precede e habilita #2** | **ESCOLHIDO (novo, re-investigação)** |
| **1** | **Form-fill → submit**: preencher todos EditText da tela + priorizar submit | UI (3) | médio | **alto** — destrava o pré-requisito dos handlers de cripto | **ESCOLHIDO** |
| **2** | **Boost discriminativo**: remover o +100 uniforme; short-circuit greedy p/ alvo MOP não-visitado | aperv (2) | baixo-médio | alto *após #0*; resolve MEC-UNIF+MEC-TIE | **ESCOLHIDO** |
| **3** | **A-5 telemetria**: `DecisionSource.MOP` quando o boost foi o maior na ação escolhida e o ramo leu prioridade | aperv | trivial | observabilidade | **ESCOLHIDO** |
| **4** | **Dump UICoverage**: 1 linha/state no teardown | aperv | trivial | observabilidade | **ESCOLHIDO** |
| **W** | **WTG-KEY**: keyar `wtgTransitions` por `baseActivity()` (`MopData.java:468`) | aperv (2) | trivial | recupera ~34 steering-edges em keepitup/sambalite/syncthingfork | **candidato barato** (folha com #0, mesmo arquivo) |

**Levers gerais de exploração (Agente F) — não-MOP-específicos; melhoram desempenho geral do aperv; opcionais, avaliar por P1:**

| # | Lever | Esforço | Impacto | Nota |
|---|---|---|---|---|
| F1 | Desligar screenshot+XML por-passo (`takeScreenshotForEveryStep`/`saveGUITreeToXmlEveryStep=false`) | trivial (config) | +20–40% passos/run | mas reach é **strategy-limited** → ganho de reach pequeno; útil p/ throughput da re-rodada (manter trace; cortar PNG/XML por passo) |
| F2 | Depth-steering p/ ações não-visitadas **adjacentes** à tela MOP | alto (estratégia) | **alto p/ breadth** — ataca "alcançada mas não exercida" | compõe com a redesign de WTG (boost navegacional, não flat) |
| F3 | Escapar loops de login/permission-wall (detector de N passos / ≤2 states / 0 edges novos) | médio | recupera ~16% dos runs travados (p90 tail=71%) | geral, não-MOP |
| F4 | Cortar overhead de navegação (BACK+MENU = 26% das ações) | médio | recupera ~¼ do orçamento de ações | geral |
| 5 | Substrato/obfuscação (gator emitir `handlerReachesTarget` do índice completo) | **alto** (cross-repo) | destrava ~90 apps *se* o join recuperar (NÃO-verificado) | **adiado** — decidir após teste justo |
| 6 | Budget 300→600 s | trivial | **só ajuda 3 apps late-reach** (opencloud, loki, tubular); 9/12 alcançam MOP ≤15 passos (~5 s) — reach NÃO é budget-limited | **adiado/targetado** |

**Decisão do usuário (2026-06-22):** **teste justo barato** = **#0**+#1+#2+#3+#4 (a re-investigação prepôs **#0**, que é pré-requisito de #2), validado no subset de 19 APKs com substrato; **W** (WTG-KEY) é um acréscimo trivial no mesmo arquivo de #0. Os levers F são **gerais** (melhoram aperv independentemente de MOP) — F1 é grátis na re-rodada; F2/F3/F4 são candidatos maiores a avaliar **depois** do teste justo. Racional P1: dar ao MOP um teste justo onde o substrato existe, com o conjunto mínimo de edições isoladas; não atacar Camada 1 (#5) nem fazer redesign de estratégia (F2) antes de saber se o núcleo move a agulha.

---

## 6. Limitações dos dados

- `[APE-STEP]` loga só a prioridade da ação **escolhida**, não das candidatas → "flip de argmax" não é reconstruível por passo; contornado pela uniformidade do +100 (que torna o flip impossível a priori). A divergência sata×sata_mop vs baseline RNG **não** é métrica robusta (depende do critério de match; sinal inverte — Agente E §1); usar a mecânica, não esse número.
- Cobertura intra-tela enviesada por LRU (`coverageMaxStates=2000`) e states tardios nunca revisitados.
- "Tela MOP alcançada" e "id-match" derivados de tokens `activity=`/`resource-id=` no trace.
- Camada 1: que `handlerReachesTarget` recuperaria `j.e0` no índice completo do gator é **plausível mas não-verificado** (filtrado fora do JSON) — exige checagem no gator antes de qualquer fix de produtor.

---

## 7. Plano de execução — #0+#1+#2+#3+#4 (+W) (PROPOSTO; não implementar; vira OpenSpec changes no repo `ape`)

> Ordem sugerida: **#3 → #4 (observabilidade) → #0 (+W) (restaurar substrato) → #2 (fazer steerar) → #1 (forms)**. Cada change segue P1/P3/P4. Validação única ao fim (§7.5).

### #3 — A-5: `decision_source` correto (capability `action-selection`)
- **Fonte:** `StatefulAgent.resolveNewAction()` (`:1256`), após `selectNewActionNonnull()` retornar a ação. Hoje `SataAgent.logActionSelected` (`:224`) seta SATA sempre.
- **Mudança mínima:** em `resolveNewAction`, depois da seleção, se `newAction` veio de um ramo que lê prioridade (`EARLY_STAGE`/`EPSILON_GREEDY`-roleta) **e** algum boost>0, setar `decisionSource` = mecanismo de maior boost (MOP/WTG/Coverage/Menu); senão manter SATA. Passar o `SataEventType` do ramo para a atribuição (já conhecido em `logActionSelected`).
- **Por quê honesto:** não afirma "decisivo" (contrafactual caro); afirma "qual mecanismo mais contribuiu na ação escolhida num ramo que de fato usou prioridade". Comentar a semântica (P4).
- **Sem teto, sem flag nova.** Não reintroduzir `llmMaxCalls` (não existe, NUNCA).

### #4 — Dump UICoverage (capability `observability` ou estende `action-selection`)
- **Fonte:** `UICoverageTracker` (getters já existem: `getTotalElements` `:290`, `getTotalInteractions` `:295`, `getInteractionCount`, `getCoverageGap`; `stateData` LRU + `activityRollup`). Sem método de dump hoje.
- **Mudança mínima:** método `dump()` chamado no teardown do agente (e opcional na evicção LRU), emitindo 1 linha/state:
  `[APE-RV] UICOV state=<key> discovered=<W> interacted=<D> gap=<1-D/W> byType=Click:a/b,Edit:c/d,Button:e/f mopReach=<0|1>`
- Fecha o loop de observabilidade sem spam por-ação. Nada de métricas especulativas.

### #0 — PARSER fidelity: parar de descartar widgets flagged (capability `mop-scoring`)
- **Fonte:** `MopData.parseWindows` (`MopData.java:308-320`) guarda `widgets.put(wd.idName, wd)` num `Map<idName,Widget>` por base-activity, **última-escrita-vence**; `getWidget` (`:642`) consulta por `extractShortId(resourceID)`.
- **Bug (verificado no fonte):** widgets de mesmo `idName` na mesma activity se sobrescrevem (730 perdas) e os de `idName==""` colapsam num único bucket (435 perdas) → **1.165/2.578 flagged (45%) somem antes do scoring** (`labnex`/`duress` perdem 100% da granularidade per-widget).
- **Mudança mínima:** na colisão de `idName` **não-vazio**, manter o widget de **flag mais forte** (direct > transitive > unflagged) em vez de última-escrita. Para `idName==""`: **não bucketizar** (são inendereçáveis por id em runtime — `extractShortId` não produz "" para um id real); apenas registrar a perda no log. Não inventar matching por classe/texto agora (P1).
- **Por que precede #2:** #2 torna o +500/+300 decisivo; sem #0, metade dos widgets que deveriam ter +500/+300 já foi sobrescrita por um vizinho não-flagged → não há o que discriminar nesses casos.
- **W (WTG-KEY), mesmo arquivo:** keyar `wtgTransitions` por `baseActivity(source.name)` (`:468`) p/ casar a query de `scoreWtg` (`MopScorer.java:84`) — recupera ~34 steering-edges em keepitup/sambalite/syncthingfork. Trivial; vai junto.

### #2 — Boost discriminativo (capability `mop-scoring` / `action-selection`)
- **2a — remover o fallback +100 uniforme.** `MopScorer.score` (`:35-55`): deletar o ramo `if (data.activityHasMop(activity)) return Config.mopWeightActivity;` → retornar 0 quando não há match widget-level. O boost passa a ser **discriminativo-only** (+500/+300). Isso resolve MEC-UNIF e MEC-TIE (some o empate com coverage +100). P3: deletar inteiro, sem deixar `mopWeightActivity` morto — remover o flag de `Config` e o javadoc correspondente.
  - *Risco/decisão:* perde-se o sinal "prefira a activity MOP". Evidência mostra que esse sinal é ruído (Camada 2). Manter removido para o teste justo; se quisermos um sinal activity-level, ele entra como **WTG/menu** (que já existem e são direcionais), não como +100 a todo widget.
- **2b — short-circuit greedy para alvo MOP não-visitado.** `SataAgent.selectNewActionEpsilonGreedyRandomly` (`:414-435`) já faz short-circuit para Back/Menu não-visitados. Adicionar análogo **antes** da roleta: se existe ação válida **não-visitada** com `mopBoost>0` (discriminativo), selecioná-la greedily. Mirroreia o padrão existente (P1, in-character). Dá ao MOP um caminho determinístico para o widget monitorado sem destruir o caráter epsilon-greedy do SATA.
  - *Alternativa mais conservadora* (se 2b for considerado forte demais): apenas recalibrar pesos não muda a diluição da roleta — por isso 2b (short-circuit) é preferível a "aumentar +500". Decidir em revisão.

### #1 — Form-fill → submit (capability `action-selection` / input)
- **Fonte:** `ApeAgent.checkInput` (`:184-196`) preenche probabilisticamente (`toss(inputRate)`) **só o EditText sendo clicado**; `doInput` (`MonkeySourceApe.java:1238`) digita se `getInputText()!=null`. `generateInputText` (`:203`) já é type-aware.
- **Mudança mínima (completude do form):** ao entrar num state com ≥1 EditText não-preenchido, preencher **todos** os EditText visíveis (via `generateInputText`/`TypedInputGenerator`, determinístico — sem o `toss`), e então priorizar a ação de **submit**. Submit = a ação `requireTarget` Button/clickable com `mopBoost>0` (composição com #2: o submit é exatamente o widget boostado) ou, na ausência, heurística mínima (Button único / texto de submit). Manter a heurística enxuta (P1) — não construir um classificador de form.
- **Open question a resolver no design:** rate efetiva 42% vs `inputRate=0.8` (propriedade da corrida?) — confirmar no `ape.properties` do cmpmop; não muda o defeito estrutural (per-campo + probabilístico).
- **Sequência:** preencher-todos numa visita, submeter na seguinte (ou no mesmo episódio via buffer). Evitar re-preencher campo já com texto (`getInputText()!=null`).

### 7.5 — Experimento mínimo de validação (um só)
- **Subset:** os **19 APKs com substrato discriminativo** (lista derivável: APKs com ≥1 widget `reachesTarget`-joined no `<apk>.json`; os 12 boostáveis ⊂ 19). Não rotular obfuscados (não há oráculo — P1).
- **Braços:** `aperv:sata` (controle) × `aperv:sata_mop` (com **#0**+#1+#2(+W) ligados), 300 s, ≥3 reps. Re-rodada pode ligar F1 (sem screenshot/XML por passo, mantendo o `.trace`) p/ throughput.
- **Métrica primária:** `mop_unique`/`mop_total`/`cov_mop` pareados (Wilcoxon) **e** nº de **states MOP-bearing distintos visitados**/run — se #0+#2 funcionam, sata_mop deve exercer mais operações monitoradas que sata. Secundárias: profundidade ao 1º boost; share de `decision_source=MOP` (via #3). **NÃO** usar a divergência sata×sata_mop como métrica primária (não-robusta, §1/§6).
- **Observabilidade:** #3 (`decision_source=MOP` deve passar a aparecer — hoje é 0) e #4 (UICOV) confirmam que o mecanismo agiu e quanto de cada tela foi exercido.
- **Critério de decisão:** se sata_mop superar sata no subset (ganho pareado em `mop_unique`/`mop_total` **e** mais states MOP visitados), vale considerar a Camada 1 (#5, gator) e os levers de breadth (F2). Se continuar nulo **com substrato restaurado (#0) e boost que steera (#2)**, o MOP-guidance está refutado **com teste justo** — pivotar para os ganhos gerais (#1 forms, F1/F3/F4), que ajudam cobertura independentemente de MOP.

---

## 8. Artefatos
- Traces/JSON: `rvsec/rv-android/data/results/cmpmop_*/cmpmop_*/<apk>/`.
- Consolidado + memo: `rvsec/rv-android/docs/20260622_cmpmop_analise.md`; plano de correção: `ape/docs/20260621_plano_correcao_aperv_e_modulos_relacionados.md`.
- Memórias: `cmpmop-mop-guidance-inert-two-layers`, `aperv-obfuscation-resilience-via-signature-reachability` (atualizada), `mop-fires-on-nonobfuscated-apps`.

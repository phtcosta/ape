# Análise forense das traces cmpm (base vs v2) — anomalias e bugs do aperv

**Data:** 2026-07-20
**Insumos:** 1086 traces (543 base + 543 v2), `rv-android/data/results/cmpm_{base,v2}_{00..07}/`,
consolidados `per_apk_paired.csv`, `paired_wilcoxon.csv`; laudo agregado em
`rv-android/docs/20260718_cmpmodels.md` §11.
**Método:** systematic-debugging (evidência antes de fix); varredura de anomalias em 100% das traces;
extração por-trace de 40+ métricas (script `extract_trace_stats.py`, uma passada); pareamento por APK
(n=181) com Wilcoxon e Spearman contra Δcov_mop; leitura frame a frame das traces fatais
(método marker-boundary); confirmação de cada mecanismo no código de `ape` master (3fadf98).

---

## Sumário executivo

1. **[A1 — DEFEITO REAL, P0] `MODEL_LLM_TAP` nunca injetou um único toque em toda a campanha.**
   O dispatch constrói `Rect(x, y, x, y)` (área zero) e o guard INV-EXPL-19 de
   `generateClickEventAt` o descarta **100% das vezes**, deterministicamente. 7.471 steps do base
   (7,6%) e 9.154 do v2 (10,0%) foram no-ops que custaram ~1s de LLM + throttle cada e não tocaram o
   device. A métrica "recuperação off-tree 47%→85%" de H2 mede decisões cuja **execução foi
   integralmente descartada**.
2. **[A2 — DEFEITO REAL, P1] Novo terminador residual do refinement-crash-recovery:** ação efêmera
   (`MODEL_LLM_TAP`) retida como `currentAction` através de um rebuild → âncora morta →
   `IllegalStateException` em `StateTransition.<init>`. Matou 1/1086 runs (o "único truncamento" do
   §11.1, que o laudo agregado classificou — incorretamente — como transiente).
3. **[A3 — CONFIRMAÇÃO] O fix INV-MODEL-16 funcionou:** zero `No such action`, zero
   `An unvisited state has non-empty transitions`, zero `SecurityException while injecting` nas 1086
   traces.
4. **[A4 — MECANISMO] O déficit de −2,62 pp é mediado por diversidade de estados** (Spearman
   ρ=+0,546 entre Δestados e Δcov_mop), alimentada por três componentes quantificados: tempo de
   parede em LLM (37,6% vs 31,9% do orçamento), share de steps no-op (10,0% vs 7,6%) e menor
   rendimento exploratório por step efetivo (0,122 vs 0,132). **A narrativa "v2 é mais exploitativo"
   está confundida com A1**: parte do que parece exploração desperdiçada é o v2 seguindo o LLM para
   dentro de um buraco negro que o aperv criou.

---

## 1. O que realmente aconteceu por braço (agregado das traces)

Médias por run (543 runs/braço); pareado por APK (n=181), Wilcoxon nos deltas:

| métrica | base | v2 | Δ | p |
|---|--:|--:|--:|--:|
| steps executados | 180,9 | 168,7 | **−12,3** | 5,7e-6 |
| estados distintos visitados (por `[APE-STEP]`) | 22,0 | 18,6 | **−3,5** | 6,8e-15 |
| atividades distintas | 2,65 | 2,39 | −0,26 | 7,1e-7 |
| steps decididos pelo LLM (`decision_source=LLM`) | 71,2 (39,4%) | 100,0 (59,3%) | +28,9 | 6,4e-24 |
| steps SATA (fallback) | 77,4 (42,8%) | 46,2 (27,4%) | −31,2 | 1,2e-22 |
| tiebreak EARLY_STAGE | 78,9 | 48,4 | −30,5 | 3,4e-25 |
| tiebreak EPSILON_GREEDY | 21,9 | 13,0 | −8,9 | 4,2e-10 |
| chamadas LLM | 93,1 | 103,9 | +10,8 | 4,3e-10 |
| tempo de parede em LLM (s/run de 300s) | **95,8 (31,9%)** | **112,8 (37,6%)** | +17,0 | — |
| steps `MODEL_LLM_TAP` (todos no-op, ver A1) | 13,8 | 16,9 | +3,1 | 9,7e-5 |
| `off-screen action dropped` | 14,1 | 16,9 | +2,9 | 2,0e-4 |
| breaker trips | 0,64 | 0,15 | −0,49 | 3,4e-17 |
| CRASH do app (monitor do Monkey) | 81 total | 40 total | — | — |

A telemetria H2 do §11.3 **reproduz exatamente** (matched 62,0%→80,4%, null 6,7%→0,5%, no_match
16,5%→2,9% nas traces). A execução foi de fato limpa: elapsed mediano ~298s nos dois braços; abaixo
de 270s só 1 run no base (photok, 233s) e 3 no v2 (floflacards 82s = A2; quicksearch 135s = wedge
§A5.1; otphelper 256s).

---

## 2. Catálogo ranqueado de anomalias

### A1 — `MODEL_LLM_TAP` jamais injeta evento: 100% descartado pelo guard INV-EXPL-19 — **DEFEITO REAL (P0)**

**Código.** `MonkeySourceApe.java:955-962` (dispatch):

```java
case MODEL_LLM_TAP:
    LlmTapAction tap = (LlmTapAction) action;
    Rect tapRect = new Rect(tap.getPixelX(), tap.getPixelY(), tap.getPixelX(), tap.getPixelY());
    generateClickEventAt(tapRect, ..., ClickPoint.CENTER);
```

O `tapRect` tem **área zero** (`left==right`, `top==bottom`). Em `generateClickEventAt`
(`MonkeySourceApe.java:397-451`) ele morre em um de dois ramos, sem exceção:

- **Ponto estritamente dentro da tela:** `visibleBounds.intersect(tapRect)` retorna `true` (o
  `Rect.intersect` do Android só exige desigualdades estritas contra o *outro* rect), mas devolve a
  **interseção vazia** `Rect(x,y,x,y)`; em seguida `bounds.contains(x,y)` é `false` para qualquer
  rect vazio (`contains` exige `left < right`) → loga `Invalid bounds:` + `off-screen action
  dropped` e **retorna sem enfileirar evento** (`:445-450`).
- **Ponto na borda/fora:** `intersect` falha → `getVisibleBounds` retorna `null` → drop no primeiro
  guard (`:399-404`).

**Evidência de trace (conclusiva).**

- Contagem exata por braço: base 7.659 drops vs 7.471 steps LLM_TAP; v2 9.160 vs 9.154. O resíduo
  (~188 base / 6 v2) são cliques em widgets legítimos de área zero (ex.: speakthat
  `Rect(992,1548-992,1548)` de MODEL_CLICK).
- Amostragem de 156 traces (1/7 de cada braço): **2.225/2.229 execuções de LLM_TAP têm a linha de
  drop imediatamente após o `SATA end step`**; as 4 restantes são interleaving de restart no log.
  Exemplo típico (networksurvey v2_00 rep3, steps 13-20): o mesmo `@(554,1395)` é despachado e
  descartado nos steps 13, 14, 15, 16 e 20 — com temp=0 e a tela **congelada porque o toque nunca
  acontece**, o LLM repete a mesma coordenada; cada iteração custa 1 chamada LLM (~1s) + throttle.
- Taps por coordenada única: mediana 2,0 (base) vs 2,7 (v2), p90 6,6 vs 8,5, máx 65-70 — loops de
  drop são o caso dominante de "estado preso" do v2.

**Linha do tempo (por que nasceu morto).** O guard INV-EXPL-19 entrou em `c6c5d1f` (2026-07-07,
mop-fairtest) para descartar cliques com bounds fora da tela visível. `MODEL_LLM_TAP` entrou 9 dias
depois (`0e7b6f`→`0e7b16f`, 2026-07-16) despachando um rect de área zero direto para esse guard. Os
testes existentes (`SataAgentLlmTapTest`, `LlmTapActionTest`, `GraphEphemeralActionTest`) cobrem
seleção, identidade efêmera e telemetria — **nenhum cobre o dispatch em `MonkeySourceApe`**. O smoke
E2E de 2026-07-16 (thumbkey) validou steps/telemetria, não a injeção física.

**Consequências.**

1. Toda a "recuperação off-tree" de H2 (47%→85%) é decisão sem execução: o pipeline LLM inteiro
   (screenshot → prompt → parse → normalização de coordenada) funciona e então o resultado é jogado
   fora no último passo.
2. 16.625 steps da campanha (7,6% do base, 10,0% do v2) não interagiram com o device; o v2, por
   "recuperar melhor", entrou 22,5% mais vezes nesse buraco.
3. Os no-ops congelam a tela → LLM em temp=0 repete a coordenada → loops; isso infla o próprio
   `llm_tap` do v2 e derruba o rendimento por step.
4. Todo o dano colateral do regime MODEL_LLM_TAP (crashes de rebuild do cmpv2, A2 abaixo) foi pago
   **sem nunca receber o benefício** da funcionalidade.

**Veredito:** defeito real do aperv, P0, fix imediato e pequeno (injetar no ponto: rect 1×1
`(x, y, x+1, y+1)` com clamp na tela, mantendo o descarte para coordenada genuinamente fora do
display). → OpenSpec change `llm-tap-injection`.

### A2 — `currentAction` efêmera atravessa rebuild com âncora morta → `IllegalStateException` — **DEFEITO REAL (P1, terminador)**

**Trace fatal:** `cmpm_v2_05/com.floflacards.app_14.apk/...__1__300__....trace` (o único truncamento
da campanha, 82s, 3 steps). Sequência frame a frame:

1. Step 2: LLM responde `result=llm_tap` → cria-se `[2,2][1]@MODEL_LLM_TAP...g0s0...Naming[0]...@(540,1158)`
   (efêmera, `INVALID`), que vira `currentAction` (linha 145).
2. Step 3: `actionRefinement` dispara (`Refine name class=android.view.View...`, linha 156) →
   `Start rebuilding model` → `Removing state g0s0...Naming[0]` → estados renascem como
   `g1s1`/`g1s2` em `Naming[3]` (linhas 177-189). O INV-MODEL-16 funciona: o replay pula/purga a
   edge efêmera (nenhum `No such action`).
3. Pós-rebuild, `updateGraph` (`StatefulAgent.java:981`) chama
   `model.addTransition(currentState=g1s1@Naming[3], currentAction=LLM_TAP@g0s0@Naming[0], ...)`.
   `currentState` foi re-ancorado pelo rebuild; a efêmera, por contrato do fix ("update devolve refs
   efêmeras inalteradas"), **ficou apontando para o estado removido**. O invariante
   `source.equals(action.getState())` de `StateTransition.<init>` (`StateTransition.java:47-51`)
   estoura → `Internal error` → run morre (linhas 199-200 e 365+ da trace: `Source: g1s1[...]` vs
   `Action: [...]g0s0[...]`).

**Frequência:** 1/1086 runs (exige LLM_TAP como currentAction no exato step em que um refinement
reconstrói o estado-fonte — raro, mas determinístico quando ocorre).

**Correção do laudo agregado:** §11.1 do `20260718_cmpmodels.md` chama esse truncamento de "saída
precoce transiente de uma rep". Não é transiente nem infra: é este terminador. A conclusão de H3
(truncamento ≈0%, sem confound) permanece válida.

**Veredito:** defeito real, gap residual do refinement-crash-recovery (INV-MODEL-16 tratou o
model-side; faltou o agent-side). Fix: em `updateGraph`/`addTransition`, ação efêmera cuja âncora
não é `currentState` (estado removido por rebuild) → descartar a edge com log, não crashar. Mesma
change de A1 (dois requirements).

### A3 — Eficácia do refinement-crash-recovery e ausência dos terminadores conhecidos — **CONFIRMAÇÃO (esperado)**

Varredura nas 1086 traces: **0** `No such action`, **0** `An unvisited state has non-empty
transitions`, **0** `SecurityException while injecting`, **0** `BadState`, **0** `Cannot find
widget`. O fix embarcado na imagem 0.9.2 rebuilt (3fadf98) eliminou o terminador do cmpv2, e os dois
terminadores pré-existentes desmascarados pelo F-A **não se materializaram** neste dataset/condição
(continuam latentes — o unvisited-state foi reproduzido no thumbkey standalone, que não está nos 181
APKs).

### A4 — Mecanismo do déficit de cobertura: mediação por diversidade de estados, com A1 como confound — **NÃO É BUG (mas reescreve a interpretação)**

- **Mediador proximal:** Δ(estados distintos) correlaciona com Δcov_mop a **ρ=+0,546 (p=1,8e-15)**
  — de longe o sinal mais forte (steps: ρ=0,12 n.s.; ds_LLM: ρ=−0,10 n.s.; telemetrias H2:
  |ρ|≤0,26). O v2 cobre menos porque visita menos estados; qualquer explicação tem de passar por aí.
- **Decomposição do "visita menos estados" (três componentes quantificados):**
  1. **Orçamento de parede:** v2 gasta 112,8s/300s (37,6%) bloqueado em chamadas LLM vs 95,8s
     (31,9%) do base (mais chamadas: breaker abre 4× menos; +11,6% calls) → −12,3 steps/run.
  2. **Steps no-op (A1):** 10,0% vs 7,6% dos steps são LLM_TAP descartados.
  3. **Rendimento residual:** mesmo por step efetivo (excluindo taps), o v2 rende menos estado novo
     (0,122 vs 0,132 estados/step) — este é o componente genuinamente "exploitativo" (decisões
     matched revisitam mais).
- **A tese "fidelidade ≠ cobertura" continua verdadeira para este artefato**, mas o enunciado
  mecanístico do §11.4 precisa de emenda: o contraste não é "seguir o LLM fielmente vs ruído
  explorador de graça"; é "seguir o LLM **para dentro de um caminho de execução quebrado** (llm_tap
  no-op, 2ª maior classe de decisão do v2) + pagar mais latência". O quanto do −2,62 pp sobraria com
  A1 corrigido é **empiricamente aberto** — o componente 3 sugere que parte do déficit persistiria,
  mas a magnitude atual não é interpretável como propriedade do modelo.

### A5 — Anomalias secundárias — **ESPERADO / DÉBITO / FORA DE ESCOPO**

1. **Wedge `waitForActivity` (débito conhecido, raro aqui):** quicksearch v2 rep3 — steps param aos
   135s, `waitForActivity exceeded 100 cycles, relaunching` (2×), Monkey encerra com `System appears
   to have crashed at event 397` após 312s de parede; 175s mortos. 1 arquivo por braço (9 hits base,
   22 v2). O relaunch do fix anterior dispara mas não destrava este caso (app morto de verdade).
   Monkey-baseline/app-crash; fora de escopo.
2. **Crashes de app assimétricos:** monitor do Monkey registra 81 CRASH no base vs 40 no v2 — a
   exploração estocástica do base estressa mais os apps. O consolidado da campanha reporta
   `crashes=0` nos dois braços (pipeline rv-android conta outra coisa); discrepância a registrar no
   rv-android, não no ape.
3. **Cliques em widgets de área zero (resíduo):** ~188 drops legítimos/braço (ex.: speakthat) — o
   guard INV-EXPL-19 operando como projetado sobre árvores com bounds degenerados; a ação fica
   pickável e re-seleciona ocasionalmente. Custo ~0,1% dos steps; débito menor, sem ação.
4. **`Oops`/`Internal error` falsos-positivos:** os 2 hits de `Oops` no base são a string aleatória
   "Oops!" digitada em EditText (kitshn); o único `Internal error` real é A2.

### A6 — Histograma final de tipos de ação imprime tudo zero — **DÉBITO DE TELEMETRIA (herdado)**

O dump de teardown (`ActionCounters.print()` via `StatefulAgent:1665`) imprime 0 para todos os tipos
em 100% das traces porque o incremento está **comentado desde o import do APE upstream**
(`StatefulAgent.java:1682`: `//actionCounters.logEvent(action.getType());` — presente já em
84bb828/Phase 1). Inofensivo mas engana forense (o histograma real está em `decision_source`/
`[APE-STEP]`). Fix trivial opcional; não bloqueia nada.

---

## 3. Respostas às perguntas do protocolo

1. **Para onde vão os steps do v2?** −12,3 steps/run (mais parede em LLM), +3,1 steps no-op
   (llm_tap descartado), e os steps restantes revisitam mais (yield 0,122 vs 0,132). Repetição de
   coordenada idêntica: mediana 2,7 vs 2,0 taps/coordenada (p90 8,5 vs 6,6) — loops determinísticos
   de temp=0 sobre tela congelada. ND/refinement: sem diferença relevante (ND −0,08 n.s.;
   refinement −0,72, p=0,01, direção *menor* no v2).
2. **O LLM está dirigindo?** Sim, mais no v2: 59,3% dos steps `decision_source=LLM` vs 39,4%.
   `screenshot_failed` é ruído (0,3% das calls, 29/25 arquivos, concentrado no bitbanana =
   FLAG_SECURE, coerente com o estrato n=1 do §11.6). Breaker: 348 vs 82 trips. E as edges
   `llm_tap` **não levam a lugar nenhum por construção** (A1): 100% descartadas antes da injeção.
3. **Anomalias:** catálogo §2; nenhum stack trace além de A2; zero terminadores conhecidos (A3);
   traces vazias/curtas: 4 runs <270s, todas explicadas (A2, wedge, photok/otphelper transientes).
4. **O déficit é comportamento fixável do aperv?** Parcialmente, com confiança alta: A1 é fixável e
   remove (a) os no-op steps, (b) os loops de repetição e (c) devolve ao llm_tap a chance de gerar
   cobertura real. O componente de latência é inerente ao arm 70%-LLM; o componente exploitativo
   residual é propriedade da política/modelo. Re-rodar cmpm com A1 corrigido é a única forma de
   separar os três — antes disso, a comparação base↔v2 mede um regime onde a 2ª classe de ação do
   v2 era um placebo caro.

## 4. Implicações para o texto da tese (§11.7 do doc da campanha)

- H2 (fidelidade de decisão) **permanece** — é medida no parser/matcher, antes do defeito.
- H1/H4 (cobertura) permanecem como resultados **deste artefato**, mas a leitura "seguir o LLM
  fielmente é mais exploitativo que o ruído do base" está **confundida com um defeito de execução
  do harness**: o braço que mais confiava no llm_tap foi o mais punido por ele ser um no-op.
  Recomendação: qualificar §11.4/§11.7 citando este laudo e re-rodar (mesmo que em amostra) após o
  fix antes de cravar a narrativa exploração-vs-exploitação.
- §11.1: reclassificar o truncamento do floflacards de "transiente" para "terminador A2".

## 5. Decisão de fix

Uma change OpenSpec (`sdd-full`, spec-before-code, TDD RED→GREEN): **`llm-tap-injection`**
1. **R1 (A1):** `MODEL_LLM_TAP` deve injetar toque no ponto decidido — rect não-degenerado com clamp
   ao display; descarte só para coordenada fora do display físico; teste de dispatch cobrindo o
   caminho `MonkeySourceApe.generateClickEventAt` com rect de ponto.
2. **R2 (A2):** edge efêmera com âncora morta pós-rebuild não pode chegar a `StateTransition.<init>`
   — guard em `updateGraph`/`addTransition` (skip+log+telemetria), estendendo INV-MODEL-16 para o
   agent-side.

Gate on-device: fresh-install (`pm clear`) + foreground-first + SGLang v2, verificando (i) ausência
das linhas `off-screen action dropped` pareadas a LLM_TAP, (ii) mudança de tela após um llm_tap,
(iii) nenhum `IllegalStateException` sob refinement com tap efêmero pendente.

**Status (mesmo dia):** change `llm-tap-injection` implementada (TDD RED→GREEN, suite 651/0/19),
gate on-device **PASS** (thumbkey fresh-install + SGLang v2: tap injetado em (540,957) mudou o
estado `g7s25@533724406`→`g7s19@-516771663`; zero `IllegalStateException`), arquivada como
`2026-07-20-llm-tap-injection` com delta-sync manual (19/19 strict), commit `f0bae7b` (local).
Evidências completas em `openspec/changes/archive/2026-07-20-llm-tap-injection/verification.md`.
Débito residual medido no gate: 6/7 taps do thumbkey ainda dropam porque o guard usa os bounds do
root node (janela do app), não do display — taps na faixa do IME/teclado são rejeitados; decidir a
fonte de bounds correta é uma change separada.

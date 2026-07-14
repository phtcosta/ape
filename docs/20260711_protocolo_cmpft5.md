# Protocolo cmpft5 (v2, launcher-ON) — teste do consumidor A′ (activity vs widget)

**Data:** 2026-07-11 · **Revisão:** v2 (launcher-ON), após o Gate 0 v1 nulo de mecanismo
**Status:** desenho fixado (2 braços pareados, launcher ON); artefatos prontos; Gate 0 v2 pendente de
autorização; run completo pendente do Gate 0 PASSAR.
**Precedentes:**
- Gate 0 v1 (launcher OFF, **nulo de mecanismo**): `rvsec/rv-android/docs/20260711_relatorio_gate0_cmpft5.md`
- Forense do nulo cmpft4: `rvsec/rv-android/docs/20260710_analise_forense_cmpft4.md`
- Resultados cmpft4: `rvsec/rv-android/docs/20260710_relatorio_cmpft4.md`

---

## 0. Uma frase

cmpft4 comparou `sata_mop_activity` vs `sata_mop_widget` mas eles rodaram **código idêntico** (A′
ampliava `mopActivities` mas ninguém consumia o conjunto ampliado → Friedman cov_method **p=0.967**, nulo
*vazio*). A change `mop-activity-consumers` deu consumidores a A′; o **Gate 0 v1 (launcher OFF)** provou
que a flag chega ao jar (`mopActsAugmented`=5/25/9 vs 0) **mas** que, com o launcher desligado, os dois
consumidores de A′ são inertes (`Nav MOP tiebreak`=0 em 6 arm-runs; `scoreWtg` idêntico) → **nulo de
mecanismo**. cmpft5 **v2** liga o launcher nos **dois** braços: A′ passa a alimentar o consumidor
**forte** (`selectTriggerCandidate`/E-mín, que **lança** atividades A′-alcançáveis na estagnação),
mantendo 2 braços pareados (Wilcoxon) e diferindo em **uma única flag** (`mop_activity_source_components`).

## 1. Hipótese

- **H1 (primária):** em apps onde A′ amplia o substrato (`mopActsAugmented>0`), o braço
  `sata_mop_activity` (launcher ON + A′) cobre mais métodos/alvos MOP que `sata_mop_widget`
  (launcher ON, A′ OFF).
- **H0:** as distribuições pareadas por app são iguais.
- **Nulo informativo:** se, com o consumidor **forte comprovadamente dosado** (Gate 0 v2: dose≥3
  launches/run) e A′ diferenciando o censo, os braços forem indistinguíveis, a conclusão é "o launch de
  atividades A′-alcançáveis não converte em cobertura MOP" — nulo *informativo*, diferente do nulo
  *vazio* do cmpft4 e do nulo *de mecanismo* do Gate 0 v1 (consumidor inerte).

## 2. Desenho: 2 braços pareados, launcher ON, 1 variável independente

| Braço | variant | `mop_activity_source_components` (A′) | `activity_trigger_enabled` (launcher) | Papel |
|---|---|---|---|---|
| controle | `sata_mop_widget` | **False** | True | reach só widget, launcher dosado |
| tratamento | `sata_mop_activity` | **True** | True | reach widget + A′, launcher dosado |

- **Única flag que difere: `mop_activity_source_components`.** Verificado empiricamente: os dois configs
  resolvidos (`{**variant, **@params}`) diferem em **exatamente 1 chave** (`mop_activity_source_components`:
  False vs True); os 4 params de launcher são idênticos nos dois braços.
- **Por que o launcher ON resgata o desenho:** com launcher OFF, A′ tinha só 2 consumidores fracos
  (nav-tiebreak raro + `scoreWtg` que não divergiu — Gate 0 v1). Com launcher ON, A′ ganha o consumidor
  **forte** `selectTriggerCandidate`/E-mín (`SataAgent.java:639-670`, INV-CT-09): na estagnação, o
  launcher **lança diretamente** atividades A′-alcançáveis. É onde A′ de fato esteça a navegação.
- **Preserva Wilcoxon:** os dois braços têm launcher ON com **config idêntica** e diferem só por A′ →
  continua **2 braços / pareado** (NÃO vira Friedman). Não sai do desenho de contraste limpo.

### 2.1. Distinguibilidade dos braços (checkpoint crítico — onde o cmpft4 falhou)

A identidade de task é `(apk,tool,variant,rep,timeout)`. Se os dois braços tivessem o **mesmo**
`tool:variant` (diferindo só por `@param`), o dedup os **funde** (confound total — erro do cmpft4).
**Resolvido usando os dois variants NOMEADOS distintos:**
- controle: `aperv:sata_mop_widget@<dose>`
- tratamento: `aperv:sata_mop_activity@<dose>` (já é `{**sata_mop_widget, mop_activity_source_components:True}`)

→ rótulos de variant distintos (`sata_mop_widget` ≠ `sata_mop_activity`) → identidades distintas, sem
colisão; diferem só por A′ + os **mesmos** params de launcher. Verificado: `_split_tool_specifications`
reconstrói os 2 specs corretamente apesar das vírgulas nos `@params` (heurística regex tool-vs-param).

## 3. Build (anti-build-skew — passo crítico) — ✅ FEITO nesta sessão

- **Jar:** worktree `ape-mop-fairtest`, branch `mop-fairtest`, `HEAD = fa4bc59` = `5d703c3`
  (`mop-activity-consumers`) + `e91c78c` (archive) + `fa4bc59` (`activity-trigger-dose`). Não pushado.
- **Rebuild + redeploy:** `mvn install -Drvsec_home=/pedro/.../workspace-rv/rvsec` (copia `ape-rv.jar`
  para `rv-android/modules/aperv-tool/.../tools/aperv/`).
- **md5 do jar deployado = `3de5b9a4ad50bbc5c3279e7ff222a390`** (≠ `bbbc6be0` = jar Gate 0 v1 sem dose;
  ≠ `2de82182` = jar cmpft4). Entrega por **bind-mount** nos 8 containers (imagem `0.9.1` intacta — o
  `Dockerfile` clona a branch default → rebuildar assaria o jar velho, o modo de falha do cmpft4).
- **Guards no dex** (`ape-rv.jar` é empacotado como `classes.dex`; usar `unzip -p jar classes.dex | grep -a`):
  `activityTriggerStagnationStep` ✓, `activityTriggerMaxPerRun` ✓, `mopActsAugmented` ✓,
  `mopActivitySourceComponents` ✓.
- Guard em runtime: `[APE-MOP-DATA] … mopActivities=… mopActsAugmented=…` (só existe no jar novo).

## 4. activity-trigger-dose — os 2 flags novos (arm-neutral)

`fa4bc59` adiciona 2 chaves ao `ape.properties`, **defaults = comportamento atual** (INV-CT-11/12):

| Python (`aperv-tool`) | `ape.property` | Default | cmpft5 v2 | Efeito |
|---|---|---|---|---|
| `activity_trigger_stagnation_step` | `ape.activityTriggerStagnationStep` | 50 | **10** | launcher dispara quando `graphStableCounter==este valor`; baixar → mais launches. `<=0` clampa p/50 (logado). |
| `activity_trigger_max_per_run` | `ape.activityTriggerMaxPerRun` | 0 (ilimitado) | **8** | teto de `EVENT_TRIGGER_ACTIVITY`/run. Só launches **retornados** gastam o cap (scan vazio não conta). `<0` clampa p/0 (logado). |

- Registrados em `APERV_PROPERTY_MAPPING` **fora** de `ARM_DEFINING_KEYS` (arm-neutral, como
  `max_idle_timeout_ms`): mesmo valor nos 2 braços → não definem braço. `ARM_DEFINING_KEYS` continua 19.
- Denylist de framework (`android.`/`androidx.`/`kotlin.`/`junit.`/`leakcanary.`) já ativa — nenhum
  launch deve cair nela.
- Log de dose: `[APE-RV] Triggering activity: <classe>`.

## 5. Amostra e execução

- **APKs:** os 219 do dataset `APKS_INSTRUMENTED_jca_dexlib2_experimento-20260706` (dexlib2 +
  `.apk.json` GATOR co-localizado). Mesmo conjunto do cmpft4.
- **Braços:** 2. **Reps:** 3. **Timeout:** 300 s. **Containers:** 8. **Spec set:** jca.
- **Total:** 2 × 219 × 3 = **1314 tasks**.
- **Recursos:** cpus 4, memory 10 g/container. **Headless.** **Unseeded** (roleta + GUI
  não-determinística tornam seed pareada inútil — variância combatida por reps + pareamento + Wilcoxon).
- **RV_TOOLS** (compose `docker/docker-compose.cmpft5.yml`, anchor compartilhado):
  ```
  aperv:sata_mop_widget@activity_trigger_enabled=true,trigger_mop_first=true,activity_trigger_stagnation_step=10,activity_trigger_max_per_run=8,aperv:sata_mop_activity@activity_trigger_enabled=true,trigger_mop_first=true,activity_trigger_stagnation_step=10,activity_trigger_max_per_run=8
  ```

## 6. Métricas

- **Primária:** `cov_mop` (`methods_mop_reachable_coverage`) — alvo do braço.
- **Secundárias:** `cov_method`, `cov_act`, nº de atividades/estados únicos, `mop_unique`
  (`coverage_metrics.total_errors`).
- **Telemetria por trace (SEMPRE — sem ela o resultado é infalsificável, erro do cmpft4):**
  `mopActivities`, `mopActsAugmented` (censo A′), contagem `[APE-RV] Triggering activity:` (dose real),
  contagem `Nav MOP tiebreak`, distribuição de `decision_source`, `maxBoost`. Os sinais ficam no
  **`.trace`** (stdout do APE-RV), não no stdout do platform.

## 7. Gate 0 v2 — smoke com gate de DOSE (obrigatório antes do run completo)

Mesmos apps alto-em-atividade do v1 (freeotpplus / speakthat / vscan; substrato MOP forte). 2 braços,
via platform (emulador gerenciado pelo platform — nunca na mão). Checar no `.trace`/logcat:

1. **DOSE:** mediana **≥3** linhas `[APE-RV] Triggering activity:` por run. Se <3 com step=10/cap=8,
   **baixar step (ex.: 7) ANTES** do run de 15 h. (Gate 0 v1 / cmpft4 tiveram ~0,17/trace = inaceitável.)
2. **DENYLIST:** **ZERO** `Triggering activity:` com classe `android.`/`androidx.`/`kotlin.`/`junit.`/
   `leakcanary.`.
3. **A′ DIFERENCIANDO:** `[APE-MOP-DATA] … mopActsAugmented=N` — `N>0` **só no tratamento**, `==0` no
   controle. Se `==0` nos dois → app MOP-pobre em nível-atividade (teto do produtor ~40/219) → trocar
   por app A′-rico.
4. (opcional) `[APE-RV] Nav MOP tiebreak: density=<d> paths=<n>` quando caminhos divergem em densidade.

### 7.1. Interpretação do Gate 0 v2 (pré-registrar)
| Observação | Conclusão |
|---|---|
| dose≥3 **E** augmented>0 só no tratamento **E** zero denylist | **PASSA** → pedir autorização → run completo (1314) |
| dose≥3 mas augmented==0 nos apps testados | teto do produtor, não bug → estratificar amostra p/ A′-ricos |
| dose<3 | ajustar step/cap e **repetir smoke** (NÃO rodar 15 h subdosado) |
| launch em classe da denylist | bug de eligibility (fora do escopo cmpft5) → reportar, não rodar |

## 8. Estratificação e análise (pré-registrar)

- **Estrato PRIMÁRIO — apps A′-delta:** `mopActsAugmented>0`. Único lugar onde tratamento *pode* diferir
  do controle. Sub-refinar pelos apps com contagem `Triggering activity:` divergente entre braços (onde
  o launch de fato agiu sobre atividades A′-novas).
- **Estrato de CONTROLE — placebo:** `mopActsAugmented=0` (activity ≡ widget por construção; não devem diferir).
- **Teste:** **Wilcoxon signed-rank pareado por app** (2 braços; medianas por app sobre reps). NÃO
  Friedman. Reportar **rank-biserial pareado** (tamanho de efeito), não só p. Primário = estrato A′-delta;
  219-completo = secundário.

## 9. Regras de decisão (run completo)

| Observação | Conclusão |
|---|---|
| `mopActsAugmented=0` no braço activity (Gate 0 ou run) com jar comprovadamente novo | teto do produtor / app; se a guard-string sumiu do jar → build-skew, abortar |
| dose (`Triggering activity:`) ~0 nos dois braços | launcher subdosado → não responde H1; re-dosar (step) |
| activity > widget no estrato A′ (Wilcoxon signif. + efeito relevante) | **H1 apoiada**: o launch A′ converte em cobertura |
| indistinguíveis COM dose≥3 e augmented>0 | **nulo informativo**: launch A′ não converte por este mecanismo |
| diferença no estrato-controle (m=0) | variância/confusão não controlada → mais reps / revisar |

## 10. Ameaças à validade

- **Variância sem seed** → 3 reps + pareamento intra-app + Wilcoxon.
- **Teto do produtor ~40 apps** (forense cmpft4): A′ muda ~40/219 apps; o contraste vive nesse
  subconjunto (estrato A′-delta). Não atribuir nulo ao mecanismo sem checar `mopActsAugmented` por app.
- **Build-skew** → §3 (md5 `3de5b9a4` + guards no dex + `[APE-MOP-DATA]` em runtime).
- **Subdosagem do launcher** → Gate 0 v2 gate de dose≥3 antes do run (§7).
- **Confusão de exploração compartilhada:** o efeito aperv>APE (árvore AndroidX + back-cap, cmpft4 widget
  p=0.027) NÃO é steering MOP; manter separado do contraste activity×widget.
- **Comparações múltiplas** → pré-registrar o estrato primário (A′-delta).

## 11. O que faz o cmpft5 v2 ser conclusivo

1. Gate 0 v2 prova o **consumidor forte dosado** (dose≥3) **antes** do run — v1 provou que o consumidor
   fraco (launcher OFF) era inerte; v2 mede o consumidor que de fato age.
2. Análise **restrita ao estrato A′** (cmpft4 diluiu nos 219).
3. **Mesmo build**, 2 braços launcher-ON idênticos diferindo só por A′ → contraste limpo, Wilcoxon pareado.
4. Telemetria por trace sempre (dose + augmented + tiebreak + decision_source).

## 12. Ponteiros

- Change `activity-trigger-dose`: worktree `ape-mop-fairtest`, commit `fa4bc59`, **ABERTA** no openspec
  até o Gate 0 v2 validar a dose (task device). `mvn test` 615/0-fail, strict-valid.
- Definição dos braços: `rvsec/rv-android/.../tools/aperv/tool.py` (variants ~369-398;
  `APERV_PROPERTY_MAPPING` ~119-131, com as 2 chaves de dose arm-neutral).
- Consumidores de A′ no jar: launcher/E-mín `SataAgent.java:639-670`; nav-tiebreak
  `SataAgent.java:1352-1374`; `MopScorer.java:50-54,108-121,142-172`.
- Relatório Gate 0 v1 (por que mudou o desenho): `rvsec/rv-android/docs/20260711_relatorio_gate0_cmpft5.md`.
- Plano operacional: `rvsec/rv-android/docs/20260711_cmpft5.md`.
- Memórias: `project_cmpft5_gate0_mechanism_null`, `mop-activity-consumers-implemented`,
  `cmpft4-forensic-null-rootcause`.

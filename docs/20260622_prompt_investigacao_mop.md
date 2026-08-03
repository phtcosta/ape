# Prompt — Investigação profunda da performance MOP do APE-RV (traces cmpmop + baseline junho)

> Cole este prompt numa nova sessão **aberta no repositório `ape`**
> (`/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape`).
> Os dados de execução estão no repo vizinho `rv-android` (paths absolutos abaixo).

---

## 1. Contexto — o que foi feito

Rodamos **duas comparações** APE-RV no mesmo dataset (169 APKs JCA dexlib2-instrumentados + `<apk>.json`
de análise estática co-localizado, 300 s/task, 3 reps):

- **Junho (`comparacao_consolidado/`)** — jar **legado** (lia a chave de schema antiga `reachesMop`):
  o MOP boost **nunca disparou** (`maxBoost>0` em **0/169**). A "vantagem do MOP" observada era um
  **confound** de component-triggering.
- **Hoje (`cmpmop_consolidado/`)** — jar **reconstruído do fonte** (gh71 builda o `ape` na imagem;
  gh15 = A-2..A-6). O boost agora dispara e o confound foi desacoplado (`componentPercentage=0.0`).

**Resultado (memo `rv-android/docs/20260622_cmpmop_analise.md`):** o fix está validado como engenharia,
**mas orientar por MOP NÃO melhora nada** — `aperv:sata_mop` ≈ `aperv:sata` em cobertura, violações
totais e violações únicas (Wilcoxon pareado n=169, todos p>0.2); **mesmo nos 12 APKs onde o boost
dispara**, `sata_mop` não supera `sata` (cov_mop até tende a menor, p=0.083). Em teoria, ser guiado por
MOP deveria achar **mais** operações monitoradas / violações — algo está muito errado.

**Objetivo desta investigação:** descobrir, **a partir dos logs semi-estruturados `.trace` confrontados
com o código-fonte**, por que o MOP-guidance é inerte, caçar anomalias/bugs, medir como está a
exploração de UI (nosso `UICoverageTracker`) por tela / componente / tipo, e **propor melhorias
concretas e rankeadas para aumentar a performance MOP**. **Ignore o LLM** — foco 100% MOP/SATA.

---

## 2. Achados preliminares (já medidos — confirme, refute e aprofunde)

Sobre **134.321** linhas `[APE-RV] MOP boost` de todos os traces `sata_mop` da corrida de hoje:

1. **O boost quase não aparece:** `maxBoost>0` em só **6.040 / 134.321 = 4,5%** das decisões (95,5%
   têm boost 0).
2. **Quando aparece, é majoritariamente uniforme e não-discriminativo:**
   - `maxBoost=100` (**activity-level**, `mopWeightActivity`, aplicado a TODOS os widgets quando a
     activity tem MOP): **62,6%** dos boosts.
   - transitive `+300`: 19,5% · wtg `+200`: 14,7% · **direct `+500`** (o que de fato discrimina o
     widget que alcança a operação monitorada): **só 3,2%** = ~**0,14% de todas as decisões**.
   - Em **85,3%** dos casos com boost, **todos** os widgets-alvo da tela foram boostados (`boosted=N/N`)
     → +constante igual a todos → **não muda o ranking** → não há steering.
3. **Realização runtime ≪ substrato estático:** só **12/168** APKs disparam `maxBoost>0` em alguma rep,
   contra ~98/169 previstos estaticamente (handler ∈ `reachesTargetSet` no JSON).
4. **Bug de telemetria (A-5):** `decision_source` no `[APE-STEP]` é **sempre `SATA`** — o código
   (`StatefulAgent.adjustActionsByGUITree`) soma o boost à `priority` mas **nunca** chama
   `setDecisionSource(MOP)`. Logo a atribuição de decisão é cega — não dá pra saber pelo log quando o
   boost foi decisivo.

**Hipótese-raiz a testar:** o boost MOP, mesmo quando dispara, **não altera qual ação é escolhida** —
ou porque é uniforme (+100 a todos), ou porque é afogado pela `priority` base do SATA, ou porque a tela
boostável raramente é alcançada. Confirme medindo **quantas decisões o boost foi de fato decisivo**
(mudou o argmax de priority).

---

## 3. Onde estão os dados (paths absolutos)

**Resultados por task** (fonte da verdade):
```
/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/data/results/cmpmop_NN/cmpmop_NN/<apk>/
    <apk>__<rep>__300__aperv:sata.trace        # log de exploração APE (SATA puro)
    <apk>__<rep>__300__aperv:sata.logcat       # RVSEC / RVSEC-COV markers (cobertura/violações)
    <apk>__<rep>__300__aperv:sata_mop.trace    # idem com MOP ligado  ← FOCO
    <apk>__<rep>__300__aperv:sata_mop.logcat
    <apk>.json                                 # MOP data estática (reachesTarget, listeners, components)
```
- `NN` = 00..07 (8 containers). APK por container nos filtros
  `rv-android/data/cmpmop_filters/batch_NN.txt`.
- **Baseline junho** (mesma estrutura): `rv-android/data/results/cmp_NN/...` e consolidado em
  `rv-android/data/results/comparacao_consolidado/`.
- **Consolidados** (CSVs por task/APK/tool + wilcoxon): `cmpmop_consolidado/` e `comparacao_consolidado/`.
- **Memo de análise:** `rv-android/docs/20260622_cmpmop_analise.md` · **Plano:**
  `rv-android/docs/20260621_cmpmop.md` (ver §10.2 = backlog de skill).

**Métricas (logcat):** `cov_mop = methods_mop_reachable_coverage`; `mop_unique = coverage_metrics.total_errors`
(violações distintas por Spec,classe,método,tipo); `mop_total` = linhas `RVSEC : <Spec>,...` no logcat.

---

## 4. Código-fonte (paths absolutos, com âncoras)

Base: `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/src/main/java/com/android/commands/monkey/ape/`

- **`agent/StatefulAgent.java`** (1741 linhas) — o coração:
  - `adjustActionsByGUITree()` **:1309** — calcula `priority` SATA (base `getActionBasePriority<<3`,
    +20 unvisited, +aliased, ±edges) e depois os passes de boost: **MOP :1367** (chama
    `mopBoostWithContainment`), **menu :1394**, **WTG :1408**, **Coverage :1434**.
  - `mopBoostWithContainment()` **:1469** — reconciliação pai/filho (B3, depth ≤2), chama `MopScorer.score`.
  - `resolveNewAction()` **:1256** + emissão do **`[APE-STEP]` :1266** (decision_source/priority/mop/wtg/coverage/menu).
  - `getActionBasePriority()` **:1290** (MODEL_CLICK=4, etc).
- **`utils/MopScorer.java`** — `score(activity, shortId, data, eventType)`: direct→**+500**,
  transitive→**+300**, senão `activityHasMop`→**+100 (a todo widget)**, senão 0. `scoreWtg` (+200),
  `scoreOpenMenu` (+250), `eventTypeOf`.
- **`utils/MopData.java`** — parser do `<apk>.json` (`reachesTarget`/`directlyReachesTarget`/`targetMethods`),
  `deriveWidgetMopFlags`, índice `bySignature`, `extractShortId`, `activityHasMop`.
- **`model/ModelAction.java`** — `enum DecisionSource` **:42**, campos `mopBoost/wtgBoost/coverageBoost/menuBoost`
  **:58+**, `setDecisionSource` **:198** (nunca chamado com MOP — ver achado 4).
- **`utils/UICoverageTracker.java`** — cobertura UI por-state widget-level: `stateData` (LRU,
  `Config.coverageMaxStates`), `activityRollup` (Activity→widgetId→count), `registerScreenElements` **:89**,
  `getCoverageGap`, `getInteractionCount`, `widgetId(action)`. **É o "nosso UI tracker".**
- **`utils/Config.java`** — pesos: `mopWeightDirect=500` **:128**, `mopWeightTransitive=300`,
  `mopWeightActivity=100`, `mopWeightOpenMenu=250`, `mopWeightWtg=200`, `coverageBoostWeight=100` **:160**,
  `componentPercentage=0.0` **:178** (A-3 desacoplado).
- Plugin que invoca o jar / monta properties: `rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py`.
- UI coverage do lado plataforma (Python, lê RVSEC-COV): `rv-android/modules/rv-coverage/`.

---

## 5. Formato semi-estruturado do `.trace`

Linhas `[APE] *** INFO *** ...`. Tipos-chave (por decisão/iteração):

| Linha | Conteúdo |
|---|---|
| `Create state g0s0[...]Activity@hash@Naming[k]@[W=3][A=5]` | novo state: **W**=widgets, **A**=ações |
| `[APE-STEP] step=N activity=A state=S action=g1a5[-1,0][0]@MODEL_CLICK class=...;[P=252][T=200][,UNVISITED][S=0.0][RN=1][bounds][text] decision_source=SATA priority=252 mop=100 wtg=0 coverage=100 menu=0` | **ação escolhida** + breakdown por mecanismo |
| `[APE-RV] MOP boost: state=A#S, boosted=N/M, maxBoost=Z, containment=C` | passe MOP: N de M alvos boostados, boost máx, quantos via containment |
| `[APE-RV] Coverage boost: state=..., boosted=N/M, gap=G` | passe cobertura (widgets não-interagidos) |
| `[APE-RV] WTG boost` / `menu boost` | quando disparam |
| `MopData: loaded W widgets, R reachability classes, T transitions, C components, M MOP option-menus` | resumo do load do `<apk>.json` |
| `GSTG(gK): ...` / `g2a26[-1,0][0]@MODEL_CLICKclass=...;resource-id=...` | dump do grafo de estados/ações (modelo) |
| `Sata Strategy:` / `New/Last/Curr state/action` | internals da estratégia SATA |
| `actionRefinement` / `Fuzzing` / `batchAbstract` / `Checking new state` | mecânica de exploração |

Notação de ação: `g<grafo>a<id>[visitas,?][saturado]@<TIPO>class=...;[P=priority][T=?][VISITED|UNVISITED][S=score][RN=?][l,t,r,b bounds][texto]`.

---

## 6. Investigações a executar (proponha mais, mas cubra estas)

### A. O boost é decisivo? (testar a hipótese-raiz)
1. Para cada `[APE-STEP]` de ação MODEL: a ação escolhida tem `priority` máximo da tela? Recompute o
   ranking **sem** o `mop` boost (`priority - mop`) e veja se o argmax muda. **Métrica: % de decisões em
   que o MOP boost flipou a escolha.** (Precisa cruzar o `[APE-STEP]` com as ações candidatas da tela —
   use o dump `GSTG`/lista de ações, ou instrumente um passe extra se faltar dado.)
2. % de telas com `boosted=N/N` (uniforme → no-op) vs `k/N` (discriminativo). Já medido 85% uniforme —
   confirme e quebre por APK e por nível (100/300/500).
3. Magnitudes: distribuição de `priority` SATA base vs boost. O +100 (e até +300) é afogado por
   `priority` grande (unvisited +20 já é grande relativo? aliased? edges)? Tabule `mop` vs `priority`.

### B. Por que o discriminativo (+500/+300) quase não dispara
4. Taxa de match handler↔target por APK: quantos widgets resolvem para `directlyReachesTarget`/`reachesTarget`
   no `MopData` (via `deriveWidgetMopFlags`/`bySignature`) vs caem no fallback activity +100.
5. Containment (B3): quantas vezes `containment>0` salvou (ancestor/descendant) — vale a profundidade ≤2?
6. Compose/R8: quantos APKs têm handlers `$$ExternalSyntheticLambda`/ofuscados (handler-join falha) — cruze
   com o caso `app.passwordstore.agrahn_11602` (boost=0 apesar de `directlyReachesTarget=true` no JSON).
7. `extractShortId` vs `resource-id` real em runtime: o id estático casa o runtime? (granularidade pai/filho).

### C. UI coverage — nosso `UICoverageTracker` (o usuário quer isto a fundo)
8. **Não há dump explícito de cobertura UI no trace hoje** — proponha **instrumentar um dump final** do
   `stateData` + `activityRollup` (por state: widgets descobertos vs interagidos; por Activity: rollup).
   Avalie emitir uma linha `[APE-RV] UI coverage` por state ao sair.
9. Com os dados disponíveis (GSTG, `[APE-STEP]`, `registerScreenElements`): por APK, **por tela**: nº de
   widgets descobertos vs efetivamente interagidos (cobertura intra-tela); **por tipo de componente**
   (Button/EditText/CheckedTextView/Spinner/...): distribuição de cliques — estamos **subverificando**
   tipos relevantes a MOP (ex.: EditText/Button que disparam cripto) e **superverificando** outros
   (listas, navegação)?
10. **Telas alcançáveis-MOP vs visitadas:** das activities que o `<apk>.json` marca com MOP, quantas o
    explorador **realmente alcançou** em 300 s? (gap runtime). Quantos cliques da inicial até a tela MOP
    (profundidade)?

### D. Exploração / saturação / desperdício
11. Ações produtivas (CLICK/LONG_CLICK/SCROLL/SET_TEXT) vs desperdiçadas (RESTART/SKIP/BACK) por braço.
12. Plateau / loops: telas repetidas, saturação (`[S=...]`, `Graph Stable Counter`), `Fuzzing` excessivo.
13. `sata` vs `sata_mop` lado a lado no MESMO APK/rep: as sequências de telas/ações divergem? Onde e quanto?
    (se idênticas, o boost não mudou nada — corrobora A).

### E. Anomalias / bugs (caçar e catalogar)
14. **Bug A-5:** `decision_source` nunca = MOP. Propor fix: setar `DecisionSource.MOP/COVERAGE/WTG` quando
    o boost daquele mecanismo for o que tornou a ação o argmax (atribuição correta).
15. `maxBoost=0` com `boosted=8/8`? (no `passwordstore`: investigar — boost computado mas 0?).
16. Interação **MOP boost (+100) × Coverage boost (+100)**: ambos uniformes e mesma magnitude — competem/
    cancelam? O coverage boost (que decai com visitas) pode estar dominando o MOP.
17. `componentPercentage=0.0` (A-3) — confirmar `[APE-RV] Triggering` = 0 em todos (já visto em amostra).

### F. Confronto trace × fonte
18. Reconstrua a seleção a partir de `adjustActionsByGUITree` + `selectNewActionNonnull` e valide contra
    o `[APE-STEP]` (priority bate? boost somado corretamente?). Verifique se há estocasticidade na seleção
    que dilui o boost.

---

## 7. Propostas de melhoria esperadas (rankeie por impacto×esforço, fundamentadas nos dados)

Candidatos a investigar/validar (não implemente sem evidência do trace):
- **Boost discriminativo, não uniforme:** nunca aplicar `mopWeightActivity` igual a TODO widget; restringir
  ao widget-level (direct/transitive) ou subtrair a média da tela (boost relativo) para preservar ranking.
- **Recalibrar magnitudes** vs `priority` SATA base (o +100/+300 precisa dominar o unvisited/aliased para
  steerar; medir o teto da priority SATA).
- **Atacar a baixa taxa de match handler↔target** (a causa de 95,5% boost=0): reconsiderar G-1/A-2
  (handlerReachesTarget, granularidade) **à luz** de "quanto match a mais isso renderia" medido aqui.
- **Orçamento de exploração** (300 s pode não alcançar a tela MOP) — quantificar profundidade e propor teste a 600 s.
- **Fix do `decision_source`** (telemetria correta para futuras análises).
- **Dump de UI coverage** por tela/componente/tipo (observabilidade para fechar o loop).

---

## 8. Comandos úteis

```bash
# raiz dos resultados
RES=/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/data/results

# distribuição de maxBoost por nível (todos os traces sata_mop)
python3 - <<'PY'
import glob,re; from collections import Counter
c=Counter(); uni=Counter(); tot=0; nz=0
for tr in glob.glob(f"{__import__('os').environ.get('RES','RES')}/cmpmop_*/cmpmop_*/*/*aperv:sata_mop.trace"):
    for m in re.finditer(r"boosted=(\d+)/(\d+), maxBoost=(\d+)", open(tr,errors='ignore').read()):
        b,t,mx=map(int,m.groups()); tot+=1
        if mx>0: nz+=1; c[mx]+=1; uni['N/N' if b==t else 'k/N']+=1
print("decisoes:",tot,"| boost>0:",nz,f"({100*nz/tot:.1f}%)"); print("niveis:",dict(c)); print("uniformidade:",dict(uni))
PY

# [APE-STEP] de um APK: ver decision_source / mop / priority
grep -a "APE-STEP" "$RES"/cmpmop_*/cmpmop_*/duress.keyboard_51.apk/*sata_mop.trace | head

# tipos de linha de um trace
grep -aoE "\[APE[^]]*\][^:]*:" <trace> | sort | uniq -c | sort -rn | head -30

# sata vs sata_mop lado a lado (mesmo APK/rep): divergência de ações escolhidas
diff <(grep -a "APE-STEP" <...sata.trace>) <(grep -a "APE-STEP" <...sata_mop.trace>) | head

# rodar testes do ape (no repo ape)
mvn -q test -Dtest=MopScorerTest,MopDataTest
```

---

## 9. Entregável

Um memo (`ape/docs/<data>_investigacao_mop.md` ou `rv-android/docs/`) com:
1. **Causa-raiz** do MOP-guidance inerte, com números do trace (confirmar/refutar a hipótese §2).
2. **Catálogo de anomalias/bugs** (severidade, evidência trace+fonte, fix proposto).
3. **Estatísticas de UI coverage** por tela / componente / tipo (sub/super-verificação).
4. **Propostas de melhoria rankeadas** (impacto × esforço), cada uma ancorada em dado, com o experimento
   mínimo para validar (idealmente uma `aperv:sata_mop` re-rodada num subconjunto após o ajuste).
5. Foco **MOP/SATA** — ignore o LLM.
```
```

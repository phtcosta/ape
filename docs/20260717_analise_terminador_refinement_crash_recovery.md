# Análise — a identidade do terminador in-loop e a validade do F-C

**Data:** 2026-07-17 (madrugada)
**Insumo:** `docs/20260717_prompt_analise_terminador_refinement_crash_recovery.md` (protocolo desta análise)
**Método:** releitura integral do laudo (`docs/20260716_investigacao_truncamento_600s_llm_tap.md`) + verificação
direta de código (esta sessão, linha a linha) + 2 subagentes (forense dos 9 traces CFW do cmpv2 run 3;
auditoria adversarial da quarentena `MODEL_LLM_TAP`)
**Status:** análise concluída; correções de artefato aplicadas; gate F-D EXECUTADO em 2026-07-17 —
**previsão CONFIRMADA frame a frame** (ver §11); F-C removido; fix real implementado (INV-MODEL-16)

---

## 0. Veredito em uma frase

O F-C guarda o site errado — mas por uma razão que ninguém tinha visto: **o terminador in-loop é, com
alta confiança, um furo na quarentena do `MODEL_LLM_TAP`** — o replay de transições do
`Model.rebuild()` re-vincula a aresta efêmera do tap via `State.getAction(MODEL_LLM_TAP)`, que lança
`IllegalStateException("No such action [MODEL_LLM_TAP]")` **sem dump**, dentro do rebuild, só no braço
LLM. O defeito não é (só) de 2019: **a cadeia que mata exige a aresta criada pela change `0e7b16f`**.

## 1. Resposta à pergunta central (§2.1 do prompt): o argumento do laudo se sustenta?

**SIM — confirmado e reforçado empiricamente.** A checagem fina pedida (o CFW do teardown também passa
por `pickNodes` e deveria imprimir dump) foi feita sobre os 9 traces CFW do run 3
(`rvsec/rv-android/data/results/cmpv2_00…08`; geteduroam r1, maskan r1/r2/r3, plugbrain r2/r3,
fosdem r1/r2/r3):

- **O dump do teardown EXISTE em 9/9** (2–9 linhas `%5d <Name>` entre `Save graph data` e o stack CFW).
  O mecanismo de dump é observável ponta a ponta nesses traces — a ausência in-loop é significativa,
  não um artefato de logging.
- **Zero dumps in-loop em 9/9** (contagem de blocos `printGUITree` no trace inteiro = exatamente o
  bloco do teardown).
- A redação do laudo ("não há dump na janela do stop") é ambígua quanto à janela, mas a inferência
  estava correta: `pickNodes`/`getNodes` in-loop estão excluídos.

Caveat teórico registrado (não muda o veredito): `printGUITree()` com `currentNames.length == 0`
imprime zero linhas — um "dump vazio" seria invisível. Irrelevante aqui: o terminador identificado
(§3) age antes de qualquer `pickNodes`.

## 2. O achado empírico decisivo — o loop morre DENTRO do `Model.rebuild()`

Fronteira de marcadores idêntica em **9/9** traces fatais (e balanceada nos controles saudáveis):

| marcador | presente na janela fatal? |
|---|:--:|
| `Find a new naming` | ✓ |
| `Start rebuilding model...` | ✓ |
| `> Removing state …` / `Removing (N) old states` | ✓ |
| `> rebuilding tree #…` + `Create state …` (re-adição de árvores) | ✓ |
| `Readding transitions finished` | **✗** |
| `Rebuilding model finished in` | **✗** |
| `Eliminating non-deterministic transitions takes` | **✗** |
| `Model has been refined, reset stateful` | **✗** |
| `Update action buffer` | **✗** (buffer vazio no refinamento fatal) |
| `>>>>>>>> … end step [N]` | ✓ (finally de `updateStateWrapper`) |

Em cada trace, `Start rebuilding model` = `Rebuilding model finished` + 1 — o rebuild fatal é o único
órfão; controles saudáveis são perfeitamente balanceados. **O killer dispara na fase de re-adição de
transições do rebuild** (`Model.java:266-286`, entre o último `Create state` e o log de `:291`) — isto
promove o item §6-"inferido" do laudo ("a exceção in-loop opera sobre referências do modelo
pós-rebuild") a **provado com localização**: nem sequer é pós-rebuild — é *intra*-rebuild.

Consequência imediata para o design: `validateAllNewActions`/`validateNewAction` (o site do F-C, que
roda **depois** de `updateModel`, em `StatefulAgent.java:768-769`) **nunca é alcançado no passo
fatal**. O guard F-C está rio abaixo do ponto de morte.

## 3. A identidade do terminador — furo na quarentena do `MODEL_LLM_TAP`

A re-verificação adversarial da quarentena (tarefa §7.5 do prompt) encontrou o que a lista de
candidatos do laudo não continha. Cadeia completa, verificada linha a linha nesta sessão:

1. O tap despachado vira `currentAction` e `updateGraph` grava a aresta como transição real —
   `StatefulAgent.java:984` → `Graph.addTransition` (`edge.append(treeTransition)` com o
   `GUITreeAction` de tipo `MODEL_LLM_TAP`). A quarentena isenta o tap de ND
   (`Model.java:356-362`) e de `markVisited` (`Graph.java:580-587`) — mas **não do replay do rebuild**.
2. Um refinamento disparado por **outra** aresta (não-efêmera) remove um estado tocado pela aresta do
   tap. `Graph.remove` (`Graph.java:1225` e `:1240`) coleta **todas** as in/out edges do estado
   removido, sem filtro `isEphemeral`.
3. `Model.rebuild` (`Model.java:227-228`) enfileira as `GUITreeTransition`s dessas arestas para replay.
4. O loop de replay (`Model.java:283`) chama `rebuild(sourceTree, source, tt.getAction())`
   (`Model.java:326-344`); `MODEL_LLM_TAP.requireTarget() == false` (`ActionType.java:56-59` — slot
   fora do range CLICK..SCROLL, por design INV-MODEL-12) → ramo else → `state.getAction(MODEL_LLM_TAP)`
   (`Model.java:340`).
5. `State.getAction(ActionType)` (`State.java:483-489`) varre `actions` — que **nunca** contém o tap
   (INV-MODEL-13/14: efêmeras são sintetizadas por decisão, jamais membros de `getActions()`) →
   `throw new IllegalStateException("No such action [MODEL_LLM_TAP]")`. **Sem dump** (esta variante
   não chama `dumpState`, ao contrário da variante com `Name` de `:469-481`).

A exceção sobe rebuild → `resolveNonDeterminism` → `resolveNonDeterministicTransitions` →
`checkNonDeterministicTransitions` → `updateStateInternal` → `updateStateWrapper`
(`catch(Exception){throw e}` + finally imprime `end step`) → escapa o loop → `finally{tearDown()}` de
`Monkey.run` → CFW do teardown a substitui (semântica Java). O trace resultante é **exatamente** o
observado 9/9.

### Por que este candidato — e nenhum outro — explica todos os fatos

| fato provado | tap-replay (furo #1) | candidatos 2019 na mesma região (R1–R7 da tabela §4) |
|---|:--:|:--:|
| morte na fase de re-adição de transições (9/9) | ✓ (`Model.java:283→340`) | ✓ (mesma região) |
| **0/2715 CFW sem LLM; exclusivo do braço LLM** | ✓ (a aresta do tap só existe com LLM) | ✗ (são arm-agnósticos; 134–151 refinamentos em sobreviventes sem morte) |
| sem dump / sem `Dumpping state` (0 ocorrências, 9/9) | ✓ (`getAction(ActionType)` não imprime nada) | parcial (R4 imprimiria `Dumpping state` — excluído empiricamente) |
| volume de refinamentos não prevê morte | ✓ (precisa de aresta-tap na vizinhança removida, não de N refinamentos) | ✗ |
| maskan determinístico e precoce | ✓ (llm_tap=7/4/2 com `matched=0` — todo call vira tap; ND no passo 5–10) | — |
| hazard 25× no jar pós-change, config LLM idêntica | ✓ (pré-change não existiam arestas-tap) | ✗ |
| 1/75 CFW pré-change | via caminho raro residual da mesma região (R1–R7, dose baixa 2019) | ✓ |

Grau de confiança: **alta, mas ainda inferência** — a identidade exata (mensagem "No such action
[MODEL_LLM_TAP]") nunca foi impressa em trace algum (mascarada). O gate F-D com o jar do Group 1 (F-A
implementado) é quem a torna ground truth. A previsão registrada está na §6.

**Furo #2 (latente, mesma mensagem, dominado pelo #1):** `updateModel` re-vincula `lastAction` sem
filtro de efêmeras (`StatefulAgent.java:252` → `Model.update(a,ga)` ramo else `Model.java:411` →
`state.getAction(MODEL_LLM_TAP)`). Se o estado do tap foi removido, o replay do rebuild crasha antes;
o furo #2 só fala se a aresta do tap escapar da coleta. O loop do actionHistory (`StatefulAgent:277`)
está protegido pelo filtro `requireTarget()`.

Correção conceitual ao laudo: "cadeia do crash 100% 2019 (blame)" e "quarentena do tap no código"
(listada como **provada**) precisam de emenda — o *mecanismo de replay* é 2019, mas a *pré-condição
letal* (aresta efêmera no grafo) é da change `0e7b16f`. A quarentena cobre gatilho-de-ND, markVisited
e replay-de-histórico; **não cobre o replay do rebuild nem o rebind de `lastAction`**.

## 4. Tabela fechada de candidatos (tarefa §7.3)

Throws in-loop alcançáveis na janela do refinamento (braço sata do cmpv2), filtro §3.4 do prompt:

| # | site | exceção | dump antes? | veredito |
|---|---|---|---|---|
| **T1** | **replay do rebuild → `State.getAction(type)` (`Model.java:340` → `State.java:489`)** | ISE `No such action [MODEL_LLM_TAP]` | **não** | **TERMINADOR (previsto)** — único candidato exclusivo-do-braço-LLM na região empiricamente correta |
| T2 | `updateModel` → `Model.update` ramo else (`Model.java:411`) | idem | não | latente, dominado por T1 |
| R1 | `Model.rebuild:271/275` | NPE `Source/Target state should not be null` | não | região certa, arm-agnóstico → não explica 0/2715; candidato residual do 1/75 pré-change |
| R2 | `Model.rebuild:278/281` | ISE `State … has been removed.` | não | idem |
| R3 | `Model.rebuild→rebuild(t,s,ta):328` | ISE vazia | não | idem |
| R4 | idem `:334-338` → `State.getAction(Name)` | ISE `No such widget` | **sim** (`Dumpping state`) | **excluído empiricamente** (0 ocorrências 9/9) |
| R5 | replay → `Graph.addTransition:437` | ISE `Untracked GUI tree transition` | não | região certa, arm-agnóstico |
| R6 | replay → `markVisited:591/601/603` | RE sanity | branch `:603` imprime `Untracked action` (0 ocorrências) | fraco |
| R7 | `Graph.rebuildHistory:1335` | RE `Sanity check failed!` | não | região certa, arm-agnóstico |
| N1 | caminho de naming do refine (ex. `A node has no namelets`, `Naming.java:517`) | RE | não | região errada (antes de `Start rebuilding`) — marcadores excluem |
| U1–U6 | internos de `updateModel` (sanity `:403`/`:279`, buffer `getStateTransition:1196/1199`, `append:365`) | ISE/RE/NPE | não | **excluídos empiricamente**: `Model has been refined` ausente 9/9 → updateModel nunca começa; buffer vazio (`Update action buffer` ausente) |
| V1 | `getThrottleForNewAction:1530` ("Oops", candidato A do prompt) | ISE `Oops` | não | **excluído** — ver §5 |
| V2 | `resolveAction:390` ("Empty GUI tree history", candidato B) | ISE | não | **excluído**: estruturalmente quase impossível (todo caminho de rebind passa por `getState`→`append`, garantindo `treeHistory≥1`) e rio abaixo do ponto de morte |
| V3 | `resolveAction:397→pickNodes` (site esperado pelo design) | ISE CFW | **sim** | **duplamente excluído**: sem dump in-loop (evidência) E estruturalmente quase impossível — o target de uma ação de `newState` pertence ao `StateKey`, derivado dos mesmos `Name`s das árvores do estado; pós-rebuild ambos são re-derivados juntos. CFW exige `Name` **congelado** contra árvore re-nomeada — que só existe em `ActionRecord` (= o teardown) |
| S1/S2/M1 | assertNotNull `:1372/:1376`, buffer `getAction:499`, markVisited in-loop `:739` | NPE/ISE/RE | não/sim/parcial | rio abaixo do ponto de morte; excluídos pela fronteira de marcadores |

## 5. Decisão do candidato A (tarefa §7.4) — "Oops" está morto para o cmpv2

Respostas às quatro checagens pedidas na §3.1 do prompt:

- **(a) o buffer sobrevive ao rebuild?** Sim, re-vinculado em `updateModel` (`StatefulAgent.java:260-269`,
  marcador `Update action buffer...`); limpo por `clearBuffer` em `resetTrace`/refill/inconsistência.
  Empiricamente: vazio no refinamento fatal (marcador ausente 9/9).
- **(b) o SataAgent passa por `selectNewActionFromBuffer`?** Sim (`SataAgent.java:491`) — **mas devolve a
  ação diretamente, sem `validateNewAction`**. O embrulho `validateNewAction(selectNewActionFromBuffer())`
  existe só no `RandomAgent:64`, que não é o braço do cmpv2. O caminho-buffer para o "Oops" não existe
  no sata.
- **(c) `validateAllNewActions` itera `newState.getActions()`** — `state == newState` por construção;
  o filtro `validatedActionFilter` (`handleNullAction:1570`) também só recebe ações de `newState`.
  Todos os funis de `validateNewAction` no sata entregam ações do próprio `newState`.
- **(d) o rebuild cria objetos novos?** Sim, para estados removidos (`getOrCreateState` por `StateKey`;
  chave nova pós-refinamento ⇒ objeto novo) — mas `updateModel` re-vincula todas as referências do
  agente, e o check do buffer (`check != action`, `StatefulAgent:503`, por referência) devolve `null` em
  vez de lançar.

Estruturalmente inalcançável no braço sata + 0 ocorrências de "Oops" nos traces ⇒ **candidato A
refutado**. (A precaução do D4 — throttle fora do try — continua correta como higiene: o guard largo
teria cegado o gate; mas o "Oops" não é o terminador.)

O candidato B ("Empty GUI tree history") também cai: ver V2 na tabela. A observação do prompt de que a
qualificação "expected"/"the rarer" do design está invertida fica **superada**: nenhum dos dois é o
terminador; a qualificação certa é "ambos não-terminadores, CFW possível apenas no teardown".

## 6. Previsão registrada do gate F-D (tarefa §7.6 — ANTES de rodar)

**Desfecho previsto: leitura 3 da §5.1 do prompt — `RebindFailures total=0` (N=0).**

Stack in-loop previsto no maskan com o jar do Group 1 (F-A):

```
IllegalStateException: No such action [MODEL_LLM_TAP]
    at com.android.commands.monkey.ape.model.State.getAction(State.java:489)
    at com.android.commands.monkey.ape.model.Model.rebuild(Model.java:340)   ← rebuild(GUITree,State,GUITreeAction)
    at com.android.commands.monkey.ape.model.Model.rebuild(Model.java:283)   ← loop de replay
    at com.android.commands.monkey.ape.naming.NamingFactory.rebuild(NamingFactory.java:251)
    at com.android.commands.monkey.ape.naming.NamingFactory.resolveNonDeterminism(NamingFactory.java:157)
    at com.android.commands.monkey.ape.model.Model.resolveNonDeterministicTransitions(Model.java:365)
    at com.android.commands.monkey.ape.agent.StatefulAgent.checkNonDeterministicTransitions(StatefulAgent.java:765)
    at com.android.commands.monkey.ape.agent.StatefulAgent.updateStateInternal(StatefulAgent.java:722)
    …
```

Justificativa: única hipótese que explica simultaneamente a fronteira de marcadores (morte na
re-adição de transições, 9/9), a exclusividade do braço LLM (0/2715), a ausência de todos os
marcadores discriminantes, a não-correlação com volume de refinamentos e o determinismo precoce do
maskan (`matched=0` ⇒ todo call LLM vira tap).

Leituras alternativas, se o stack cair em outro lugar:
- **stack em R1/R2/R5/R7 (2019 puro, sem menção a MODEL_LLM_TAP):** a exclusividade LLM teria de ser
  explicada só pela mediação-por-navegação do laudo — possível, mas então o pré-change 1/75 vs 32%
  pós fica sem mecanismo; re-examinar antes de qualquer fix.
- **N>0 com stack em `validateNewAction`:** exigiria dump in-loop no trace novo (ou `currentNames`
  vazio) — só nesse cenário o F-C se confirma como estava desenhado.

Em qualquer leitura, o gate deve capturar: o stack completo, `RebindFailures total=`, e a presença/
ausência de `Rebuilding model finished` no passo fatal.

## 7. Consequências para a change (assimetria da §5 do prompt, atualizada)

| peça | veredito | fundamento |
|---|---|---|
| F-A (desmascarar) | **mantém-se por si** | mascaramento é semântica Java provada; sem ele o gate não existe |
| F-B (history tolerante) | **mantém-se por si** | o CFW do teardown é o stack observado em 9/9 (com dump); F-B converte crash-de-teardown em skip+telemetria. Continua **não** corrigindo o truncamento |
| fix do `Naming` finally | mantém-se por si | NPE de código, independente do terminador |
| **F-C (rebind tolerante)** | **guard especulativo (previsto N=0) → remover se o gate confirmar** | o site guardado é estruturalmente quase inalcançável (V3 da tabela) e fica rio abaixo do ponto de morte real; P1 (no speculative guards) |
| **fix novo (fora do catálogo F-A..F-D):** excluir arestas efêmeras do replay do rebuild (e do rebind de `lastAction`) | **é ele que elimina o truncamento** — o "F-D do laudo" ganhou endereço | §3 desta análise; NÃO implementar antes do gate confirmar o stack |

Nota sobre o D3 do design: o argumento "sticky-valid → despacho em coordenadas obsoletas → corrupção
silenciosa" era a defesa alternativa do F-C (leitura 2 da §5.1). Ele pressupõe que a falha de rebind
*ocorre*. Com o terminador rio acima e a falha de rebind estruturalmente quase impossível no site
guardado, a defesa perde o objeto. Se o gate der N=0, remover — não estender.

## 8. Como o design errou (tarefa §7.2 — hipótese da §2.3 do prompt: CONFIRMADA)

O laudo `:47-48` exclui `pickNodes` in-loop; o laudo `:62` diz "Blame: toda a cadeia
refinamento→`validateAllNewActions`→`State.resolveAction:397`→`pickNodes` é 2019" — uma afirmação de
**idade de código** que nomeia exatamente a cadeia que o design adotou como "expected" nas Open
Questions. As duas frases adjacentes se leem como opostas; o design herdou a segunda sem notar a
primeira. Vale a correção de redação no laudo (proposta abaixo), porque o laudo é insumo de outras
sessões — e o erro se propagou também para `proposal.md` ("This targets the most likely in-loop
killer") e para o javadoc do teste (§6.3 do prompt, confirmado: chama a assinatura do teardown de
"the production exception" do caminho in-loop).

A divergência de nomenclatura F-C/F-D (§4 do prompt) fica assim resolvida: o "F-D do laudo" (fix do
terminador real) agora tem endereço concreto (replay de efêmeras no rebuild) e **não coincide** com o
F-C do design — a aposta do design era falsa. A nota de tradução proposta (§9.4) registra as três
nomenclaturas (laudo F-C/F-D, design F-C/F-D, "F4" do D3).

## 9. Correções de artefato propostas (NÃO aplicadas — aguardam aprovação)

1. **`design.md` → Open Questions:** substituir a expectativa
   `validateNewAction → resolveAction:388 → pickNodes:397` pela previsão da §6 desta análise (stack
   `No such action [MODEL_LLM_TAP]` no replay do rebuild), com as leituras alternativas. Registrar o
   fato que exclui o site antigo (dump 9/9 só no teardown + fronteira de marcadores).
2. **`design.md` → D4:** reconstruir a justificativa de posicionamento do guard: o argumento "line
   1332 é o único call site" continua verdadeiro, mas a premissa "é lá que o terminador mora" caiu.
   D4 passa a citar a previsão N=0 e a condição de remoção do F-C.
3. **`design.md` → D7 / API Design:** atualizar a sequência do gate (o stack esperado agora é o da §6)
   e remover a inversão "expected"/"the rarer" — nenhum dos dois é terminador (V2/V3 da tabela §4).
4. **`design.md` → nota de tradução de nomenclatura** F-C/F-D/F4 entre laudo, design e D3, conforme §8.
5. **`tasks.md` → 2.3:** adicionar o terceiro desfecho: **se `RebindFailures total=0` e o stack não
   passar por `validateNewAction` → remover o F-C (guard especulativo, P1)**, não estender. Adicionar a
   2.2 a captura da fronteira de marcadores (`Rebuilding model finished` presente/ausente).
6. **`tasks.md` → novo item pós-gate (Group 4-bis, condicional):** se o stack confirmar T1, criar o fix
   real — excluir `GUITreeTransition`s de ações efêmeras do replay em `Model.rebuild` (e o rebind de
   `lastAction` efêmera em `updateModel`) — com teste `rebuild + tap edge` (hoje inexistente:
   `GraphEphemeralActionTest` cobre só markVisited/addTransition).
7. **`ValidateNewActionToleranceTest`:** corrigir o javadoc — a exceção injetada não é "the production
   exception" do caminho in-loop (é a assinatura do teardown); o guard é agnóstico à mensagem. Se o
   F-C for removido (desfecho N=0), o teste sai junto.
8. **`docs/20260716_investigacao_truncamento_600s_llm_tap.md` (laudo):** (a) reescrever `:62` para
   desfazer a ambiguidade blame-da-cadeia × identidade-do-terminador; (b) emendar "quarentena do tap
   no código" em §6-Provado — a quarentena tem furo no replay do rebuild e no rebind de `lastAction`
   (esta análise, §3); (c) atualizar §3 "maskan reclassificado": mesmo mecanismo, gatilho antecipado
   por `matched=0`; (d) promover a §2.3-item-3 de inferência a provado-com-localização (morte
   intra-rebuild, fronteira de marcadores 9/9).
9. **`specs/` da change:** INV-EXPL-30 (rebind tolerante) fica condicionado ao desfecho do gate; se
   N=0, o delta correspondente sai da change no archive.

## 10. Índice de evidências novas desta análise

| afirmação | evidência |
|---|---|
| morte intra-rebuild, fase de re-adição de transições | fronteira de marcadores 9/9 (`Start`=`finished`+1 só nos fatais; controles balanceados) — traces `rvsec/rv-android/data/results/cmpv2_00…08` |
| dump do teardown presente | 9/9, 2–9 linhas entre `Save graph data` e o stack (ex.: fosdem r1 L6441-6443) |
| zero dumps in-loop | contagem printGUITree = bloco do teardown, 9/9 |
| zero: `Oops`/`Dumpping state`/`No such widget|action`/`Sanity check failed`/`Untracked action`/NPE/`Empty GUI tree history`/`BadStateException` | grep 9/9 traces fatais |
| aresta do tap é persistida sem exclusão | `StatefulAgent.java:984` → `Graph.addTransition` |
| `Graph.remove` coleta efêmeras | `Graph.java:1225, 1240` (sem filtro `isEphemeral`) |
| replay chama `getAction(type)` p/ targetless | `Model.java:283` → `:326-344` → `:340` |
| `getAction(ActionType)` lança sem dump | `State.java:483-489` |
| tap nunca em `getActions()` | INV-MODEL-13/14; `Graph.markVisited:580-587` (isenção existente) |
| `MODEL_LLM_TAP.requireTarget()==false` | `ActionType.java:41-59` |
| updateModel re-vincula `lastAction` sem filtro efêmera | `StatefulAgent.java:252` → `Model.java:411` |
| actionHistory do updateModel protegido | `StatefulAgent.java:277` (filtro `requireTarget()`) |
| sata não valida ação do buffer | `SataAgent.java:491-494` (sem `validateNewAction`; contraste `RandomAgent.java:64`) |
| buffer protege por referência | `StatefulAgent.java:503` (`check != action` → null) |
| CFW in-loop estruturalmente quase impossível | consistência Name↔StateKey↔árvores do mesmo estado (`GUITreeBuilder.getStateKey`; `Model.java:482-489`) |
| maskan `matched=0`, llm_tap=7/4/2 | LLM Summary dos traces maskan r1/r2/r3 |

## 11. Resultado do gate F-D (2026-07-17, pós-análise) — previsão CONFIRMADA

Protocolo executado standalone no @RVSec (emulador do host), reproduzindo o braço
`sata_mop_llm_v13` do cmpv2: SGLang `phtcosta/aperv-qwen3vl-4b-v2-merged` (docker-compose
standalone, porta 30000), APK instrumentado do dataset do cmpv2, `ape.properties` com
`llmPercentage=0.7`, `llmTemperature=0`, `llmPromptVariant=v13`, `mopDataPath` populado.

- **Condição necessária descoberta:** o crash exige o estado *fresh-install* do cmpv2
  (`pm clear` antes do run) — é ele que põe o maskan na tela esparsa W=2 onde todo call LLM vira
  tap (`llm_tap=9, matched=1`). Com perfil "quente", as árvores ficam ricas (`matched=77/96`),
  6 rebuilds sobrevivem e não há crash em 285 passos. Com `pm clear`: crash na primeira
  tentativa, passo 12.
- **Stack in-loop desmascarado (F-A funcionou):** exatamente a §6 —
  `IllegalStateException: No such action [MODEL_LLM_TAP]` em `State.getAction(State.java:489)` ←
  `Model.rebuild(340/283)` ← `NamingFactory.rebuild(251)` ← `resolveNonDeterminism(157)` ←
  [`AbstractNamingManager.resolveNonDeterminism(57)`, frame intermediário elidido na previsão] ←
  `Model.resolveNonDeterministicTransitions(365)` ← `checkNonDeterministicTransitions(765)`.
- **Telemetria:** `[APE-RV] RebindFailures total=0` (N=0, leitura 3 confirmada) e
  `[APE-RV] ActionHistory total=11 skipped=2` (F-B pulou 2 registros stale que antes matavam o
  teardown). Fronteira de marcadores idêntica aos 9/9: último `Create state` → morte, sem
  `Readding transitions finished`.
- **Estado removido:** 4 transições, 2 delas `(g0s0,,g0s0)` — as arestas efêmeras do tap
  (graphId vazio), como nos traces do cmpv2.
- **Consequências aplicadas:** F-C removido (guard, contador, linha de teardown, teste,
  INV-EXPL-30 e requirement do delta exploration); fix real implementado com TDD
  (`ModelRebuildEphemeralQuarantineTest`): `Model.collectReplayTreeTransitions` (extraído do
  `rebuild()`) pula arestas efêmeras e expurga suas `GUITreeTransition`s do
  `treeTransitionHistory` (senão `rebuildHistory` ressuscita a aresta removida como referência
  pendurada), + early-return `isEphemeral()` em `Model.update(ModelAction, GUITreeAction)`
  (cobre os re-anchors de `currentAction`/`lastAction`/`newAction` do `updateModel` — furo #2).
  Invariante novo: INV-MODEL-16 (delta model da change).
- **Verificação de recuperação:** mesmo regime fresh-install, jar com o fix: um rebuild removeu
  aresta-tap (`[APE-RV] Rebuild: dropped 1 ephemeral edge(s) from replay (INV-MODEL-16)`) e o
  run sobreviveu — 156 passos, 4/4 rebuilds balanceados, `ActionHistory total=162 skipped=0`,
  zero `No such action`. (O run terminou aos 162s pelo abort pré-existente
  `SecurityException while injecting event` do Monkey — comportamento baseline, fora do escopo
  desta change.)

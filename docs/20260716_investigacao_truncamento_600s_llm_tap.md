# Truncamento cmpv2 — investigação de causa raiz (lado APE)

**Data:** 2026-07-16 (noite)
**Entrada:** `rv-android/docs/20260716_cmpv2_truncation_bug.md` (relatório da outra sessão)
**Método:** 4 subagentes paralelos (2 código APE, 2 dados rv-android) + verificação direta
**Status:** diagnóstico fechado; correções propostas, não implementadas
**Emenda 2026-07-17:** a análise de follow-up
(`docs/20260717_analise_terminador_refinement_crash_recovery.md`) localizou a morte DENTRO do
`Model.rebuild()` (9/9 traces, fronteira de marcadores) e identificou o terminador previsto: furo
na quarentena do `MODEL_LLM_TAP` no replay de transições do rebuild. As passagens corrigidas abaixo
estão marcadas com "[emenda 17/07]". Duas afirmações originais caem: "cadeia do crash 100% 2019" e
"quarentena do tap provada".

---

## 1. Veredito

O relatório original isolou "600s" como única variável distintiva. **Isso está errado: timeout e
versão do jar mudaram juntos.** A imagem `phtcosta/rvandroid:0.9.2` foi **reconstruída às 17:30**,
11 min após o merge do `llm-coordinate-tap` (0e7b16f, 17:19); a tag é mutável e mudou de significado
entre os experimentos. O cmpv2 é o **primeiro experimento em campo com MODEL_LLM_TAP** (jar da
imagem byte-idêntico ao `target/ape-rv.jar` pós-change, md5 `9df76094…`; 15–20 ocorrências de
`MODEL_LLM_TAP` por trace). O `_prechange_cmpv2s_base` (LLM@300s, 1,3% truncado) rodou jar
pré-change (0/75 traces com a telemetria nova).

**Variável dominante: o jar pós-change, não o timeout.** Argumento de taxa de risco: 7/10
truncamentos do run 3 morrem **antes de 300s** (~32% das tasks), na mesma janela em que o
`_prechange` — com config LLM **idêntica** (llmPercentage=0.7 hard-coded no arm gh43/INV-APV-17,
v13, temp 0, verificado nos dumps de config dos traces) — truncava 1,3%. Um timeout de 600s não
altera o comportamento dos primeiros 300s. Hazard ≈ 25× maior no mesmo intervalo ⇒ causa = delta
do jar. O 600s apenas alonga a exposição.

**Porém a causa raiz do crash é código upstream de 2019** (ETH, commit 38377b4a), não a change de
hoje. A change é o *regime* que expõe o defeito latente (§3).

## 2. Mecanismo (encadeado e provado por partes)

1. **Tap como indutor.** Pré-change, respostas do LLM com coordenadas sem widget correspondente
   eram descartadas (`no_match`). Pós-change viram taps reais (`MODEL_LLM_TAP`). Taps navegam o app
   para substratos de linhas repetidas (drawers, RecyclerViews, listas de `android.view.View`
   genéricos) que o SATA sozinho quase não alcançava. Nos 3 traces dissecados, os passos
   imediatamente anteriores ao fatal são LLM (fosdem2: o clique LLM no hambúrguer abre o drawer
   cuja lista dispara o ND).
2. **ND → refinamento.** Nesses substratos, o naming grosseiro (sem `index`) faz a mesma ação
   abstrata resolver para alvos diferentes → transição não-determinística → refinamento
   (`IndexNamer`) → rebuild do modelo, **removendo inclusive o estado corrente do agente**
   (`Removing state g1s2…`).
3. **Loop morre abruptamente.** Última linha do loop é sempre `>>>>>>>> … end step [N]` (finally de
   `ApeAgent.updateStateWrapper`); zero marcadores de shutdown normal (`Events injected`,
   `## Network stats` ausentes — presentes em todos os runs completos). Uma exceção não-capturada
   dispara em `updateStateInternal`. [emenda 17/07 — promovido de inferência a provado-com-
   localização] A lista original de candidatos (`validateAllNewActions` / `resolveNewAction` /
   `markVisited` / `recordActionHistory` / `moveForward` "pós-refinamento") está SUPERADA: a
   fronteira de marcadores em 9/9 traces fatais (último `Start rebuilding model` sem `Rebuilding
   model finished`; `Model has been refined, reset stateful` ausente) prova que a morte é
   INTRA-rebuild, na fase de re-adição de transições (`Model.java:266-286`) — antes de qualquer um
   daqueles candidatos rodar. Não é o `pickNodes` in-loop: `pickNodes`/`getNodes`
   imprimem `printGUITree()` antes do throw (GUITree.java:164-175) e não há dump na janela do stop
   ([emenda 17/07] precisão: o dump do TEARDOWN existe em 9/9, 2–9 linhas antes do stack CFW — o
   mecanismo de dump é observável ponta a ponta; zero dumps in-loop no trace inteiro).
   Não é evento nulo: `getNextEvent` só retorna null via `StopTestingException` (único site vivo:
   "Too many bad states" >100, sem logs correspondentes).
4. **Mascaramento do finally (o porquê do "silêncio").** A exceção escapa por
   `updateStateWrapper` (`catch(Exception){throw e}`, ApeAgent.java:370-371), `getNextEvent` (só
   captura `StopTestingException`, MonkeySourceApe.java:1402) e `runMonkeyCycles` (sem catch), e cai
   no `try{…} finally{ tearDown() }` de Monkey.java:775-786 (tearDown no finally = fork, c6c5d1f
   07/07, INV-EXPL-16 — presente também nos builds saudáveis). O `tearDown` →
   `saveActionHistory` → `ActionRecord.resolveModelAction` (Model.java:87) → `pickNodes`
   (GUITree.java:168) lança o `Cannot find widget` de 2019 (registro histórico com descritor
   pré-refinamento, agora ambíguo/ausente). **Por semântica de Java, a exceção do finally substitui
   a original** — o stack visível é a segunda ocorrência; a assassina do loop foi apagada.
   Identificá-la exatamente exige o fix F-A abaixo.

Blame (afirmação sobre IDADE DE CÓDIGO, não sobre a identidade do terminador — [emenda 17/07]: a
redação original desta frase nomeava a cadeia `validateAllNewActions→State.resolveAction:397→pickNodes`
logo após o parágrafo que a exclui, e foi lida pela sessão seguinte como se fosse a cadeia do crash;
o design da change `refinement-crash-recovery` herdou daí o site errado): o mecanismo de
refinamento/replay/teardown é 2019 (38377b4a); nada no caminho é mais novo que fabebab (14/07,
build dos runs saudáveis). **[emenda 17/07] Porém a cadeia LETAL não é 100% 2019**: a pré-condição
do crash previsto é a aresta efêmera do tap no grafo, introduzida pela change 0e7b16f.
[emenda 17/07] A quarentena do MODEL_LLM_TAP (identidade própria; arestas `isEphemeral()` isentas
de ND e de `markVisited`; replay do histórico sem `pickNodes`) está íntegra nesses três pontos, mas
tem DOIS FUROS: (1) o replay do `Model.rebuild` re-ancora a aresta do tap via
`State.getAction(MODEL_LLM_TAP)` (`Model.java:283→340` → `State.java:489`, `Graph.remove` coleta
arestas sem filtro `isEphemeral` em `Graph.java:1225/1240`) → ISE "No such action [MODEL_LLM_TAP]"
sem dump, dentro do rebuild — o terminador previsto; (2) latente: `updateModel` re-vincula
`lastAction` efêmera sem filtro (`StatefulAgent.java:252` → `Model.java:411`), mesma exceção.

## 3. Dados-chave (forense dos traces)

| condição | LLM | jar | traces | CFW (`Cannot find widget`) |
|---|:--:|:--:|--:|--:|
| cmpma 300s (5 braços) | não | pré | 2715 | **0 (0,0%)** |
| `_prechange` 300s | sim | pré | 75 | 1 (1,3%) |
| cmpv2 run 3, 600s | sim | **pós** | 28 | 9 (32%) + 7 kills |
| cmpv2 run 2, 600s | sim | **pós** | 24 | 11 (46%) |

- CFW é **exclusivo de braços LLM** (0/2715 sem LLM) e já existia em dose baixa no jar pré-change
  (1/75, mesma assinatura).
- **Volume de refinamentos não prevê morte** (sobreviventes com 134–151 [REFINE]; mortos com 1–2).
  O discriminador é o refinamento fatal remover o estado corrente + órfão no histórico. O
  `Find a new naming` fatal ocorre no último passo de todos os traces CFW.
- **maskan reclassificado:** mesmo CFW (mesma stack, widget `android.view.View` genérico), apenas
  determinístico e precoce (passos 5–10) por UI densa de Views clicáveis genéricas — não é defeito
  distinto. [emenda 17/07] Detalhe do gatilho: nos 3 reps o `LLM Summary` mostra `matched=0` com
  `llm_tap=7/4/2` — TODA chamada LLM vira tap de coordenada, o que antecipa a transição ND e o
  rebuild fatal; mesmo mecanismo dos demais, dose máxima do regime MODEL_LLM_TAP.
- **Terceira classe descoberta: 7/28 "kills" externos** (sem CFW e sem shutdown normal): p.ex.
  libchecker2 morre no MEIO de um prompt SGLang (hang em chamada LLM, 3:16), winterkongress1
  SIGKILL durante I/O do teardown aos 9:57. Modo de falha separado — acompanhar à parte.
- Truncados marcam **COMPLETED** no rv-android (defeito de desacoplamento de exit code do
  aperv_tool, já conhecido) — coberturas parciais passam como sucesso.

## 4. Correções propostas (APE — este repo)

| # | Fix | Local | Efeito | Prioridade |
|---|---|---|---|---|
| F-A | **Capturar, não mascarar**: em `Monkey.run`, logar o throwable original antes do finally (`catch(Throwable t){ t.printStackTrace(); throw t; }`) e envolver o `tearDown()` do finally em try/catch próprio | Monkey.java:775-786 | Expõe a exceção real que mata o loop (hoje inobservável); pré-requisito de qualquer fix definitivo | **P0, trivial** |
| F-B | `saveActionHistory` tolerante por registro: try/catch em volta de `resolveModelAction`, pular registro não-resolvível com warn+contador | Model.java:95-112 | Exit honesto (0), histórico parcial salvo. **Não corrige o truncamento** (o loop já parou) | P0, trivial |
| F-C | Invalidar/re-resolver `ActionRecord`s obsoletos no momento do refinamento; falha de rebind em `validateAllNewActions` vira remoção/skip da ação, não exceção | Model + StatefulAgent | Ataca a raiz de 2019 (registro/ação obsoletos pós-rebuild) | P1, médio |
| F-D | Corrigir o terminador in-loop real assim que F-A o expuser em um run de reprodução. [emenda 17/07: candidatos originais (`resolveNewAction`/`markVisited`/`moveForward`) superados — endereço previsto: excluir arestas efêmeras do replay do `Model.rebuild` (+ rebind de `lastAction` em `updateModel`); ver doc de 17/07] | ~~StatefulAgent~~ Model/Graph | Elimina o truncamento | P1, depende de F-A |

Reprodução barata pós-F-A: 1 APK (fosdem ou maskan — maskan é determinístico em ~20–30s!), braço
LLM, local. maskan vira o fixture ideal de regressão.

## 5. Lado rv-android (para a outra sessão)

1. **§10.1 do relatório original (controle no-LLM@600s) perdeu valor**: com 0/2715 CFW sem LLM, o
   resultado é previsivelmente saudável e não decide nada. O controle certo e barato é **jar
   pós-change, braço LLM, a 300s** (isola o jar com timeout constante; comparar com `_prechange`).
   Previsão de H-jar: ~30% truncado. Caveats: `_prechange` tem n=75 (subconjunto de APKs) e
   assume-se o mesmo modelo SGLang.
2. Corrigir o desacoplamento de exit code (`aperv_tool` loga sucesso em exit 1) — §10.5 do
   relatório, continua válido e independente.
3. Registrar o **digest da imagem** em `experiment_config.json` e fixar digest no compose (a tag
   0.9.2 mudou de conteúdo silenciosamente entre cmpma/prechange e cmpv2 — foi isso que escondeu o
   confound).
4. Investigar a classe "kill" separadamente (hang SGLang mid-prompt; watchdog por task).

## 6. Fatos vs inferências

**Provado:** reconstrução da imagem às 17:30 pós-merge; jar do cmpv2 = pós-change (md5), `_prechange` = pré;
config LLM idêntica entre os braços (0.7/v13/temp0, dump nos traces); ~~cadeia do crash 100% 2019 (blame)~~
[emenda 17/07: o blame 2019 vale para o mecanismo de replay/teardown; a cadeia LETAL prevista exige a aresta
efêmera da change 0e7b16f — ver §2]; ~~quarentena do tap no código~~ [emenda 17/07: REFUTADO — a quarentena
tem furo no replay do rebuild e no rebind de `lastAction`; ver §2 e o doc de 17/07]; 0/2715 CFW sem LLM;
1/75 CFW pré-change com LLM; mortes majoritariamente <300s;
ausência de dump `printGUITree` na janela do stop (exclui pickNodes in-loop) [emenda 17/07: reforçado — dump
do teardown presente 9/9, zero dumps in-loop]; ausência de marcadores de shutdown
normal (exclui fim de orçamento); `LLM Summary` é impresso no tearDown (StatefulAgent.java:1645); mascaramento
por finally é semântica Java dada a estrutura de Monkey.java:775-786; maskan = mesma assinatura CFW;
[emenda 17/07] morte INTRA-rebuild na fase de re-adição de transições (fronteira de marcadores, 9/9).

**Inferido (forte, não provado):** ~~que a exceção in-loop específica opera sobre referências do modelo
pós-rebuild (identidade exata requer F-A)~~ [emenda 17/07: localização promovida a provado (intra-rebuild);
a IDENTIDADE exata continua inferida — previsão registrada: `No such action [MODEL_LLM_TAP]` em
`State.getAction:489` ← `Model.rebuild:340/283`; ground truth = gate F-D]; que o tap é o mediador causal via
navegação para substratos de linhas repetidas (mediação observada em 3 traces dissecados, não em todos os
20 CFW) [emenda 17/07: se a previsão confirmar, a mediação dominante é mais direta — a própria aresta do tap
na vizinhança removida].

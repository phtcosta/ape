# Prompt — Análise profunda: a identidade do terminador in-loop e a validade do F-C (change `refinement-crash-recovery`)

> Cole este prompt numa nova sessão **aberta no repositório `ape`**
> (`/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape`).
> Use SEMPRE caminhos absolutos ao referenciar arquivos fora deste repo (ex.: o repo vizinho
> `rv-android`, onde vivem os traces do cmpv2).
>
> **Data de origem:** 2026-07-17 (madrugada). **Autor:** sessão anterior (Opus 4.8), que implementou
> os Groups 1/3/4 da change e descobriu, tarde demais, a contradição descrita na §2.

---

## 0. O que esta análise É e NÃO é

**NÃO é** para implementar nada. Nem código, nem correção de artefato. A sessão anterior já
implementou (working tree, sem commit) e é justamente por ter implementado antes de reler a forense
que este documento existe.

**NÃO é** um code-review da implementação. Ela já passou por `sdd-code-reviewer` sem achados de alta
confiança (o que, veja a §6, é em si um dado interessante: o review confirmou fidelidade **ao
design**, e o problema está *no design*, não na fidelidade a ele).

**É** uma análise profunda de **uma pergunta só**, da qual tudo o mais depende:

> A change `refinement-crash-recovery` guarda o site certo? Ou o F-C (o guard de tolerância a rebind
> em `StatefulAgent.validateNewAction`) é um guard especulativo, apontado para um caminho que a
> própria forense já havia excluído por evidência?

A ordem obrigatória é: **(1) analisar → (2) decidir sobre os artefatos → (3) só então implementar.**
A sessão anterior fez (3) antes de (1). Não repita.

---

## 1. Contexto mínimo (leia antes de qualquer coisa)

### 1.1 O problema original

No experimento **cmpv2** (braço LLM, 600s), ~45% das runs do aperv morrem cedo — truncam sem
shutdown normal. A run perde o orçamento de tempo e a amostra. Diagnóstico fechado em:

- **`docs/20260716_investigacao_truncamento_600s_llm_tap.md`** — o laudo forense (lado APE). **Leia
  inteiro. É a autoridade factual desta análise.** Especialmente §2 (mecanismo), §3 (dados), §4
  (correções propostas) e §6 (fatos vs inferências).
- `rv-android/docs/20260716_cmpv2_truncation_bug.md` — o relatório da outra sessão que originou o
  laudo (contexto; o laudo o corrige em pontos importantes).

Cadeia do defeito, resumida: um tap de coordenada do LLM (`MODEL_LLM_TAP`, feature nova, commit
`0e7b16f`) navega para substratos de linhas repetidas → naming grosseiro faz a mesma ação abstrata
resolver para alvos diferentes → transição não-determinística → refinamento (`IndexNamer`) → rebuild
do modelo, **removendo inclusive o estado corrente do agente** → uma exceção não-capturada mata o
loop de exploração → o `finally` de `Monkey.run` chama `tearDown()`, cuja `saveActionHistory` lança
`IllegalStateException: Cannot find widget` → **por semântica de Java, a exceção do finally
substitui a original**. O stack reportado é o do teardown; a identidade da assassina do loop foi
destruída.

Nada da cadeia é novo: é código upstream de 2019 (ETH, `38377b4a`). O `MODEL_LLM_TAP` é o *regime*
que expõe o defeito latente — hazard ~25× maior no mesmo intervalo de tempo, com config LLM idêntica
(laudo §1).

### 1.2 A change

`openspec/changes/refinement-crash-recovery/` (schema `sdd-full`). Artefatos: `proposal.md`,
`design.md`, `tasks.md`, `specs/exploration/spec.md`, `specs/model/spec.md`.

Quatro fixes, na nomenclatura **do design** (veja §4 — ela **diverge** da do laudo!):

| id (design) | o quê | onde | status |
|---|---|---|---|
| **F-A** | desmascarar: guards no `finally` de `Monkey.run` + `safeStep` nos teardowns + fix do `finally` do `Naming` | `Monkey`, `MonkeySourceApe`, `StatefulAgent`, `Naming` | implementado |
| **F-B** | `saveActionHistory` tolerante por registro + sumário `total=/skipped=` | `Model` | implementado |
| **F-C** | tolerar falha de rebind em `validateNewAction`: contar, logar, `setValid(false)`, `return null` | `StatefulAgent` | implementado ← **o objeto desta análise** |
| **F-D** | *gate*: rodar maskan com o jar do F-A e ler o stack real do terminador | (device) | **NÃO feito** |

### 1.3 Estado exato do working tree (nada commitado)

`git log -1` = `f915440` (docs da change). Modificados, sem commit:

```
M openspec/changes/refinement-crash-recovery/design.md   (D4 + Testing Strategy — ver §6.2)
M openspec/changes/refinement-crash-recovery/tasks.md    (16/22 marcadas)
M src/main/java/com/android/commands/monkey/Monkey.java
M src/main/java/com/android/commands/monkey/MonkeySourceApe.java
M src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java
M src/main/java/com/android/commands/monkey/ape/model/Model.java
M src/main/java/com/android/commands/monkey/ape/naming/Naming.java
?? src/test/java/com/android/commands/monkey/ape/agent/StatefulAgentTearDownTest.java
?? src/test/java/com/android/commands/monkey/ape/agent/ValidateNewActionToleranceTest.java
?? src/test/java/com/android/commands/monkey/ape/model/SaveActionHistoryToleranceTest.java
```

Suíte: **642 testes, 0 falhas, 19 skipped** (`mvn test`). `mvn package` gera `target/ape-rv.jar`.
`openspec validate refinement-crash-recovery --strict` passa. Tarefas abertas: 2.1–2.3 (gate F-D),
5.2, 5.3 (device), 5.8 (sync manual de specs no archive).

---

## 2. O ACHADO CENTRAL — o design apostou no caminho que a forense excluiu

### 2.1 O que o laudo prova

Laudo §2.3 (`docs/20260716_investigacao_truncamento_600s_llm_tap.md:47-48`), repetido na lista de
**fatos provados** (`:122`):

> "**Não é o `pickNodes` in-loop**: `pickNodes`/`getNodes` imprimem `printGUITree()` antes do throw
> (GUITree.java:164-175) e não há dump na janela do stop."

O argumento foi verificado no código pela sessão anterior e **se sustenta**:

- `GUITree.printGUITree()` (`src/main/java/com/android/commands/monkey/ape/tree/GUITree.java:109-113`)
  é **incondicional** — `Logger.format` direto para stdout, sem gate de `Config`, sem flag de debug.
- É chamado nas quatro rotas de throw de "Cannot find widget": `GUITree.java:139, 153, 167, 185`
  (`getFirstNode`, `getNodes`, `pickNodes`, `getCountOfTargetNodes`).

Logo: se `pickNodes` tivesse lançado **dentro do loop**, o trace teria o dump dos widgets logo antes
da parada. Não tem.

### 2.2 O que o design supõe

`design.md`, seção **Open Questions**:

> "Identity of the in-loop terminator (resolved by the F-D maskan gate; **expected:
> `validateNewAction` → `State.resolveAction:388` → `pickNodes` at `:397`**)."

E D4 constrói toda a justificativa de posicionamento do guard em cima disso.

**`State.resolveAction:397` chama `latest.pickNodes(action)`.** É exatamente o caminho que o laudo
exclui. O design escolheu, como site esperado do terminador, o único candidato que a forense já
havia descartado por evidência positiva.

### 2.3 Como isso passou despercebido

Hipótese da sessão anterior (verifique, não assuma): o laudo §2 (linha `:62`) diz —

> "Blame: toda a cadeia refinamento→`validateAllNewActions`→`State.resolveAction:397`→`pickNodes` é
> 2019 (38377b4a)"

— e isso é uma afirmação sobre a **idade do código** (blame), não sobre a **identidade do
terminador**. É fácil ler essa frase como "a cadeia do crash é essa" quando o parágrafo anterior
diz exatamente o oposto. **Tarefa para a análise: confirmar ou refutar essa explicação.** Se for
isso, é um achado sobre como o laudo comunica (duas frases adjacentes que se leem como opostas), e
vale corrigir o laudo também, não só a change.

---

## 3. Os candidatos reais ao terminador

O laudo (§2.3, `:45-46`) descreve a assassina como uma exceção não-capturada disparando em
`updateStateInternal` **após** o refinamento, com estes candidatos nominais:

> `validateAllNewActions` / `resolveNewAction` / `markVisited` / `recordActionHistory` / `moveForward`
> "operando sobre referências do modelo recém-reconstruído"

Cruzando com a restrição provada da §2.1 (**o terminador não pode passar por `pickNodes`/`getNodes`,
senão haveria dump**), a análise precisa enumerar, no código atual, todos os throws in-loop
pós-refinamento **que não imprimem `printGUITree`**. Os que a sessão anterior já localizou:

### 3.1 Candidato A — `getThrottleForNewAction` → `IllegalStateException("Oops")`

`src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:1530`.

```java
protected int getThrottleForNewAction(State state, ModelAction action) {
    if (state != action.getState()) {
        throw new IllegalStateException("Oops");
    }
```

Por que encaixa em **todos** os fatos provados:

- é **in-loop**, dentro do próprio `validateNewAction` (`:1339`, argumento do `resolveAction`);
- **não imprime dump** → compatível com a ausência de `printGUITree` na janela do stop;
- a condição é `state != action.getState()` — **comparação por referência**. Um rebuild cria objetos
  `State` novos com a mesma `StateKey`; uma ação carregada do buffer aponta para o objeto **antigo**,
  que é `.equals()` do novo mas não `==`. Isso é literalmente "operar sobre referências do modelo
  recém-reconstruído", a descrição do laudo.
- há um caminho plausível para uma ação "estrangeira" chegar: `RandomAgent.java:64` →
  `selectNewActionFromBuffer()` (`StatefulAgent.java:448`), que tira `t.action` de uma
  `StateTransition` do buffer. Note que as checagens defensivas ali usam `.equals()`
  (`:461`, `:467`), enquanto o `getThrottleForNewAction` usa `!=` — **a checagem que protege e a
  que lança usam critérios de identidade diferentes.**

**Verificar (não assumir):** (a) o buffer sobrevive a um rebuild? quem o limpa (`clearBuffer`) e
quando? (b) o `SataAgent` (braço ativo do cmpv2, `--ape sata`) passa por `selectNewActionFromBuffer`?
(c) `validateAllNewActions` itera `newState.getActions()`, cujas ações têm `state == newState` por
construção — então **por ali** o "Oops" não dispara; o candidato depende inteiramente do caminho do
buffer. (d) `Graph.getOrCreateState`/`Model.update`: o rebuild realmente cria objetos novos, ou
reusa por `StateKey`? **Esse é o ponto que decide o candidato A.**

### 3.2 Candidato B — `State.resolveAction` → `"Empty GUI tree history"`

`src/main/java/com/android/commands/monkey/ape/model/State.java:390` (e um segundo site em `:455`,
`getCountOfTargetNodes`). Lança **antes** do `pickNodes`, sem dump.

O design menciona esse throw na seção API Design, mas o qualifica como **"the rarer"** — enquanto
trata `Cannot find widget` como "expected". Dada a §2.1, essa ordem de probabilidade está
**invertida**: dos dois, só este pode ser o terminador in-loop.

**Verificar:** um `State` recém-reconstruído pode ter `treeHistory == null` no momento em que o
agente o revalida? Quem popula `treeHistory` (`State.java:368-371`) e em que ordem, relativo ao
rebuild?

### 3.3 Candidatos C — os outros nomes do laudo

`resolveNewAction`, `markVisited`, `moveForward`, `recordActionHistory` sobre estado removido.
Enumerar os throws de cada um e aplicar o mesmo filtro (imprime dump? não imprime?).

Nota relevante: memória institucional da change `llm-coordinate-tap` (commit `0e7b16f`) registra que
**toda ação sintetizada fora de `State.getActions()` crashava `Graph.markVisited`** — daí os
invariantes INV-MODEL-13/14 e o conceito de aresta `isEphemeral()`. O laudo (`:64-66`) afirma que o
`MODEL_LLM_TAP` está "corretamente em quarentena" (identidade própria, arestas isentas de ND e de
`markVisited`, replay sem `pickNodes`). **Vale re-verificar essa quarentena de forma adversarial** —
é a peça que, se tiver um furo, explicaria por que o defeito de 2019 só aparece no braço LLM.

### 3.4 O filtro que a análise deve aplicar

Para cada candidato: **(1)** é alcançável in-loop pós-refinamento? **(2)** imprime `printGUITree`
antes de lançar? (se sim → excluído pela §2.1) **(3)** o tipo/mensagem bate com o que se vê (ou não
se vê) nos traces? **(4)** existe telemetria hoje que o distinguiria?

---

## 4. Divergência de nomenclatura F-C/F-D (laudo × design) — não registrada em lugar nenhum

Os dois documentos usam os mesmos rótulos para coisas diferentes:

| | **laudo** (`20260716…:97-98`) | **design** (`design.md`) |
|---|---|---|
| **F-C** | invalidar/re-resolver `ActionRecord`s obsoletos **no momento do refinamento** — efeito: "Ataca a raiz de 2019" | tolerar falha de rebind **no `validateNewAction`** (por-ação, não por-registro) |
| **F-D** | **corrigir o terminador in-loop real** (candidatos: `resolveNewAction`/`markVisited`/`moveForward`) — efeito: **"Elimina o truncamento"** | o **gate** que descobre a identidade do terminador |

Consequências que a análise precisa pesar:

1. O design **rejeitou explicitamente** o F-C do laudo — é a alternativa "(b) invalidate history
   records at refinement time (report's F4)" da decisão D3. (Note: o design chama de "F4" o que o
   laudo chama de "F-C"; há uma terceira nomenclatura em jogo.) A rejeição é argumentada e pode
   estar certa. O problema não é a decisão, é ela não estar rastreável.
2. Segundo o laudo, **quem elimina o truncamento é o F-D** — e o F-D do laudo é um fix de código sem
   endereço conhecido, não um gate. O design colapsou os dois, apostando que o site do seu F-C
   coincide com o terminador do F-D do laudo. **Essa aposta é a hipótese em julgamento.**
3. Efeito declarado do F-B pelo laudo: "**Não corrige o truncamento** (o loop já parou)". O F-B é
   sólido — mas ninguém deve esperar que ele salve uma run.

---

## 5. O que está em jogo para cada peça (assimetria)

Isto é o que a análise precisa concluir com clareza, porque determina o que sobrevive:

| peça | depende do gate? | por quê |
|---|---|---|
| **F-A** (desmascarar) | **não** | O mascaramento por `finally` é semântica de Java dada a estrutura de `Monkey.java:777`; o laudo o lista como **provado** (`:123-124`). O fix se justifica sozinho: sem ele nenhum trace é confiável. |
| **F-B** (history tolerante) | **não** | O replay do teardown é o stack que **já foi observado** — é a exceção visível em 20 traces CFW. Não é inferência. |
| **fix do `Naming` finally** | **não** | NPE em `results.getNameSize()` com `results == null` é fato de código (`Naming.java`, `finally` sobre variável só atribuída no try). |
| **F-C** (rebind tolerante) | **SIM, inteiramente** | Único guard apoiado numa hipótese sobre a identidade do terminador — e a hipótese escolhida é a que a §2.1 exclui. |

### 5.1 As três leituras do gate para o F-C

A telemetria `[APE-RV] RebindFailures total=N` (adicionada pela sessão anterior no teardown) é o
discriminador. Três desfechos, com decisões **diferentes**:

1. **Stack cai no `validateNewAction` e N>0** → a aposta era certa (apesar da §2.1 — o que exigiria
   explicar por que não há dump!). F-C confirmado. Gate 5.2 deve mostrar a run inteira.
2. **Stack cai em outro lugar, mas N>0** → o F-C tolera uma falha **real** que não é a assassina.
   Ele sobrevive por um argumento **diferente** do que o criou: D3 (impedir que ação sticky-valid
   seja despachada em coordenadas obsoletas e alimente prompt do LLM com nó velho). A justificativa
   precisa ser **reescrita**, e o truncamento continua, precisando do fix do site verdadeiro.
3. **N=0** → o caminho guardado nunca dispara. F-C é guard especulativo para falha que não acontece
   → **viola o P1** (no speculative guards) → o certo é **remover**, não estender. Indício a favor
   desta leitura: o laudo (`:79-81`) achou sobreviventes com **134–151 refinamentos e zero falhas de
   rebind**; e mortos com **1–2** refinamentos. "Volume de refinamentos não prevê morte."

**A análise deve dizer, antes do gate rodar, qual desfecho ela prevê e por quê.** Uma previsão
registrada é o que transforma o gate em experimento em vez de confirmação.

---

## 6. O que a sessão anterior implementou (e onde a implementação pode estar errada)

### 6.1 Inventário

Todos em `src/main/java/com/android/commands/monkey/`:

| site | linhas atuais | o quê |
|---|---|---|
| `Monkey.run` `finally` | `Monkey.java:777-799` | dois `try/catch(Throwable)` inline (rotação, tearDown), cada um só loga |
| `MonkeySourceApe.tearDown` | `MonkeySourceApe.java:~213-249` | `safeStep(label, Runnable)` privado, 6 passos, `disconnect()` primeiro |
| `StatefulAgent.safeStep` | `StatefulAgent.java:1668` | idem, catch `Throwable`, loga label + stack |
| `StatefulAgent.tearDown` | `StatefulAgent.java:1677` | 8 passos originais + `rebindFailures` |
| `StatefulAgent.rebindFailureCount` | `StatefulAgent.java:129` | contador (privado) |
| `StatefulAgent.validateNewAction` | `StatefulAgent.java:1331-1355` | throttle fora do try (`:1339`); `catch (IllegalStateException)` (`:1342`) → count + log + `setValid(false)` + `return null` |
| `Model.saveActionHistory` | `Model.java:96-123` | guard por registro `catch (RuntimeException)` (`:110`); sumário (`:122`) |
| `Naming.naming` | `Naming.java:~491-505` | log movido do `finally` p/ o caminho de sucesso; arg duplicado corrigido p/ `getNodeSize()` |

Testes novos (3 arquivos, 5 casos): `StatefulAgentTearDownTest`, `SaveActionHistoryToleranceTest`
(3 casos), `ValidateNewActionToleranceTest` (2 casos).

### 6.2 O episódio do "Oops" — um guard largo demais, pego e corrigido

Vale como **precedente metodológico** para esta análise. A primeira versão do F-C envolvia o
statement inteiro no `try`, e o argumento `getThrottleForNewAction(newState, action)` é avaliado
**dentro** dele. Resultado: o `IllegalStateException("Oops")` — violação de integridade do modelo —
era capturado e **reclassificado como falha de rebind**: contado, logado como warning, ação
invalidada, run seguindo em frente.

Provado empiricamente com um teste descartável nas duas versões (throttle dentro do try → engolido;
fora → propaga). Corrigido: throttle computado num local antes do `try`. Pinado pelo teste
`ValidateNewActionToleranceTest.testModelIntegrityViolationIsNotReclassifiedAsRebindFailure`. O
raciocínio foi registrado no D4 do `design.md` (uma das duas edições de artefato feitas).

**A lição, e por que ela importa aqui:** o guard largo teria **cegado o próprio gate F-D** —
justamente contra o candidato A da §3.1, que é hoje o candidato mais forte. Capturar cedo demais
transforma bug em telemetria silenciosa. Aplique essa desconfiança ao resto da change.

### 6.3 Fidelidade dos testes ao caminho real — suspeita aberta

`ValidateNewActionToleranceTest` injeta `IllegalStateException("Cannot find widget")` e o javadoc
afirma ser "the production exception" desse caminho. Dada a §2.1, essa string é a assinatura do
**teardown**, não do loop. O guard funciona igual (captura `IllegalStateException`, a mensagem é
irrelevante), mas **a justificativa escrita está apoiada num caminho excluído**. Avaliar se o teste
deve injetar outra exceção — ou se ele está testando a coisa errada por inteiro.

Desvio conhecido e deliberado do mesmo teste: o ato 1 estabelece `isValid() == true` via
`setValid(true)` direto, em vez de um resolve bem-sucedido, porque **`MonkeySourceApe` não é
carregável na JVM** — `pom.xml:96-104` exclui deliberadamente `dalvik-stub` e `framework-full-debug`
do classpath de teste (fix do conflito de `org.json`), e o campo `UiAutomation` puxa
`android.app.IUiAutomationConnection`. Verificado como sólido: os **únicos** writers de `valid` no
projeto inteiro são `StatefulAgent:1344/1348/1352` (grep exaustivo) — o flag é sticky, sem reset
por step, então o ato 1 escreve o mesmo campo pelo mesmo setter que o caminho de sucesso. Poder
discriminante verificado empiricamente: removida a linha `setValid(false)` da implementação, o
teste fica **RED** com "the stale action must not stay selectable".

### 6.4 O code review não pegou nada disso

`sdd-code-reviewer` rodou e reportou zero achados de alta confiança, verificando explicitamente
D1/D2/D3/D4/D5/D6 como "faithful". Está correto — e é o ponto: **ele revisou o código contra o
design, e o defeito está no design**. Uma revisão que não relê a fonte forense não pode achar isto.
Não repita o erro: a §2 nasceu de reler o laudo, não o código.

---

## 7. Tarefas desta análise (em ordem)

1. **Ler o laudo inteiro** (`docs/20260716_investigacao_truncamento_600s_llm_tap.md`), com atenção
   cirúrgica à §2 e à §6 (fatos vs inferências). Depois `design.md` e `proposal.md` da change.
2. **Confirmar ou refutar a §2.1** — o argumento do `printGUITree` realmente exclui `pickNodes`
   in-loop? Cuidado com o ponto fino: o CFW do **teardown** também passa por `pickNodes`
   (`Model.java:87` → `GUITree.java:168`) e **também deveria imprimir dump**. Então "não há dump na
   janela do stop" precisa ser lido com precisão: *qual* janela? Se o dump do teardown estiver
   presente nos traces, a redação do laudo é ambígua e o argumento precisa ser reencenado sobre os
   traces reais. **Esta é a checagem mais importante da análise inteira** — se a §2.1 cair, o design
   volta a fazer sentido e a change está quase pronta. Traces em `rv-android` (cmpv2 runs 2 e 3).
3. **Enumerar os candidatos** (§3) no código atual, aplicando o filtro da §3.4. Produzir uma tabela
   fechada de throws in-loop pós-refinamento × imprime dump? × alcançável?
4. **Decidir o candidato A** (§3.1): o rebuild cria objetos `State` novos por `StateKey`? O buffer
   sobrevive? O `SataAgent` passa pelo buffer? (`Graph.getOrCreateState`, `Model.update`,
   `StatefulAgent.selectNewActionFromBuffer:448`, `clearBuffer`).
5. **Re-verificar a quarentena do `MODEL_LLM_TAP`** (§3.3) de forma adversarial.
6. **Registrar uma previsão** do desfecho do gate (§5.1) com justificativa, ANTES de rodar.
7. **Só então**: propor as correções de artefato (§8) — e só depois de aprovadas, implementar.

---

## 8. Correções de artefato candidatas (NÃO aplicar antes da análise)

A sessão anterior identificou estas, mas **não as aplicou** — de propósito, para não contaminar a
análise com uma narrativa já escrita:

1. **`design.md` → Open Questions**: substituir a expectativa `validateNewAction → resolveAction:388
   → pickNodes:397` pelo fato que a exclui (§2.1) + listar os candidatos reais do laudo, incluindo
   o "Oops" com o detalhe `!=` vs `.equals()`.
2. **`design.md` → D4**: a justificativa de posicionamento do guard se apoia no site excluído.
   Reconstruir sobre o candidato que sobreviver à análise.
3. **`design.md` → API Design**: inverter a qualificação "expected"/"the rarer" entre
   `Cannot find widget` e `Empty GUI tree history` (§3.2).
4. **`design.md` → nota de tradução** F-C/F-D/F4 entre laudo, design e a decisão D3 (§4).
5. **`tasks.md` → 2.3**: hoje diz "se o stack NÃO cair em `validateNewAction`…, estenda o alvo do
   Group 4". Falta o terceiro desfecho: **se `N=0`, remova o F-C** (§5.1, leitura 3).
6. **`ValidateNewActionToleranceTest`** → javadoc e/ou exceção injetada (§6.3).
7. **`docs/20260716_investigacao_truncamento_600s_llm_tap.md`** → se a §2.3 confirmar a hipótese da
   §2.3-deste-doc (as duas frases adjacentes que se leem como opostas), corrigir a redação do laudo
   também. O laudo é insumo de outras sessões.

---

## 9. Índice de evidências (tudo verificado pela sessão anterior, re-verifique)

| afirmação | evidência |
|---|---|
| `printGUITree` é incondicional | `GUITree.java:109-113` — `Logger.format` sem gate |
| chamado antes de todo throw de CFW | `GUITree.java:139, 153, 167, 185` |
| `resolveAction` lança sem dump em 1 caso | `State.java:390` ("Empty GUI tree history"); 2º site `:455` |
| `resolveAction` chega ao `pickNodes` | `State.java:397` |
| "Oops" existe e não imprime dump | `StatefulAgent.java:1530` |
| "Oops" usa identidade por referência | `state != action.getState()` — `StatefulAgent.java:1529` |
| buffer usa `.equals()` para proteger | `StatefulAgent.java:461, 467` |
| ação estrangeira pode vir do buffer | `RandomAgent.java:64` → `StatefulAgent.selectNewActionFromBuffer:448` |
| `valid` é sticky; 3 writers só | grep exaustivo: `StatefulAgent.java:1344, 1348, 1352` |
| `MonkeySourceApe` não carrega na JVM | `pom.xml:96-104` (exclusão deliberada); cf. `MonkeySourceApeForeignGuardTest` javadoc |
| recuperação in-process já existe | `ApeAgent.updateStateWrapper:334-372` — `BadStateException` → repega janela → `stopTopActivity()` após 10 → `StopTestingException` após 100 |
| `catch (Exception e) { throw e; }` é no-op | `ApeAgent.java:369-371` |
| `getNextEvent` só para limpo via StopTesting | `MonkeySourceApe.java:1418-1421` |
| exceção do loop é sempre logada | `Monkey.main:616-620` — `catch (Throwable)` + `printStackTrace` + `exit(1)` |
| tearDown tem 1 único caller | `Monkey.java:793` (dentro do `finally`) |
| maskan = mesmo CFW, determinístico 20–30s | laudo `:82-84`, `:100-101` |
| volume de refinamento não prevê morte | laudo `:79-81` |
| CFW é exclusivo de braços LLM | laudo `:72-78` — 0/2715 sem LLM; 1/75 pré-change com LLM |
| truncados marcam COMPLETED no rv-android | laudo `:88-89` (defeito conhecido, outro repo) |

## 10. Protocolo do gate (para quando houver emulador)

Pré-requisitos: emulador `@RVSec` + SGLang alcançável; braço LLM (`llmPercentage=0.7`, hard-coded no
arm gh43); APK `app.maskan.chat`. **Protocolo foreground-first**: `monkey -c LAUNCHER` antes do APE
— sem isso o APE trava em `waitForActivity` e não lança o app (bug pré-existente conhecido; sintoma
= 0 chamadas LLM).

Sequência: `mvn package` → push do jar → maskan → confirmar que **ainda trunca** (os guards mudaram o
reporte, não o defeito) → ler o stack in-loop (agora é ground truth) → ler `RebindFailures total=N`
→ aplicar a §5.1.

> **Nota da sessão anterior:** o experimento cmpv2 estava rodando e ocupando o emulador; por isso o
> gate não foi executado. Confirme que o device está livre antes de começar.

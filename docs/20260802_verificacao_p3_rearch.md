# Varredura P3 — estratégias de compatibilidade nos 7 changes `rearch-0*`

**Data**: 2026-08-02
**Alvo**: `openspec/changes/rearch-01-parity-oracle/` … `rearch-07-compact-static-artifact/` (28 artefatos), contra `docs/analise_fable-selecao.md` (rev. 3), `docs/plans/20260802_rearchitecture_roadmap.md`, `openspec/specs/` e o código em `src/main/java/`.
**Objetivo**: achar e classificar toda estratégia que mantenha código legado vivo (adapter, shim, alias, conversor, janela de fallback, tolerância especulativa), aplicando o P3 do `rv-android/CLAUDE.md`:

> Na evolução deste sistema o código pode mudar; **todas** as mudanças devem ser feitas sem estratégias que mantenham o código legado vivo (p.ex. um adapter para preservar compatibilidade). Toda mudança deve ser feita e o código legado removido/sobrescrito. Mover os arquivos antigos para a pasta `backup/`.

**Estado**: esta é uma verificação. **Nenhum artefato foi editado, nenhum código foi tocado, 0/299 tarefas.** As varreduras e checagens rodaram no scratchpad da sessão.

**Vocabulário de severidade** (o mesmo de `docs/20260802_verificacao_consistencia_rearch.md`): **contradição** / **lacuna** / **smell** / **cosmético**.

---

## 1. Veredito executivo

| # | Achado | Change(s) | Severidade | Disposição |
|---|---|---|---|---|
| **A1** | Conversor NDJSON→legado escrito por cima do `.trace` | 04 (05, 07 propagam) | **contradição** | **remover** — decisão do dono de 2026-08-02 |
| **C1** | Superfície transitória do estágio 2 sem quem a mate | 02 (05 deveria matar) | **lacuna** | decisão do dono: opção (a) ou (b), §5 |
| **D1** | `Deterministic Dead-Pair Ban` órfão, afirma `[APE-LLM-TEL]`/`[APE-OUTCOME]` | — (nenhum estágio) | **lacuna** | agravado por A1; dono natural: 04 |
| **D2** | `Tolerant Action-History Persistence` órfão, normatiza `saveActionHistory` | — (nenhum estágio) | **lacuna** | resíduo L1 da sessão 6; dono natural: 04 |
| **B1** | `sata_mop` chamado "back-compat alias" | 05 | **smell** | legítimo — trocar a redação |
| **B2** | "temporarily beside" / "dual-parser stage" | 07 | **cosmético** | legítimo — trocar a redação |
| **B3** | `formatVersion` justificado por "forward compatibility" | 07 | **cosmético** | legítimo — trocar a redação; opção de endurecer |
| **B4** | "until the `rearch-04` NDJSON sink lands" | 07 | **cosmético** | texto morto pela ordem dos estágios |
| **D3** | Três citações parentéticas ao formato `[APE-*]` em specs órfãs | — | **cosmético** | opcional |
| **D4** | Prefixos `/sdd-*` em tasks que rodam no rv-android | 05 | **cosmético** | resíduo do X2 da sessão 6 |

**Falsos positivos verificados e dispensados**: `rearch-01` (3 sítios), `rearch-03` (4 sítios), `rearch-06` (**zero** ocorrências nas duas varreduras). Detalhe em §4.

**Leitura de conjunto**: o P3 dos sete changes é muito melhor do que o inventário de partida sugeria. Existe **uma** violação real (o conversor, já decidida pelo dono) e **uma** superfície de compatibilidade genuína (§5), criada pela fronteira entre os estágios 2 e 5 e não por descuido. Todo o resto do que a varredura levantou é ou afirmação explícita de P3, ou restrição de identidade de dado, ou vocabulário mal escolhido sobre um mecanismo correto. O padrão de defeito não é "os autores gostam de shims" — é "os autores descrevem corretamente e rotulam mal".

---

## 2. Método

### 2.1 Varredura de termos

Duas passadas em Python sobre os 28 artefatos (`os.walk` + leitura linha a linha). **`grep` não foi usado**: a sessão 6 registrou que ele retorna falso-negativo em `rearch-04-step-ndjson-telemetry/specs/event-sink/spec.md` para strings ASCII demonstravelmente presentes.

| Passada | Termos | Hits |
|---|---|---|
| 1 | os 16 prescritos: `compat`, `temporar`, `shim`, `adapter`, `alias`, `legacy`, `during migration`, `deprecat`, `fallback window`, `transitional`, `coexist`, `for now`, `interim`, `dual`, `formatversion`, `convert` | **146** |
| 2 | 30 complementares: `back-compat`, `backward(s)`, `both formats`, `old parser`, `keep the old`, `grace period`, `rollback`, `opt-in`, `phase out`, `side-by-side`, `beside`, `escape hatch`, `bridge`, `wrapper`, `facade`, `delegating`, `preserve the old`, `superseded`, `retained for`, `kept for`, `re-add`, `reintroduc`, … | **93**, nenhum achado novo de substância |

Ruído dominante da passada 1, identificado e descartado: `dual` casa dentro de `resi**dual**`, `indivi**dual**ly`, `gra**dual**`; `legacy` casa em `android.support.v4.view.ViewPager (legacy support library)` — nome de classe Android; `convert` casa em "convert to minutes for `--running-minutes`"; `temporar` casa em "large **temporar**ies nulled in a finally block" (INV-RTR-06). Da passada 2, `backward` casa quase inteiramente em "back**ward** scan" (`rearch-06`, direção de percurso) e `stale` em "stale objects".

Verificação direta do arquivo problemático, por Python:

```python
p="openspec/changes/rearch-04-step-ndjson-telemetry/specs/event-sink/spec.md"
# 20.645 bytes, 217 linhas
# 'convert','legacy','temporar','compat','shim','adapter','trace_ndjson' -> ZERO
# 'gzip' -> linha 9
```

### 2.2 Checagens mecânicas (§2 do documento da sessão 6)

Os três scripts foram reconstruídos no scratchpad e rodados **antes** de qualquer conclusão, para estabelecer a linha de base:

| Checagem | Resultado |
|---|---|
| 1. Aplicabilidade dos deltas (simula os 7 estágios em ordem sobre `openspec/specs/`) | **0** achados |
| 2. Colisões de requisito (requisito tocado por mais de um estágio) | **6** — as mesmas 6 da sessão 6, todas carregando o texto do estágio anterior |
| 3. Varredura de mecanismo deletado sem dono (lista da sessão 6) | **0** achados |

O verificador de aplicabilidade precisou de dois consertos em relação ao que a sessão 6 descreve, ambos de parsing e nenhum de substância:

1. O bloco `## RENAMED Requirements` de `rearch-02-runspec/specs/ui-coverage/spec.md:15-16` escreve os nomes **entre crases** (`` - FROM: `### Requirement: …` ``); o regex sem crase produzia um falso positivo de "MODIFIED sobre requisito inexistente".
2. A checagem 3 precisa registrar o requisito como "tocado" **sob os dois nomes** (o `FROM` e o `TO`), senão o nome antigo — que é o que ainda está na main spec — aparece como órfão.

Com os dois consertos: 0 / 6 / 0, idêntico ao pós-correção da sessão 6. **A varredura P3 partiu de um estado mecanicamente limpo.**

### 2.3 Extensão deliberada da checagem 3

A lista de mecanismos da sessão 6 (`apePureMode`, `ape_pure`, `rvForcedOff*`, `rvExemptReasons`, `stepTelemetryEnabled`, `saveGraph`/`readGraph`/`sataModel.obj`, `ape.xpath`/`ape.strings`, `ThreadLocalRandom`) foi estendida com **as tags do formato legado** — `[APE-STEP]`, `[APE-OUTCOME]`, `[APE-LLM-TEL]`, `[APE-LLM-ERROR]`, `[APE-LLM-CONFIG]`, `[APE-LLM-PROMPT]`, `[APE-LLM-RESPONSE]`, `[APE-MOP-DATA]`, `[APE-ARCH]`, `LLM Summary`, `Decision ratio` — e com `saveActionHistory`/`action-history.log`.

A razão é específica desta sessão: **enquanto o conversor existia, um requisito que afirmasse `[APE-LLM-TEL]` continuava literalmente verdadeiro do `task.result.trace_file`, porque o conversor re-emitia aquelas linhas.** Matando o conversor, qualquer requisito assim se torna insatisfazível. A extensão não é escopo novo — é a consequência direta de A1. Resultado em §6.

---

## 3. A1 — o conversor NDJSON→legado (contradição; remover)

### 3.1 O que o artefato manda fazer

`rearch-04-step-ndjson-telemetry/design.md:193-195`, decisão **D-8**. Depois da corrida, o lado Python: (1) comprime a captura NDJSON crua em `<trace>.ndjson.gz`; (2) roda `trace_ndjson.convert_to_legacy()` **sobrescrevendo o próprio `task.result.trace_file`** com linhas `[APE-*] key=value` reconstruídas — expandindo os IDs de dicionário `act`/`st` de volta para strings, re-emitindo os defaults omitidos, re-expandindo `t` relativo para `clock=` epoch, re-partindo os sub-eventos agrupados `llm[]` em uma linha por chamada, e re-expandindo `RUN_END.counters` nas linhas `LLM Summary`/`Decision ratio`.

### 3.2 Por que está errado — quatro contas

1. **Inverte qual artefato é a verdade.** O `.trace` — o artefato primário que todo mundo abre — vira derivado reconstruído, enquanto o dado real se esconde num `.gz` lateral.
2. **Cancela o benefício inteiro e custa mais armazenamento.** O próprio design admite (`design.md:195`): *"during migration, storage = legacy-format trace (as today) + compressed NDJSON; the raw-volume win lands when the converter is retired"*. Os ~3,5 GB por 880 tasks são motivação declarada do estágio 4 (`proposal.md:3`).
3. **Reintroduz a exata classe de defeito que a mudança existe para matar.** INV-SNK-01/02 (`specs/event-sink/spec.md`) garantem que qualquer conteúdo cabe no formato — aspas, contrabarra, controles, NUL, newline escapados **por construção**. O formato legado `key=value` **não tem escaping nenhum**. Converter de volta re-impõe o formato frágil sobre o artefato primário: um `\n` dentro de um `text=` quebra a linha de novo, e o resíduo A8 renasce no `.trace`. A garantia de escaping valeria só dentro do `.gz` que ninguém lê.
4. **O relatório nunca pediu isso.** A fonte da verdade (Sec. 6.5) diz: *"Transporte: stdout (contrato de coleta intocado — zero mudança no Python além do parser, com conversor temporário durante a migração)"*. Isso descreve um shim **do lado da análise**, para o qual se aponta um parser velho quando ele precisa da forma velha. Sobrescrever o `.trace` do pipeline é invenção do autor do artefato (commit `ea1e89e`), não decisão do dono.

**Decisão do dono já tomada (2026-08-02): o conversor morre. O rv-android se adapta ao formato novo.**

### 3.3 Inventário de consumidores — verificado contra a árvore

O propósito declarado do conversor é manter *"every existing rv-platform/analysis parser"* funcionando. Medido:

| Consumidor | Parseia o formato `[APE-*]`? |
|---|---|
| `rv-platform`, `rv-coverage`, `rv-experiment`, `rv-tools` | **0 parsers** — o consumidor nomeado não existe |
| `modules/aperv-tool/src/aperv_tool/analysis/coverage_dump.py:62-63` | lê **só** `[APE-RV] UICOV-ACT ` e `[APE-RV] UICOV `, que o estágio 4 explicitamente não toca (`rearch-04/design.md:38`) → **zero migração** |
| `modules/aperv-tool/src/aperv_tool/analysis/clock_logcat_join.py:63` | **o único parser real** de `[APE-STEP]` |
| `scripts/cmpm_stratify.py:22`, `scripts/analyze_cmpv2_llm.py`, `experimento-cal/scripts/*`, `experimento-20260721/scripts/*`, `calibracao/*` | leem traces **arquivados**, formato legado para sempre |

Comando: `grep -rln 'APE-STEP\|APE-OUTCOME\|APE-LLM-TEL\|APE-LLM-ERROR\|APE-MOP-DATA\|APE-RV\]' --include=*.py modules/ scripts/ experimento-cal/ experimento-20260721/ calibracao/` no repo `rv-android`.

**Ganho não-óbvio a carregar para o design**: `clock_logcat_join.py` gasta a maior parte da sua complexidade reconstruindo o offset UTC do device, porque o trace usa `System.currentTimeMillis()` enquanto o logcat carimba hora local sem ano e sem zona (três candidatos de ano, arredondamento para o quarto de hora mais próximo, escolha de âncora, `alignment_residual_ms`). A decisão do dono **D4** (heartbeat logcat `s=N t=…` via `Log.i`, default ligado) põe passo e violação no mesmo arquivo, mesmo relógio, mesma renderização — toda a reconstrução de offset vira código morto. Migrar esse módulo é **simplificação**, não porte.

### 3.4 O carve-out que precisa estar escrito no artefato

O relatório de calibração de 2026-07-24 e a corrida decisiva estão sobre traces **legados**. Se o leitor NDJSON substituir o parser velho no lugar, os corpora congelados ficam ilegíveis. O desenho correto: o leitor NDJSON é módulo **novo**; `clock_logcat_join.py` e os scripts de experimento ficam congelados como leitores do dataset arquivado.

**Isso não é violação de P3 e precisa estar dito no artefato, para que uma varredura P3 futura não os apague.** Eles não são shim de compatibilidade para dado novo — são os leitores de um dataset que nunca mais vai mudar. P3 governa *implementação* superseded, não código de análise de dado congelado.

### 3.5 Sítios — 41 linhas nomeiam o conversor diretamente

Comando: varredura Python por `converter|convert_to_legacy|trace_ndjson|NDJSON→legacy|legacy conversion|legacy line family|legacy format|current parsers|existing parsers`.

| Change | Arquivo | Linhas |
|---|---|---|
| `rearch-04` | `design.md` | 31, 76, 80, 99, 113, 175, **193, 195** (D-8), 218, 219, 272, 281, 288, 301, 307 |
| | `proposal.md` | 16, 31, 36 |
| | `tasks.md` | 7, 74 (título do grupo 8), 76, 77, 78, 84 |
| | `specs/aperv-tool/spec.md` | 5, 20, 23, 25, 29, 32, 35, 37, 68, 69 |
| `rearch-05` | `specs/aperv-tool/spec.md` | 142, 145, 147, 151, 154, 157, 159 |
| `rearch-07` | `specs/aperv-tool/spec.md` | 49, 65, 68, 101 |

`specs/event-sink/spec.md` está **limpo** (§2.1). A cadeia dos passos 11–12 que a sessão 6 encadeou tem de ser deletada nos três changes consistentemente; o que sobra do bloco de coleta é só o gzip (passo 10 no 04; passo 11 no 05 e no 07).

### 3.6 Consequência de escopo — a que não se pode contornar

O gate de aceitação **Sec. 9.11** ("o relatório de calibração de 2026-07-24 deve ser regenerável a partir do trace novo") é hoje satisfazível *via conversor*: `design.md:219` (*"Acceptance Sec. 9.11 | converter + new parser"*) e `tasks.md:84` (*"via the converter + existing parsers, and via a native NDJSON parse"*).

Com o conversor morto, **o leitor NDJSON nativo passa a ser entregável do estágio 4 e bloqueador do gate** — não "uma mudança posterior". O grupo 8 do `rearch-04/tasks.md:74-79` deixa de ser "conversor + gzip" e passa a ser "leitor NDJSON nativo + gzip + migração do `clock_logcat_join.py`", com o carve-out de §3.4 declarado. **Isto aumenta o escopo do estágio 4 e tem de ser escrito, não escondido.**

Observação menor, sem invenção de decisão: mantendo o passo de gzip como está, o estado pós-conversor é `.trace` = NDJSON cru + `<trace>.ndjson.gz`. É exatamente a aritmética que o próprio `design.md:195` projeta (o ganho de volume vem do `.trace` encolher 3–5×, não da eliminação do `.gz`). Nada a decidir aqui — registrado só para que a leitura do "storage" não pareça esquecida.

---

## 4. Falsos positivos verificados e dispensados

O inventário de partida marcava `rearch-01` e `rearch-03` como "só falsos positivos, mas verifique". Verificado:

| Sítio | Texto | Veredito |
|---|---|---|
| `rearch-01/design.md:70` | "P3 (nothing shimmed in…)" | afirmação explícita de P3 |
| `rearch-01/tasks.md:10` | "Spike test (temporary, refined into group 2)" | spike de investigação que vira o teste real do grupo 2 — não é estratégia de compatibilidade |
| `rearch-01/tasks.md:129` + `design.md:187-190` | "forked-surefire escape hatch for future non-default-Config presets" | é **alternativa considerada e deferida**, documentada num README (`design.md:188`: *"deferred; it buys nothing while the scripted router owns the only preset-divergent ladder-read key"*). Zero código, zero mecanismo — exatamente o que P1 pede |
| `rearch-03/design.md:196` | "`LlmRouter` **dies** (P3 — no shim, no delegating facade)" | afirmação explícita de P3 |
| `rearch-03/tasks.md:45` | "**Delete `LlmRouter`** (P3 — no facade)" | idem |
| `rearch-03/specs/llm-routing/spec.md:5` | "dismantled (P3 — complete deletion, no delegating facade)" | idem |
| `rearch-03/design.md:107` | "`[APE-STEP]`/`[APE-OUTCOME]`/`[APE-LLM-*]` lines are byte-compatible" | é o enunciado de **neutralidade de comportamento** do estágio 3 (não-goal: nenhuma mudança de formato), não uma promessa de compatibilidade com legado |

**`rearch-06-memory-surgical`: zero ocorrências nas duas passadas.** Os 19 hits da passada 2 são todos `backward scan` (direção de percurso) e `stale objects` (referências obsoletas em memória) — o assunto do change. É o único dos sete sem nada a discutir sob P3.

---

## 5. C1 — a única superfície de compatibilidade genuína (lacuna)

### 5.1 O que D-4 realmente cria

`rearch-02-runspec/design.md:147` — **D-4 "No-preset-key compatibility: stage 2 deploys against unchanged Python"** — e `tasks.md:61`, grupo 6, *"Python-contract compatibility (fixtures, no Python edits)"*.

**O mecanismo não é um shim.** `Presets.resolve(name)` devolve um vetor de chave/valor base, as chaves explícitas sobrescrevem por cima, e *"the result feeds the same validator"* (`design.md:138`; requisito em `specs/run-spec/spec.md:100`). Há **um** caminho de resolução; o preset é uma camada-base opcional. Não existe segundo parser, nem adapter, nem duplo formato.

**O que é transitório é outra coisa**, e é aí que está a lacuna:

1. **O requisito** `Explicit-Key Resolution When No Preset Is Named` (`rearch-02/specs/run-spec/spec.md:113-126`) está escrito **inteiro** em termos transitórios:
   - `:115` — *"When `ape.preset` is absent — **the case for the entire current Python deployment, which this change does not touch** — … `preset + overrides` becomes the Python-side contract only at stage 5"*;
   - `:123` — cenário literalmente chamado **"zero Python changes verified"**, cujo `THEN` é *"the four campaign arms SHALL run end-to-end with no modification to `APERV_PROPERTY_MAPPING`, the arm dicts, or `_push_properties`"*.

   Depois do estágio 5 esse cenário é **falso por construção**: o estágio 5 reescreve `_push_properties` (`rearch-05/tasks.md:18`, task 2.1).

2. **As fixtures e os testes** que fixam a saída pré-mudança do Python: `RunSpecCompatTest` e `PresetsTest` (`rearch-02/tasks.md:63-66`, grupo 6), com fixtures por arm reproduzindo o output de `_push_properties` dos 29 arms.

### 5.2 O defeito medido: ninguém mata nem um nem outro

`rearch-02/design.md:280` declara a morte: *"`RunSpecCompatTest` pins the four preset vectors against fixtures generated from the arm dicts at apply time; **stage 5 replaces the fixtures with the real contract**"*. E `tasks.md:65` repete: *"pins design D-3 **until stage 5**"*.

Mas:

- **`RunSpecCompatTest` tem 0 ocorrências em todos os artefatos do `rearch-05`** (varredura sobre `openspec/changes/**/*.md` por `RunSpecCompatTest|PresetsTest|Explicit-Key Resolution|no preset key`). O único change que remove seus próprios artefatos transitórios é o 05, e só os **dele** (`tasks.md:76`, task 9.6, deleta `test_arm_regeneration_diff.py` — feito corretamente).
- **O requisito não é tocado por estágio nenhum** (checagem 2: `run-spec :: Explicit-Key Resolution When No Preset Is Named` não aparece em colisão alguma). Ele sincroniza permanentemente para `openspec/specs/run-spec/spec.md` com a redação transitória intacta.

Contraste, para calibrar: o `rearch-05` trata a **sua** verificação transitória de forma exemplar — INV-APV-44 (`specs/aperv-tool/spec.md:40`) diz textualmente *"The check is one-time: after sign-off the test is deleted and the record archived — it MUST NOT survive as a standing constant-vs-constant guard"*, com a task 9.6 executando. O padrão certo existe no conjunto; só não foi aplicado à superfície do estágio 2.

### 5.3 O que sobra se o transitório for retirado

O caso "sem `ape.preset`" **não some**: a execução standalone nua (sem arquivo de properties) continua tendo de resolver a partir dos defaults do jar — é o que `design.md:166` sustenta (*"Defaults never activate a feature whose dependencies are unmet — the feature is simply absent (this is what keeps a bare standalone run valid)"*). Isso não é compatibilidade; é um default documentado. O que morre é o **enquadramento**: "o deployment Python atual", "zero Python changes verified", e as fixtures que congelam a saída pré-mudança.

### 5.4 Opções para o dono

| | O que faz | Custo |
|---|---|---|
| **(a) recomendada** | O `rearch-05` ganha (i) task deletando `RunSpecCompatTest`, `PresetsTest` e as fixtures por arm, substituídas por testes do contrato preset+overrides; (ii) um delta `run-spec` retirando o enquadramento transitório do requisito — o que sobra é "preset ausente ⇒ resolve das chaves explícitas e dos defaults do jar", sem os dois cenários transitórios | Alarga o escopo declarado do 05, que hoje diz *"all edits land in rv-android; the ape jar is **not** modified"* (`rearch-05/tasks.md:3`). Deletar teste não modifica o jar, mas edita o repo `ape` — o alargamento tem de ser explícito no artefato |
| (b) | Fundir ou recortar os estágios 2 e 5 para que o jar só aceite uma forma | O estágio 2 deixa de ser "zero Python changes", contrariando a Sec. 10 do relatório; vira cross-repo; e acopla a re-arquitetura Java ao `gh88-cal-llm-control` (47/58, parado desde 2026-07-24), hoje o único bloqueador vivo do estágio 5. **Não recomendada** |

---

## 6. Achados incidentais das checagens mecânicas

### 6.1 Requisitos órfãos que afirmam o formato legado (§2.3)

Quatro requisitos de `openspec/specs/` afirmam tags `[APE-*]` e **nenhum change `rearch-0*` os toca**:

| Capability :: Requisito | Linhas | O que afirma | Severidade |
|---|---|---|---|
| `llm-routing :: Deterministic Dead-Pair Ban` | 669, 671, 682 | ver §6.2 | **lacuna** |
| `wtg-navigation :: WTG Frontier Boost for Unvisited Activities` | 110 | parentético: gravar em `wtgBoost` é o que torna o ganho *"visible in the `[APE-STEP] … wtg=` telemetry field"* | cosmético |
| `llm-infrastructure :: ScreenshotCapture — Failure-Stage Cause Seam` | 454 | parentético: o estágio de falha viaja no `[APE-LLM-ERROR]` do router | cosmético |
| `llm-prompt :: Widget List Generation` | 126 | ver §6.3 | cosmético |

Os três cosméticos citam o **nome da renderização**, não a existência do dado: `wtg=` sobrevive como `dec.wtg` e a causa do screenshot como campo do sub-evento `llm[]` (tabela de mapeamento em `rearch-04/design.md:113` e `:129-136`). Corrigir é opcional e barato.

### 6.2 D1 — `Deterministic Dead-Pair Ban` (lacuna)

`openspec/specs/llm-routing/spec.md:629` em diante. Três problemas empilhados, e nenhum estágio o toca:

- `:669` — *"`StatefulAgent` SHALL report the outcome … **at the point where `new_state` is computed for the `[APE-OUTCOME]` line**, using the same single-shot buffered-decision discipline that guards `[APE-OUTCOME]` emission"*. É exatamente o mecanismo que a D-1 do `rearch-04` (`design.md:167`) reaproveita: o buffer de join vira o acumulador do registro e `outcome()` fecha a linha.
- `:671` — *"in **`selectAction()`** … the **router** SHALL compute the result's ban key … SHALL emit `[APE-LLM-TEL] result=no_match reason=dead_pair`"*. `selectAction()` e o `LlmRouter` **morrem no estágio 3** (`rearch-03/design.md:196`).
- `:682` — **cenário**: *"`[APE-LLM-TEL]` line SHALL carry `result=no_match reason=dead_pair`"*.

O dado sobrevive: a tabela do estágio 4 mapeia `result` → `no_match` e `reason` ∈ `dead_pair` no sub-evento `llm[]` (`rearch-04/design.md:129-130`). O que morre é a renderização que o requisito normatiza.

**Por que isto é desta sessão**: enquanto o conversor existia, `:682` continuava literalmente verdadeiro do `task.result.trace_file` — o conversor re-emitia `[APE-LLM-TEL]`. Sem conversor, não existe artefato algum com essa linha, e o cenário fica inverificável.

**Agravante independente**: o delta `llm-routing` do `rearch-03` opera em 7 requisitos (`REMOVED: LlmRouter Lifecycle`; `ADDED: LLM Unit Lifecycle and Ownership`, `Declared LLM Fallback`; `MODIFIED: New-State LLM Mode`, `Stagnation LLM Mode`, `Probabilistic LLM Routing`, `Action Selection Pipeline`) e o dead-pair não é nenhum deles, apesar de o change deletar a classe e o método que o requisito normatiza.

Dono natural: `rearch-04` (que já tem delta `llm-routing`), com a parte de `selectAction()`/router cabendo ao `rearch-03`.

### 6.3 D2 — `Tolerant Action-History Persistence` (lacuna; resíduo L1)

`openspec/specs/model/spec.md:205-230`. O requisito normatiza `Model.saveActionHistory` — *"SHALL resolve and write each `ActionRecord` inside a per-record guard"* (`:205`), com cenários exigindo que *"records 1–59 SHALL be written to `action-history.log` in order"* (`:222`) e que *"the produced `action-history.log` SHALL be **byte-identical** to the pre-change format"* (`:230`).

`rearch-04/tasks.md:67` (task 7.2) deleta `saveActionHistory()` e `Model.saveActionHistory` inteiros. **Nenhum estágio toca o requisito** — o `rearch-06` tem delta `model`, mas sobre outros requisitos.

É a mesma classe dos 11 achados L1 que a sessão 6 corrigiu; escapou porque a lista de mecanismos dela tinha `saveGraph`/`readGraph`/`sataModel.obj` mas não `saveActionHistory`. Aprovada como está, a main spec passaria a exigir, byte a byte, um arquivo que a implementação deixou de escrever. Dono natural: `rearch-04`.

### 6.4 D3/D4 — cosméticos

- Os três parentéticos de §6.1.
- **Resíduo do X2 da sessão 6**: `rearch-05/tasks.md:73, 74, 77` (tasks 9.3, 9.4, 9.7) ainda usam `/sdd-qa-lint-fix`, `/sdd-verify`, `/sdd-docs-sync` sobre `modules/aperv-tool`, que é rv-android — lá os skills se chamam `rv-*`. A sessão 6 corrigiu 1.8 e 8.6 e deixou estes três. (Lembrando a decisão permanente de 2026-07-31: não rodar skills `rv-*` sem o dono pedir.)

---

## 7. B1 — `sata_mop` não é "back-compat alias" (smell)

`rearch-05/specs/aperv-tool/spec.md:63`: *"`sata_mop` SHALL remain the **back-compat alias** of `sata_mop_widget`, bound to the same object"*, com o cenário "Alias preserved" em `:86-89` e a task 4.1 (`tasks.md:32`).

A pergunta honesta é: restrição de identidade de dado (legítima) ou compatibilidade de código (violação)? **Medido, nas duas pontas:**

```
# nomes de artefato congelado
find results experimento-cal experimento-e3-decisiva calibracao \
     experimento-20260604 experimento-20260721 -maxdepth 6 -name "*aperv:<arm>.trace" | wc -l

  sata_mop         4096   (3894 em results/aperv_precal_macro, 80+80 em cal_v2_valid_*, 25 em aperv_smoke_test, …)
  sata_mop_widget     0
  sata               0
  bfs                0
  ape_pure           0
  dfs                0

# valor de coluna nos CSVs consolidados
1066 arquivos sob results/ contêm o token exato `sata_mop`
  ex.: results/gh53_smoke_dexlib2/performance.csv
       cryptoapp.apk,1,60,aperv:sata_mop,103,TaskState.COMPLETED,1777659715.512281
```

Enumeração completa dos arms que já produziram dado (`… -name "*aperv:*.trace"`, agregado): `sata_mop` 4096 · `sata_mop_llm_v13` 1094 · `mop_on_llm_70` 126 · `mop_on_llm_off` 123 · `mop_off_llm_off` 123 · `cal_a1/a3/a8` 84 cada · `sata_mop_act_frontier` 80 · `cal_a2/a4/a5/a6/a7/a9` 80 cada.

**Veredito: legítimo — é restrição de identidade de dado, e a redação inverte qual nome é o primário.** `sata_mop` é o nome de arm mais usado de todo o corpus congelado, em nome de arquivo **e** em coluna de consolidação; `sata_mop_widget` nunca produziu um artefato. Chamar `sata_mop` de "back-compat alias" do nome que nunca rodou é factualmente ao contrário.

Correção: trocar a frase por uma que diga a razão real — o nome do arm é a chave de identidade de resume e a chave de coluna de consolidação de dado congelado (4.096 traces, 1.066 CSVs), e por isso não pode ser renomeado — dropando "back-compat".

**Efeito colateral útil**: isto fecha o item que a sessão 6 deixou aberto em §6.1 (*"não verifiquei se `bfs` chegou a rodar em alguma campanha"*). **`bfs` nunca rodou** — 0 artefatos, assim como `ape_pure` e `dfs`. A aposentadoria dos dois não custa identidade de dado nenhuma, o que é a assimetria exata que justifica dispor de `bfs` e de `sata_mop` de formas diferentes.

---

## 8. B2/B3/B4 — legítimos com redação ruim (cosméticos)

### 8.1 B2 — `rearch-07`, o "parser duplo"

`tasks.md:30` (cabeçalho do grupo 3): *"Jar-side rewrite, **dual-parser stage** (ape: old parser still present)"*; `tasks.md:33` (task 3.2): *"Implement the compact-format parser as new code paths in `MopData` (**temporarily beside** the full-JSON parser)"*.

Contra `design.md:99` (*"No transitional dual-format support in the jar (P3: single coordinated cut, no adapters)"*) e D8 (`:132`, *"Single coordinated cut; no fallback window"*).

**Não é contradição real.** O parser velho é o **oráculo do gate de equivalência**: `tasks.md:42` (*"old parser on full JSON vs new parser on derived artifact"*) e `:43` (*"the old parser is the oracle — never adjust the oracle"*). Ele é deletado pelo grupo 5 (`tasks.md:48`, task 5.1) e os grupos 3+5 pousam **num commit só** (`tasks.md:65`, task 7.1: *"Land the ape commit (Groups 3+5) and the rv-android commit (Groups 2+6) together"*). O próprio D8 já diz exatamente isso: *"the corpus equivalence gate (**run pre-cutover with both parsers in-tree**) is the safety mechanism"* (`design.md:134`).

Ou seja: os dois parsers coexistem no *tree de trabalho*, nunca num estado pousado, e nunca no jar entregue. **Veredito: legítimo.** Só a redação lê como janela de fallback. Correção: nomear o que é — o andaime do oráculo de equivalência, que nunca é entregue e morre no mesmo pouso.

### 8.2 B3 — `formatVersion` e "forward compatibility"

`rearch-07/specs/mop-guidance/spec.md:50`: *"Unknown JSON keys within a supported `formatVersion` are ignored **for forward compatibility** (INV-MOP-11)"*.

Duas verificações:

1. **INV-MOP-11 já existe, idêntico, na main spec**: `openspec/specs/mop-guidance/spec.md:34` — *"Unknown JSON keys are ignored for forward compatibility (INV-MOP-11); the parser reads the file once into an `org.json` DOM (design D21)"*. A tolerância é o **comportamento default do DOM**; ela é a *ausência* de uma checagem. Exigir rejeição estrita seria **adicionar** código, não remover. Não há mecanismo legado a matar.
2. **`formatVersion` não é mecanismo de compatibilidade — é sentinela de rejeição.** `specs/mop-guidance/spec.md:40`: *"The version gate **replaces the `"complete": true` sentinel** of the full-JSON era (INV-MOP-09)"*, e INV-MOP-34 (`:28`) manda rejeitar *"including a legacy full static-analysis JSON"* com `reason=version-mismatch`. É fail-fast (D6), não tolerância.

**Veredito: legítimo; só a justificativa é linguagem de compatibilidade especulativa.** Correção mínima: reescrever a justificativa — nenhuma checagem estrita é adicionada; a tolerância é o default do parser, e o corte coordenado (D8) faz de uma chave desconhecida um sinal de skew gerador↔jar.

**Opção para o dono, se quiser ir além**: endurecer para rejeitar chave desconhecida, alinhando com a disciplina fail-fast de D6 (nenhum estado de entrada em que uma corrida MOP-planejada siga silenciosamente). Custo: código novo no `MopData.load`, e o cenário `:85-87` ("Unknown keys in a v1 artifact are ignored") inverte de sentido. É mudança de comportamento, não limpeza de P3 — por isso fica como opção, não como correção.

### 8.3 B4 — texto morto pela ordem dos estágios

`rearch-07/specs/mop-guidance/spec.md:21` e `:156`: *"the `[APE-MOP-DATA]` status line **until the `rearch-04` NDJSON sink lands**; the `MOP_DATA` NDJSON record thereafter"*.

O `rearch-07` é o estágio 7; o `rearch-04` é o 4 (roadmap `:31,46`). Quando o 07 aplica, o sink já pousou. A alternativa pré-04 é texto morto — que sincronizaria para a main spec como afirmação dupla **viva**. **Cosmético**: enunciar só a forma pós-04.

---

## 9. O que ficaria por decidir

1. **C1** — opção (a) ou (b) de §5.4. Recomendação: (a).
2. **B3** — manter a tolerância a chave desconhecida (só reescrever a justificativa) ou endurecer para rejeição estrita (§8.2). Recomendação: manter e reescrever.
3. **D1/D2** — se os dois requisitos órfãos entram nesta rodada de correção ou viram item próprio. Ambos são baratos e o dono natural é o `rearch-04`; o D1 é criado *como problema verificável* por A1, o D2 é resíduo independente.
4. **D3/D4** — cosméticos opcionais.

Nada em §§3–8 exige rediscutir arquitetura, e nada bloqueia a implementação do `rearch-01`, que segue independente de todos os achados.

---

*Documento de verificação. Nenhum artefato OpenSpec foi editado, nenhum código foi alterado, nenhuma implementação foi iniciada (0/299 tarefas). Toda afirmação factual acima cita `file:line`; as afirmações quantitativas indicam o comando ou o script que as produziu (§2, §3.3, §3.5, §7).*

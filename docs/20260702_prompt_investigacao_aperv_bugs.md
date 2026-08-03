# Prompt — Investigação completa, profunda e rigorosa do APE-RV (aperv): código, dados estáticos e observabilidade

> Cole este prompt numa nova sessão **aberta no repositório `ape`**
> (`/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape`).
> Use SEMPRE caminhos absolutos ao referenciar qualquer arquivo fora deste repositório
> (ex.: o repo vizinho `rv-android`, ou o worktree de mudança em andamento citado abaixo).

---

## 0. O que esta investigação É e NÃO é

**NÃO é** uma revisão de code-review da change em andamento (worktree `mop-fairtest`, ver §5).
Essa change é só **um dos objetos** de investigação, não o objeto principal — ela nasceu de uma
análise prévia (`docs/20260622_investigacao_mop.md`) que cobriu uma fatia específica do
problema (scoring MOP + seleção de ação) a partir de traces de UMA corrida. É plausível que
haja **muita coisa não-detectada ainda**, tanto dentro dessa fatia quanto — principalmente —
fora dela.

**É** uma investigação completa, profunda e rigorosa do projeto aperv como um todo, nos
mínimos detalhes, cobrindo três frentes independentes que se reforçam:

1. **O código-fonte** do aperv inteiro (não só os arquivos tocados pela change em andamento).
2. **Os dados de entrada** — o formato/schema do `<apk>.json` de análise estática (o que ele
   promete entregar, o que realmente entrega, onde a ponte código↔dado é frágil).
3. **A observabilidade** — se o log semi-estruturado `.trace` do aperv, do jeito que existe
   hoje, é **suficiente para reconstruir depois, com confiança, o que exatamente aconteceu
   numa execução** e rastrear bugs/anomalias a partir dele. Se não for, isso É um achado
   (uma lacuna de instrumentação), não um detalhe secundário.

O objetivo real do projeto, que deve nortear a priorização de tudo que você encontrar:
**maximizar (a) cobertura de UI, (b) cobertura de métodos/classes/operações MOP alcançadas,
e (c) o número de violações MOP distintas encontradas pelos monitores JavaMOP em runtime.**
Classifique cada achado por qual dessas métricas ele afeta (ou "geral/qualidade" se nenhuma).

**Regra mais importante:** não se prenda apenas aos dados de execução (traces/logs) de
investigações anteriores. Eles descrevem UMA corrida específica, com um critério de medição
específico, e podem estar desatualizados, incompletos ou enviesados. Verifique o código-fonte
e os dados **diretamente, com ceticismo**. Traces/relatórios anteriores são só *pistas de onde
olhar*, nunca prova. Ative-se a refutar, não só a confirmar.

**NÃO IMPLEMENTE NADA.** Análise apenas. Não edite o repositório principal. Pode ler (não
editar) o worktree de mudança em andamento.

---

## 1. Contexto do projeto

Leia primeiro `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/CLAUDE.md`
— arquitetura, fluxo de teste (Monkey → MonkeySourceApe → Agent → Model → Naming), mapa de
pacotes, flags de configuração centrais.

Documentos de contexto histórico (leia com ceticismo — são ponto de partida, não verdade):
- `docs/20260622_prompt_investigacao_mop.md` — prompt de uma investigação anterior, focada
  em traces de uma corrida específica (`cmpmop`).
- `docs/20260622_investigacao_mop.md` — o resultado dessa investigação: concluiu que o boost
  MOP era inerte por (1) colapso do substrato estático discriminativo e (2) mecanismo de boost
  fraco (uniforme, diluído por seleção por roleta). Propôs um plano de correção (`#0`-`#4`, `W`).
- `docs/analise_*.md`, se existirem (ex. `docs/analise_claude.md`) — rodadas de análise
  estática anteriores. **Não repita achados já catalogados lá como se fossem seus** —
  confirme-os rapidamente se quiser, mas concentre a maior parte do esforço em achar coisas
  **novas**, especialmente fora do recorte MOP/SATA que essas análises já cobriram bem.

---

## 2. Frente 1 — Código-fonte completo do aperv (a maior parte do esforço vai aqui)

Base do pacote: `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/src/main/java/com/android/commands/monkey/ape/`

Cubra o pacote **inteiro**, não só os arquivos relacionados a MOP. Trate cada pacote como
merecedor de uma investigação própria e completa:

| Pacote/arquivo | Conteúdo | Por que importa |
|---|---|---|
| `agent/` | `StatefulAgent`, `SataAgent`, `RandomAgent`, `ReplayAgent`, `ApeAgent`, `StatefulAgent` | Núcleo de decisão — todo bug aqui afeta as 3 métricas do objetivo diretamente |
| `naming/` | `Naming`, `Namer` (Text/Type/Index/Xpath/...), `NamingFactory`, `Namelet`, `AbstractNamingManager`, `StateNamingManager`, `ActivityNamingManager` | A inovação central (abstração/refinamento) — mais complexo, historicamente menos auditado que MOP |
| `model/` | `Model`, `State`, `StateTransition`, `Action`/`ModelAction`, `Graph`, `ActivityNode` | O grafo de exploração — bugs aqui corrompem silenciosamente a memória do que já foi visitado, inflando ou reduzindo cobertura sem nenhum sintoma visível no curto prazo |
| `tree/` | `GUITree`, `GUITreeNode`, `GUITreeBuilder` | Captura de tela → é a fonte de verdade de "o que existe na UI"; bug aqui distorce tudo rio abaixo |
| `events/` | `ApeClickEvent`, `ApeDragEvent`, `ApeKeyEvent`, `ApeFuzzer`, etc. | Geração de eventos reais no dispositivo — bug aqui pode silenciosamente não executar a ação pretendida |
| `utils/` | `Config`, `MopData`, `MopScorer`, `UICoverageTracker`, `Logger`, `RandomHelper`, `Utils` | Scoring MOP (já parcialmente auditado, mas confirme com ceticismo) + tudo mais |
| `llm/` | `SglangClient`, `LlmRouter`, `ApePromptBuilder`, `ToolCallParser`, `ImageProcessor`, `ScreenshotCapture`, `CoordinateNormalizer`, `LlmCircuitBreaker` | Baixa prioridade (fora do foco SATA/MOP declarado) — só reporte achados óbvios de crash/segurança, não invista tempo extenso aqui |
| raiz `com.android.commands.monkey` | `MonkeySourceApe` (orquestração principal, ponte Agent↔fila de eventos do Monkey), `Monkey` (entrypoint) | Se este componente falhar, a execução inteira morre — merece leitura completa |
| `reducer/` | Minimização de casos de crash | Baixa prioridade, mas cheque rapidamente por bugs óbvios |

Para CADA arquivo relevante: leia o arquivo **inteiro**, não trechos. Rastreie os caminhos de
execução mais quentes (por-passo: captura de tela → naming → scoring → seleção de ação →
execução do evento → atualização do modelo). Procure especificamente por:
- Null-pointer / índice fora de limites / exceptions não tratadas em caminho quente.
- Lógica invertida (nome do método promete X, implementação faz o oposto — ex.: um `isX()`
  que retorna o contrário do esperado).
- Parsers/comparações frágeis: `indexOf`/`substring` sem validação, uso incorreto de
  `binarySearch` (retorna qualquer valor negativo em caso de "não encontrado", não só `-1`),
  sentinelas ambíguos (`""`, `-1`, `null`) representando mais de um caso distinto.
- Efeitos colaterais perdidos: self-assignment (`x = x` em vez de `x = y`), `return`/`break`
  faltando, coleção nunca populada, evento construído mas nunca enfileirado, boost calculado
  mas nunca somado.
- Duas partes do código que deveriam derivar a mesma chave/decisão e usam heurísticas
  diferentes (ex.: uma função usa um helper compartilhado de "activity base", outra faz
  substring manual).
- Estado global mutável compartilhado indevidamente — o loop é single-threaded por design;
  qualquer padrão que pareça assumir concorrência (ou a ausência de sincronização onde
  deveria haver) é suspeito.
- Flags de `Config.java` lidas mas nunca conectadas a comportamento real, ou documentadas em
  `CLAUDE.md`/specs mas ausentes do código (e vice-versa) — dead config é sinal de deriva.
- Lógica de saturação/parada da exploração (`Graph Stable Counter`, contadores de saturação,
  thresholds hardcoded) — thresholds que nunca resetam ou que desistem cedo demais reduzem
  diretamente a métrica (a) cobertura de UI.

---

## 3. Frente 2 — O formato/schema dos dados de análise estática (`<apk>.json`)

O aperv consome um arquivo `<apk>.json` (produzido por uma ferramenta externa, fora deste
repo) para o scoring MOP-guiado. Você tem um exemplar real disponível para inspeção:
`/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/test-apks/cryptoapp.apk.json`
(se não existir nesse caminho exato, procure por `find /home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/test-apks -name "*.json"`).

Investigue:
1. **Fidelidade schema↔parser:** abra o JSON real e compare campo a campo com o que
   `MopData.java` (`load`, `parseWindows`, e helpers relacionados) espera. Existe algum campo
   presente no JSON que o parser ignora silenciosamente? Algum campo que o parser espera mas
   que pode estar ausente/nulo no JSON real, causando fallback silencioso para "sem MOP" em
   vez de erro explícito?
2. **Robustez a variação de schema:** o parser lida bem com listas vazias, objetos aninhados
   ausentes, tipos inesperados (string onde espera número, etc.)? Alguma exceção de parsing
   descarta o resultado inteiro (inclusive dados parcialmente válidos) em vez de degradar
   graciosamente?
3. **Vocabulário `Target` vs `MOP`:** o javadoc de `MopData` documenta que o JSON usa
   vocabulário `Target` (`reachesTarget`/`directlyReachesTarget`/`targetMethods`) e o modelo
   Java interno usa vocabulário `MOP`. Essa fronteira está mesmo isolada onde o javadoc diz que
   está, ou vaza para outros lugares do código (nomes de variável, comentários, testes)
   incoerentemente?
4. **Ambiguidade de identidade:** como o JSON identifica um widget (idName, resourceID
   completo, classe, texto, posição)? Essa identidade é estável o suficiente para casar de
   forma confiável com o `resourceID`/`className` reportado pelo `AccessibilityNodeInfo` em
   runtime? Onde especificamente essa ponte pode falhar silenciosamente (nomes ofuscados,
   IDs dinâmicos, Compose sem resourceID)?
5. Se você tiver acesso a mais de um `<apk>.json` (ex. no repo vizinho `rv-android`, caminho
   absoluto se existir: `/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/rvsec/rv-android/`),
   compare a estrutura entre alguns exemplos para achar variação de schema que o parser possa
   não cobrir (campos opcionais que aparecem só em alguns arquivos, etc.). Não é obrigatório
   ter acesso a esse repo — se não tiver, documente isso como limitação.

---

## 4. Frente 3 — O log semi-estruturado `.trace`: é suficiente para diagnosticar depois?

O aperv emite um log de texto semi-estruturado (linhas `[APE] *** INFO *** ...`,
`[APE-STEP]`, `[APE-RV] MOP boost`, `Create state`, `GSTG`, etc. — ver
`docs/20260622_prompt_investigacao_mop.md` §5 para o formato já catalogado) que é a única
fonte pós-hoc para reconstruir o que aconteceu numa execução (não há replay determinístico
disponível sempre). A pergunta central desta frente: **esse log, do jeito que está hoje,
contém informação suficiente para, olhando só para ele depois, confirmar com confiança
hipóteses de bug/anomalia — sem precisar reinstrumentar e re-rodar?**

Investigue lendo o código de logging (procure por `Logger`/`log(...)`/`println` relacionados a
decisão de ação, scoring, naming, transições de estado — grep por `"[APE"` e `"[APE-STEP]"`
e `"[APE-RV]"` no código-fonte para achar todos os pontos de emissão) e responda:
1. **Cobertura de decisão:** `[APE-STEP]` loga a prioridade e os componentes de boost da ação
   **escolhida**, mas loga as ações **candidatas** (não escolhidas) da mesma tela em algum
   lugar? Sem isso, não dá pra reconstruir se o boost realmente mudou o argmax — é possível
   confirmar isso sem re-rodar com instrumentação extra?
2. **Cobertura de causalidade:** existe alguma forma de, só pelo log, distinguir "a ação foi
   escolhida por causa do boost" de "a ação tinha o maior boost mas foi escolhida por outro
   motivo" (ex. busca em grafo, backtrack, short-circuit de Back/Menu)? Se a mudança em
   andamento (§5) adicionou uma telemetria de `decision_source`, ela realmente fecha essa
   lacuna ou ainda é uma correlação, não uma prova de causalidade (veja §5 abaixo)?
3. **Cobertura de estado/UI:** dá pra reconstruir, só do log, quantos widgets existiam numa
   tela vs. quantos foram de fato interagidos (cobertura intra-tela), por tipo de widget? Ou
   isso precisa ser inferido indiretamente (contando ocorrências de `[APE-STEP]` por
   `resource-id`), com risco de erro?
4. **Cobertura de violações MOP:** o log do aperv (`.trace`) e o logcat (`RVSEC`/`RVSEC-COV`)
   são a fonte de violações MOP — investigue se há qualquer perda de informação entre "o
   monitor JavaMOP detectou uma violação em runtime" e "isso aparece de forma inequívoca e
   contável no log". Existe risco de deduplicação incorreta, truncamento, ou perda de eventos
   sob alta frequência (rate limiting de log, buffer overflow do logcat)?
5. **O que falta:** proponha, concretamente, que linhas de log (novas ou modificadas) tornariam
   possível, numa investigação futura, responder as perguntas de §2-§4 **sem precisar
   reinstrumentar o código de novo**. Priorize instrumentação que seja barata (sem overhead de
   performance no caminho quente) — não proponha logar tudo, proponha o mínimo que fecha as
   lacunas de causalidade/cobertura que você identificou.

---

## 5. Frente 4 — Avaliação da mudança em andamento (worktree, leia mas não edite)

Isto é **secundário** às Frentes 1-3, mas ainda deve ser feito com o mesmo rigor:

```
/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest
```

Branch `mop-fairtest`, aponta para o mesmo commit de `master` — mudanças **apenas no working
tree, não commitadas, não compiladas, não testadas em dispositivo**. Rode (read-only):

```bash
cd /home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest
git status
git diff
```

Implementa (ver `openspec/changes/mop-parser-fidelity/`, `openspec/changes/mop-discriminative-boost/`,
`openspec/changes/form-completion/`, `openspec/changes/exploration-observability/` dentro do
worktree para o design pretendido):
1. **Parser fidelity** — conserta descarte de widgets flagged em `MopData.parseWindows`.
2. **Boost discriminativo** — remove fallback uniforme +100/activity em `MopScorer.score`;
   short-circuit greedy para alvo MOP não-visitado em `SataAgent`.
3. **Telemetria `decision_source`** — atribui MOP/WTG/Coverage/Menu quando esse mecanismo teve
   o maior boost na ação escolhida, em ramos que leem prioridade.
4. **Dump de `UICoverageTracker`** — observabilidade por-state no teardown.
5. **`FormCompletion.java` (novo)** — preencher todos os EditText antes de priorizar submit.

Avalie com o mesmo ceticismo do resto: ela corrige de fato o que alega (rastreie o caminho de
execução)? Introduz bugs novos? Os testes novos exercitam o comportamento real ou são
fracos/tautológicos? Está consistente com os specs OpenSpec em
`.../ape-mop-fairtest/openspec/specs/`? E — cruzando com a Frente 3 — a telemetria nova
(`#3`/`#4`) realmente fecha as lacunas de observabilidade que você identificou lá, ou fecha só
parcialmente?

Pontos específicos a checar:
- O short-circuit de MOP em `SataAgent` interage com o de Back/Menu já existente e com o novo
  de `FormCompletion` — a ordem de precedência entre os três está correta e documentada?
- `FormCompletion`: a heurística de "candidato a submit" pode escolher o botão errado? Falha em
  UIs Compose/AndroidX (`getClassName()` raramente contém `"Button"`)?
- A convergência do laço "preencher todos → submeter" depende de a identidade do widget
  (resourceID/xpath) ser estável entre capturas de GUITree consecutivas — isso é garantido
  pelo código ou é suposição não verificada?
- A atribuição de `decision_source` é uma alegação de causalidade — algum ramo de seleção
  (busca em grafo, backtrack) pode escolher a ação por outro motivo mesmo quando o boost é o
  maior componente da prioridade final?

---

## 6. Confronto com o objetivo declarado e priorização final

1. Para **cada** achado das Frentes 1-4, classifique explicitamente: qual das três métricas do
   objetivo (cobertura UI / cobertura de métodos-classes-MOP / violações MOP encontradas) ele
   afeta, e como (diretamente vs indiretamente vs não afeta — só qualidade geral).
2. Caçe especificamente por qualquer mecanismo, em qualquer lugar do código (não só MOP), que
   **limite artificialmente** o que é contado/reportado mesmo que a exploração o alcance
   (deduplicação agressiva, caps em contadores, filtros que descartam eventos legítimos,
   thresholds hardcoded de saturação/parada). Esse tipo de bug mascara progresso real e é
   fácil de não perceber olhando só pra métricas agregadas.
3. Rankeie TODOS os achados por impacto×esforço, com colunas: métrica do objetivo afetada,
   severidade, confiança (rastreado end-to-end vs suspeita), NOVO vs já documentado. Separe em:
   (i) bugs que bloqueiam a validade de qualquer experimento futuro (inclusive o teste justo da
   change em andamento) — corrigir primeiro; (ii) débito técnico geral, independente de MOP;
   (iii) lacunas de observabilidade (Frente 3) — propostas concretas de instrumentação; (iv)
   propostas novas de melhoria que ninguém tinha levantado antes.

---

## 7. Como investigar (paralelismo / subagentes / raciocínio estruturado)

Esta é uma investigação grande — não tente cobrir tudo sequencialmente num único fio de
raciocínio. Se sua ferramenta suportar múltiplos agentes/subagentes em paralelo, use-os
extensivamente. Sugestão de divisão (ajuste à sua ferramenta):
- Um agente por pacote da tabela em §2 (pelo menos: `agent/`, `naming/`, `model/`, `tree/` +
  `events/`, `utils/` incluindo MOP).
- Um agente dedicado à Frente 2 (schema JSON↔parser).
- Um agente dedicado à Frente 3 (suficiência do log `.trace`).
- Um ou mais agentes para a Frente 4 (a change em andamento), só depois que os anteriores
  já tiverem varrido o restante — não deixe a change em andamento consumir a maior parte do
  orçamento de investigação.

Instrua cada subagente a: ler arquivos **inteiros**; verificar `master` antes de comparar com
o worktree; ser ativamente cético (tentar refutar qualquer alegação de correção, inclusive as
deste prompt e as de investigações anteriores); reportar achados com `arquivo:linha`,
descrição de uma frase, severidade (bloqueante/alta/média/baixa/observação), confiança, e se é
achado NOVO ou já documentado em `docs/20260622_investigacao_mop.md`/`docs/analise_*.md`.

Se disponível, use uma ferramenta de raciocínio estruturado ("sequential thinking" ou
equivalente) para: (a) planejar a divisão de cobertura antes de disparar os subagentes,
evitando sobreposição e lacunas; (b) sintetizar os achados no final — deduplicar,
resolver contradições entre agentes, decidir o que é de fato bloqueante vs. observação.

---

## 8. Comandos úteis

```bash
# ver a mudança em andamento (não editar, apenas ler)
cd /home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest
git status
git diff -- src/main/java
git diff -- src/test/java

# rodar os testes existentes no repo principal (master) — não afeta o worktree
cd /home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape
mvn -q test

# rodar os testes no worktree (compila o código não-commitado; NÃO faça commit)
cd /home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape-mop-fairtest
mvn -q test

# inspecionar um exemplar real de <apk>.json (Frente 2)
find /home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/test-apks -name "*.json"

# achar todos os pontos de emissão do log semi-estruturado (Frente 3)
grep -rn "\[APE" /home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/src/main/java/com/android/commands/monkey/ape/ | grep -iE "log|println"
```

---

## 9. Entregável

Escreva um memo detalhado em:
`/home/pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape/docs/analise_<NOME_DO_MODELO>.md`

(substitua `<NOME_DO_MODELO>` pelo nome do seu modelo/família, ex. `analise_gemini.md`,
`analise_codex.md`, `analise_qwen.md`, `analise_minimax.md` — não sobrescreva o arquivo de
outro modelo se já existir um com esse nome).

O memo deve conter, no mínimo:
1. **Catálogo de bugs/anomalias no código** (Frente 1, §2) — arquivo:linha, severidade,
   confiança, novo vs já conhecido. Cubra o pacote inteiro, não só MOP/SATA.
2. **Achados sobre o schema `<apk>.json`** (Frente 2, §3) — divergências schema↔parser,
   ambiguidades de identidade de widget, robustez a variação.
3. **Avaliação da suficiência do log `.trace`** (Frente 3, §4) — o que dá e o que não dá para
   reconstruir hoje, e uma proposta concreta (mínima) de instrumentação adicional.
4. **Avaliação da mudança em andamento** (Frente 4, §5) — corrige o que alega? Riscos
   residuais? Pronta para o experimento de validação (§7.5 de `docs/20260622_investigacao_mop.md`,
   se consultado) ou há bloqueadores primeiro?
5. **Mapeamento explícito ao objetivo** (§6.1) — cada achado rotulado com a métrica que afeta.
6. **Lista priorizada de próximos passos** (§6.3) — bloqueadores, débito técnico geral,
   lacunas de observabilidade, propostas novas — cada uma com o experimento mínimo (se houver)
   para validá-la.
7. Uma seção final de **limitações** — o que você não conseguiu verificar (precisa rodar em
   dispositivo, precisa de mais exemplares de `<apk>.json`, precisa de mais tempo/agentes,
   etc.).

Seja honesto sobre incerteza: prefira "não consegui confirmar se X dispara em runtime" a
inventar uma conclusão. O valor está em achados **verificáveis no código/dados**, cobrindo o
projeto inteiro — não em replicar as conclusões dos documentos anteriores nem em focar
desproporcionalmente na change em andamento.

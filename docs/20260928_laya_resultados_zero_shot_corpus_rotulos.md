# APE-RV / branch `laya` — resultados: Laya zero-shot, corpus próprio e escolha do rótulo de treino

Data: 2026-09-28 · Branch: `laya` (a partir de `master` e93dea86) · Status: **análise concluída, nenhum código do jar alterado**
Continua: `docs/20260928_laya_analise_plano.md` (análise e plano iniciais).
Material: `docs/laya/` — `NOTES.md` (diário), `scripts/` (todos os scripts, reprodutíveis com `uv`), `results/` (saídas).
Área de trabalho original (dados grandes, fora do repositório): `/home/pedro/tmp/laya` (`corpus/steps.jsonl`, 4,6 GB).

---

## 1. Resumo executivo

1. **Laya zero-shot não serve como selecionador de ações.** Casa palavras entre estado e opções; ignora histórico e
   negação; tem viés de posição; não prevê quais ações levam a tela nova (AUROC 0,45–0,50). Pontos bons: 27–29 ms por
   decisão (RTX 5070 Ti) e confiança monotônica com a acurácia. **O valor depende inteiramente de fine-tune.**
2. **Nossos próprios traces bastam como dados de treino.** 2.042.243 passos de SATA/MOP em 167 apps, **cada passo com a
   lista completa de candidatas**, a escolhida, a estratégia e a transição. Só 2 desses apps estão entre os 163 de
   avaliação → treino e avaliação sem vazamento. Rico e MobileViews descartados (imitação de humanos / do DroidBot).
3. **Rótulo recomendado: graduado por desfecho (opção F).** `new_state` (tela nova) mede histórico — é o trabalho do SATA
   — e foi descartado. `effect`, `act_change` e `mop_act` são propriedades estáveis da ação (91–99% de repetição do
   desfecho), e compõem os níveis 0–3. ~72 mil estados com contraste em 153 apps.
4. **Formulários (todos os tipos de componente)**: dependência real mas pontual no corpus (teste pareado: 80 botões
   melhoram, 92 pioram, 1.580 iguais). Só o valor do EditText aparece nos traces.
5. **Uso no aperv**: Laya como *prior* somado à pontuação do SATA, com amostragem (nunca argmax) — lição do Qwen v2.

---

## 2. Ambiente

`/home/pedro/tmp/laya` — projeto `uv` (Python 3.12), `laya[serve]==0.3.21`, torch 2.14+cu130 (sm_120 OK).
Checkpoints: `english` (ModernBERT-large, 512 tok), `typed-decisions` (1024), `multilingual` (mmBERT-base, 1024).
Primeira chamada 15–23 s (carga); depois 22–30 ms.

## 3. Laya zero-shot

### 3.1 Sonda de uma tela (`probe1.py`, `probe2.py`) — SimpleTextCrypt, tela de bloqueio ("Default passcode is: 1111")

- Todos os checkpoints escolhem "digitar no campo de senha" (certo como 1º passo).
- Estado "já digitei 1111" → p(digitar) sobe de 0,94 para 0,99 (sem noção de ordem).
- "O botão UNLOCK está quebrado, saia" → escolhe UNLOCK (0,48–0,97): negação ignorada (issue #377 do Laya).
- Estado vazio → cada checkpoint escolhe uma opção diferente. `act_probability` sempre 1,0.
- **Implicação**: nunca colocar histórico textual no estado (mencionar uma ação a reforça); anti-repetição por
  mascaramento de opções.

### 3.2 Exp A — grounding com gabarito (`batch.py A`), study03 E0: 636 alvos não ambíguos, 28 apps

A instrução nomeia o alvo ("Tap the "OK" button"); opções = candidatas acionáveis da tela.

| N opções | aleatório | lexical | english | typed-decisions | multilingual |
|---|---|---|---|---|---|
| 5 | 0,20 | 0,975 | 0,71 | 0,74 | 0,45 |
| 10 | 0,10 | 0,97 | 0,46 | 0,47 | 0,46 |
| 20 | 0,05 | 0,97 | 0,20–0,22 | 0,23–0,34 | 0,29–0,38 |
| 40 | 0,03 | 1,00 | 0,00 / 0,13* | 0,04 / 0,29* | 0,04 / 0,21* |
| todas | 0,21 | 0,96 | 0,58 | 0,60 | 0,45 |

\* com `head_max_len=768`. Confiança (typed-decisions): conf ≥ 0,4 → acurácia ≥ 0,93. Latência p50 27–29 ms.

### 3.3 Exp B — 2.679 decisões reais do Qwen3-VL (study03 E5/E5b/E5c) reprocessadas sem screenshot (`batchB.py`)

Concordância com o Qwen 0,20 (acaso 0,12). AUROC de p_Laya(escolha do Qwen) → estado novo: 0,47 / 0,50 / 0,45.
A regra trivial "não testada" separa: estado novo 0,19 vs 0,08.

### 3.4 Exp C — modo exploração, 427 telas × 4 embaralhamentos (`batch.py C`)

Mesma escolha nas 4 ordens: english 0,40, multi 0,30, typed 0,45. Argmax no 1º quartil da lista: multi 0,49,
english 0,34. EditText escolhido 3–4× acima da taxa-base. Concordância entre checkpoints 0,40–0,57 (acaso 0,21).

## 4. Pesquisa de apoio (subagentes)

- **Laya/Jev**: todas as opções numa só sequência (`[MASK]` por opção), <~20 opções recomendado, fine-tune aceita
  **distribuição-alvo suave** por opção; precedente `laya-browser`: 0,10 → 0,66 top-1 com ~14k amostras; treino local
  em 16 GB viável (bf16, micro-batch 4 × 1024, ~45 min/10k itens em 4 épocas); `Router(models=...)` aceita só as chaves
  embutidas (mapear `english`/`multilingual` para o checkpoint local); `laya-serve` não carrega checkpoint local
  (script próprio com `create_app(router=...)`). Jev: API fechada da TypeSafe.
- **SOTA** (atualiza `rv-android/docs/20260721_sota_llm_gui_testing.md`): híbridos vencem (LLMDroid, HybridMonkey,
  GraphDroid); pontuar candidatos > gerar (V-Droid, MindAct); treino pareado > imitação; memória em esquema fixo;
  split por app; baseline lexical obrigatório. Nenhum seletor aprendido usa alcançabilidade estática para APIs
  monitoradas — nicho em aberto.
- **Fine-tune anterior do Qwen** (`rvsec-fine-tuning`, `rv-android/docs/20260718_cmpmodels.md`,
  `20260729_reanalise_base_v2_llm_tap.md`): v2 decidiu melhor (widget-match 62→80%) mas cobriu menos
  (cov_mop −2,62 pp) por colapso de diversidade (argmax, sem termo de novidade); corpus sem holdout e sem identidade de
  app. Causas que se aplicam ao Laya: rótulo por imitação, argmax, falta de split por app, ausência de MOP em dados
  públicos.
- **Decisão a revisitar**: `20260721_sota_llm_gui_testing.md` §6 registra (2026-07-29) "seleção por lista DESCARTADA".
  O Laya é seleção por lista. A evidência de então (VLM copiando centros de contêineres: 14,89% vs 31,61%) não se aplica
  a um seletor que devolve a própria `ModelAction`, mas a decisão precisa ser revista explicitamente.
- **Datasets públicos**: MobileViews (DroidBot → aprende política aleatória), AndroidControl (dirigido por objetivo,
  coordenadas), Rico (traces filtrados: a tela seguinte **não** é o desfecho; só imitação humana; zero-shot Laya 0,24
  vs prior de 5 features 0,38 vs aleatório 0,15), Mobile3M (chinês, NC). Nenhum liga ações a APIs. Descartados como
  fonte principal; dados do Rico apagados.
- **Vídeo do Sandeco** (Jev vs Laya): framework "It's Fine" é fechado (livro/mentoria); 100% num classificador binário
  sobre mensagens geradas por template (risco de vazamento); útil só como ordem de grandeza (LoRA em T4, ~30 min).
- **"Agrupamento"**: não há agrupamento de ações em nenhum experimento recente (E5, E5b, E5c; E6 não iniciado); todo
  modo LLM devolve uma ação. `FORM_COMPLETION` dá boost a campos vazios e segura o submit, um campo por passo, só
  EditText. A abstração de estado não distingue campo vazio de preenchido.

## 5. Corpus próprio

### 5.1 Fonte e conversão (`scripts/corpus/convert.py`)

`rvsec/rv-android/results/**` — traces no formato texto do APE (`aperv:sata_mop`, `ape`, braços `mop_*`). A cada passo o
trace imprime **todas as candidatas do estado** (prioridade, visitada ou não, classe, resource-id, bounds, texto), a
ação escolhida e a estratégia, e no passo seguinte a aresta Source → Action → Target. As marcas MOP vêm do `<apk>.json`
ao lado do trace (listeners/eventos do widget → métodos com `reachesMop`/`directlyReachesMop`).

Bug corrigido durante a análise: arestas guardadas por (estado, ação) eram sobrescritas; agora cada aresta é ligada ao
passo que a gerou (a consistência de `effect` caiu de 0,996 para 0,907 — o número correto).

### 5.2 Números (`results/stats_full.txt`)

| | valor |
|---|---|
| Passos | 2.042.243 (1,79 M `sata_mop`, 247 k `ape`) em 167 apps |
| Sobreposição com os 163 de avaliação | 2 apps |
| Candidatas por passo | mediana 8, p90 23, 88% ≤ 20 |
| Rótulo das candidatas | texto 50%, só resource-id 35%, nenhum 15% |
| Passos com candidata MOP | 19% |
| Desfechos | effect 0,53 · act_change 0,07 · new_state 0,08 |
| act_change da ação escolhida | MOP 0,13 vs não-MOP 0,07 |
| Estados abstratos | 126.874; 100.622 com ≥ 2 ações testadas (mediana 5); 71.706 contrastivos (593.312 pares) em 153 apps |

Visibilidade do valor nos traces: EditText sim; CheckBox/Radio/Switch (checked), Spinner (item) e SeekBar (valor) não.

### 5.3 Formulários (`form_paired.py`)

Mesmo botão (app, activity, widget) clicado sem e com componentes-valor manipulados desde a chegada na tela (todos os
tipos): 1.752 botões; act_change 0,171 vs 0,168; 80 melhoram, 92 pioram, 1.580 iguais. A dependência existe em casos
pontuais (ex.: "Save" de perfil), provavelmente atenuada porque o texto do fuzz falha na validação e `act_change` não vê
salvamento sem mudança de activity. (Um número anterior, 71% vs 40%, vinha de 3 traces e não se sustenta.)

## 6. Escolha do rótulo de treino (`labels.py`, `results/labels_out.txt`)

Critérios: consistência na repetição da mesma ação no mesmo estado (vs acaso), dependência do histórico (1ª execução vs
seguintes), contraste entre ações de um mesmo estado, e atalhos triviais (AUROC de "não testada", "é back", "é Button"…).

| Rótulo | Taxa | 1ª / depois | Consistência (acaso) | Contraste | Maior atalho |
|---|---|---|---|---|---|
| new_state | 0,064 | 0,149 / 0,012 | 0,71 (0,74) | 0,39 | não testada 0,77 |
| new_act | 0,005 | 0,014 / 0,000 | 0,97 (0,96) | 0,04 | não testada 0,79 |
| effect | 0,529 | 0,565 / 0,508 | 0,91 (0,29) | 0,71 | ~0,55 |
| act_change | 0,074 | 0,085 / 0,067 | 0,98 (0,71) | 0,30 | Button 0,62 |
| escape_ok | 0,058 | 0,074 / 0,049 | 0,99 (0,76) | 0,28 | Button 0,67 |
| mop_act | 0,051 | 0,059 / 0,047 | 0,99 (0,78) | 0,19 | Button 0,62 |

Opções: A imitação (descartada) · B new_state (descartada: histórico, é o SATA) · C effect binário · D act_change /
escape_ok binário · E mop_act binário · **F graduado (recomendado)** · G MOP executado via logcat (extensão futura).

**F**: nível por ação tentada — 0 nada mudou, 1 efeito na mesma activity, 2 outra activity (sem ser back), 3 activity
que alcança MOP; back/menu fora do cálculo. Alvo de cada estado = distribuição sobre as ações tentadas, proporcional ao
nível (formato nativo do treino do Laya). Todas as opções do exemplo têm desfecho observado. **G**: nível 4 com método
MOP/violação realmente disparado, se a ligação logcat↔passo for confiável.

## 7. Próximos passos (aguardando aprovação)

1. Aprovar o rótulo F.
2. Gerar o dataset: níveis, texto das opções, marcas MOP, estado de formulário genérico; split por app; avaliação
   offline nos 163 (inclui traces NDJSON de `rv-android/data/results/estudo02-*`, só ações tentadas).
3. Primeiro fine-tune local (typed-decisions ou mmBERT-base, `head_max_len` 640–768) + fit de temperatura; comparar
   com baselines (aleatório, lexical, "não testada", prioridade do SATA).
4. Investigar a ligação logcat ↔ passo para o nível 4.
5. No jar (via OpenSpec, só após aprovação): modo de dump `{state, options}` com o valor de cada componente, e o estágio
   LAYA como prior amostrado sobre a pontuação do SATA.

## 8. Decisões (2026-09-29)

1. **Rótulo F aprovado** (nível graduado por desfecho sobre as ações tentadas de cada estado; G como extensão futura).
2. **Seleção por lista reaberta para o Laya.** A decisão de 2026-07-29 (`rv-android/docs/20260721_sota_llm_gui_testing.md`
   §6, "seleção por lista DESCARTADA") valia para o VLM, cuja evidência era copiar centros de contêineres de uma lista
   impressa (14,89% vs 31,61% de rendimento). O Laya devolve a própria `ModelAction` (sem coordenadas), então a
   decisão fica revista para esta branch.

## 9. Primeiro fine-tune (v1, 2026-09-29)

Scripts: `docs/laya/scripts/train/` (`build_dataset.py`, `balance.py`, `train.py` — cópia local do treino single-GPU do
`cklxx/laya-browser` —, `calibrate.py`, `evaluate.py`, `analyze_v1.py`). Checkpoint (fora do repo):
`/home/pedro/tmp/laya/train/out/v1`.

- **Dataset (rótulo F)**: estados contrastivos, sem duplicatas, corte por app (train 600, val/test 300):
  train 13.116 itens / 117 apps, val 2.113 / 15, test 1.805 / 17 (os 2 apps que coincidem com os 163 ficam no test).
  Sequências curtas (mediana 173 tokens), nenhuma opção truncada.
- **Treino**: typed-decisions, `head_max_len` 640, 4 épocas, bf16, uma GPU: 21 min, 11,7 GB. Temperatura calibrada
  no val: T = 1,44.

| test (17 apps, macro) | best@1 | pairacc |
|---|---|---|
| aleatório | 0,40 | 0,53 |
| Laya zero-shot | 0,45–0,48 | 0,50 |
| prior verbo+classe (aprendido no train) | 0,724 | 0,749 |
| **Laya v1** | **0,740** | **0,744** |

Bootstrap por app (v1 − prior): test best@1 +0,017 [−0,028; +0,058], pairacc −0,005 [−0,039; +0,025]; val best@1
+0,067 [−0,010; +0,139]. **MOP**: em estados com uma opção nível 3, o v1 põe essa opção em primeiro em 0,66 vs 0,48
(val) e 0,722 vs 0,655 (test).

**Leitura**: no ranking geral o v1 empata com o prior de tipo de widget; o ganho consistente está em reconhecer ações
que levam a MOP. Próximos experimentos propostos (não iniciados): IC da métrica MOP; ablação sem texto das opções;
mais dados (sem corte, ponderando por app); checkpoint mmBERT-base.

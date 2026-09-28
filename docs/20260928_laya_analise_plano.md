# APE-RV / branch `laya` — análise e plano (rascunho para aprovação)

Data: 2026-09-28 · Branch: `laya` (a partir de `master` e93dea86) · Status: **análise, nenhum código alterado**

> **Atualização (mesmo dia):** os testes zero-shot, a medição do corpus próprio e a escolha do rótulo de treino estão
> em `docs/20260928_laya_resultados_zero_shot_corpus_rotulos.md`. Eles mudam partes deste plano: o fine-tune deixou de
> ser opcional; a fonte de dados passou a ser os traces antigos do rv-android (que já trazem a lista de candidatas);
> e o Laya entra como prior amostrado sobre o SATA, não como seleção direta.

## 1. O que é o Laya (e o Jev)

| | Laya | Jev |
|---|---|---|
| Quem | Convai Innovations, Apache-2.0 (código + pesos) | TypeSafe AI, fechado, hospedado |
| O que é | Encoder bidirecional **não generativo**: ModernBERT-large (~421M) ou mmBERT-base (~322M) + cabeça de decisão | "System One" decision model; Laya é um clone aberto da API dele |
| API | `POST /v1/systemone` `{state, questions}` → `{answers:{q:{choice, probabilities, confidence, answer_confidence}}}` | mesma forma, `https://api.typesafe.ai/v1/systemone`, waitlist |
| Contexto | `laya` 512 tok (opções ≤192); `typed-decisions` 1024 (opções ≤256); multilingual até 8192. `max_len`/`head_max_len` ajustáveis por requisição | 64k; até 255 opções |
| Latência | ~35–40 ms/pergunta em T4; 200–600 ms em CPU | ~250 ms p50 |
| Maturidade | repo criado em 2026-09-18, v0.3.21 (27/09), ~27k stars, sem paper | early access |

**Mecânica da pergunta `choice`** (verificado no código, `laya/common.py::build_sequence`):
`[CLS] <tipo> instruções [SEP] [MASK] opt0 [MASK] opt1 … [SEP] state [SEP]` — todas as opções numa só sequência,
softmax sobre os marcadores. Cada opção é truncada a 48 tokens; se o bloco estoura, todas são cortadas por igual;
o `state` preenche o resto e é truncado **silenciosamente**. Recomendação oficial: **< ~20 opções**
(Banking77 com 77 rótulos: 0,425 vs 0,870 do Jev). O servidor recusa > 100 opções.

**Achados que condicionam o projeto:**
1. **Zero-shot é quase aleatório** (typed-decisions: 0,362 vs 0,318 aleatório). O README diz: "a fast base to specialise, not a zero-shot decision engine". Precedente agentic: `laya-browser` (seleção de elemento web entre ~45) foi de **0,10 → 0,66** top-1 só após fine-tune com ~14k amostras.
2. **Calibração ruim de fábrica** (ECE 0,466 → 0,081 após fit de temperatura); `act_probability` sem sinal (AUROC 0,30) — usar `answer_confidence`, com temperatura ajustada por nós.
3. Viés de posição de opção (flip rate 0,15 com 20 opções); `noul` tende a seguir rótulos em vez do estado.
4. Sem camada OpenAI-compatível → cliente Java novo (JSON simples). Servidor: `laya-serve` (0.0.0.0:8000, 1 worker, env `LAYA_DEVICE`, `LAYA_REVISION` para fixar versão).
5. Fine-tune suportado (notebook RLCD, alvos = distribuição gold por opção; 6k decisões ≈ 5 min em 2×T4).

## 2. Estado da arte (atualização do `20260721_sota_llm_gui_testing.md`)

O que é novo e pesa no desenho:
- **Híbrido vence**: todos os vencedores 2025–26 (LLMDroid, HybridMonkey ASE'26, GraphDroid 2609.10031, LLM-Explorer) mantêm o explorador heurístico como laço principal e o modelo como guia. LLMDroid: 69,75% das ações recomendadas pelo LLM não levaram a página nova.
- **Pontuar candidatos > gerar ações**: V-Droid (2503.15937) é o análogo mais próximo — extrai ~20 candidatos da árvore a11y e pontua cada um; verificador não treinado = 0%, **treino pareado (preferência) 47,4% vs seletor SFT 35,8%**. MindAct (Mind2Web): cross-encoder DeBERTa como shortlister + escolha múltipla com opção **"nenhuma das anteriores"**.
- **Representação textual compacta basta e até ajuda**: UIFormer (−51% tokens, +3,7 pp), AndroidLab (XML text-only ≈ multimodal), estudo de text input (contexto extraído 71,4% vs visão 65,1%). Pontos cegos do text-only: ícones sem rótulo e ambiguidade de layout (DailyDroid; LiMAC mostra que imagem importa em tarefas dirigidas por objetivo).
- **Memória**: bloco de estado de esquema fixo > histórico cru (V-Droid: 59,5 / 46,1 / 40%; TSR +12 pp; PRMs sofrem "lost in the middle"). Anti-repetição **por mascaramento de opções**, não por prosa (Guardian).
- **Generalização**: AndroidControl — fine-tune ganha in-domain, mas fora do domínio (apps não vistos) escala devagar → splits **disjuntos por app**.
- **Avaliação de encoders**: "Lexical Coupling" (2608.21794) — acertos de encoders são previsíveis pelo ranking lexical; sempre reportar baseline lexical.
- **Nicho nosso continua aberto**: nenhum seletor aprendido publicado consome alcançabilidade estática por widget/listener para APIs monitoradas (MOP).

## 3. Onde isso encaixa no APE-RV hoje

- `DecisionPipeline` (`agent/pipeline/`): ordem fixa `BUDGET, LLM_NEW_STATE, LLM_STAGNATION, LLM_RANDOM, MOP_LAUNCHER, COMPONENT_TRIGGER, SATA_CHAIN`, **primeiro SELECT vence**, sem mescla.
- Antes de qualquer estágio, `adjustActionsByGUITree` já calculou em cada `ModelAction` a prioridade e todos os boosts (MOP direto/transitivo, menu, WTG, fronteira MOP, cobertura, formulário). → **um shortlister não precisa recalcular nada**.
- Caminho LLM atual: screenshot → `ApePromptBuilder` → SGLang `/chat/completions` → `ToolCallParser` (3 níveis + reparo) → `CoordinateNormalizer` → `CoordinateMapper` (snap, bordas, dead-pair, tap fora da árvore). **Com Laya, todo o bloco coordenadas/screenshot/parse desaparece**: a saída é uma chave da nossa própria lista de opções → mapeamento exato por construção.
- `hint`/`inputType` só existem via `MopData.Widget` (só em braços MOP, só widgets com resource-id); o `hint=` do prompt atual é na verdade o texto já digitado.
- **Lacuna de dados**: `StepRecord` não registra o conjunto de candidatos → os traces existentes **não servem** diretamente como dados de treino.

## 4. Proposta de arquitetura (`LAYA`)

```
StatefulAgent.resolveNewAction
  └─ adjustActionsByGUITree  (prioridades + boosts MOP já prontos)
  └─ DecisionPipeline
        BUDGET → LAYA_NEW_STATE → LAYA_STAGNATION → LAYA_RANDOM → MOP_LAUNCHER → … → SATA_CHAIN
                     │
                     ▼ LayaEngine.selectAction(state, actions, mopData, history, mode)
            1. Shortlist  K≤16 (prioridade desc., MOP como hard-keep, dedup, sem ações saturadas/dead-pair)
                          + fixas: back, menu (se houver), defer
            2. Serializa  state (JSON curto) + criteria {chave: descrição}
            3. LayaClient POST http://10.0.2.2:8000/v1/systemone
            4. Decide     argmax; se "defer" ou confiança < limiar → CONTINUE (SATA segue)
            5. Mapeia     chave → ModelAction (exato); type → texto de fuzzInputTyped
```

**Formato (esboço):**
```json
state = {
  "activity": "EncryptActivity", "screen": "new|visited 3x",
  "steps_since_new_state": 4, "last": "click 'Save' -> same screen",
  "mop": {"activity_reaches": true, "widgets_marked": "2/9", "unvisited_mop_activities": 3},
  "screen_text": "Encrypt file · Key size · …(texto não acionável, truncado)"
}
questions = {"next": {"type": "choice",
  "instructions": "Pick the action most likely to reach unexplored behaviour or a security-sensitive (MOP) operation.",
  "criteria": {
    "btn_encrypt":  "click Button 'Encrypt' | mop:direct | untried",
    "et_password":  "type password into EditText 'Password' (hint 'min 8') | untried",
    "list_item_3":  "click ListItem 'AES-256' | tried 2x no effect",
    "menu":         "open options menu | mop-gateway",
    "back":         "press back",
    "defer":        "none of these is clearly better; let the explorer decide"
  }}}
```
Chaves derivadas do conteúdo (não índices) e ordem embaralhada com a semente do run (mitiga viés de posição mantendo determinismo).

**Decisões de desenho recomendadas:**
1. **Manter o caminho LLM intacto** na branch e adicionar `Feature.LAYA` (+ sub-features `LAYA_NEW_STATE/STAGNATION/RANDOM`, pois o sistema de features não expressa dependência OU) e presets `laya` / `laya_mop`. Assim o mesmo jar roda braços llm vs laya.
2. **Extrair uma interface `ActionSelector`** de `LlmEngine` (os estágios hoje recebem a classe concreta); `LlmGate.accept` parametrizado por `DecisionSource` (novo `LAYA`, `PickChannel.LAYA`). Dead-pair e histórico de ações passam a valer para LAYA.
3. **Modo A primeiro** (seleção nos mesmos gatilhos do LLM, mas com `layaPercentage` alto, já que custa ~35 ms): comparação direta com os braços LLM. **Modo B depois** (`LayaPass` no `ScoringPipeline`: `boost = w·p(ação)` a cada passo, prior à la Humanoid) — mais novo, porém mexe na roleta do SATA em todo passo e exige calibração.
4. **Telemetria**: sub-evento `laya[]` no `StepRecord` (modo, K, chave escolhida, top-3 probabilidades, confiança, ms, `defer`), e **dump opcional do par `{state, questions}` exato** (análogo a `llmPromptDump`). Com o `out` já na mesma linha, cada linha vira um exemplo de treino — isso fecha a lacuna de dados.
5. **Infra**: serviço `laya-serve` no docker-compose ao lado do SGLang (GPU), revisão fixada, ponte socat na porta 8000 no entrypoint do rvandroid; aperv-tool ganha `laya_*` no `APERV_PROPERTY_MAPPING`, braços `sata_mop_laya` / `mop_on_laya_*`, proveniência e `trace_ndjson.py`.

## 5. Plano em fases

| Fase | Entrega | Critério de saída |
|---|---|---|
| **P0 – Infra + zero-shot** | Feature/presets, `LayaClient`, `LayaEngine`, shortlister, serializador, estágios, telemetria + dump, compose, aperv-tool | Run completo no emulador (cryptoapp); latência/step medida; zero-shot registrado (esperado: fraco) |
| **P1 – Dados + fine-tune (offline)** | Coleta com dump em braços SATA/MOP/LLM; rótulos por desfecho (novo estado, nova activity, método MOP atingido via junção `ApeRvHb`) → alvos soft/pareados; opcional destilação do Qwen em modo texto; fine-tune RLCD + fit de temperatura | Split disjunto por app: top-1/top-3, recall@K do shortlister, ECE; vence baseline lexical |
| **P2 – Campanha** | Braços: `mop_on_llm_off` (SATA+MOP), `mop_on_llm_70` (Qwen-VL), `laya_mop` zero-shot, `laya_mop` fine-tuned, ablação sem tags MOP / tags embaralhadas | Cobertura de activities/métodos, alcance MOP, `mop_unique`, crashes, s/ação; 3–5 repetições |

**Observação importante:** SATA+MOP já é, na prática, um seletor "por tags" forte. O Laya precisa superar `mop_on_llm_off`, não apenas o aleatório — e a ablação com tags MOP removidas/embaralhadas mostra se ele lê o estado ou só segue rótulos.

## 6. Riscos

- Zero-shot inútil → o valor científico depende de P1 (dados + fine-tune). P0 sozinho provavelmente mostra "Laya ≈ ruído".
- Projeto de 10 dias, API mudando rápido → fixar `LAYA_REVISION` e versão do pacote.
- 512/1024 tokens: truncamento silencioso do `state` → logar tokens usados (`usage`) e o que foi cortado.
- Telas com ícones sem rótulo (ponto cego do text-only).
- Generalização entre apps (AndroidControl) → avaliação disjunta por app obrigatória.

## 7. Decisões pendentes (do usuário)

1. Manter o caminho LLM (Qwen-VL) no mesmo jar, ou remover na branch `laya`?
2. Modo A (seleção nos gatilhos) primeiro, ou ir direto ao modo B (prior em todo passo)?
3. Checkpoint: `laya-typed-decisions` (1024) ou `laya` (512)?
4. O fine-tune (P1) entra no escopo desta branch, ou P0 é o marco?
5. Seguir com uma change OpenSpec (ex.: `laya-action-selector`) para P0?

## Fontes principais

Laya: [GitHub](https://github.com/NandhaKishorM/laya) · [card](https://huggingface.co/convaiinnovations/laya) · [typed-decisions](https://huggingface.co/convaiinnovations/laya-typed-decisions) · [blog HF](https://huggingface.co/blog/sora-2/laya-ai-model-how-it-works-run-it-locally-and-eval) · [laya-browser](https://huggingface.co/cklxx/laya-browser) · issues [#555](https://github.com/NandhaKishorM/laya/issues/555), [#394](https://github.com/NandhaKishorM/laya/issues/394).
Jev: [API](https://docs.typesafe.ai/api.md) · [models](https://docs.typesafe.ai/models.md) · [MarkTechPost](https://www.marktechpost.com/2026/09/19/typesafe-ai-releases-jev/).
SOTA: V-Droid [2503.15937](https://arxiv.org/abs/2503.15937) · Mind2Web [2306.06070](https://arxiv.org/abs/2306.06070) · LiMAC [2410.17883](https://arxiv.org/abs/2410.17883) · AndroidControl [2406.03679](https://arxiv.org/abs/2406.03679) · AndroidLab [2410.24024](https://arxiv.org/abs/2410.24024) · GraphDroid [2609.10031](https://arxiv.org/abs/2609.10031) · HybridMonkey [2604.06763](https://arxiv.org/abs/2604.06763) · UIFormer [2512.13438](https://arxiv.org/abs/2512.13438) · DailyDroid [2604.17817](https://arxiv.org/abs/2604.17817) · TSR [2607.00502](https://arxiv.org/abs/2607.00502) · Lexical Coupling [2608.21794](https://arxiv.org/abs/2608.21794) · LELANTE [2504.20896](https://arxiv.org/abs/2504.20896) · Humanoid [1901.02633](https://arxiv.org/abs/1901.02633) · DQT (ICSE'24) · LLM-Explorer [2505.10593](https://arxiv.org/abs/2505.10593).

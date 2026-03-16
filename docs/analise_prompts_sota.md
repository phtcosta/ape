# Analise Comparativa de Prompts: Ferramentas SOTA de Teste Android com LLM

**Data**: 16 de marco de 2026
**Autor**: Claude Opus 4.6 (1M context)
**Objetivo**: Analisar os prompts reais das ferramentas SOTA de teste Android com LLM e comparar com o design do APE-RV gh6

---

## 1. Ferramentas Analisadas

Cinco ferramentas SOTA foram baixadas e seus codigos-fonte analisados diretamente, alem do rvsmart (infraestrutura propria do RVSEC):

| Ferramenta | Venue | Repositorio | Linguagem |
|---|---|---|---|
| **LLMDroid** | FSE 2025 | LLMDroid-Droidbot/Humanoid/Fastbot | Python + C++ |
| **DroidBot-GPT** | arXiv 2023 | DroidBot-GPT | Python |
| **AutoDroid** | MobiCom 2024 | AutoDroid | Python |
| **DroidAgent** | ICST 2024 | droidagent | Python |
| **rvsmart** | RVSEC interno | rvsec-android/rvsmart | Java |

---

## 2. Representacao da UI no Prompt

### 2.1 Formatos encontrados

Cada ferramenta converte a arvore de acessibilidade do Android em um formato textual distinto para alimentar o LLM:

**LLMDroid** — HTML simplificado com 5 tags:
```html
<button id=3 class="ImageButton" resource-id="search" text="Search">Search</button>
<input id=5 class="EditText" resource-id="query" value="?">Enter query</input>
<scroller id=7 class="RecyclerView" direction="vertical"></scroller>
<p class="TextView">Status: Online</p>
```
- Tags: `<button>`, `<checkbox>`, `<scroller>`, `<input>`, `<p>` (informacional, sem id)
- Atributos: `id` (inteiro sequencial), `class`, `resource-id`, `content-desc`, `text`, `direction`, `value`
- Filhos `<p>` com texto sao mesclados no pai via `<br>` para reduzir profundidade
- Truncado a 7000 caracteres para analise de estado
- Prefixado com `[Activity: com.example.MainActivity]`

**DroidBot-GPT** — Lista flat textual descritiva:
```
The current state has the following UI views and corresponding actions, with action id in parentheses:
 - a editable view "Search" with text "Enter query" that can edit (0), click (1);
 - a view "Submit" that can click (2);
 - a scrollable view that can scroll up (3), scroll down (4);
 - a key to go back (5)
```
- Texto/content-description truncado a 20 caracteres
- Status flags: `editable`, `checked`
- Sem hierarquia, sem coordenadas, sem bounds
- `go back` sempre adicionado como ultimo item

**AutoDroid** — HTML-like flat com tags simplificadas:
```html
<input id=0>Search folders</input>
<button id=1 text='Open camera'></button>
<button id=2 text='Show all folders content'></button>
<button id=3 text='More options'></button>
<button id=4>Internal<br>2</button>
<button id=6>go back</button>
```
- Tags: `<button>`, `<checkbox>`, `<input>`, `<p>`, `<div class='scroller'>`
- Scrollable views sao IGNORADOS na lista de acoes (interessante — similar a nossa decisao)
- Texto truncado a 50 caracteres
- `go back` sintetico sempre no final

**DroidAgent** — JSON hierarquico com arvore de widgets:
```json
{
  "page_name": "MainActivity",
  "children": [
    {
      "ID": 5,
      "widget_type": "Button",
      "text": "Submit",
      "possible_action_types": ["touch"],
      "num_prev_actions": 3,
      "widget_role_inference": "The widget submits the form data",
      "children": [...]
    }
  ]
}
```
- Arvore completa com `children` aninhados
- `num_prev_actions`: contagem de interacoes previas (similar ao nosso `(v:N)`)
- `widget_role_inference`: descricao do papel do widget inferida pelo LLM em interacoes anteriores
- `possible_action_types`: lista de acoes validas por widget
- Truncado a 15000 caracteres com `[...truncated...]`
- Aspas duplas removidas para reduzir tokens

**rvsmart** — Lista numerada com coordenadas em pixels:
```
UI elements:
  1. Button "Submit" @(540,1200) [UNTESTED] [DM] [triggers MOP handler]
  2. EditText [Search field] @(540,300) [TESTED-2x] [M]
  3. ImageView "Logo" @(540,100) [WELL-TESTED]

SCREEN: com.example.MainActivity | 3/5 actions tested | iter #42
```
- V13: formato basico sem tags de status nem MOP
- V17: adiciona `[UNTESTED]`/`[TESTED-Nx]`/`[WELL-TESTED]`, `[DM]`/`[M]`, `[triggers MOP handler]`
- Coordenadas em **pixels do dispositivo** (centro dos bounds)
- Texto truncado a 40 caracteres

**APE-RV gh6 (nosso design)**:
```
Screen "MainActivity":
[0] BACK (key)
[1] MENU (key)
[2] Button "Encrypt" @(185,117) [DM] (v:0)
[3] EditText "Password" @(208,169) (v:3)
[4] TextView "Help" @(231,219) [M] (v:1)
```
- Coordenadas em **[0,1000) normalizado** — mesmo espaco que a resposta do LLM
- MOP markers `[DM]`/`[M]` compactos
- Visited count `(v:N)` inline
- Texto truncado a 50 caracteres

### 2.2 Analise comparativa da representacao

| Aspecto | LLMDroid | DroidBot-GPT | AutoDroid | DroidAgent | rvsmart | **APE-RV** |
|---|---|---|---|---|---|---|
| Formato | HTML | Texto flat | HTML-like | JSON tree | Lista num. | Lista compacta |
| Hierarquia | Sim (tags) | Nao | Nao | Sim (children) | Nao | Nao |
| Coordenadas | Nenhuma | Nenhuma | Nenhuma | Nenhuma | Device px | **[0,1000)** |
| IDs | Inteiro seq. | Inteiro seq. | Inteiro seq. | Widget ID | Indice | Indice |
| Visited count | Nao | Nao | Nao | `num_prev_actions` | Status tags | `(v:N)` |
| Info semantica | Nao | Nao | `onclick` hint | `widget_role_inference` | MOP markers | MOP markers |
| Truncamento | 7000 chars | 20 chars/widget | 50 chars/widget | 15000 chars | 40 chars/widget | 50 chars/widget |

**Observacao importante**: 4 das 5 ferramentas SOTA nao enviam coordenadas ao LLM — o LLM seleciona widgets por ID inteiro. Apenas o rvsmart (e APE-RV) usam coordenadas, o que e necessario para suportar **raw clicks em elementos dinamicos** (WebView, canvas) invisiveis ao UIAutomator.

---

## 3. Acoes Oferecidas ao LLM

### 3.1 Esquemas de acoes

**LLMDroid** — 7 acoes numericas em JSON:
```json
{"Element Id": 2, "Action Type": 4}
```
- `0`: click, `1`: long press, `2`: swipe top→bottom, `3`: swipe bottom→top
- `4`: swipe left→right, `5`: swipe right→left, `6`: input text
- Variante Humanoid: apenas 6 acoes (sem input text)
- Input text: chave `"Input"` no JSON
- Termino: `{"Element Id": -1, "Action Type": 0}`

**DroidBot-GPT** — Selecao por ID numerico, texto separado:
- click, edit, scroll up, scroll down, back
- O LLM retorna apenas o ID inteiro da acao
- Para `edit`: **segunda chamada LLM** pergunta "What text should I enter?"
- Resposta esperada: um unico inteiro (`\d+` regex)

**AutoDroid** — 2 acoes em JSON estruturado:
```json
{"Steps": "...", "Analyses": "...", "Finished": "Yes/No",
 "Next step": "...", "id": 3, "action": "tap", "input_text": "N/A"}
```
- Apenas `tap` e `input` (sem scroll, sem long press!)
- Chain-of-thought obrigatorio (Steps, Analyses, Finished)
- `id=-1` para tarefa concluida

**DroidAgent** — OpenAI function calling com 7 funcoes:
```json
{"name": "touch", "parameters": {"target_widget_ID": 5}}
{"name": "scroll", "parameters": {"direction": "UP", "target_widget_ID": 7}}
{"name": "set_text", "parameters": {"target_widget_ID": 12}}
{"name": "go_back", "parameters": {}}
{"name": "end_task", "parameters": {}}
{"name": "wait", "parameters": {}}
```
- `touch`, `long_touch`, `scroll` (4 direcoes), `set_text`, `go_back`, `end_task`, `wait`
- `enum` dos widget IDs e **dinamicamente populado** a partir da tela atual
- Texto para `set_text` e gerado em chamada separada usando perfil da persona
- Unica ferramenta que usa OpenAI function calling nativo

**rvsmart** — 5 acoes em JSON livre:
```json
{"name": "click", "arguments": {"x": 540, "y": 399}}
{"name": "scroll", "arguments": {"x": 500, "y": 500, "direction": "down"}}
{"name": "type_text", "arguments": {"text": "test@mail.com"}}
{"name": "back", "arguments": {}}
```
- click, long_click, scroll (4 direcoes), type_text, back
- Coordenadas em [0,1000) na resposta (mas device pixels no prompt — mismatch)
- Parseado por `ToolCallParser` com 3 niveis de fallback

**APE-RV gh6 (nosso design)** — 3-4 acoes em JSON:
```json
{"name": "click", "arguments": {"x": 185, "y": 117}}
{"name": "type_text", "arguments": {"x": 208, "y": 169, "text": "google.com"}}
{"name": "back", "arguments": {}}
```
- click, long_click, back (sempre); type_text (dinamico — so quando ha EditText na tela)
- **Sem scroll** — SATA ja faz isso mecanicamente
- Coordenadas [0,1000) consistentes prompt↔resposta
- Mesmo ToolCallParser do rvsmart (3 niveis de fallback)

### 3.2 Tabela comparativa

| Ferramenta | click | long_click | scroll | type_text | back | extras | Total |
|---|---|---|---|---|---|---|---|
| **LLMDroid** | Sim | Sim | 4 swipes | Sim (Droidbot) | Nao | Element Id=-1 | 7 |
| **DroidBot-GPT** | Sim | Nao | up/down | Sim (2a chamada) | Sim | — | 5 |
| **AutoDroid** | tap | Nao | **Nao** | input | Nao | Steps/Analyses | 2 |
| **DroidAgent** | touch | long_touch | 4 direcoes | set_text (separado) | go_back | end_task, wait | 7 |
| **rvsmart** | Sim | Sim | 4 direcoes | Sim | Sim | — | 5 |
| **APE-RV** | Sim | Sim | **Nao** | **Dinamico** | Sim | — | 3-4 |

**Observacao**: AutoDroid tambem exclui scroll — validacao independente da nossa decisao. A exclusao de scroll nao e unica, e sim uma decisao de design que prioriza o LLM para acoes semanticas.

---

## 4. Historico de Acoes / Contexto

### 4.1 Mecanismos encontrados

**LLMDroid** — Historico limitado por modo:
- `TEST_FUNCTION`: ultimos ~5 eventos executados inline no prompt
  ```
  I have already executed: [click on button "Settings",
  click on scroller to scroll down]
  ```
- `GUIDE`: funcoes ja testadas como conjunto: `{func1, func2, ...}`
- Sem conversacao multi-turn — cada chamada e standalone (temperature=0)
- Fastbot: `_maxCachedConversation = 0` (descarta historico imediatamente)

**DroidBot-GPT** — Historico ilimitado (cresce sem limite!):
```
I have already completed the following steps, which should not be performed again:
 - start the app gallery;
 - click view "Navigate up" with text "Home";
 - enter "Alice" into view with text "Name";
 - scroll down scrollable view with text "List items"
```
- Lista completa desde o inicio da sessao
- Sem truncamento — pode causar estouro de contexto em sessoes longas
- Instrucao: "which should not be performed again"

**AutoDroid** — Historico completo + pensamentos opcionais:
```
Previous UI actions:
- launchApp gallery
- TapOn: <button text='More options'></button>
- TapOn: <input class='Search'>query</input> InputText: hello
```
- Formato HTML-like no historico
- Opcional: `Reason:` por acao quando `use_thoughts=True`
- Sem limite de tamanho

**DroidAgent** — Conversacao multi-turn simulada (mais sofisticado):
- Converte historico em mensagens alternadas user/assistant
- Primeira mensagem: contexto da tarefa + plano
- Cada acao vira mensagem do assistant
- Cada observacao vira mensagem do user: "I performed the action, and as a result, {observation}. What should be the next action?"
- Critica do `Reflector` injetada periodicamente
- Apenas a ultima observacao e detalhada; anteriores sao simplificadas

**rvsmart** — Historico minimo:
- V13: **nenhum historico de acoes**
- V17: ultimas N acoes em formato compacto: `click@(540,1200), back@(0,0), click@(300,800)`
- Sem resultado da acao (apenas tipo + coordenadas)
- Ring buffer no caller

**APE-RV gh6 (nosso design)** — Ring buffer com resultados:
```
Recent:
- click @(208,169) EditText "Password" → same
- type_text @(208,169) "test@mail.com" → same
- click @(185,117) Button "Encrypt" → new screen
- back → previous screen
```
- Ultimas 3-5 acoes
- Inclui **resultado** da acao: "same", "new screen", "previous screen"
- Coordenadas em [0,1000) (mesmo espaco do widget list)
- Tipo da acao + widget class + texto + resultado

### 4.2 Comparacao

| Ferramenta | Historico | Resultado da acao | Limite | Formato |
|---|---|---|---|---|
| **LLMDroid** | ~5 eventos | Nao | Por modo (5 steps) | Texto descritivo |
| **DroidBot-GPT** | Completo | Nao | **Sem limite** | Lista textual |
| **AutoDroid** | Completo | Nao | **Sem limite** | HTML-like |
| **DroidAgent** | Completo | **Sim** (observacoes) | Ultima obs. detalhada | Multi-turn simulado |
| **rvsmart V17** | Ultimas N | Nao | Ring buffer | Compacto |
| **APE-RV** | Ultimas 3-5 | **Sim** (same/new/previous) | Ring buffer max 5 | Compacto com resultado |

**Insight**: Nosso design e o unico (alem do DroidAgent) que inclui o resultado da acao. Porem, nosso formato e mais compacto — DroidAgent usa conversacao multi-turn completa que consome muitos tokens. O resultado "same"/"new screen"/"previous screen" informa ao LLM se a acao anterior teve efeito, evitando repeticoes.

---

## 5. Quando o LLM e Chamado (Triggers)

### 5.1 Estrategias de invocacao

**LLMDroid** — Maquina de estados com 4 modos, triggered por coverage:
```
EXPLORE → (coverage stagna ou timeout 240s) → ASK_GUIDANCE → NAVIGATE → TEST_FUNCTION → EXPLORE
```
1. `EXPLORE`: execucao autonoma (DFS/BFS). Em cada novo cluster, enfileira `OVERVIEW` assincronamente
2. Trigger: **taxa de crescimento de cobertura abaixo do threshold** OU **intervalo de tempo** (240s Droidbot, 150s Humanoid)
3. `ASK_GUIDANCE`: LLM decide qual estado e funcao testar
4. `NAVIGATE`: replay de caminho no grafo UTG
5. `TEST_FUNCTION`: ate 5 chamadas LLM sequenciais para completar a funcao
6. `REANALYSIS`: clusters que ganharam novos estados sao re-analisados

Custo por sessao: **baixo** — ~20-40 chamadas por ciclo GUIDE+TEST, tipicamente 2-5 ciclos por sessao de 1 hora.

**DroidBot-GPT** — **Cada passo** (always-on):
- Uma chamada LLM por acao
- Sem otimizacao de custo — simples mas caro
- Para type_text: segunda chamada por campo

**AutoDroid** — **Cada passo** (always-on):
- Uma chamada por acao com chain-of-thought completo
- Usa GPT-3.5-turbo para reduzir custo
- Memoria pre-computada offline reduz necessidade de raciocinio complexo

**DroidAgent** — **Multi-agente por tarefa** (mais caro):
- Planner (GPT-4): 1 chamada por tarefa
- Actor (GPT-3.5-16k): 1 chamada por acao (ate 13 acoes/tarefa)
- Observer (GPT-3.5-16k): 1 chamada por acao
- Critic (GPT-4): 1 chamada a cada 4 acoes
- Reflector (GPT-4): 1 chamada por tarefa
- Widget summarizer (GPT-3.5): esporadico
- Total: ~30-40 chamadas por tarefa, com multiplos modelos

**rvsmart** — **Cada passo** (always-on):
- Uma chamada LLM por acao
- Qwen3-VL local via SGLang
- Latencia: ~3-5s por chamada
- Overhead: significativo em sessoes longas

**APE-RV gh6 (nosso design)** — **2 triggers pontuais**:
- **New-state**: primeira visita a cada estado novo (~50-100 chamadas/10min)
- **Stagnation**: quando `graphStableCounter > threshold/2` (~10-30 chamadas/10min)
- Qwen3-VL local via SGLang (mesmo que rvsmart)
- Budget maximo: 200 chamadas/sessao
- Circuit breaker: 3 falhas → 60s de pausa

### 5.2 Comparacao de overhead

Todas as ferramentas que usam API cloud (GPT-4, GPT-3.5) tem custo monetario. APE-RV e rvsmart usam **Qwen3-VL local via SGLang**, portanto o custo e exclusivamente de **latencia e tempo de GPU**:

| Ferramenta | Modelo | Infra | Chamadas/10min | Overhead de tempo |
|---|---|---|---|---|
| **LLMDroid** | GPT-4o-mini | Cloud API | ~5-15 (por ciclo) | ~15-45s por ciclo |
| **DroidBot-GPT** | GPT-3.5-turbo | Cloud API | ~100-200 | ~50-100s (API rapida) |
| **AutoDroid** | GPT-3.5-turbo | Cloud API | ~100-200 | ~50-100s |
| **DroidAgent** | GPT-4 + GPT-3.5 | Cloud API | ~30-40/tarefa | ~2-4min/tarefa |
| **rvsmart** | Qwen3-VL | **SGLang local** | ~100-200 | **+5-17min** (3-5s/call) |
| **APE-RV** | Qwen3-VL | **SGLang local** | **~60-130** | **+3-11min** (3-5s/call) |

**Observacao**: O overhead de APE-RV e ~40-60% menor que o rvsmart para a mesma infra local, porque o LLM so e invocado em new-state e stagnation, nao a cada passo. Em um run de 10 minutos, o APE-RV gasta ~3-11 minutos adicionais em LLM, enquanto o rvsmart gasta ~5-17 minutos. Essa reducao e significativa para experimentos em larga escala (169 APKs × multiplas repeticoes).

---

## 6. System Messages e Instrucoes

### 6.1 Comparacao de system messages

**LLMDroid** — **Sem system message**. Tudo em uma unica mensagem `user` (temperature=0). Nao ha separacao de instrucoes e contexto.

**DroidBot-GPT** — **Sem system message**. Prompt unico com task + state + history + question concatenados.

**AutoDroid** — **Sem system message**. Introducao inline:
```
You are a smartphone assistant to help users complete tasks by interacting with mobile apps.
Given a task, the previous UI actions, and the content of current UI state, your job is to
decide whether the task is already finished by the previous actions, and if not, decide which
UI element in current UI state should be interacted.
```

**DroidAgent** — System messages por agente:
- Planner: ~300 tokens com persona, app info, 4 propriedades de tarefas (Realism, Importance, Diversity, Difficulty)
- Actor: ~100 tokens com persona e lista de acoes
- Observer: ~30 tokens ("You are a helpful assistant who can interpret two consecutive Android GUI screens")
- Critic: ~80 tokens com tarefa e persona
- Reflector: ~200 tokens com historico de paginas visitadas

**rvsmart V13** — System message compacto (~120 tokens):
```
You are an Android UI testing agent. Your task is to explore the app by interacting with UI elements.
DIALOG HANDLING: If you see a permission dialog, click Allow/Accept/OK.
  If you see an error or modal dialog, dismiss it before any other action.
PRIORITY: MOP target elements > navigation to new screens > [UNTESTED] elements > [TESTED] elements.
Available actions:
  click(x, y) — tap an element at normalized coordinates [0,1000)
  long_click(x, y) — long press at normalized coordinates
  scroll(x, y, direction) — scroll at position, direction: up/down/left/right
  type_text(text) — type text into the focused input field
  back() — press the system back button
RULE: Do not click the same position twice in a row.
Respond with exactly one action as JSON: {"name": "<action>", "arguments": {<args>}}
```

**rvsmart V17** — System message detalhado (~300 tokens):
```
You are an Android UI automation assistant.

REASONING STEPS:
1. SCREEN: Identify screen type (dialog, form, list, menu).
2. DIALOG: If blocking dialog present, handle it first.
3. MOP CHECK: If [DM] or [M] elements are shown, prioritize them.
4. NAVIGATION: Check for actions leading to unvisited screens.
5. ELEMENTS: Select [UNTESTED] element if no navigation or MOP target available.
6. ACTION: Call the action with normalized coordinates [0,1000).

DIALOG HANDLING: ...
PRIORITY: Elements reaching monitored operations ([DM] direct / [M] transitive) > other actions ...
RULES: Do not click the same position consecutively ...
AVOID: navigation bar (bottom), status bar (top)
Available actions: click(x, y), long_click(x, y), scroll(x, y, direction), type_text(text), back()
```

**APE-RV gh6 (nosso design)** — System message compacto (~120 tokens, baseado em V13):
```
You are an Android UI testing agent exploring an app.
DIALOG: If permission/error dialog visible, dismiss it first (click Allow/OK).
PRIORITY: [DM]/[M] elements > unvisited (v:0) > visited.
AVOID: status bar (top), navigation bar (bottom).
RULES: Don't click same position twice. Use type_text for input fields with valid data
  (email: user@example.com, password: Test1234!, domain: example.com, search: relevant term).
Tools (coordinates in [0,1000) normalized space):
  click(x, y) — tap element
  long_click(x, y) — long press element
  type_text(x, y, text) — type into field [apenas quando ha campos de input na tela]
  back() — press back
Respond with one JSON: {"name": "<action>", "arguments": {<args>}}
```

### 6.2 Analise

| Aspecto | LLMDroid | DroidBot-GPT | AutoDroid | DroidAgent | rvsmart V13 | **APE-RV** |
|---|---|---|---|---|---|---|
| System message | Nao | Nao | Nao | Sim (por agente) | Sim (~120 tok) | Sim (~120 tok) |
| Dialog handling | Nao | Nao | Nao | Nao | Sim | Sim |
| Prioridades | Nao | Nao | Nao | Guidelines | MOP > nav > untested | MOP > v:0 > visited |
| Boundary reject | Nao | Nao | Nao | Nao | Sim (AVOID) | Sim (AVOID) |
| type_text hints | Nao | Nao | Nao | Persona profile | Nao | **Sim** (exemplos) |
| Tool schema dinamico | Nao | Nao | Nao | Sim (enum IDs) | Nao | **Sim** (type_text condicional) |
| Formato resposta | JSON | Inteiro | JSON + CoT | Function calling | JSON | JSON |

---

## 7. Uso de Visao (Screenshots)

| Ferramenta | Screenshot | Formato | Resolucao |
|---|---|---|---|
| **LLMDroid** | **Nao** | — | — |
| **DroidBot-GPT** | **Nao** (captura mas nao envia) | — | — |
| **AutoDroid** | **Nao** | — | — |
| **DroidAgent** | **Nao** | — | — |
| **rvsmart** | **Sim** | JPEG base64, max 1000px edge, quality 80 | Resize proporcional |
| **APE-RV** | **Sim** | JPEG base64, max 1000px edge, quality 80 | Resize proporcional |

**Observacao significativa**: Das 5 ferramentas SOTA analisadas, **nenhuma usa screenshots/visao**. Todas operam exclusivamente com representacao textual da arvore de acessibilidade. Apenas rvsmart e APE-RV (ambos do ecossistema RVSEC) enviam screenshots ao LLM.

A vantagem do screenshot e permitir **raw clicks em elementos dinamicos** (WebView, canvas, conteudo JavaScript) invisiveis ao UIAutomator. Essa capacidade e unica do nosso ecossistema e e um diferencial relevante frente ao SOTA.

---

## 8. Coordenadas

| Ferramenta | Coords no prompt | Coords na resposta | Consistencia |
|---|---|---|---|
| **LLMDroid** | Nenhuma | Widget ID inteiro | N/A (sem coords) |
| **DroidBot-GPT** | Nenhuma | Action ID inteiro | N/A |
| **AutoDroid** | Nenhuma | Widget ID inteiro | N/A |
| **DroidAgent** | Nenhuma | Widget ID inteiro | N/A |
| **rvsmart** | **Device pixels** | **[0,1000) normalizado** | **MISMATCH** |
| **APE-RV** | **[0,1000) normalizado** | **[0,1000) normalizado** | **Consistente** |

**Descoberta importante**: O rvsmart envia coordenadas em pixels do dispositivo no prompt mas espera a resposta em [0,1000) normalizado Qwen3-VL. Essa inconsistencia forca o LLM a fazer uma conversao mental entre dois espacos de coordenadas, o que pode degradar a precisao.

O design do APE-RV corrige isso explicitamente: tanto as coordenadas no widget list quanto as coordenadas na resposta usam o mesmo espaco [0,1000). A conversao `normX = (centerPixelX / deviceWidth) * 1000` e feita no `ApePromptBuilder` antes de construir o prompt.

---

## 9. Informacao Semantica e MOP

### 9.1 Tipos de informacao semantica

**LLMDroid** — Funcoes inferidas pelo LLM:
- `OVERVIEW` mode: LLM analisa cada pagina e gera "Function List" com descricoes naturais ("navigate to News", "play a video")
- As funcoes sao rankeadas por importancia
- Usado para guiar navegacao, nao para selecao de acao individual

**DroidBot-GPT** — Nenhuma informacao semantica alem do texto/content-description dos widgets.

**AutoDroid** — Memoria injetada via atributos HTML:
- `onclick='go to complete the adjust notification settings'` — injetado em widgets especificos usando memoria pre-computada de exploracao offline
- `title='settings screen'` — adicionado via predicao de item (o que acontece ao clicar)
- Baseado em embeddings INSTRUCTOR comparados com descricoes pre-computadas

**DroidAgent** — Conhecimento de widget acumulado:
- `widget_role_inference`: "The widget opens the settings page" (gerado pelo LLM Observer apos interacoes)
- `num_prev_actions`: contagem de interacoes previas
- `initial_knowledge`: conhecimento injetado no inicio (persona-specific)
- Armazenado em `SpatialMemory` (per-widget) e `TaskMemory` (ChromaDB com busca semantica)

**rvsmart V17** — MOP markers de analise estatica:
- `[DM]` (direct MOP): widget alcanca diretamente operacao monitorada
- `[M]` (transitive/indirect MOP): widget alcanca transitivamente
- `[triggers MOP handler]`: anotacao adicional do `StaticMap`
- `[UNTESTED]`/`[TESTED-Nx]`/`[WELL-TESTED]`: status de teste
- `MOP NAVIGATION:` secao com dica de navegacao

**APE-RV gh6** — MOP markers + visited count:
- `[DM]` (direct monitored): widget alcanca diretamente operacao monitorada por RV
- `[M]` (transitive monitored): widget alcanca transitivamente
- `(v:N)`: contagem de visitas a acao
- Informacao derivada de **analise estatica real** (JSON produzido pelo pipeline rv-android), nao inferida pelo LLM

### 9.2 Comparacao

| Ferramenta | Fonte da info semantica | Tipo | Dinamica? |
|---|---|---|---|
| **LLMDroid** | LLM inference (OVERVIEW) | Descricao de funcoes | Sim (atualiza com REANALYSIS) |
| **DroidBot-GPT** | Nenhuma | — | — |
| **AutoDroid** | Embeddings offline | onclick hints | Nao (pre-computada) |
| **DroidAgent** | LLM inference + ChromaDB | widget_role_inference | Sim (acumulativa) |
| **rvsmart V17** | Analise estatica (StaticMap) | MOP markers | Nao (estatica) |
| **APE-RV** | Analise estatica (MopData JSON) | MOP markers | Nao (estatica) |

**Diferencial do APE-RV**: A informacao MOP vem de analise estatica real do pipeline rv-android (instrumentacao de bytecode + analise de alcancabilidade). Isso e fundamentalmente diferente de DroidAgent (que infere funcoes via LLM) ou AutoDroid (que usa embeddings de exploracao passada). A informacao MOP e **factual** (determinada por analise de programa), nao **inferida** (estimada pelo LLM).

---

## 10. Licoes para o Design do APE-RV

### 10.1 Decisoes validadas pelo SOTA

| Decisao APE-RV | Validacao |
|---|---|
| **Excluir scroll do LLM** | AutoDroid tambem exclui. LLMDroid nao usa scroll para guidance, so para exploracao autonoma |
| **2 triggers pontuais (new-state + stagnation)** | LLMDroid (FSE'25) provou que coverage-triggered > always-on. Nosso design e mais granular |
| **Ring buffer de historico (max 5)** | DroidBot-GPT e AutoDroid crescem sem limite — problema para contexto. LLMDroid usa ~5 steps |
| **type_text com hints** | DroidAgent gera texto via persona. Nossos hints genericos sao mais simples e suficientes |
| **System message compacto (~120 tok)** | Maioria das ferramentas nao usa system message. rvsmart V13 provou que compacto funciona |
| **JSON como formato de resposta** | 3 de 5 ferramentas usam JSON. DroidAgent usa function calling (mais robusto, mas requer API compativel) |

### 10.2 Diferenciais unicos do APE-RV

| Diferencial | Descricao | Nenhuma outra ferramenta tem |
|---|---|---|
| **MOP markers de analise estatica** | `[DM]`/`[M]` baseados em alcancabilidade real, nao inferencia LLM | Correto — unico |
| **Coordenadas consistentes [0,1000)** | Mesmo espaco no prompt e na resposta | rvsmart tem mismatch; outros nao usam coords |
| **Tool schema dinamico** | type_text omitido quando nao ha EditText na tela | DroidAgent tem enum dinamico de IDs, mas nao omite ferramentas |
| **Resultado da acao no historico** | "same"/"new screen"/"previous screen" | DroidAgent tem observacoes, mas muito mais verbose |
| **Screenshot + texto estruturado** | Dual input (imagem + widget list) | Nenhuma ferramenta SOTA usa screenshot |
| **Raw clicks em elementos dinamicos** | WebView, canvas, conteudo JS | Impossivel sem screenshot |

### 10.3 Oportunidades futuras (nao para v1)

| Oportunidade | Inspiracao | Complexidade | Beneficio potencial |
|---|---|---|---|
| Coverage-aware re-invocacao | LLMDroid (coverage stagnation → guidance) | Media | Re-chamar LLM em estados revisitados com baixo ganho |
| Widget role inference | DroidAgent (widget_role_inference) | Alta | Acumular conhecimento sobre widgets entre chamadas |
| Navegacao guiada | LLMDroid (NAVIGATE mode com caminho no grafo) | Media | Usar o grafo APE para navegar ate estados-alvo |
| Multi-step planning | DroidAgent (Planner → tarefas de 13 passos) | Alta | LLM planeja sequencia de acoes, nao apenas uma |
| Memoria cross-sessao | AutoDroid (embeddings pre-computados) | Media | Reutilizar conhecimento de runs anteriores |

---

## 11. Conclusao

O design do APE-RV gh6 esta **bem posicionado em relacao ao SOTA**, com varias decisoes que refletem as melhores praticas encontradas nas ferramentas analisadas:

1. **Triggers pontuais** (new-state + stagnation) seguem o principio validado pelo LLMDroid de que invocacao seletiva e superior a always-on
2. **Prompt compacto** (~120 tokens system) e suficiente — a maioria das ferramentas SOTA nem usa system message
3. **Historico com resultado** combina a compacidade do rvsmart com a riqueza de informacao do DroidAgent
4. **MOP markers** sao um diferencial unico e factual (analise estatica real vs inferencia LLM)
5. **Screenshot + texto** e unico no SOTA e habilita raw clicks em elementos dinamicos
6. **Coordenadas consistentes** corrigem um mismatch real do rvsmart

O overhead de tempo (~3-11min por run de 10min com Qwen3-VL local) e aceitavel e significativamente menor que o rvsmart always-on (~5-17min), graças aos triggers pontuais. O circuit breaker e o budget limit (200 chamadas) garantem degradacao graceful.

---

## Apendice: Arquivos-Fonte Analisados

| Ferramenta | Arquivo | Conteudo |
|---|---|---|
| LLMDroid | `LLMDroid-Droidbot/droidbot/policy/prompt.py` | 4 templates de prompt |
| LLMDroid | `LLMDroid-Droidbot/droidbot/policy/llm_agent.py` | Montagem de prompts |
| LLMDroid | `LLMDroid-Droidbot/droidbot/policy/utg_based_policy.py` | Trigger logic (4 modos) |
| LLMDroid | `LLMDroid-Droidbot/droidbot/desc/device_state.py` | UI → HTML conversion |
| LLMDroid | `LLMDroid-Fastbot/native/agent/prompt.h` | Prompts C++ (mesmos) |
| LLMDroid | `LLMDroid-Fastbot/native/agent/GPTAgent.cpp` | LLM agent C++ |
| DroidBot-GPT | `droidbot/input_policy.py` | TaskPolicy + prompts |
| DroidBot-GPT | `droidbot/device_state.py` | UI → texto + action desc |
| AutoDroid | `droidbot/input_policy.py` | TaskPolicy + prompts + memoria |
| AutoDroid | `tools.py` | query_gpt + insert_onclick + parsing |
| AutoDroid | `droidbot/device_state.py` | UI → HTML conversion |
| AutoDroid | `memory/` | Pre-computed embeddings e descricoes |
| DroidAgent | `droidagent/prompts/act.py` | Actor prompt |
| DroidAgent | `droidagent/prompts/plan.py` | Planner prompt |
| DroidAgent | `droidagent/prompts/critique_during_task.py` | Critic prompt |
| DroidAgent | `droidagent/prompts/reflect_task.py` | Reflector prompt |
| DroidAgent | `droidagent/prompts/summarize_state.py` | Observer prompt |
| DroidAgent | `droidagent/functions/possible_actions.py` | 7 function definitions |
| DroidAgent | `droidagent/types/gui_state.py` | UI → JSON tree |
| DroidAgent | `droidagent/memories/working_memory.py` | History → conversation |
| rvsmart | `llm/PromptBuilder.java` | V13 + V17 prompt templates |
| rvsmart | `llm/PromptContext.java` | Context data object |
| rvsmart | `llm/SglangClient.java` | SGLang HTTP client |
| rvsmart | `llm/ToolCallParser.java` | 3-level fallback parser |
| rvsmart | `llm/CoordinateNormalizer.java` | Qwen [0,1000) → pixels |
| rvsmart | `llm/ImageProcessor.java` | PNG → JPEG base64 |
| rvsmart | `llm/ScreenshotCapture.java` | SurfaceControl screenshot |
| rvsmart | `llm/LlmCircuitBreaker.java` | Circuit breaker |

# Relatório de Validação Rigorosa: Integração LLM no APE-RV (gh6-aperv-llm-integration)

Este documento apresenta uma análise técnica profunda e validação rigorosa da proposta de integração de Large Language Models (LLMs) no motor de exploração do APE-RV. O objetivo desta mudança é romper padrões de exploração determinística identificados em experimentos prévios (exp1+exp2) e elevar a capacidade do APE-RV para o estado da arte (SOTA) em testes automatizados de aplicativos Android.

## 1. Contexto e Objetivos

A mudança proposta (`gh6-aperv-llm-integration`) visa integrar o modelo multimodal **Qwen3-VL** (via servidor SGLang) em dois pontos críticos de decisão do agente SATA:
1. **New-State Mode**: Primeira visita a um novo estado abstrato.
2. **Stagnation Mode**: Detecção precoce de estagnação na exploração (antes do restart forçado).

A motivação central é que 47% dos APKs analisados em experimentos anteriores apresentaram traços idênticos em múltiplas execuções, pois o SATA torna-se determinístico após a visita inicial a todas as ações. O uso de LLM multimodal permite decisões semanticamente informadas baseadas na captura visual (screenshot) e na estrutura da UI.

---

## 2. Análise do Estado da Arte (SOTA) vs. Proposta APE-RV

Com base na pesquisa bibliográfica de ferramentas SOTA (2024-2025), como **DroidAgent (ICST 2024)**, **LLMDroid (2025)** e **ScenGen (2025)**, comparamos a proposta do APE-RV:

| Característica | SOTA (DroidAgent/LLMDroid) | Proposta APE-RV | Avaliação |
| :--- | :--- | :--- | :--- |
| **Estratégia** | Agentes autônomos com memória de longo prazo (Goal-driven). | Punctual override (SATA+MOP base com intervenções LLM). | **Realista**: O custo de LLMs em cada clique é proibitivo. A abordagem pontual é mais eficiente (seguindo a tendência do LLM-Explorer 2025). |
| **Visão** | Multimodal (Screenshot + UI Tree). | Multimodal (JPEG 1000px + Structured Widget List). | **Alinhado**: O uso de ambos reduz o erro de coordenadas (84% vs 70%). |
| **Gatilhos** | Contínuo ou por baixa cobertura. | New-state + Stagnation (> threshold/2). | **Superior**: Foca o orçamento de chamadas LLM onde o ganho de informação é maior. |
| **Oráculo** | LLM como Test Oracle (OLLM 2024). | MOP reachability markers [DM]/[M]. | **Diferencial**: O APE-RV usa orientação estática (MOP) para guiar o LLM, o que é mais preciso para bugs de segurança/protocolo do que oráculos genéricos. |

---

## 3. Verificação Técnica Rigorosa

### 3.1 Consistência entre PRD, Specs e Design
A rastreabilidade entre os documentos está excelente.
- **PRD/Specs**: As novas capacidades (`llm-infrastructure`, `llm-routing`, `llm-prompt`) estão formalmente definidas com invariantes e cenários de teste.
- **Tasks**: O plano de implementação (`tasks.md`) segue uma ordem lógica de dependências (Infra -> Prompt -> Router -> Agent Hooks).
- **Consistência**: A decisão D7 (usar `org.json` em vez de `Gson`) é crucial para evitar conflitos no runtime do Android e reduzir o tamanho do JAR final.

### 3.2 Análise do Código-Fonte (Herança do rvsmart)
A reutilização das classes do `rvsmart` (`SglangClient`, `ToolCallParser`, etc.) é uma decisão pragmática correta (D1), mas exige atenção na conversão:
- **Ponto Positivo**: O `ToolCallParser` com 3 níveis de fallback (Native, XML, JSON) é essencial para lidar com as instabilidades do Qwen3-VL na geração de JSON.
- **Ponto Crítico**: A conversão de `Gson` para `org.json` precisa garantir que o parsing de arrays de coordenadas (comum no Qwen) seja mantido manualmente, já que `org.json` é menos flexível que o mapping automático do Gson.

### 3.3 Estratégia de Mapeamento de Coordenadas
O design propõe: **Bounds containment (prioritário) -> Euclidean distance (fallback)** com tolerância proporcional.
- **Análise**: Esta é a estratégia mais robusta disponível. O uso de `max(50, min(w, h) / 2)` como tolerância é superior a valores fixos, pois se adapta ao tamanho do widget.
- **Raw Clicks**: A capacidade de realizar cliques em elementos invisíveis ao UIAutomator (WebView/Canvas) via `MonkeyTouchEvent` é um avanço significativo sobre o APE original e o FastBot, que ficam "cegos" nessas áreas.

---

## 4. Pontos Positivos, Negativos e Riscos

### Pontos Positivos
- **Hibridismo Inteligente**: Não tenta substituir o SATA, mas sim complementá-lo onde ele falha (determinismo).
- **MOP-Aware Prompting**: Inserir marcadores [DM]/[M] no prompt dá ao LLM a "visão" da análise estática, algo que o DroidAgent não possui.
- **Eficiência de Custo**: O orçamento de chamadas (max 200) e os gatilhos seletivos mitigam o custo e a latência (3-5s por chamada).

### Pontos Negativos / Ambiguidades
- **Scroll Exclusion**: O design exclui propositalmente `scroll` do esquema de ferramentas do LLM. Embora o SATA lide com isso, o LLM poderia identificar semanticamente a necessidade de scroll (ex: "Clique no botão 'Aceitar' que está no fim do termo de uso").
- **Multi-turn Context**: O histórico de ações (3-5) é bom, mas o agente não tem um "plano" ou "objetivo" explícito (como no DroidAgent), o que pode levar a decisões desconexas entre chamadas sucessivas.

### Riscos e Mitigação
| Risco | Impacto | Mitigação Proposta |
| :--- | :--- | :--- |
| **Latência** | Alta (adds ~5 min por run). | Circuit breaker e budget (já incluídos). Sugiro timeout agressivo de 10s. |
| **Coordinate Accuracy** | Média (16% de erro). | Estratégia de mapeamento em 2 fases + rejeição de system UI (já incluído). |
| **OOM (Out of Memory)** | Alta (screenshots base64). | Bloque `finally` limpando buffers de imagem (já incluído no design). |
| **SGLang Failure** | Alta (interrompe testes). | Circuit breaker desativa LLM por 60s, mantendo SATA puro (graceful degradation). |

---

## 5. Sugestões de Melhoria (Roadmap)

1. **Vision-based Stagnation**: Atualmente a estagnação é baseada no contador do grafo. Poderíamos usar o LLM para comparar screenshots e detectar visualmente se o app está em um "loop" (ex: carrossel que não avança).
2. **Dynamic Tool Schema**: Adaptar as ferramentas disponíveis no prompt com base no estado (ex: se não há EditText, não oferecer `type_text`).
3. **MOP weighting feedback**: Permitir que o LLM sugira novos pesos para o MOP dinamicamente com base no que ele vê na tela.
4. **Scroll Semântico**: Reavaliar a inclusão de scroll no tool schema se a cobertura estagnar mesmo com LLM.

## 6. Conclusão

A proposta é **extremamente sólida** e representa um salto qualitativo para o APE-RV. Ao contrário de ferramentas puramente baseadas em LLM que são lentas e caras, o APE-RV propõe um **modelo híbrido cirúrgico** que utiliza a força do SATA para exploração rápida e a força do LLM para decisões semânticas complexas.

As decisões de engenharia (herança de código do rvsmart, conversão para `org.json`, prompt builder multimodal) estão alinhadas com as melhores práticas de desenvolvimento para Android e pesquisa acadêmica recente.

**Recomendação**: Aprovar o plano e prosseguir para a implementação, com foco especial nos testes de unidade para o `ToolCallParser`, que é o componente mais frágil devido às inconsistências dos modelos de IA.

---
*Relatório gerado pelo Gemini CLI em 16/03/2026.*

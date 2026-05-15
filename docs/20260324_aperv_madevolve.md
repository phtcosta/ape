# APE-RV Evolutionary Optimization via Claude Code Skills

**Data**: 2026-03-24
**Status**: Ideacao (entrada para workflow SDD)
**Refs**: MadEvolve (arXiv:2602.15951), hello-claude-code (validacao empirica de nesting)

---

## 1. Problema

O APE-RV tem ~21 parametros tuneaveis (8 novos da gh9 + 13 existentes, dos quais 14 foram testados com Optuna) e diversas heuristicas no codigo Java que influenciam a exploracao. O tuning manual mostrou-se limitado:

- 7 variantes testadas (MOP weights, LLM, Optuna) com spread de apenas 1.17pp
- O gargalo eh estrutural, mas a interacao entre parametros eh complexa
- Cada avaliacao leva ~7 min (boot + 5 min run + teardown) — inviavel para grid search exaustivo
- Mudancas de codigo (heuristicas, pesos, formulas) requerem raciocinio sobre o algoritmo, nao apenas numeros

## 2. Proposta

Usar o framework conceitual do MadEvolve (evolucao de algoritmos guiada por LLM) implementado como **Claude Code skills**, sem API key externa. Claude Code atua simultaneamente como:

- **Operador de mutacao**: propoe mudancas em parametros ou codigo
- **Avaliador de fitness**: executa rv-experiment e parseia resultados
- **Seletor**: decide manter ou reverter baseado no score
- **Analista**: raciocina sobre PORQUE uma mutacao funcionou, usando contexto do codebase inteiro

### 2.1 Por que Skills e nao API

| Aspecto | MadEvolve + API | Skills Claude Code |
|---------|----------------|-------------------|
| Custo LLM | $0.01-0.10/mutacao (API key) | Incluido no Claude Code |
| Contexto | Prompt limitado (~8K tokens) | Codebase inteiro + CLAUDE.md + git history |
| Raciocinio | Sem contexto do projeto | Entende a arquitetura, os specs, o historico |
| Automacao | Script Python externo | /loop nativo do Claude Code |
| Persistencia | SQLite/JSON proprio | population.json + git commits |
| Nesting | N/A | Skills podem chamar outras skills (validado ate 5 niveis) |

### 2.2 Validacao empirica do mecanismo

O projeto hello-claude-code (UnB, 2026-02-17) validou empiricamente que:

1. Skills com `context: fork` podem chamar outras skills via Skill tool (T4)
2. Nesting funciona ate 5+ niveis sem degradacao (T11)
3. Scripts bundled podem ser executados via Bash (padrao documentado)
4. `!`command`` injeta dados reais ANTES do Claude raciocinar (dynamic context injection)
5. /loop executa skills em intervalo fixo (built-in do Claude Code)

## 3. Arquitetura

### 3.1 Principio: main context limpo, skills com contexto proprio

O main context do Claude Code funciona APENAS como orquestrador — nao carrega codigo, historico, ou traces. As skills forked tem seu proprio contexto isolado, carregando apenas o que precisam.

Fundamentacao: o hello-claude-code (T4, T9, T11) validou que forked skills podem chamar outras forked skills ate 5+ niveis, com ~3-4s de latencia por nivel e sem degradacao.

### 3.2 Arquitetura hibrida (2 niveis de fork)

```
Main context: "/loop 20m /evolve-step"
  |                                        ← LIMPO: so ve "Step 15: fitness=42.5, kept=true"
  v
evolve-step [context: fork]               ← contexto isolado (~30K tokens)
  |  !`manage_population.py show`          injeta estado + historico
  |  !`manage_population.py history 10`
  |
  |-- Claude analisa historico
  |-- Claude propoe UMA mutacao
  |-- Claude aplica (Edit/Write)
  |-- Claude roda build (mvn package)
  |
  +-- Skill(evolve-evaluate) [context: fork]  ← sub-skill isolada
  |     |  Roda: mvn install + rv-experiment   (~10 min, mecanico)
  |     |  Parseia: summary.csv → JSON         sem raciocinio, so execucao
  |     +-- Retorna: {"fitness": 42.5, "method": 28.3, "mop": 38.2}
  |
  |-- Claude compara score com best
  |-- Se melhor: git commit + atualiza population.json
  |-- Se pior: git checkout + git clean -fd + registra falha
  |
  v
Main context recebe: "Step 15 complete. Mutation: X. Fitness: 42.5. Kept: true."
```

**Por que hibrida (nao 4 sub-skills)**:
- Claude raciocina melhor quando ve historico + proposta + resultado JUNTOS numa conversa coerente
- Dividir analyze→mutate→select em skills separadas perde nuance no raciocinio
- A avaliacao (build + rv-experiment) eh puramente mecanica — NAO precisa de raciocinio
- Isolar a avaliacao numa sub-skill mantém o contexto do orchestrator limpo de logs/traces
- 2 forks = ~7s overhead (vs ~16s para 4 forks)

**Alternativas consideradas**:
- Monolitica (1 fork so): Funciona, mas o contexto do evolve-step carregaria logs de build/experiment
- Totalmente aninhada (4 sub-skills): Overhead de ~16s, perde coerencia de raciocinio, data-passing limitado

### 3.2 Estrutura de arquivos

A arquitetura separa o **plugin generico** (reutilizavel) da **configuracao do projeto** (especifica do aperv). Ver secao 15 para detalhes.

```
# PLUGIN GENERICO (reutilizavel em qualquer projeto)
~/.claude/plugins/madevolve/
|-- skills/
|   |-- evolve-step/
|   |   |-- SKILL.md                # Instrucoes genericas de evolucao
|   |   +-- scripts/
|   |       |-- manage_population.py  # CRUD no population.json
|   |       +-- parse_fitness.py      # Parseia output do evaluator
|   +-- evolve-report/
|       +-- SKILL.md                # Gera relatorio de evolucao
|
# CONFIGURACAO ESPECIFICA DO APERV (no repo do ape)
.claude/skills/aperv-evolve/
|-- config/
|   |-- evolve.yaml               # Comandos de build/evaluate/revert, fitness weights
|   |-- mutable_params.md         # Parametros tuneaveis com descricoes e ranges
|   |-- mutable_code.md           # Regioes de codigo editaveis + invariantes
|   +-- baseline.json             # Resultados do ape original para comparacao
+-- scripts/
    +-- evaluate.sh               # Script de avaliacao especifico do aperv
```

**Resolucao do working directory**: O plugin generico (`~/.claude/plugins/madevolve/`) roda fora do repo do projeto. O `find .claude/skills` relativo ao CWD pode falhar. Solucao: o SKILL.md generico usa `${CLAUDE_SKILL_DIR}` para seus proprios scripts, e descobre o config do projeto via CWD (que o Claude Code seta para o projeto ativo):
```bash
# No SKILL.md generico — CWD eh o projeto ativo
CONFIG_FILE="$(find . -path '*/.claude/skills/*/config/evolve.yaml' | head -1)"
```
Se multiplos projetos tiverem evolve.yaml, o primeiro encontrado eh usado. Para evitar ambiguidade, o evolve.yaml pode incluir `project: aperv` e o script valida.

### 3.3 SKILL.md generico (rascunho)

A skill nao sabe nada sobre Android, Java, ou APE. Le o evolve.yaml para saber como build/evaluate/revert.

```yaml
---
name: evolve-step
description: Run one evolutionary optimization step. Reads project config from .claude/skills/*/config/evolve.yaml
disable-model-invocation: true
allowed-tools: Bash, Read, Write, Edit, Grep, Glob
---

# Evolutionary Optimization Step

## Current State
!`python3 ${CLAUDE_SKILL_DIR}/scripts/manage_population.py show`

## Recent History
!`python3 ${CLAUDE_SKILL_DIR}/scripts/manage_population.py history 10`

## Project Configuration
!`cat $(find .claude/skills -name evolve.yaml -path "*/config/*" | head -1)`

## Your Role

You are an evolutionary algorithm operator. Your goal: maximize the
fitness metric defined in the project config. You have ONE mutation
to propose per step.

## Process

1. **Analyze**: Read the history. What patterns emerge? What improved scores?
   What hurt? Read the mutable_params.md or mutable_code.md from the
   project config directory for context on what can be changed.

2. **Propose**: Describe ONE atomic mutation with clear reasoning.

3. **Apply**: Make the change using Edit or Write tools.

4. **Build**: Run the build_cmd from evolve.yaml.
   If build fails, revert and try a different mutation.

5. **Deploy**: Run the install_cmd from evolve.yaml.

6. **Evaluate**: Run the evaluate_cmd from evolve.yaml.
   Parse the output with: `python3 ${CLAUDE_SKILL_DIR}/scripts/parse_fitness.py`

7. **Record**:
   `python3 ${CLAUDE_SKILL_DIR}/scripts/manage_population.py record \
     --mutation "description" --score <value>`

8. **Decide**:
   - If score > current best: keep changes, commit with evolve.yaml commit_prefix
   - If score <= best: run revert_cmd from evolve.yaml

## Constraints

- ONE mutation per step (atomic, attributable)
- Build MUST succeed before evaluation
- ALWAYS revert on failure or regression (use revert_cmd from config)
- NEVER modify test files, specs, or docs
- Log EVERYTHING in population.json
- Max 3 parameters changed per param mutation
- Max 20 lines changed per code mutation
- BEFORE editing code, READ the file to confirm function/variable names exist
- Revert includes `git clean -fd` to remove any new files created

## Invariants (from project config, NEVER violate)

Read the project's mutable_params.md and mutable_code.md for specific invariants.
Common invariants: all numeric parameters must be > 0, epsilon must be in [0,1],
priority must be >= 0, weights must be non-negative.

## Stopping Criteria

Read stopping config from evolve.yaml. Check before each step:
- If generation > max_generations: STOP
- If no improvement in last stagnation_generations: STOP
- If fitness > target_fitness: STOP and celebrate
```

## 4. Componentes detalhados

### 4.1 evaluate.sh

```bash
#!/bin/bash
# Avalia a versao atual do aperv contra APKs selecionados
# Cria tmpdir com symlinks para filtrar APKs (rv-experiment roda TODOS do diretorio)
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG=$(find .claude/skills -name evolve.yaml -path "*/config/*" | head -1)

RVSEC_HOME="/pedro/.../workspace-rv/rvsec"
RV_ANDROID="$RVSEC_HOME/rv-android"
SOURCE_APKS="$RV_ANDROID/results/cli_experiment_20260305_180341_fe33918e/instrumented_apks"

# APKs de avaliacao rapida (1 simples, 1 medio, 1 complexo)
SELECTED=(
  "com.blippex.app_5.apk"
  "fr.kwiatkowski.ApkTrack_24.apk"
  "org.secuso.privacyfriendlyludo_5.apk"
)

# Criar tmpdir com symlinks apenas para APKs selecionados
EVAL_DIR=$(mktemp -d /tmp/evolve-eval-XXXXXX)
trap "rm -rf $EVAL_DIR" EXIT
for apk in "${SELECTED[@]}"; do
  ln -s "$SOURCE_APKS/$apk" "$EVAL_DIR/$apk"
  # Incluir o JSON de analise estatica se existir
  [ -f "$SOURCE_APKS/${apk}.json" ] && ln -s "$SOURCE_APKS/${apk}.json" "$EVAL_DIR/${apk}.json"
done

cd "$RV_ANDROID"
uv run rv-experiment run \
  --tools aperv:sata_mop \
  --apks-dir "$EVAL_DIR" \
  --timeout 120 \
  --repetitions 1 \
  --skip-monitors --skip-instrument --skip-static \
  2>/dev/null

# Encontrar o resultado mais recente
LATEST=$(ls -td results/cli_experiment_* | head -1)

# Parsear e retornar metricas
python3 "${SCRIPT_DIR}/parse_results.py" "$LATEST/summary.csv"
```

**Tempo estimado**: 3 APKs x (boot + 2 min run + teardown) = ~10 min por avaliacao.

### 4.2 parse_results.py

```python
#!/usr/bin/env python3
"""Parse rv-experiment summary.csv and output fitness metrics."""
import csv, sys, json

def parse(summary_path):
    rows = list(csv.DictReader(open(summary_path)))
    aperv_rows = [r for r in rows if 'aperv' in r['tool']]
    if not aperv_rows:
        print(json.dumps({"error": "no aperv results"}))
        return

    avg_method = sum(float(r['cov_method']) for r in aperv_rows) / len(aperv_rows)
    avg_mop = sum(float(r['cov_rv_method']) for r in aperv_rows) / len(aperv_rows)
    avg_act = sum(float(r['cov_act']) for r in aperv_rows) / len(aperv_rows)
    total_errors = sum(int(r['errors']) for r in aperv_rows)

    result = {
        "method_coverage": round(avg_method, 2),
        "mop_coverage": round(avg_mop, 2),
        "activity_coverage": round(avg_act, 2),
        "violations": total_errors,
        "num_apks": len(set(r['apk'] for r in aperv_rows)),
        "fitness": round(avg_method * 0.6 + avg_mop * 0.3 + avg_act * 0.1, 2)
    }
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    parse(sys.argv[1])
```

A **fitness function** combina:
- 60% method coverage (objetivo principal)
- 30% MOP method coverage (objetivo secundario — metodos crypto)
- 10% activity coverage (diversificacao)

**Penalty terms** (evitar reward hacking):
- Se algum APK tem activity_coverage < 30%: penalty -5.0
- Se algum APK tem method_coverage < 5%: penalty -10.0 (indica crash/stall)

### 4.2.1 Protocolo de avaliacao em 2 fases

O SATA eh estocastico — 1 repeticao NAO distingue melhoria real de ruido. Protocolo:

1. **Triagem** (rapida): 1 rep x 120s x 3 APKs (~10 min)
   - Se fitness > best + 1.0pp: passa para confirmacao
   - Se fitness < best - 1.0pp: rejeita imediatamente
   - Se entre best-1.0 e best+1.0: inconclusive, registra e segue

2. **Confirmacao** (so para candidatos promissores): 3 reps x 120s x 3 APKs (~30 min)
   - Se media 3 reps > best: aceita mutacao
   - Se media 3 reps <= best: rejeita

Isso triplica o tempo para mutacoes aceitas mas EVITA falsos positivos. A maioria das mutacoes eh rejeitada na triagem (~10 min).

### 4.3 manage_population.py

Gerencia o population.json com subcomandos:
- `show`: imprime estado atual (generation, best score, current params)
- `history N`: imprime ultimas N mutacoes com scores
- `record --mutation "..." --score X --generation N`: registra resultado
- `best`: imprime a melhor configuracao encontrada
- `revert`: marca ultima mutacao como revertida

### 4.4 population.json (schema)

```json
{
  "generation": 15,
  "baseline": {
    "method_coverage": 22.39,
    "mop_coverage": 34.73,
    "fitness": 24.85
  },
  "best": {
    "generation": 12,
    "fitness": 28.50,
    "method_coverage": 26.10,
    "mop_coverage": 38.20,
    "mutation": "Increased activityBaseBudget from 50 to 150",
    "params_snapshot": { "coverageBoostWeight": 100, "activityBaseBudget": 150 },
    "code_hash": "abc123"
  },
  "current": {
    "params": { "coverageBoostWeight": 100, "activityBaseBudget": 150 },
    "code_hash": "abc123"
  },
  "learned_patterns": [
    "fuzzing=false consistently better (Optuna finding)",
    "coverage boost formula more impactful than weight tuning",
    "activity budget > 100 helps complex apps but hurts simple ones"
  ],
  "history": [
    {
      "generation": 1,
      "parent_generation": 0,
      "mutation_type": "param",
      "mutation": "coverageBoostWeight 100 -> 200",
      "reasoning": "Coverage boost was effective in cryptoapp smoke test",
      "fitness": 23.10,
      "method_coverage": 21.50,
      "mop_coverage": 33.00,
      "kept": false,
      "confirmed": false,
      "timestamp": "2026-03-24T22:00:00"
    }
  ]
}
```

## 5. Selecao de APKs para avaliacao rapida

Para ~10 min por avaliacao, precisamos 2-3 APKs representativos. Criterios:

| Criterio | APK candidato | Justificativa |
|----------|--------------|---------------|
| Simples (poucas activities, coverage alta) | com.blippex.app_5.apk | 2 activities, ape original 33.72% |
| Medio (varias activities, MOP relevante) | fr.kwiatkowski.ApkTrack_24.apk | 4+ activities, MOP 68.7%, WTG util |
| Complexo (muitas activities, state explosion) | org.secuso.privacyfriendlyludo_5.apk | 9 activities, ape so 14.75%, aperv ganhou +13.6pp |

Alternativa: usar apenas cryptoapp (unico APK, avaliacao em ~3 min) para iteracao rapida, e validar com os 20 APKs periodicamente.

## 6. Tipos de mutacao

### 6.1 Parametros tuneaveis (baixo risco)

| Parametro | Default | Range sugerido | Impacto esperado |
|-----------|---------|---------------|-----------------|
| coverageBoostWeight | 100 | 0-500 | Peso do boost per-action para widgets nao testados |
| activityBaseBudget | 50 | 20-500 | Base de iteracoes por activity antes de budget exhaust |
| activityBudgetPerWidget | 5 | 1-20 | Iteracoes adicionais por widget na activity |
| mopWeightWtg | 200 | 0-500 | Peso do WTG boost para navegacao MOP |
| maxEpsilon | 0.15 | 0.05-0.50 | Epsilon maximo (estado novo, alta exploracao) |
| minEpsilon | 0.02 | 0.01-0.10 | Epsilon minimo (estado explorado, exploitation) |
| mopWeightDirect | 500 | 100-1000 | Peso MOP direto (existente) |
| mopWeightTransitive | 300 | 50-500 | Peso MOP transitivo (existente) |
| mopWeightActivity | 100 | 0-300 | Peso MOP activity-level (existente) |
| activityStableRestartThreshold | 200 | 50-500 | Transicoes na mesma activity antes de restart |
| graphStableRestartThreshold | 100 | 30-300 | Transicoes sem nova acao antes de restart |
| trivialActivityRankThreshold | 3 | 2-10 | Minimo de activities para habilitar trivial activity selection |

### 6.2 Mutacoes de codigo (alto impacto, alto risco)

| Regiao | Arquivo | O que pode mudar |
|--------|---------|-----------------|
| Coverage boost formula | StatefulAgent.adjustActionsByGUITree() | Boost proporcional a 1/(1+count) em vez de binario 0/100 |
| Greedy tiebreaker | State.greedyPickLeastVisited() | Randomizar entre top-3 em vez de pegar o maior priority |
| Budget exhaustion | SataAgent.selectNewActionNonnull() | Boost BACK priority em vez de fallthrough |
| Dynamic epsilon formula | SataAgent.computeDynamicEpsilon() | Usar media movel do gap em vez de gap instantaneo |
| Early-stage forward | SataAgent.selectNewActionEarlyStageForwardGreedy() | Considerar MOP/WTG boost na selecao de greedy states |

### 6.3 Estrategia de mutacao

A calibracao Optuna (secao 14) provou que tuning de parametros sozinho NAO resolve — 50 trials empataram com baseline. Portanto, o MadEvolve deve focar em mutacoes que o Optuna NAO pode fazer: mudancas de logica, formulas, e heuristicas.

Estrategia:
- **Geracoes 1-5**: Aplicar os insights da calibracao como ponto de partida fixo (do_fuzzing=false, MOP weights altos, epsilon baixo) — NAO evoluir parametros, apenas estabelecer baseline com configuracao otima conhecida
- **Geracoes 6+**: Mutacoes de codigo (70% diff patches em 1-3 linhas, 30% reescrita de funcao)
- **Intercalar**: A cada 10 geracoes de codigo, 2-3 geracoes de ajuste fino de parametros para recalibrar ao novo codigo

## 7. Fitness function

```
fitness = 0.6 * avg_method_coverage + 0.3 * avg_mop_coverage + 0.1 * avg_activity_coverage
```

Justificativa:
- Method coverage eh o objetivo principal da tese (explorar mais codigo)
- MOP coverage eh o objetivo secundario (exercitar crypto APIs)
- Activity coverage eh pre-requisito (nao ficar preso numa activity)

### 7.1 Baseline

Do experimento de 20260305 (2 reps, 300s, 20 APKs):
- ape original: method 22.39%, MOP 34.73%, activity variavel
- fitness baseline: 22.39 * 0.6 + 34.73 * 0.3 + ~60 * 0.1 = **23.85**

Qualquer fitness > 23.85 eh melhoria sobre o ape original.

**IMPORTANTE**: Este baseline usa protocolo DIFERENTE da avaliacao rapida (20 APKs x 300s vs 3 APKs x 120s). NAO comparar diretamente fitness do baseline completo (23.85) com fitness da avaliacao rapida (42.49 nos 3 APKs). Cada protocolo tem seu proprio baseline. A avaliacao rapida usa a secao 7.2 como referencia.

### 7.2 Fitness dos APKs selecionados (baseline ape)

| APK | method | MOP | activity | fitness |
|-----|--------|-----|----------|---------|
| com.blippex.app_5 | 33.72 | 67.86 | 100.0 | 50.59 |
| fr.kwiatkowski.ApkTrack_24 | 39.91 | 68.70 | 100.0 | 54.56 |
| org.secuso.privacyfriendlyludo_5 | 14.75 | 26.41 | 55.56 | 22.33 |
| **Media** | **29.46** | **54.32** | **85.19** | **42.49** |

## 8. Integracao com /loop

```bash
# Tuning de parametros (rapido, seguro)
/loop 15m /aperv-evolve-step

# Ou manualmente, um step por vez
/aperv-evolve-step
```

Estimativas:
- 15 min por ciclo = 4 ciclos/hora = ~40 ciclos overnight (10h)
- 20 min por ciclo = 3 ciclos/hora = ~30 ciclos overnight
- Validacao completa (20 APKs) a cada 10 ciclos ou apos melhoria significativa

## 9. Skill de relatorio

```yaml
---
name: aperv-report
description: Generate evolution report comparing discovered algorithms with baseline
disable-model-invocation: true
context: fork
agent: Explore
allowed-tools: Read, Grep, Glob, Bash
---

# APE-RV Evolution Report

Read test-results/evolution/population.json and generate a report:

1. **Lineage tree**: Show the chain of mutations from baseline to best
2. **Parameter sensitivity**: Which parameters had most impact?
3. **Code mutations**: Which code changes helped?
4. **Comparison table**: best vs baseline per APK
5. **Recommendations**: What to try next

Write the report to docs/evolution_report.md
```

## 10. Riscos e mitigacoes

| Risco | Severidade | Mitigacao |
|-------|-----------|-----------|
| Mutacao quebra compilacao | Baixa | mvn package antes de avaliar; revert automatico |
| Mutacao piora coverage | Media | Revert automatico se fitness <= best |
| Overfitting nos 2-3 APKs de avaliacao | Alta | Validacao periodica com os 20 APKs |
| /loop timeout mata avaliacao no meio | Media | evaluate.sh com timeout interno; population.json atualizado atomicamente |
| Context window overflow no skill | Baixa | Skill forked tem contexto limpo; reference files on-demand |
| Mutacao de codigo gera bug sutil (passa build mas comportamento errado) | Media | Invariantes do spec protegem; testes unitarios rodam no build |
| Stagnacao (muitas geracoes sem melhoria) | Media | Historico visivel para Claude raciocinar sobre novas direcoes; intercalar param+code mutations |
| **Permissao bloqueia execucao overnight** | **Alta** | **Ver secao 10.1** |

### 10.1 Permissoes para execucao autonoma overnight

O Claude Code pede aprovacao do usuario para cada tool use que nao esta pre-aprovado. Se uma permissao for solicitada durante a madrugada, o /loop trava esperando input humano.

**Solucao**: configurar `allowed-tools` no SKILL.md + rules em settings.json para pre-aprovar TODAS as operacoes que a skill precisa:

```yaml
# No SKILL.md
allowed-tools: Bash, Read, Write, Edit, Grep, Glob
```

Alem disso, adicionar regras no `.claude/settings.json` (ou `.claude/settings.local.json`) que permitam os comandos especificos:

```json
{
  "permissions": {
    "allow": [
      "Bash(mvn *)",
      "Bash(python3 *)",
      "Bash(uv run rv-experiment *)",
      "Bash(git add *)",
      "Bash(git commit *)",
      "Bash(git checkout *)",
      "Bash(cat *)",
      "Bash(ls *)",
      "Bash(cp *)",
      "Read",
      "Write",
      "Edit",
      "Grep",
      "Glob"
    ]
  }
}
```

**Importante**: testar a skill manualmente ANTES de rodar overnight para verificar que nenhuma permissao eh solicitada. Rodar `/aperv-evolve-step` uma vez completa e observar se houve prompt de permissao.

**Alternativa**: rodar com `--dangerously-skip-permissions` para a sessao overnight (nao recomendado para producao, mas aceitavel para pesquisa em ambiente local controlado):

```bash
claude --dangerously-skip-permissions
/loop 20m /aperv-evolve-step
```

**Cuidado com o /loop**: o /loop roda dentro de uma sessao interativa do Claude Code. Se a sessao morrer (terminal fechado, OOM, etc.), o loop para. Para robustez, considerar rodar dentro de `tmux` ou `screen`:

```bash
tmux new -s evolve
claude --dangerously-skip-permissions
/loop 20m /aperv-evolve-step
# Ctrl+B D para desanexar
```

## 11. Consideracoes operacionais

### 11.1 /loop e avaliacoes longas (L1)

O /loop do Claude Code dispara a skill em intervalo fixo. Se a avaliacao anterior ainda estiver rodando quando o proximo ciclo dispara, o comportamento depende de como o /loop eh implementado:
- Se sequencial (espera o anterior terminar): o intervalo vira "tempo minimo entre ciclos" — OK
- Se concorrente (dispara mesmo com anterior rodando): pode haver conflito de git/files

**Mitigacao**: usar um lockfile no evaluate.sh. Se o lock existe, o ciclo atual pula:
```bash
LOCKFILE="/tmp/evolve-eval.lock"
if [ -f "$LOCKFILE" ]; then
  echo '{"skipped": true, "reason": "previous evaluation still running"}'
  exit 0
fi
touch "$LOCKFILE"
trap "rm -f $LOCKFILE" EXIT
# ... avaliacao ...
```

**Recomendacao**: Intervalo do /loop = tempo de avaliacao + 5 min margem. Para 3 APKs (~10 min), usar `/loop 15m`.

### 11.2 Disk space (L2)

Cada rv-experiment gera: traces, screenshots, logcat, summary.csv, model objects. Para 3 APKs: ~50-100 MB por run. Em 40 runs overnight: ~2-4 GB.

**Mitigacao**: O evaluate.sh limpa resultados antigos apos parsear:
```bash
# Manter apenas os 10 resultados mais recentes
ls -td results/cli_experiment_* | tail -n +11 | xargs rm -rf
```

Para o desktop com 128 GB RAM e disco SSD: nao eh problema. Para o laptop: monitorar disk usage.

### 11.3 Reprodutibilidade (L4)

O processo evolutivo NAO eh reprodutivel no sentido estrito — Claude raciocina diferente a cada sessao. Mas eh RASTREAVEL:

- **population.json**: registra cada mutacao, reasoning, score, timestamp
- **git commits**: cada melhoria eh commitada com descricao da mutacao
- **git log**: lineage completa das mudancas que foram mantidas
- **traces do rv-experiment**: resultados brutos de cada avaliacao

Para replicar um resultado especifico: checkout do commit correspondente + mesmos APKs + mesmo timeout. O resultado varia por aleatoriedade do SATA, mas a mutacao eh deterministica.

### 11.4 Crashes do emulador durante avaliacao

O rv-experiment ja trata crashes: timeout por task, retry logic, error reporting. O evaluate.sh deve tratar exit code != 0:
```bash
if ! uv run rv-experiment run ...; then
  echo '{"error": "evaluation failed", "fitness": -1}'
  exit 0  # Nao propagar erro para o skill — registrar como falha
fi
```
Fitness -1 eh registrado no population.json como falha, e a mutacao eh revertida.

## 12. Mapeamento de componentes MadEvolve → Skills

### 12.1 Principio: adaptar, nao reinventar

O MadEvolve tem ~12.6K linhas de Python em 10 modulos. NAO vamos reescrever do zero — vamos **adaptar** os scripts existentes para funcionar como scripts bundled nas skills.

### 12.2 Tabela de mapeamento

| Modulo MadEvolve | Linhas | O que faz | Disposicao | Como adapta |
|-----------------|--------|-----------|-----------|-------------|
| `transformer/blocks.py` | 175 | Detecta EVOLVE-BLOCK markers, split mutable/immutable | **REUSAR** | As-is. Marca regioes mutaveis no codigo Java |
| `repository/selection/ancestry.py` | 324 | Parent selection (power-law, tournament, adaptive) | **REUSAR** | Logica de selecao eh domain-agnostic. Simplificar interface |
| `repository/storage/schema.py` | 151 | Schema SQLite para populacao | **ADAPTAR** | Converter para JSON schema. Manter colunas de lineage |
| `repository/storage/artifact_store.py` | 374 | CRUD + lineage queries | **ADAPTAR** | ProgramRecord → JSON. get_lineage/get_top reutilizaveis |
| `repository/selection/context.py` | 220 | Inspiration selection + PatternTracker | **ADAPTAR** | Manter PatternTracker (rastreia quais patterns melhoraram). Remover embedding-based diversity |
| `repository/analytics/metrics.py` | 312 | Complexidade de codigo (AST Python) | **SUBSTITUIR** | Python-specific. Substituir por fitness via rv-experiment |
| `analyzer/generate_report.py` | 365 | Relatorio markdown de evolucao | **ADAPTAR** | Simplificar: ler JSON, gerar tabela + lineage tree |
| `analyzer/core.py` | 590 | Analise comparativa via LLM | **ADAPTAR** | Em vez de chamar API, o Claude Code analisa diretamente |
| `engine/configuration.py` | 295 | Config dataclasses | **ADAPTAR** | Simplificar para evolve.yaml flat |
| `templates/foundation.py` | 186 | Prompts base para LLM | **SUBSTITUIR** | Claude Code nao precisa de prompt template — o SKILL.md eh o prompt |
| `templates/differential.py` | 201 | Prompts SEARCH/REPLACE | **SUBSTITUIR** | Claude Code tem Edit tool nativo |
| `engine/orchestrator.py` | 1075 | Loop principal de evolucao | **SUBSTITUIR** | SKILL.md + /loop substitui o orchestrator |
| `provider/` (todo) | ~900 | Gateway LLM (APIs HTTP) | **SUBSTITUIR** | Claude Code eh o LLM, nao precisa de gateway |
| `executor/` (todo) | ~700 | Job runners (local/cluster) | **SUBSTITUIR** | evaluate.sh + Bash tool |
| `synthesizer/composer.py` | 335 | Crossover de programas | **PULAR** (v2) | Nao implementar em v1 |
| `repository/topology/` | ~800 | MAP-Elites grid + features | **PULAR** (v2) | JSON linear em v1 (ver secao 12.1.1 para quando adotar) |

### 12.3 Scripts a criar (adaptados do MadEvolve)

| Script | Baseado em | Linhas estimadas | O que faz |
|--------|-----------|-----------------|-----------|
| `manage_population.py` | artifact_store.py + schema.py + ancestry.py | ~200 | CRUD + parent selection + lineage. JSON em vez de SQLite |
| `parse_fitness.py` | metrics.py (conceito, nao codigo) | ~50 | Parseia summary.csv → JSON com fitness |
| `generate_report.py` | generate_report.py + core.py | ~150 | Lineage tree + tabela comparativa + insights |
| `pattern_tracker.py` | context.py (PatternTracker) | ~80 | Rastreia quais tipos de mutacao melhoraram scores |
| `blocks.py` | blocks.py | ~175 (reuso direto) | EVOLVE-BLOCK markers para codigo Java |

### 12.4 O que NAO precisa de script (Claude Code nativo)

| Funcionalidade MadEvolve | Substituido por |
|--------------------------|----------------|
| LLM API calls (prompt → response) | Claude Code raciocina diretamente no SKILL.md |
| SEARCH/REPLACE diff parsing | Edit tool nativo |
| Full code rewrite | Write tool nativo |
| Job submission + monitoring | Bash tool + evaluate.sh |
| Prompt template rendering | !`command` dynamic injection no SKILL.md |
| Model selection (UCB1 bandit) | 1 unico LLM (Claude), nao precisa |
| Embedding computation | Nao precisa (diversidade via raciocinio) |

### 12.5 Equivalencia: prompt templates → SKILL.md

Os prompt templates do MadEvolve (`templates/foundation.py`, `templates/differential.py`, etc.) NAO sao descartados — sao **absorvidos** pelo SKILL.md. O conteudo eh equivalente:

| MadEvolve (Python) | Skills (SKILL.md) |
|--------------------|--------------------|
| `BASE_SYSTEM_PROMPT` (papel do LLM) | Secao "## Your Role" no SKILL.md |
| `EVOLUTION_CONTEXT` com `{score}`, `{generation}` | `!`manage_population.py show`` (dynamic injection) |
| `format_code_block(parent_code)` | `!`cat $(find ... mutable_code.md)`` ou Claude le via Read tool |
| `DIFFERENTIAL_PROMPT` (instrucoes SEARCH/REPLACE) | Secao "## Process" com instrucao "Use Edit tool" |
| `HOLISTIC_PROMPT` (reescrita completa) | Instrucao "Use Write tool to rewrite the function" |
| `format_metrics(scores)` | `!`manage_population.py history 10`` |
| `DOMAIN_SPECIFIC_PROMPT` (contexto cientifico) | reference/mutable_code.md + reference/mutable_params.md |
| `CONSTRAINT_PROMPT` (limites, invariantes) | Secao "## Constraints" + "## Invariants" no SKILL.md |

A diferenca fundamental: no MadEvolve, os templates sao renderizados em Python (`template.format(**kwargs)`) e enviados via HTTP API. Nas skills, o SKILL.md **eh** o template — o Claude Code o le diretamente, com os `!`command`` ja expandidos antes da leitura. Nao ha camada de renderizacao intermediaria.

## 13. Diferenca vs MadEvolve original

| Aspecto | MadEvolve | Nossa implementacao |
|---------|-----------|-------------------|
| LLM | API externa (GPT-4, Gemini, Claude via HTTP) | Claude Code nativo (sem API key) |
| Populacao | MAP-Elites grid + islands + elite archive | JSON simples com historico linear |
| Paralelismo | Async, multiplas avaliacoes simultaneas | Sequencial via /loop |
| Mutacao | Prompt template, LLM ve so o codigo da funcao | Claude ve todo o codebase + specs + git + CLAUDE.md |
| Avaliacao | HPC cluster, simulacoes rapidas (~segundos) | rv-experiment (~7-10 min por avaliacao) |
| Parametros | Auto-diff ou grid search interno | Claude propoe baseado em raciocinio |
| Report | Pipeline automatizado de 3 etapas | Skill separada (/aperv-report) |
| Escala | Centenas de geracoes, milhares de avaliacoes | Dezenas de geracoes (~30-40 overnight) |

### 11.1 O que NAO copiamos do MadEvolve (v1)

- **MAP-Elites grid**: Complexidade desnecessaria para v1 (~12 parametros, ~30-40 geracoes)
- **Island model**: Sem paralelismo, nao precisa de isolamento
- **LLM ensemble com UCB1 bandit**: Temos 1 LLM (Claude), nao precisa de selecao
- **Meta-prompt evolution**: O skill eh fixo, Claude raciocina a cada step
- **Novelty filtering**: Historico linear eh suficiente para evitar repeticoes

### 11.1.1 Escalabilidade: quando adotar MAP-Elites

O JSON linear funciona para dezenas de geracoes com ~12 parametros. Se escalarmos para **centenas de geracoes** ou **evolucao de codigo complexo** (onde mutacoes podem ser qualitativamente diferentes — nova formula, nova heuristica, nova estrutura), o historico linear fica insuficiente para manter diversidade.

Sinais de que precisamos de MAP-Elites:
- **Stagnacao persistente** apos 50+ geracoes sem melhoria
- **Overfitting**: fitness sobe nos APKs de avaliacao mas desce no conjunto completo
- **Convergencia prematura**: todas as mutacoes sao variantes do mesmo tema
- **Espaco de busca cresce**: mutacoes de codigo criam variantes qualitativamente diferentes (nao apenas numericas)

Nesse ponto, migrar para MAP-Elites com 3 eixos:
- **Performance** (fitness score)
- **Complexity** (linhas de codigo modificadas vs baseline)
- **Diversity** (distancia de edit vs melhor programa)

A infraestrutura de skills suportaria: manage_population.py passaria a usar a grid em vez de lista, e a selecao de parent consideraria diversidade alem de fitness. O SKILL.md e o evaluate.sh nao mudariam.

### 11.2 O que ADAPTAMOS

- **Population tracking**: JSON com historico linear em vez de grid
- **Fitness evaluation**: rv-experiment em vez de simulacao
- **Mutation operator**: Claude Code com contexto total em vez de API com prompt
- **Report generation**: Skill separada que gera markdown
- **Iteration loop**: /loop do Claude Code em vez de loop Python

## 14. Resultados de calibracao anteriores (contexto critico)

### 12.1 Optuna MACRO calibracao (14 parametros, 50/130 trials)

O Optuna calibrou 14 parametros em 50 trials com 30 APKs. Resultado:

| Tool | Method% | MOP% | Score | Violations |
|------|---------|------|-------|-----------|
| ape (original) | 32.36 | 38.33 | 35.34 | 128 |
| aperv:sata_mop_v1 (500/300/100) | 33.57 | 37.18 | 35.38 | 134 |
| **CALIBRATED best (#35)** | **32.81** | **37.71** | **35.26** | **72** |
| aperv:sata (no MOP) | 31.38 | 35.27 | 33.33 | 128 |
| aperv:sata_mop_llm (defaults) | 30.77 | 34.93 | 32.85 | 190 |
| rvsmart:mvp | 27.41 | 30.33 | 28.87 | 164 |

**Conclusao**: Calibracao EMPATOU com baseline (35.26 vs 35.34). 50 trials de Optuna nao conseguiram bater o ape original. Isso CONFIRMA que o gargalo eh estrutural, nao parametrico.

### 12.2 Insights da calibracao que o MadEvolve deve aproveitar

- **do_fuzzing=false**: TODOS os top-10 trials desabilitaram fuzzing. Fuzzing desperdicava o budget de 600s.
- **mop_weight_direct alto**: Melhor trial em 990 (teto do range [100,1000]). O peso MOP direto deve ser alto.
- **Epsilon baixo (0.06)**: Modo greedy/exploitation domina em runs de 10 min.
- **Fast restart (50 steps)**: Threshold de stagnacao baixo ajuda.
- **max_states_per_activity alto (30)**: Mais estados = mais exploracao intra-activity.

### 12.3 LLM nao ajudou (pre-calibracao)

- `aperv:sata_mop_llm` com defaults: 30.77% method vs 33.57% baseline = **-2.8pp**
- 37.3% no_match rate (3,554 de 9,525 chamadas desperdicadas)
- Cada no_match = ~1.5s de overhead puro
- LLM precisa do fix de no_match (gh46) antes de poder contribuir

### 12.4 Implicacoes para o MadEvolve

1. **Tuning de parametros sozinho NAO resolve** — Optuna provou isso em 50 trials
2. **Mutacoes de codigo sao o caminho** — mudar formulas, heuristicas, estrutura
3. **Fuzzing deve ficar desabilitado** como ponto de partida
4. **MOP weights altos funcionam** — manter como baseline
5. **O MadEvolve deve focar em mutacoes que o Optuna NAO pode fazer**: mudar logica, adicionar heuristicas, reformular scoring

## 15. Skills genericas vs especificas

### 13.1 Principio: skills genericas, configuracao especifica

As skills MadEvolve devem ser **genericas e reutilizaveis** em qualquer projeto. O que eh especifico do APE-RV vai em arquivos de configuracao, nao na skill.

```
# GENERICO (plugin reutilizavel)
~/.claude/plugins/madevolve/
|-- skills/
|   |-- evolve-step/
|   |   |-- SKILL.md              # Instrucoes genericas de evolucao
|   |   +-- scripts/
|   |       |-- manage_population.py
|   |       +-- parse_fitness.py
|   +-- evolve-report/
|       +-- SKILL.md              # Gera relatorio de evolucao
|
# ESPECIFICO DO PROJETO (no repo do aperv)
.claude/skills/aperv-evolve/
|-- SKILL.md                       # Wrapper que chama evolve-step com config
|-- config/
|   |-- evolve.yaml                # Config: evaluate_cmd, fitness_weights, etc.
|   |-- mutable_params.md          # Parametros tuneaveis do aperv
|   |-- mutable_code.md            # Regioes de codigo editaveis
|   +-- baseline.json              # Resultados do baseline para comparacao
+-- scripts/
    +-- evaluate.sh                # Script de avaliacao especifico do aperv
```

### 13.2 evolve.yaml (configuracao especifica do projeto)

```yaml
# .claude/skills/aperv-evolve/config/evolve.yaml
project: aperv
evaluate_cmd: "${SKILL_DIR}/scripts/evaluate.sh"
build_cmd: "mvn -f /pedro/.../ape package -q"
install_cmd: "mvn -f /pedro/.../ape install -q"
revert_cmd: "git checkout -- src/ ape.properties && git clean -fd src/"
commit_prefix: "evolve(gh9)"

fitness:
  weights:
    method_coverage: 0.6
    mop_coverage: 0.3
    activity_coverage: 0.1

population:
  path: "test-results/evolution/population.json"
  max_history: 200

mutation:
  # Estrategia escalonada (definida no SKILL.md, nao aqui):
  # Gen 1-5: baseline fixo com insights Optuna (sem mutacao)
  # Gen 6+: foco em codigo (70% diff, 30% reescrita)
  # A cada 10 gen de codigo: 2-3 gen de recalibracao de params
  strategy: "phased"  # skill le e segue a estrategia

stopping:
  max_generations: 100
  stagnation_generations: 15   # parar se 15 geracoes sem melhoria
  target_fitness: 55.0         # parar se atingir (nos 3 APKs de avaliacao)
  min_improvement_rate: 0.05   # parar se <5% das ultimas 20 mutacoes melhoram

apks:
  evaluation:
    - com.blippex.app_5.apk
    - fr.kwiatkowski.ApkTrack_24.apk
    - org.secuso.privacyfriendlyludo_5.apk
  validation:
    dir: "results/cli_experiment_20260305_180341_fe33918e/instrumented_apks"
```

### 13.3 SKILL.md generico (evolve-step)

A skill generica le o evolve.yaml e segue o protocolo sem saber nada sobre Android, Java, ou APE:

```yaml
---
name: evolve-step
description: Run one evolutionary optimization step. Reads config from .claude/skills/*/config/evolve.yaml
disable-model-invocation: true
allowed-tools: Bash, Read, Write, Edit, Grep, Glob
---

# Evolutionary Optimization Step

## Current State
!`python3 ${CLAUDE_SKILL_DIR}/scripts/manage_population.py show`

## Configuration
!`cat $(find .claude/skills -name evolve.yaml -path "*/config/*" | head -1)`

## Mutable Parameters
!`cat $(find .claude/skills -name mutable_params.md -path "*/config/*" | head -1)`

## Your Role
[... instrucoes genericas de mutacao, avaliacao, selecao ...]
```

### 13.4 Reutilizacao em outros projetos

O mesmo plugin funcionaria para:
- **rv-agent**: evoluir scorers e estrategias de navegacao
- **rvsmart**: evoluir a combinacao de scorers
- **Qualquer projeto com build + evaluate**: basta criar o evaluate.sh e o evolve.yaml

## 16. Execucao paralela no desktop (64 cores, 128GB RAM)

### 14.1 Infraestrutura existente

O rv-android gh9 ja tem infraestrutura Docker para execucao paralela:
- `calibration_orchestrator.py` (~600 linhas): Optuna ask/tell com Docker
- `baseline_docker.py`: Execucao batch com round-robin de APKs
- Docker image `phtcosta/rvandroid:0.8.0` com ape-rv.jar
- 10 containers x 4 CPUs = 40 CPUs paralelos

### 14.2 Adaptacao para MadEvolve paralelo

Em vez de Optuna propor parametros, Claude propoe N mutacoes em batch:

```
Ciclo paralelo:
1. Claude propoe 10 mutacoes (params ou code variants)
2. Cada mutacao eh avaliada em 1 container Docker
3. 10 avaliacoes rodam em paralelo (~7 min)
4. Resultados coletados
5. Claude analisa e propoe proximo batch
6. Repete
```

**Throughput**:
- Laptop (sequencial): ~4 avaliacoes/hora
- Desktop (10 paralelo): ~85 avaliacoes/hora (21x speedup)
- Desktop overnight (10h): ~850 avaliacoes vs ~40 no laptop

### 14.3 JAR deployment sem rebuild de imagem Docker

Cada mutacao produz um JAR diferente. Sao 3 estrategias possiveis:

**A) Volume mount (simples, sequencial)**
```bash
docker run -v /path/to/ape-rv.jar:/opt/.../aperv-tool/tools/aperv/ape-rv.jar:ro ...
```
Funciona para avaliacao sequencial (1 mutacao por vez). O host faz `mvn package`, o container monta o JAR via bind mount. Sem rebuild de imagem.

**B) Diretorio de JARs (paralelo, batch)**
Para N mutacoes em paralelo, cada uma precisa de um JAR diferente:
```
/tmp/evolve/
|-- mutation_01/ape-rv.jar   # JAR com mutacao 1
|-- mutation_02/ape-rv.jar   # JAR com mutacao 2
|-- ...
```
Cada container monta seu JAR especifico:
```bash
docker run -v /tmp/evolve/mutation_01/ape-rv.jar:/opt/.../ape-rv.jar:ro container_01
docker run -v /tmp/evolve/mutation_02/ape-rv.jar:/opt/.../ape-rv.jar:ro container_02
```
O host prepara todos os JARs antes de lancar os containers. Para isso, precisa compilar N variantes. Opcoes:
- **Git worktrees**: N worktrees com N versoes do codigo, cada uma compila independentemente
- **Copiar src, editar, compilar**: Copia src/ para tmpdir, aplica patch, `mvn package`
- **Apenas parametros**: Se a mutacao eh so ape.properties, o JAR eh o MESMO para todos. Cada container recebe um ape.properties diferente via volume mount.

**C) Mutacoes de parametros nao precisam de rebuild**
Para tuning de parametros, todos os containers usam o MESMO JAR. A diferenca esta no ape.properties, que eh passado via variavel de ambiente do Docker (o aperv-tool le as config e faz `adb push` do properties para o emulador Android dentro do container):
```bash
# O aperv-tool aceita parametros via tool spec no rv-experiment:
# --tools "aperv:sata_mop@coverageBoostWeight=200,activityBaseBudget=150"
# Cada container recebe uma tool spec diferente

docker-compose up container_01  # tools=aperv:sata_mop@coverageBoostWeight=200
docker-compose up container_02  # tools=aperv:sata_mop@coverageBoostWeight=50
```
A infraestrutura de calibracao do rv-android (calibration_orchestrator.py) ja faz exatamente isso: gera tool specs com parametros diferentes e lanca containers em paralelo. O MadEvolve reutiliza essa infraestrutura.

Isso eh muito mais rapido — sem compilacao, sem worktrees. O JAR eh montado via volume e os parametros vao via tool spec.

**Recomendacao**: Comecar com C (parametros via ape.properties volume). Quando escalar para mutacoes de codigo, usar B (worktrees).

### 14.4 Arquitetura desktop

```
Claude Code (sessao no desktop)
  |
  +-- /evolve-batch 10        # Propoe 10 mutacoes
  |     |-- mutation_1.patch
  |     |-- mutation_2.patch
  |     +-- ...
  |
  +-- scripts/evaluate_batch.sh  # Lanca 10 containers Docker
  |     |-- container_1 (mutation_1) -> summary_1.csv
  |     |-- container_2 (mutation_2) -> summary_2.csv
  |     +-- ...
  |
  +-- /evolve-select            # Analisa resultados, seleciona melhores
        +-- Atualiza population.json
```

Isso requer uma skill adicional:

```yaml
---
name: evolve-batch
description: Propose N mutations for parallel evaluation on Docker cluster
disable-model-invocation: true
---
```

### 14.5 Compatibilidade laptop/desktop

| Aspecto | Laptop (sequencial) | Desktop (paralelo) |
|---------|--------------------|--------------------|
| Skill | /evolve-step (1 mutacao por vez) | /evolve-batch N (N mutacoes por vez) |
| Avaliacao | evaluate.sh (rv-experiment local) | evaluate_batch.sh (Docker containers) |
| Throughput | ~4/hora | ~85/hora |
| Claude | Raciocina entre cada avaliacao | Raciocina entre batches |
| Population | JSON linear | JSON linear (mesmo formato) |
| Config | evolve.yaml | evolve.yaml (mesmo formato, diff evaluate_cmd) |

O population.json eh o mesmo formato em ambos os modos. Pode comecar no laptop (sequencial, iterativo) e continuar no desktop (paralelo, batch) sem perder historico.

## 17. Plugin: estrutura e publicacao

### 17.1 Estrutura do plugin madevolve

```
madevolve/
|-- .claude-plugin/
|   +-- plugin.json                # Manifest (nome, versao, autor)
|-- skills/
|   |-- evolve-step/
|   |   |-- SKILL.md               # Skill principal (orchestrator forked)
|   |   +-- scripts/
|   |       |-- manage_population.py  # Adaptado de artifact_store.py + ancestry.py
|   |       |-- parse_fitness.py      # Parseia output generico do evaluator
|   |       |-- pattern_tracker.py    # Adaptado de context.py (PatternTracker)
|   |       +-- blocks.py            # Reuso direto de transformer/blocks.py
|   |-- evolve-evaluate/
|   |   +-- SKILL.md               # Sub-skill forked para avaliacao mecanica
|   +-- evolve-report/
|       |-- SKILL.md               # Gera relatorio de evolucao
|       +-- scripts/
|           +-- generate_report.py   # Adaptado de analyzer/generate_report.py
|-- settings.json                  # Permissions pre-configuradas para overnight
+-- README.md                      # Docs de instalacao e uso
```

### 17.2 plugin.json

```json
{
  "name": "madevolve",
  "description": "Evolutionary optimization of algorithms via Claude Code skills. Adapts MadEvolve (arXiv:2602.15951) to run without API keys — Claude Code IS the LLM.",
  "version": "0.1.0",
  "author": {
    "name": "Pedro Costa",
    "url": "https://github.com/phtcosta"
  },
  "repository": "https://github.com/phtcosta/madevolve-claude",
  "license": "MIT"
}
```

### 17.3 settings.json do plugin

Pre-aprova as permissoes necessarias para execucao autonoma:

```json
{
  "permissions": {
    "allow": [
      "Bash(python3 *)",
      "Bash(mvn *)",
      "Bash(git add *)",
      "Bash(git commit *)",
      "Bash(git checkout *)",
      "Bash(git clean *)",
      "Read", "Write", "Edit", "Grep", "Glob"
    ]
  }
}
```

### 17.4 Distribuicao

| Metodo | Como instalar | Para quem |
|--------|--------------|-----------|
| **Local (dev)** | `claude --plugin-dir ./madevolve` | Desenvolvimento e teste |
| **GitHub** | `/plugin install github:phtcosta/madevolve-claude` | Comunidade open-source |
| **Marketplace oficial** | Submit em claude.ai/settings/plugins/submit | Todos os usuarios Claude Code |

### 17.5 Uso pelo projeto (aperv)

O projeto APE-RV instala o plugin e adiciona configuracao especifica:

```bash
# Instalar o plugin
/plugin install github:phtcosta/madevolve-claude

# Ou via local durante desenvolvimento
claude --plugin-dir /path/to/madevolve
```

Configuracao especifica no repo do ape:
```
.claude/skills/aperv-evolve/
|-- config/
|   |-- evolve.yaml              # Comandos de build/evaluate/revert
|   |-- mutable_params.md        # Parametros tuneaveis do aperv
|   |-- mutable_code.md          # Regioes de codigo editaveis
|   +-- baseline.json            # Resultados baseline para comparacao
+-- scripts/
    +-- evaluate.sh              # Script de avaliacao especifico
```

Skills invocadas com namespace do plugin:
```bash
/madevolve:evolve-step              # Um passo de evolucao
/madevolve:evolve-report            # Relatorio
/loop 20m /madevolve:evolve-step    # Overnight automatico
```

O plugin descobre o evolve.yaml do projeto automaticamente:
```bash
find . -path '*/.claude/skills/*/config/evolve.yaml' | head -1
```

### 17.6 Reutilizacao em outros projetos

Qualquer projeto pode usar o plugin criando seu proprio evolve.yaml + evaluate.sh:

| Projeto | evolve.yaml | evaluate.sh |
|---------|------------|-------------|
| **aperv** (APE-RV) | mvn package + rv-experiment | 3 APKs, 120s |
| **rv-agent** | pytest + rv-experiment | scorers optimization |
| **rvsmart** | pytest + rv-experiment | scorer combination |
| **Qualquer ML** | train.py + eval.py | custom metrics |
| **Qualquer software** | make + test suite | test pass rate |

## 18. Proximos passos

1. Finalizar a gh9 do ape (commitar, fechar issue)
2. Criar repo `madevolve-claude` no GitHub
3. Adaptar scripts do MadEvolve (manage_population.py, blocks.py, pattern_tracker.py, generate_report.py)
4. Criar SKILL.md do evolve-step e evolve-evaluate (arquitetura hibrida, secao 3.2)
5. Criar a configuracao especifica do aperv (evolve.yaml, evaluate.sh, mutable_params.md, mutable_code.md)
6. Testar localmente: `claude --plugin-dir ./madevolve` + `/madevolve:evolve-step`
7. Validar permissoes: rodar 1 ciclo completo sem prompts de permissao
8. Rodar overnight no laptop: `/loop 20m /madevolve:evolve-step` via tmux
9. Analisar resultados: `/madevolve:evolve-report`
10. Quando desktop disponivel: adaptar evaluate_batch.sh para Docker paralelo
11. Publicar v0.1.0 no GitHub
12. Submit ao marketplace oficial do Claude Code

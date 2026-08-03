# Investigação: seção `components` do JSON estático × component-triggering no APE-RV

**Data:** 2026-07-07. **Pergunta:** estamos usando corretamente a seção `components` do JSON de análise estática (todos os componentes do manifest), com vistas a invocar componentes diretamente (activities com intent, services, etc.)? Relação com a futura 4ª change (profundidade/cov_act).
**Método:** 3 subagentes (schema+parser; consumidor; evidência empírica/histórica) + síntese. Evidência de 37 JSONs reais do dataset cmpft2, código do worktree `ape-mop-fairtest`, 1314 traces (cmpft+cmpft2), histórico git/openspec dos dois repos.

## Veredito em três camadas

### 1. Parser (MopData) — CORRETO E COMPLETO
`MopData.parseComponents` (`MopData.java:589-643`, Pass 4) lê 100% da seção: os 4 tipos (`activities` 290 / `receivers` 146 / `services` 104 / `providers` 89 no dataset), com `className`, `isMain`, `exported`, `permission` (+`readPermission`/`writePermission` em providers), `intentFilters` completos — incluindo o bloco `data{}` (schemes/hosts/ports/paths/pathPrefixes/pathPatterns/mimeTypes, `parseDataSpec` `:661-671`), `reachesTarget`, `targetMethods`, `authorities`. **Nenhum campo é dropado.** POJOs em `ComponentInfo.java` refletem tudo (gh60 D15).

### 2. Uso real nos experimentos — INEXISTENTE
O consumidor único é o component-triggering (`SataAgent.java:414-419` → `StatefulAgent.triggerMopComponent` `:1075-1155`), **dormente por default duas vezes**: `componentPercentage=0.0` (`Config.java:182`) e, para activities, `activityTriggerEnabled=false` (`Config.java:140`). Confirmado: **0 linhas `[APE-RV] Triggering`** nos 1314 traces de cmpft/cmpft2 (hits aparentes eram logback `TimeBasedFileNamingAndTriggeringPolicy` em `.logcat`). Ou seja: carregamos a seção inteira e não a exercitamos. (`decision_source=Budget` ≠ triggering: é o soft-constraint do activity-budget gh9, `SataAgent.java:376`.)

### 3. Qualidade do consumo latente (se ligado) — INCOMPLETO EM 4 PONTOS
1. **Deep-links desperdiçados**: 88 intent-filters com `data{}` (23/37 apps) parseados e expostos, mas `dispatchTrigger` (`StatefulAgent.java:1118-1144`) só faz `setComponent/setAction/addCategory` — **nunca monta URI nem MIME**. O dado para `Intent(VIEW, uri)` está todo na mesa.
2. **Permissões só logadas**: `hasPermissionGate()` aparece no log e nada mais; trigger sob gate falha com `SecurityException` engolida (49 recv/svc no dataset).
3. **Invisibilidade no modelo**: o trigger é side-effect sem `[APE-STEP]`; `DecisionSource.Component` é enum morto (nunca atribuído). Uma activity lançada por trigger vira **aresta órfã/mal-atribuída** no modelo (o próximo `generateEvents` credita a transição à ação errada) — corrompe a WTG. Era o aviso original da spec.
4. **`startActivity` reflexivo só Q+** (`AndroidDevice.java:509-531`): pré-Q falha com WARNING silencioso.

Higiene: **duas specs desatualizadas** — `component-triggering/spec.md` ainda diz "Activities and ContentProviders are excluded" (INV-CT-03) quando o código gh13 T1.4/T1.5 já implementa os 4 tipos; `static-analysis-entrypoints/spec.md:123-127` usa vocabulário antigo `reachesMop`/`mopMethods` e omite `data{}`/`permission` (o correto está só em `mop-guidance/spec.md:21`).

## A evidência gh11 ("sandwichroulette −45pp") está desatualizada

- Fonte única: mensagem do commit `0829133` (2026-04-01) — medição ad-hoc, sem campanha logada. O que mediu: `startActivity()` **não-filtrado** (sem `reachesTarget`, sem `exported`, schema pré-gh57/gh13) deu 53 saltos cegos de activity no app trivial `com.maxfierke.sandwichroulette` (−45pp; 10/32 apps com perda >2pp puramente por activity triggers). Conclusão da época: desligar, não redesenhar.
- O código atual (gh13 T1.4) é outro: round-robin gated por `reachesTarget` + `exported`. O −45pp mede um mecanismo que **não existe mais**.
- Caveat que inverte o risco: `reachesTarget` está quebrado producer-side em apps ofuscados (B1/B8, relatório 20260619: join 0/14.658; 46% dos apps com reachability <1%). Ligar o gate atual hoje → conjunto de tuplas **vazio** na maioria dos apps reais. O risco mudou de "atrapalha o SATA" para "não faz nada".

## Insight central para a 4ª change (profundidade)

**`reachesTarget` é o filtro errado para o objetivo de profundidade.** Ele serve ao MOP-guidance; para cov_act, o alvo são as activities **não-visitadas** — e o critério correto usa campos **manifest-based** (`exported`, `intentFilters`, `data{}`), que vêm do DefaultXMLParser/manifest e são **imunes** ao problema de ofuscação que quebra o reachability. A seção components pode servir à profundidade exatamente pelo eixo que não está quebrado.

Desenho candidato (2 alavancas complementares — **não gerado**, aguardando decisão):
- **(A) GUI-first — activity-frontier boost**: usar as `transitions` estáticas já carregadas para dar boost ao widget cujo destino é activity não-visitada. Sem nenhum dos problemas do triggering (o modelo vê um clique normal).
- **(B) Trigger direto calibrado como fallback de estagnação**: `startActivity` de activity **exported e não-visitada** (critério manifest-based, não reachesTarget), disparado por **estagnação** (não probabilístico por step — o erro de calibração do gh11), com URI de deep-link quando `data{}` existir, e com **integração ao modelo**: emitir `[APE-STEP]` com `decision_source=Component` (ressuscita o enum morto) e registrar a transição como aresta de trigger para não corromper a WTG.
- Pré-requisito de higiene (independente): atualizar as specs `component-triggering` e `static-analysis-entrypoints` para o estado real do código (P4).

## Números de referência (dataset cmpft2, 37 apps amostrados)
- exported: 101/290 activities, 66/146 receivers, 35/104 services, 5/89 providers.
- reachesTarget=true: 210 act, 75 recv, 46 svc, 17 prov (mas ver caveat de ofuscação producer-side).
- Deep-links: 88 intent-filters com data{} em 23/37 apps.
- Substrato trigger atual (se ligado, gate reachesTarget): 121 recv/svc candidatos em 25/37 apps; 11 apps com provider candidato.

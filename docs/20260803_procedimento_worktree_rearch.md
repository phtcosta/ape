# Procedimento de worktree — re-arquitetura `rearch-01` … `rearch-07`

**Data da decisão**: 2026-08-03 (dono do projeto)
**Escopo**: os 7 changes em `openspec/changes/rearch-0*` (309 tarefas, 0 implementadas nesta data)
**Estado**: decisão de procedimento. Nada foi implementado; nenhuma worktree foi criada ainda.

---

## 1. A decisão

A implementação dos 7 estágios acontece em **uma única worktree**, na branch `rearch`, criada quando
o estágio 1 começar. Os estágios aterrissam em sequência nessa mesma branch e o merge em `master`
acontece **uma vez só, depois do estágio 7**.

A alternativa considerada e recusada foi uma worktree por estágio. Ela é defensável — cada change já é
uma unidade de commit e de gate independente — mas produz 7 ciclos de criação/merge/remoção para um
esforço que é, do ponto de vista do `master`, uma coisa só: o `master` não tem estado intermediário
útil entre os estágios 1 e 7, porque os estágios 5 e 7 são *breaking* cross-repo e um `master` com 1–4
aplicados e 5–7 não está numa configuração que alguém queira rodar.

Motivo de existir worktree, e não simplesmente uma branch: a árvore de trabalho do `master` continua em
uso durante todo o esforço — a análise do E3 decisive run e o deploy do `ape-rv.jar` para o
`aperv-tool` saem dela. Trocar de branch no lugar disso obrigaria a um `stash` a cada alternância.

## 2. Criação

```bash
cd /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape
git worktree add ../ape-rearch -b rearch
cd ../ape-rearch
mvn package          # confirma que a worktree compila ANTES de qualquer edição
```

O `mvn package` inicial não é cerimônia: ele é o que separa "a worktree está incompleta" de "o estágio
1 quebrou o build", e essas duas falhas são indistinguíveis se a primeira compilação da worktree só
acontecer depois da primeira edição.

O `d8` precisa estar no `PATH`: a execução `d8-dex` do `pom.xml` (`:143`) invoca o executável pelo nome
simples, e o SDK não põe `build-tools/` no `PATH` por conta própria — só `platform-tools`, `emulator` e
`cmdline-tools`. Num shell que não seja o interativo do dono, prefixar:
`PATH="$ANDROID_HOME/build-tools/35.0.1:$PATH" mvn package` (35.0.1 é a versão da imagem
`rvandroid_tools:0.9.1`). Sem isso o build morre na fase `package`, *depois* de compilar — o que se
parece com uma falha de código e não é.

**O carimbo de proveniência mente dentro da worktree.** A partir do grupo 2 do estágio 2 o `pom.xml`
grava `BuildInfo.GIT_SHA` via `git-commit-id-maven-plugin`, e numa worktree vinculada o `.git` é um
*arquivo* (`gitdir: <main>/.git/worktrees/ape-rearch`). O plugin normaliza esse ponteiro para o
diretório comum do repositório principal e carimba o HEAD do **`master`**, não o da `rearch`: um build
feito em `61274ba` sai marcado `b7baa68`. Verificado nas versões 9.0.1 e 10.0.0 do plugin, e também com
`useNativeGit=true` — não é questão de versão nem de backend, e por isso nenhuma opção foi acrescentada
ao pom para contorná-la. Num clone normal (`.git` diretório) o carimbo está correto, confirmado por
build de controle. Consequência prática: **o `GIT_SHA` de um jar construído na worktree não serve para
identificar o que ele contém** — enquanto os sete estágios estiverem em voo, use o sha do commit da
`rearch` que você mesmo construiu. Como nenhum jar de worktree é deployado (o `mvn install` que copia
para o `aperv-tool` não é rodado daqui), nenhum jar entregue carrega o carimbo errado.

**Execução de 2026-08-03**: worktree criada em `b7baa68`, `mvn package` e `mvn test` verdes antes de
qualquer edição. Dois números que valem como linha de base: o `target/ape-rv.jar` da worktree saiu
sha256 `386ce08d…`, **byte-idêntico ao jar medido na corrida decisiva E3** — o que confirma de uma vez
que a worktree está completa e que a `rearch` parte exatamente do commit medido; e a suíte pré-mudança
roda **785 testes, 0 falhas, 19 skipped**. É contra esses dois valores que o estágio 1 se compara.

## 3. O que a worktree herda, e o que não herda

**Herda** (tudo versionado, nada a copiar):

- `framework/classes-full-debug.jar` e `dalvik_stub/classes.jar` — as duas dependências `system`-scope
  do `pom.xml` estão versionadas no repositório, então a worktree compila sem nenhum passo manual.
- `src/test/resources/` — os JSONs de análise estática (`cryptoapp.apk.gh60-fresh.json`,
  `cryptoapp.apk.gh60.json`) e as fixtures `.uiautomator`/`.png`. O estágio 7 gera o artefato compacto
  a partir do primeiro (tarefa 3.1) e o estágio 1 captura goldens contra as fixtures.
- `test-apks/cryptoapp.apk.json` — versionado (só o `.apk` não é).

**Não herda**:

- `test-apks/cryptoapp.apk` — gitignorado. Nenhuma tarefa dos 7 estágios precisa dele para `mvn test`;
  se um smoke local exigir, copiar do caminho em `CLAUDE.md`.
- `target/` — gitignorado, portanto **cada worktree tem o seu**. Isso é a favor: builds na `rearch`
  não invalidam o `target/` do `master`.

**Cuidado com o único efeito que escapa da worktree**: `mvn install -Drvsec_home=<path>` copia o
`ape-rv.jar` para dentro do módulo `aperv-tool` no rv-android, que fica **fora** da worktree. Rodar
`install` a partir da `rearch` sobrescreve o jar que o deploy do `master` compartilha. Rodar `install`
na worktree só quando a intenção for exatamente essa — deployar o jar da re-arquitetura. Para
verificação de build, `mvn package` basta.

## 4. Cross-repo: estágios 5 e 7

Os estágios 5 (`thin-python-arms`) e 7 (`compact-static-artifact`) editam o **rv-android**
(`modules/aperv-tool/`), que é outro repositório. A worktree do ape não o cobre — esses estágios
precisam de uma branch correspondente lá, e o `openspec` do rv-android tem o seu próprio fluxo
(a `spec.md` de lá não pode ser editada à mão; ver tarefa 8.5a do estágio 5).

O estágio 5 é o único que commita nos **dois** repositórios: além da reexpressão dos arms em Python,
o seu grupo 10 apaga o scaffolding transitório do estágio 2 no lado do ape.

## 5. Restrição de ordem que a worktree não dispensa

Os goldens do estágio 1 são capturados a partir do código **pré-mudança**. Eles precisam estar
commitados na `rearch` **antes da primeira edição de produção do estágio 2**. Capturar (ou
regenerar) goldens depois de qualquer edição dos estágios 2/3 faz o oráculo validar a migração contra
ela mesma, que é exatamente a falha que o estágio 1 existe para impedir.

Estar tudo na mesma branch torna esse erro mais fácil de cometer do que seria com worktrees separadas
— não há fronteira física entre o "antes" e o "depois". A defesa é o commit: goldens commitados e o
`mvn test` verde no estágio 1 é o gate, e a regeneração é ato deliberado e documentado
(`rearch-01` design D8), nunca automática.

## 6. Encerramento

```bash
cd /pedro/desenvolvimento/workspaces/workspaces-doutorado/workspace-rv/ape
git merge rearch
git worktree remove ../ape-rearch
git branch -d rearch
```

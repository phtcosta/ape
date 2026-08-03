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

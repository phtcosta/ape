# Investigação: comportamento do caminho LLM em apps FLAG_SECURE

**Data:** 2026-07-16
**Área:** `ape.llm` (Fase 5 — integração LLM/VLM)
**Desfecho:** change de "detecção de frame preto" (Caso B) **ABANDONADA** — premissa invalidada empiricamente.

---

## 1. Contexto

O caminho LLM do APE-RV começa capturando um screenshot via
`ScreenshotCapture.capture()` (`SurfaceControl.screenshot(Rect,int,int,int)` por
reflexão, executando em `app_process` como uid shell). A pergunta levantada foi:
**o que acontece em apps que bloqueiam screenshot (`FLAG_SECURE`)?**

Duas hipóteses foram formuladas a partir da leitura do código:

- **Caso A — captura retorna `null`.** Tratado corretamente por `LlmRouter.selectAction`:
  `breaker.recordFailure()` + `screenshotFailedCount++` + `return null` (fallback SATA);
  o circuit breaker abre após 3 falhas consecutivas e o run para de tentar LLM.
- **Caso B — captura retorna um bitmap NÃO-nulo porém preto.** Hipótese: o
  `FLAG_SECURE` enegrece as camadas seguras e a captura devolve um frame preto.
  Nesse caso o guard de `null` não dispara, o VLM recebe uma imagem preta
  (gasta latência + tokens), **nenhuma** `breaker.recordFailure()` é chamada e o
  circuit breaker **nunca abre** — o run inteiro paga latência à toa. Não há
  detecção de imagem em branco em nenhum ponto (`ImageProcessor` confirmado).

A proposta inicial era criar uma change (FF SDD) para detectar frames pretos e
roteá-los ao mesmo caminho do Caso A. **Antes de planejar, decidimos obter
certeza empírica de qual caso realmente ocorre.**

## 2. Metodologia

Reproduzir o caminho de captura **real** (mesma API, mesmo uid, mesmo contexto do
runtime), não um proxy.

1. **Sonda** (`ScreenshotProbe`): `main` que instancia o `ScreenshotCapture` real,
   chama `capture(w,h)` e imprime o resultado. Se não-nulo, decodifica o PNG e
   reporta estatísticas de pixel (fração quase-preta com luminância < 8, média/mín/máx),
   separando frame inteiro vs. região central (barras de status/navegação excluídas).
   Compilada dentro do `ape-rv.jar`; invocada como
   `adb shell CLASSPATH=/data/local/tmp/ape-rv.jar app_process /system/bin com.android.commands.monkey.ape.probe.ScreenshotProbe`.
2. **APK FLAG_SECURE** (`com.example.secureprobe`): 1 Activity com
   `getWindow().setFlags(FLAG_SECURE, FLAG_SECURE)`, fundo azul sólido + texto branco
   (cores distintivas: uma captura bem-sucedida seria obviamente azul; um bloqueio
   seria preto ou nulo). Montada à mão com os build-tools do SDK (javac → d8 →
   aapt2 link → zip dex → zipalign → apksigner).
3. **Controle sempre presente:** capturar uma tela normal (home) imediatamente antes
   para provar que o caminho de captura funciona nesta plataforma — se o próprio
   controle falhasse, o problema seria outro (caminho de screenshot quebrado, não FLAG_SECURE).

**Plataforma:** RVSec AVD, `emulator-5554`, API 30 / Android 11 (a plataforma-alvo dos experimentos).

## 3. Resultados (reprodutíveis 2/2)

| Tela | Resultado de `capture()` | Estatísticas |
|---|---|---|
| Home (controle) | **BYTES válidos** (~530 KB, 1080x1920) | `darkFrac=0.0000`, `meanLuma=151.9`, min=20, max=255 |
| FLAG_SECURE | **`NULL`** (nenhum arquivo escrito) | — |

- O `control.png` foi puxado e conferido visualmente: screenshot real e correto da home.
- Alternância home→válido / secure→null repetida duas vezes: **causalidade** confirmada
  (o `FLAG_SECURE` é a causa do `null`).
- Nenhum `secure.png` foi escrito no device (o caminho `null` não grava nada), corroborando o resultado.

## 4. Conclusão

**No RVSec AVD (API 30), `FLAG_SECURE` produz o Caso A (`capture()` → `null`).**
O `SurfaceControl.screenshot` legado retorna `null` quando há conteúdo seguro em tela
para um chamador uid shell (sem `CAPTURE_SECURE_LAYERS`), em vez de vazar um frame preto.

O **Caso B (frame preto não-nulo) NÃO ocorre** nesta plataforma. O `LlmRouter` já trata
o Caso A corretamente (breaker + `screenshotFailedCount` + fallback SATA), inclusive
tornando a degradação por FLAG_SECURE contável post-hoc via `screenshotFailedCount`.

## 5. Decisão

**Change abandonada.** Implementar um detector de tela-preta seria uma feature
especulativa para um caso inexistente — violando P1 (simplicidade, sem features
especulativas) e ainda arriscando falso-positivo em telas legitimamente escuras
(dark-mode, player de vídeo, splash). Nenhum artefato OpenSpec foi criado.

**Caveat:** verificado apenas na API 30. Se um experimento futuro rodar em outra API
e observar `FLAG_SECURE` produzindo um frame escuro não-nulo, reabrir a investigação
com o fixture preservado (`scratchpad/secureprobe/`: APK + `ScreenshotProbe.java.fixture`).
O javadoc de `ScreenshotCapture` já se restringe a API 29+.

## 6. Limpeza

- A sonda foi **removida** de `src/main/.../ape/probe/` (não deve ficar no `ape-rv.jar`
  de produção); sai limpa no próximo `mvn package`.
- Fixture (APK assinada + fonte da sonda) preservado no scratchpad da sessão.
- A APK `com.example.secureprobe` permanece instalada no emulator (inócua; desinstalar
  com `adb uninstall com.example.secureprobe` quando conveniente).

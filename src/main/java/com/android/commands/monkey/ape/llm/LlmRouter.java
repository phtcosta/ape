package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.AndroidDevice;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Config;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates LLM-assisted action selection in the APE-RV agent loop.
 *
 * <p>LlmRouter wires together the infrastructure components (screenshot capture,
 * image encoding, prompt building, HTTP client, response parsing, coordinate
 * normalisation, circuit breaker) and exposes two entry points:
 * <ul>
 *   <li>{@link #shouldRouteNewState} — true when the agent just entered a new
 *       state and LLM routing is configured for that trigger.</li>
 *   <li>{@link #shouldRouteStagnation} — true when the graph-stable counter
 *       reaches the half-threshold, signalling exploration stagnation.</li>
 * </ul>
 *
 * <p>The main method {@link #selectAction} runs the full pipeline:
 * capture → encode → prompt → LLM → parse → map → return ModelAction.
 * It never throws; all exceptions are caught internally and null is returned.
 */
public class LlmRouter {

    // Input widget class names that support type_text
    private static final Set<String> INPUT_CLASS_NAMES = new HashSet<>(Arrays.asList(
            "android.widget.EditText",
            "android.widget.AutoCompleteTextView",
            "android.widget.SearchView",
            "androidx.appcompat.widget.SearchView"
    ));

    // Infrastructure — all final, wired in constructor
    private final SglangClient       client;
    private final LlmCircuitBreaker  breaker;
    private final ScreenshotCapture  screenshot;
    private final ImageProcessor     imageProcessor;
    private final ToolCallParser     parser;
    private final ApePromptBuilder   promptBuilder;
    private final java.util.Random   random;

    // Telemetry counters
    private int totalCalls      = 0;
    private int totalTokensIn   = 0;
    private int totalTokensOut  = 0;
    private long totalTimeMs    = 0L;
    private int matchedCount    = 0;
    // Off-tree coordinate taps synthesized for the off-tree case (llm-coordinate-tap). Kept
    // separate from matchedCount/noMatchCount so the off-tree effect is countable post-hoc; it
    // joins the decisions denominator (an off-tree event formerly counted under no_match).
    private int llmTapCount     = 0;
    private int noMatchCount    = 0;
    // Successful decisions whose tool call needed a ToolCallParser repair (repair-form label other
    // than none, per llm-infrastructure INV-LLM-09). A subset overlay on matched/llm_tap/no_match —
    // NOT a cause counter and NOT part of the decisions denominator (INV-RTR-13) — so the base×v2
    // tool-call fidelity contrast stays countable post-hoc from the summary line alone.
    private int repairedCount   = 0;
    // Discriminated failure-cause counters (INV-RTR-11): each attempt abandoned before the mapping
    // step increments exactly one, so the seven counters partition the retired single nullCount and
    // the decisions denominator is value-identical. Client-observed causes (timeout/http/connection/
    // parse-envelope) are read once from SglangClient.getLastErrorCause() at the chat()-null site;
    // router-observed causes (parse tool-call, image, internal) are attributed directly.
    // screenshotFailedCount is a peer cause (no longer a subset of an aggregate) so per-app
    // FLAG_SECURE degradation of the LLM arm to SATA stays countable post-hoc from the summary line.
    private int timeoutCount    = 0;
    private int httpErrorCount  = 0;
    private int connErrorCount  = 0;
    private int parseErrorCount = 0;
    private int imageErrorCount = 0;
    private int internalErrorCount = 0;
    private int screenshotFailedCount = 0;
    private int breakerTrips    = 0;

    // [APE-LLM-CONFIG-ACK] latch (INV-RTR-12): emitted once, on the first successful chat() response.
    private boolean ackEmitted  = false;
    // Breaker-OPEN latch: emit the breaker-OPEN line once per open episode; reset when the breaker
    // next allows an attempt (leaves OPEN).
    private boolean breakerOpenLatched = false;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Create and wire all infrastructure using Config fields.
     *
     * @param random Monkey-seeded Random for reproducible probabilistic routing
     */
    public LlmRouter(java.util.Random random) {
        this.random = random;
        // Hoisted so the manifest and the request body share one max_tokens value (J1c). Config-sourced
        // with default 1024 — byte-identical to the pre-change hard-coded local at defaults (INV-RTR-14).
        int maxTokens = Config.llmMaxTokens;
        this.client = new SglangClient(
                Config.llmUrl,
                Config.llmModel,
                Config.llmTemperature,
                Config.llmTopP,
                Config.llmTopK,
                maxTokens,
                Config.llmTimeoutMs);
        this.client.setTools(buildToolsSchema());
        this.breaker       = new LlmCircuitBreaker();
        this.screenshot    = new ScreenshotCapture();
        this.imageProcessor = new ImageProcessor();
        this.parser        = new ToolCallParser();
        this.promptBuilder = new ApePromptBuilder();

        // Effective-config manifest (INV-RTR-10): one line per run recording the values actually in
        // use — read from the effective Config fields and the hoisted max_tokens, NOT the raw
        // ape.properties map. Makes each .trace self-describing from its first seconds, independent of
        // how the run ends (the Config dump runs only at tearDown and echoes raw, pre-clamp strings).
        Logger.println("[APE-LLM-CONFIG]"
                + " model=" + Config.llmModel
                + " temperature=" + Config.llmTemperature
                + " top_p=" + Config.llmTopP
                + " top_k=" + Config.llmTopK
                + " max_tokens=" + maxTokens
                + " timeout_ms=" + Config.llmTimeoutMs
                + " prompt_variant=" + ApePromptBuilder.getPromptVariant()
                + " llm_percentage=" + Config.llmPercentage
                + " on_new_state=" + Config.llmOnNewState
                + " on_stagnation=" + Config.llmOnStagnation
                + " stagnation_threshold=" + Config.graphStableRestartThreshold
                + " url=" + Config.llmUrl);
    }

    /**
     * Build the OpenAI tools schema for the VLM.
     * For ape_reasoning variant, adds optional "reasoning" param to click/long_click/type_text.
     */
    private static JSONArray buildToolsSchema() {
        boolean addReasoning = ApePromptBuilder.VARIANT_APE_REASONING
                .equals(ApePromptBuilder.getPromptVariant());
        try {
            JSONArray tools = new JSONArray();
            tools.put(buildTool("click", "Tap on an element",
                    new String[]{"x", "y"}, new String[]{"integer", "integer"}, addReasoning));
            tools.put(buildTool("long_click", "Long press on an element",
                    new String[]{"x", "y"}, new String[]{"integer", "integer"}, addReasoning));
            tools.put(buildTool("type_text", "Type text into an input field",
                    new String[]{"x", "y", "text"}, new String[]{"integer", "integer", "string"}, addReasoning));
            tools.put(buildTool("back", "Press the back button",
                    new String[]{}, new String[]{}, false));
            return tools;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONObject buildTool(String name, String description,
                                         String[] paramNames, String[] paramTypes,
                                         boolean addReasoning) throws Exception {
        JSONObject props = new JSONObject();
        JSONArray required = new JSONArray();
        for (int i = 0; i < paramNames.length; i++) {
            JSONObject prop = new JSONObject();
            prop.put("type", paramTypes[i]);
            props.put(paramNames[i], prop);
            required.put(paramNames[i]);
        }
        if (addReasoning) {
            JSONObject reasoningProp = new JSONObject();
            reasoningProp.put("type", "string");
            reasoningProp.put("description", "Brief reason for this action");
            props.put("reasoning", reasoningProp);
        }
        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", required);

        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", params);

        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("function", fn);
        return tool;
    }

    // -------------------------------------------------------------------------
    // Routing predicates
    // -------------------------------------------------------------------------

    /**
     * Returns true when a new-state LLM call should be attempted.
     *
     * @param isNewState true if the agent just entered a previously-unseen state
     */
    public boolean shouldRouteNewState(boolean isNewState) {
        return isNewState
                && Config.llmOnNewState
                && breakerAllows()
;
    }

    /**
     * Returns true when an exploration-stagnation LLM call should be attempted.
     * Fires at the half-threshold so the LLM can nudge the agent before a full restart.
     *
     * @param graphStableCounter current value of the agent's graphStableCounter field
     */
    public boolean shouldRouteStagnation(int graphStableCounter) {
        return graphStableCounter == Config.graphStableRestartThreshold / 2
                && Config.llmOnStagnation
                && breakerAllows()
;
    }

    /**
     * Returns true when a probabilistic (random) LLM call should be attempted.
     * Fires with probability Config.llmPercentage on each step.
     */
    public boolean shouldRouteRandom() {
        return Config.llmPercentage > 0.0
                && random.nextDouble() < Config.llmPercentage
                && breakerAllows()
;
    }

    /**
     * Consult the breaker exactly once — preserving {@code shouldAttempt()}'s OPEN→HALF_OPEN probe
     * transition — and log the breaker-OPEN line at the FIRST breaker-caused decline of each open
     * episode. The emission check uses the side-effect-free {@code isOpen()} (never a second
     * {@code shouldAttempt()}), and the latch resets when the breaker next allows an attempt (leaves
     * OPEN), so each open episode logs once regardless of how many predicates it declines. Because
     * this runs only after the earlier predicate conjuncts pass (short-circuit &&), a decline here is
     * unambiguously breaker-caused, not a mode/coin/stagnation condition returning false.
     */
    private boolean breakerAllows() {
        boolean allow = breaker.shouldAttempt();
        if (allow) {
            breakerOpenLatched = false;
        } else if (breaker.isOpen() && !breakerOpenLatched) {
            breakerOpenLatched = true;
            Logger.println("[APE-RV] LLM circuit breaker OPEN, skipping (trips="
                    + breaker.getTripCount() + ")");
        }
        return allow;
    }

    // -------------------------------------------------------------------------
    // Main pipeline
    // -------------------------------------------------------------------------

    /**
     * Run the full LLM pipeline and return the best matching ModelAction, or null.
     *
     * <p>This method NEVER throws. All exceptions are caught internally.
     * Memory-heavy temporaries (pngBytes, base64, messages) are nulled in the
     * finally block to allow GC before the method returns.
     *
     * @param tree         current GUITree (used for device dimensions and prompt)
     * @param state        current abstract state
     * @param actions      candidate ModelActions available in this state
     * @param mopData      MOP reachability data (may be null)
     * @param recentActions recent action history (may be null or empty)
     * @param mode         routing mode label for telemetry ("new-state" or "stagnation")
     * @param step         agent exploration step of this attempt (the same value the step's
     *                     {@code [APE-STEP]} line carries); stamped on {@code [APE-LLM-TEL]} /
     *                     {@code [APE-LLM-ERROR]} as the join key. Supplied by the caller — the
     *                     router holds no agent reference.
     * @return the selected ModelAction, or null if selection failed
     */
    public ModelAction selectAction(GUITree tree,
                                    State state,
                                    List<ModelAction> actions,
                                    MopData mopData,
                                    List<ApePromptBuilder.ActionHistoryEntry> recentActions,
                                    String mode,
                                    int step) {
        totalCalls++;
        long startMs = System.currentTimeMillis();

        // Determine device dimensions from GUITree root node, same as ApePromptBuilder
        int deviceWidth  = 1080;
        int deviceHeight = 1920;
        try {
            Rect displayBounds = AndroidDevice.getDisplayBounds();
            if (displayBounds.right > 0)  deviceWidth  = displayBounds.right;
            if (displayBounds.bottom > 0) deviceHeight = displayBounds.bottom;
        } catch (Exception ignored) {
            // Fallback: try GUITree root node bounds
            try {
                if (tree != null && tree.getRootNode() != null) {
                    Rect rootBounds = tree.getRootNode().getBoundsInScreen();
                    if (rootBounds.right > 0)  deviceWidth  = rootBounds.right;
                    if (rootBounds.bottom > 0) deviceHeight = rootBounds.bottom;
                }
            } catch (Exception ignored2) { /* use defaults */ }
        }

        // Temporaries — nulled in finally for GC
        byte[] pngBytes  = null;
        String base64    = null;
        List<SglangClient.Message> messages = null;

        try {
            // Step 1: Capture screenshot
            pngBytes = screenshot.capture(deviceWidth, deviceHeight);
            if (pngBytes == null) {
                // A null capture (secure window) is a failure, not a free retry: record it so a
                // persistently-null app opens the breaker and stops per-step LLM attempts.
                breaker.recordFailure();
                breakerTrips = breaker.getTripCount();
                Logger.println("[APE-RV] LLM screenshot capture failed, skipping LLM step");
                // Screenshot failure is a peer cause counter (INV-RTR-11): it keeps its existing
                // line above and emits no [APE-LLM-ERROR].
                screenshotFailedCount++;
                return null;
            }

            // Step 2: Process image (resize + base64-encode)
            base64 = imageProcessor.processScreenshot(pngBytes);
            if (base64 == null) {
                imageErrorCount++;
                Logger.println("[APE-RV] LLM image processing failed, skipping LLM step");
                Logger.println("[APE-LLM-ERROR] step=" + step
                        + " cause=image detail=image processing returned null");
                return null;
            }

            // Step 3: Build prompt
            messages = promptBuilder.build(tree, state, actions, mopData, base64, recentActions);

            // Log prompt (without base64 image — reconstructible from screenshot)
            if (messages != null && messages.size() >= 2) {
                Logger.println("[APE-LLM-PROMPT] system=" +
                        messages.get(0).getTextContent());
                SglangClient.Message userMsg = messages.get(1);
                if (userMsg.getContentParts() != null) {
                    for (SglangClient.ContentPart part : userMsg.getContentParts()) {
                        if ("text".equals(part.getType())) {
                            Logger.println("[APE-LLM-PROMPT] user_text=" + part.getText());
                        }
                    }
                }
            }

            // Step 4: Call LLM (chat() returns null on failure per INV-LLM-01)
            SglangClient.ChatResponse response = client.chat(messages);
            if (response == null) {
                breaker.recordFailure();
                breakerTrips = breaker.getTripCount();
                // Client seam: the ONLY site that may read getLastErrorCause(). chat() reset it at
                // entry, so its value belongs to exactly this call (INV-LLM-08); any later read is stale.
                String cause = client.getLastErrorCause();
                if (cause == null) cause = "connection";
                if (cause.startsWith("http_"))      httpErrorCount++;
                else if ("timeout".equals(cause))   timeoutCount++;
                else if ("parse".equals(cause))     parseErrorCount++;
                else                                connErrorCount++;
                Logger.println("[APE-RV] LLM call failed: null response from SGLang");
                Logger.println("[APE-LLM-ERROR] step=" + step + " cause=" + cause
                        + " detail=null response from SGLang");
                return null;
            }

            // Server-model acknowledgement (INV-RTR-12): once per run, on the first successful
            // chat() response. The manifest records the requested model; the ACK proves what the
            // server served.
            if (!ackEmitted) {
                ackEmitted = true;
                String serverModel = response.getModel() != null ? response.getModel() : "unknown";
                Logger.println("[APE-LLM-CONFIG-ACK] server_model=" + serverModel);
            }

            // Log response raw
            Logger.println("[APE-LLM-RESPONSE] content=" +
                    (response.getContent() != null ? response.getContent() : "null") +
                    " tool_calls=" + response.getToolCalls().size());

            // Step 5: Parse tool call
            ToolCallParser.ParsedAction parsed = parser.parse(response);
            if (parsed == null) {
                breaker.recordFailure();
                breakerTrips = breaker.getTripCount();
                // Router-attributed parse: chat() already succeeded, so its error seam is stale here
                // and MUST NOT be read — the failure is tool-call extraction, not transport.
                parseErrorCount++;
                Logger.println("[APE-RV] LLM response parse failed, no action extracted");
                Logger.println("[APE-LLM-ERROR] step=" + step
                        + " cause=parse detail=no tool call extracted");
                return null;
            }

            // Step 6: Convert normalized coordinates → pixel coordinates
            int[] pixels = CoordinateNormalizer.normalize(
                    parsed.getX(), parsed.getY(), deviceWidth, deviceHeight);

            // Step 7: Map to a ModelAction
            ModelAction match = mapToModelAction(
                    pixels[0], pixels[1], parsed.getActionType(), parsed.getText(),
                    actions, state, deviceWidth, deviceHeight);

            // Step 8: Compute nearest widget for telemetry
            String nearestClass = "none";
            double nearestDist = -1;
            int widgetCount = 0;
            if (actions != null) {
                for (ModelAction a : actions) {
                    try {
                        if (a == null || !a.requireTarget() || !a.isValid()) continue;
                        GUITreeNode n = a.getResolvedNode();
                        if (n == null) continue;
                        widgetCount++;
                        Rect b = n.getBoundsInScreen();
                        int cx = (b.left + b.right) / 2;
                        int cy = (b.top + b.bottom) / 2;
                        double d = Math.hypot(cx - pixels[0], cy - pixels[1]);
                        if (nearestDist < 0 || d < nearestDist) {
                            nearestDist = d;
                            String cn = n.getClassName();
                            nearestClass = cn != null ? cn.substring(cn.lastIndexOf('.') + 1) : "View";
                        }
                    } catch (Exception ignored) {}
                }
            }

            // Step 9: Record outcome
            breaker.recordSuccess();
            long elapsedMs = System.currentTimeMillis() - startMs;
            totalTimeMs   += elapsedMs;
            totalTokensIn += response.getPromptTokens();
            totalTokensOut += response.getCompletionTokens();

            String resultTag;
            String matchedClass = "none";
            String noMatchReason = null;
            if (match instanceof LlmTapAction) {
                // Off-tree coordinate tap (llm-coordinate-tap): the LLM coordinate matched no widget
                // and was synthesized into a MODEL_LLM_TAP. Counted separately from matched/no_match
                // so the off-tree effect is countable post-hoc; it joins the decisions denominator.
                llmTapCount++;
                resultTag = "llm_tap";
            } else if (match != null) {
                matchedCount++;
                resultTag = "matched";
                try {
                    GUITreeNode mn = match.getResolvedNode();
                    if (mn != null) {
                        String cn = mn.getClassName();
                        matchedClass = cn != null ? cn.substring(cn.lastIndexOf('.') + 1) : "View";
                    }
                } catch (Exception ignored) {}

                // Apply text for type_text actions
                if ("type_text".equals(parsed.getActionType()) && parsed.getText() != null) {
                    try {
                        GUITreeNode node = match.getResolvedNode();
                        if (node != null) {
                            node.setInputText(parsed.getText());
                        }
                    } catch (Exception e) {
                        Logger.println("[APE-RV] LLM setInputText failed: " + e.getMessage());
                    }
                }
            } else {
                noMatchCount++;
                resultTag = "no_match";
                // Separate the two remaining no_match mechanisms: a (0,0) model emission
                // (degenerate) vs a status/nav-band coordinate (boundary). Emitted only here.
                noMatchReason = (parsed.getX() == 0 && parsed.getY() == 0) ? "degenerate" : "boundary";
            }

            // Enhanced telemetry line for experiment analysis
            String variant = ApePromptBuilder.getPromptVariant();
            String activityName = "unknown";
            try {
                if (state != null) activityName = state.getActivity();
            } catch (Exception ignored) {}

            StringBuilder tel = new StringBuilder();
            tel.append("[APE-LLM-TEL]")
                    .append(" step=").append(step)
                    .append(" variant=").append(variant)
                    .append(" call=").append(totalCalls)
                    .append(" mode=").append(mode)
                    .append(" action=").append(parsed.getActionType())
                    .append(" qwen=(").append(parsed.getX()).append(",").append(parsed.getY()).append(")")
                    .append(" pixel=(").append(pixels[0]).append(",").append(pixels[1]).append(")")
                    .append(" result=").append(resultTag);
            if (noMatchReason != null) {
                tel.append(" reason=").append(noMatchReason);
            }
            // Repair-form overlay (INV-RTR-13): additive, emitted once, only when the model's tool
            // call needed a pre-parse repair. Never suppresses an error line, never touches cause=parse.
            if (!"none".equals(parsed.getRepairForm())) {
                tel.append(" repair=").append(parsed.getRepairForm());
                repairedCount++;
            }
            tel.append(" matched_class=").append(matchedClass)
                    .append(" nearest_class=").append(nearestClass)
                    .append(" nearest_dist=").append(String.format("%.1f", nearestDist))
                    .append(" widgets=").append(widgetCount)
                    .append(" activity=").append(activityName)
                    .append(" tokens_in=").append(response.getPromptTokens())
                    .append(" tokens_out=").append(response.getCompletionTokens())
                    .append(" time_ms=").append(elapsedMs);
            if (parsed.getText() != null && !parsed.getText().isEmpty()) {
                tel.append(" text=\"").append(parsed.getText()).append("\"");
            }
            Logger.println(tel.toString());

            return match;

        } catch (Exception e) {
            internalErrorCount++;
            Logger.println("[APE-RV] LLM unexpected error in selectAction: " + e.getMessage());
            Logger.println("[APE-LLM-ERROR] step=" + step + " cause=internal detail=" + e.getMessage());
            return null;
        } finally {
            // Memory cleanup — these objects can be large
            pngBytes = null;
            base64   = null;
            messages = null;
        }
    }

    // -------------------------------------------------------------------------
    // Action mapping
    // -------------------------------------------------------------------------

    /**
     * Map pixel coordinates and action type to the best matching ModelAction.
     *
     * <p>Matching strategy (in order):
     * <ol>
     *   <li>back → return state.getBackAction()</li>
     *   <li>Boundary reject: y < 5% or y > 94% of screen height</li>
     *   <li>type_text: filter to input-field widgets only</li>
     *   <li>Bounds containment: smallest widget whose bounds contain (pixelX, pixelY)</li>
     *   <li>Euclidean fallback: nearest widget center within tolerance</li>
     * </ol>
     *
     * @param pixelX     x coordinate in device pixels
     * @param pixelY     y coordinate in device pixels
     * @param actionType LLM action type string ("click", "long_click", "type_text", "back")
     * @param text       typed text for type_text (may be null)
     * @param actions    candidate ModelActions
     * @param state      current state (for back action)
     * @param deviceWidth  display width in pixels
     * @param deviceHeight display height in pixels
     * @return matched ModelAction, or null if no suitable match found
     */
    ModelAction mapToModelAction(int pixelX, int pixelY,
                                         String actionType, String text,
                                         List<ModelAction> actions,
                                         State state,
                                         int deviceWidth, int deviceHeight) {
        if (actionType == null) return null;

        // Handle back action
        if ("back".equals(actionType)) {
            try {
                return state.getBackAction();
            } catch (Exception e) {
                return null;
            }
        }

        // Boundary reject: top and bottom bands of the screen (J1b, Config-sourced with defaults
        // 0.05/0.94 — byte-identical to the pre-change hard-coded literals at defaults, INV-RTR-14).
        if (pixelY < deviceHeight * Config.llmBoundaryTopPct
                || pixelY > deviceHeight * Config.llmBoundaryBottomPct) {
            Logger.println("[APE-RV] LLM coordinate rejected (boundary): pixelY=" + pixelY
                    + " deviceHeight=" + deviceHeight);
            return null;
        }

        if (actions == null || actions.isEmpty()) return null;

        // Determine preferred ActionType for click vs long_click
        boolean preferLongClick = "long_click".equals(actionType);

        // --- Bounds containment pass ---
        ModelAction bestBounds  = null;
        long        bestArea    = Long.MAX_VALUE;

        for (ModelAction action : actions) {
            try {
                if (!action.requireTarget() || !action.isValid()) continue;
                GUITreeNode node = action.getResolvedNode();
                if (node == null) continue;

                // For type_text: restrict to input-capable widgets
                if ("type_text".equals(actionType) && !isInputClass(node)) continue;

                // For long_click: prefer MODEL_LONG_CLICK; fall through to MODEL_CLICK if needed
                if (preferLongClick && action.getType() != ActionType.MODEL_LONG_CLICK) continue;

                Rect bounds = node.getBoundsInScreen();
                if (bounds.contains(pixelX, pixelY)) {
                    long area = (long)(bounds.width()) * bounds.height();
                    if (area < bestArea) {
                        bestArea   = area;
                        bestBounds = action;
                    }
                }
            } catch (Exception ignored) { /* skip bad actions */ }
        }

        if (bestBounds != null) return bestBounds;

        // If long_click had no match with MODEL_LONG_CLICK, retry with any click type
        if (preferLongClick) {
            for (ModelAction action : actions) {
                try {
                    if (!action.requireTarget() || !action.isValid()) continue;
                    GUITreeNode node = action.getResolvedNode();
                    if (node == null) continue;
                    Rect bounds = node.getBoundsInScreen();
                    if (bounds.contains(pixelX, pixelY)) {
                        long area = (long)(bounds.width()) * bounds.height();
                        if (area < bestArea) {
                            bestArea   = area;
                            bestBounds = action;
                        }
                    }
                } catch (Exception ignored) { /* skip bad actions */ }
            }
            if (bestBounds != null) return bestBounds;
        }

        // --- Euclidean fallback ---
        ModelAction bestEuclidean    = null;
        double      bestDist         = Double.MAX_VALUE;
        double      bestTolerance    = Double.MAX_VALUE;

        for (ModelAction action : actions) {
            try {
                if (!action.requireTarget() || !action.isValid()) continue;
                GUITreeNode node = action.getResolvedNode();
                if (node == null) continue;

                if ("type_text".equals(actionType) && !isInputClass(node)) continue;

                Rect bounds = node.getBoundsInScreen();
                int centerX = (bounds.left + bounds.right) / 2;
                int centerY = (bounds.top  + bounds.bottom) / 2;
                double dist = Math.hypot(centerX - pixelX, centerY - pixelY);

                // tolerance = max(floor, min(nodeWidth, nodeHeight) / 2); floor J1b-configurable with
                // default 50 — byte-identical to the pre-change hard-coded 50.0 at defaults (INV-RTR-14).
                int nodeWidth  = bounds.width();
                int nodeHeight = bounds.height();
                double tolerance = Math.max((double) Config.llmSnapTolerancePx,
                        Math.min(nodeWidth, nodeHeight) / 2.0);

                if (dist <= tolerance && dist < bestDist) {
                    bestDist      = dist;
                    bestTolerance = tolerance;
                    bestEuclidean = action;
                }
            } catch (Exception ignored) { /* skip bad actions */ }
        }

        if (bestEuclidean != null) {
            return bestEuclidean;
        }

        // --- Off-tree coordinate tap (dynamic element) ---
        // No widget contains the point and none is within Euclidean tolerance. The boundary reject
        // ran first, so a coordinate reaching here is guaranteed in-bounds and non-degenerate. For a
        // click/long_click, synthesize a targetless MODEL_LLM_TAP carrying the LLM coordinate so APE
        // can act on elements invisible to UIAutomator (game canvas, custom view, Compose-without-
        // semantics). type_text and any other type stay null — a raw coordinate has no node to
        // receive text. (llm-coordinate-tap, D4)
        if ("click".equals(actionType) || "long_click".equals(actionType)) {
            return new LlmTapAction(state, pixelX, pixelY, "long_click".equals(actionType));
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isInputClass(GUITreeNode node) {
        if (node == null) return false;
        try {
            String cn = node.getClassName();
            return cn != null && INPUT_CLASS_NAMES.contains(cn);
        } catch (Exception ignored) { return false; }
    }

    // -------------------------------------------------------------------------
    // Telemetry / summary
    // -------------------------------------------------------------------------

    /**
     * Print a summary of all LLM calls made during this session.
     */
    public void printSummary() {
        // The seven cause counters partition the retired single nullCount (INV-RTR-11), so the
        // decisions denominator is value-identical to the pre-change matched + llm_tap + no_match +
        // null. screenshot_failed is a peer cause (no longer a subset of an aggregate); llmTapCount is
        // a completed mapping outcome inside decisions; the numerator stays matchedCount (match rate).
        int failureCount = timeoutCount + httpErrorCount + connErrorCount + parseErrorCount
                + imageErrorCount + internalErrorCount + screenshotFailedCount;
        int decisions = matchedCount + llmTapCount + noMatchCount + failureCount;
        double matchRate = decisions > 0 ? (matchedCount * 100.0 / decisions) : 0.0;
        Logger.println("[APE-RV] LLM Summary"
                + " calls=" + totalCalls
                + " tokens_in=" + totalTokensIn
                + " tokens_out=" + totalTokensOut
                + " time_ms=" + totalTimeMs
                + " matched=" + matchedCount
                + " llm_tap=" + llmTapCount
                + " no_match=" + noMatchCount
                + " repaired=" + repairedCount
                + " timeout=" + timeoutCount
                + " http_error=" + httpErrorCount
                + " conn_error=" + connErrorCount
                + " parse_error=" + parseErrorCount
                + " image_error=" + imageErrorCount
                + " internal_error=" + internalErrorCount
                + " screenshot_failed=" + screenshotFailedCount
                + " breaker_trips=" + breakerTrips);
        Logger.println(String.format("[APE-RV] LLM Decision ratio: %.1f%% (%d/%d)",
                matchRate, matchedCount, decisions));
    }

    // -------------------------------------------------------------------------
    // Accessors (for testing)
    // -------------------------------------------------------------------------

    /** Current call count (includes both attempted and completed calls). */
    public int getCallCount() { return totalCalls; }

    /** Number of successful action matches. */
    public int getMatchedCount() { return matchedCount; }

    /** Number of synthesized off-tree coordinate taps (MODEL_LLM_TAP). */
    public int getLlmTapCount() { return llmTapCount; }

    /** Number of times parsing / matching produced no result. */
    public int getNoMatchCount() { return noMatchCount; }

    /** Attempts abandoned at connect/read timeout. */
    public int getTimeoutCount() { return timeoutCount; }

    /** Attempts abandoned on a non-200 HTTP status. */
    public int getHttpErrorCount() { return httpErrorCount; }

    /** Attempts abandoned on a non-timeout I/O failure reaching the server. */
    public int getConnErrorCount() { return connErrorCount; }

    /** Attempts abandoned on an unparseable envelope (client) or unextractable tool call (router). */
    public int getParseErrorCount() { return parseErrorCount; }

    /** Attempts abandoned because {@code ImageProcessor} returned null. */
    public int getImageErrorCount() { return imageErrorCount; }

    /** Attempts abandoned by the {@code selectAction()} catch-all. */
    public int getInternalErrorCount() { return internalErrorCount; }

    /**
     * Total attempts abandoned before the mapping step — the sum of the seven cause counters
     * (partition of the retired {@code nullCount}, INV-RTR-11). {@code no_match} outcomes are
     * excluded (they emit an {@code [APE-LLM-TEL]} line and are counted by {@link #getNoMatchCount()}).
     */
    public int getFailureCount() {
        return timeoutCount + httpErrorCount + connErrorCount + parseErrorCount
                + imageErrorCount + internalErrorCount + screenshotFailedCount;
    }

    /** Number of null returns attributed to screenshot-capture failure (peer cause counter). */
    public int getScreenshotFailedCount() { return screenshotFailedCount; }

    /** Number of circuit-breaker trips recorded by this router. */
    public int getBreakerTrips() { return breakerTrips; }

    /** Expose the circuit breaker for testing. */
    LlmCircuitBreaker getBreaker() { return breaker; }
}

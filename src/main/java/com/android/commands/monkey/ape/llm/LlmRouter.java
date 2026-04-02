package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.AndroidDevice;
import com.android.commands.monkey.ape.model.ActionType;
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
    private int noMatchCount    = 0;
    private int nullCount       = 0;
    private int breakerTrips    = 0;

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
        this.client = new SglangClient(
                Config.llmUrl,
                Config.llmModel,
                Config.llmTemperature,
                Config.llmTopP,
                Config.llmTopK,
                1024,
                Config.llmTimeoutMs);
        this.client.setTools(buildToolsSchema());
        this.breaker       = new LlmCircuitBreaker();
        this.screenshot    = new ScreenshotCapture();
        this.imageProcessor = new ImageProcessor();
        this.parser        = new ToolCallParser();
        this.promptBuilder = new ApePromptBuilder();
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
                && breaker.shouldAttempt()
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
                && breaker.shouldAttempt()
;
    }

    /**
     * Returns true when a probabilistic (random) LLM call should be attempted.
     * Fires with probability Config.llmPercentage on each step.
     */
    public boolean shouldRouteRandom() {
        return Config.llmPercentage > 0.0
                && random.nextDouble() < Config.llmPercentage
                && breaker.shouldAttempt()
;
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
     * @return the selected ModelAction, or null if selection failed
     */
    public ModelAction selectAction(GUITree tree,
                                    State state,
                                    List<ModelAction> actions,
                                    MopData mopData,
                                    List<ApePromptBuilder.ActionHistoryEntry> recentActions,
                                    String mode) {
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
                Logger.println("[APE-RV] LLM screenshot capture failed, skipping LLM step");
                nullCount++;
                return null;
            }

            // Step 2: Process image (resize + base64-encode)
            base64 = imageProcessor.processScreenshot(pngBytes);
            if (base64 == null) {
                Logger.println("[APE-RV] LLM image processing failed, skipping LLM step");
                nullCount++;
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
                Logger.println("[APE-RV] LLM call failed: null response from SGLang");
                nullCount++;
                return null;
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
                Logger.println("[APE-RV] LLM response parse failed, no action extracted");
                nullCount++;
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
            if (match != null) {
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
            }

            // Enhanced telemetry line for experiment analysis
            String variant = ApePromptBuilder.getPromptVariant();
            String activityName = "unknown";
            try {
                if (state != null) activityName = state.getActivity();
            } catch (Exception ignored) {}

            StringBuilder tel = new StringBuilder();
            tel.append("[APE-LLM-TEL]")
                    .append(" variant=").append(variant)
                    .append(" call=").append(totalCalls)
                    .append(" mode=").append(mode)
                    .append(" action=").append(parsed.getActionType())
                    .append(" qwen=(").append(parsed.getX()).append(",").append(parsed.getY()).append(")")
                    .append(" pixel=(").append(pixels[0]).append(",").append(pixels[1]).append(")")
                    .append(" result=").append(resultTag)
                    .append(" matched_class=").append(matchedClass)
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
            Logger.println("[APE-RV] LLM unexpected error in selectAction: " + e.getMessage());
            nullCount++;
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

        // Boundary reject: top 5% and bottom 6% of screen
        if (pixelY < deviceHeight * 0.05 || pixelY > deviceHeight * 0.94) {
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

                // tolerance = max(50, min(nodeWidth, nodeHeight) / 2)
                int nodeWidth  = bounds.width();
                int nodeHeight = bounds.height();
                double tolerance = Math.max(50.0, Math.min(nodeWidth, nodeHeight) / 2.0);

                if (dist <= tolerance && dist < bestDist) {
                    bestDist      = dist;
                    bestTolerance = tolerance;
                    bestEuclidean = action;
                }
            } catch (Exception ignored) { /* skip bad actions */ }
        }

        return bestEuclidean;
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
        int decisions = matchedCount + noMatchCount + nullCount;
        double matchRate = decisions > 0 ? (matchedCount * 100.0 / decisions) : 0.0;
        Logger.println("[APE-RV] LLM Summary"
                + " calls=" + totalCalls
                + " tokens_in=" + totalTokensIn
                + " tokens_out=" + totalTokensOut
                + " time_ms=" + totalTimeMs
                + " matched=" + matchedCount
                + " no_match=" + noMatchCount
                + " null=" + nullCount
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

    /** Number of times parsing / matching produced no result. */
    public int getNoMatchCount() { return noMatchCount; }

    /** Number of null returns due to infrastructure failures. */
    public int getNullCount() { return nullCount; }

    /** Number of circuit-breaker trips recorded by this router. */
    public int getBreakerTrips() { return breakerTrips; }

    /** Expose the circuit breaker for testing. */
    LlmCircuitBreaker getBreaker() { return breaker; }
}

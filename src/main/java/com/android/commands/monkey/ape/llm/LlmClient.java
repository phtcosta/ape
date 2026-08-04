package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.utils.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * The run's single door to the model: HTTP transport and fault tolerance as one unit.
 *
 * <p>Transport and the circuit breaker are composed here rather than held side by side because the
 * only sound way to consult a breaker is once per decision, and a caller holding both references
 * can always consult it twice. {@link #allows()} is that single consultation, and it is the reason
 * this class exists as something more than a pair of fields: {@code shouldAttempt()} has a side
 * effect — the OPEN&rarr;HALF_OPEN probe — so asking again to find out whether the breaker is open
 * would spend the probe the first ask already granted. The emission check therefore uses the
 * side-effect-free {@code isOpen()}.
 *
 * <p><b>Every parameter arrives from the plan.</b> Sampling values, the timeout and the wire
 * schemas are fixed at construction from {@link RunSpec.LlmParams}; nothing here reads static
 * configuration during the run. The {@code [APE-LLM-CONFIG]} manifest is emitted from the same
 * values, in the constructor, so a trace describes the request bodies it is about to carry rather
 * than the file they were read from.
 */
public final class LlmClient {

    private final SglangClient client;
    private final LlmCircuitBreaker breaker;

    // The two wire schemas, built once (INV-LLM-11). Which one a request carries is decided per
    // call by the same hasInputField predicate that decides whether the system message lists
    // type_text, so prompt and wire cannot disagree about which tools exist.
    private final JSONArray toolsWithTypeText;
    private final JSONArray toolsWithoutTypeText;

    // Breaker-OPEN latch: emit the breaker-OPEN line once per open episode; reset when the breaker
    // next allows an attempt (leaves OPEN).
    private boolean breakerOpenLatched = false;

    /**
     * Wire the transport from the plan and announce the effective configuration.
     *
     * @param llm the plan's LLM parameters; every transport value and the manifest come from here
     * @param stagnationThreshold the exploration restart threshold the manifest reports. It is not
     *        a transport parameter and does not reach the request body — it is carried on the
     *        manifest so a trace states the stagnation midpoint its LLM hook fires at, and it lives
     *        in the exploration scope rather than the LLM one, which is why it arrives separately
     */
    public LlmClient(RunSpec.LlmParams llm, int stagnationThreshold) {
        // Hoisted so the manifest and the request body share one max_tokens value: the manifest's
        // whole purpose is to report what is sent, which a second read could not guarantee.
        int maxTokens = llm.integer("ape.llmMaxTokens");
        double temperature = llm.dbl("ape.llmTemperature");
        double topP = llm.dbl("ape.llmTopP");
        int topK = llm.integer("ape.llmTopK");
        int timeoutMs = llm.integer("ape.llmTimeoutMs");
        String model = llm.model();
        String promptVariant = llm.str("ape.llmPromptVariant");

        this.client = new SglangClient(llm.url(), model, temperature, topP, topK, maxTokens,
                timeoutMs);
        this.toolsWithTypeText = buildToolsSchema(true, promptVariant);
        this.toolsWithoutTypeText = buildToolsSchema(false, promptVariant);
        this.breaker = new LlmCircuitBreaker();

        // Effective-config manifest (INV-RTR-10): one line per run recording the values actually in
        // use. Makes each .trace self-describing from its first seconds, independent of how the run
        // ends (the plan echo runs at startup and the Config dump at tearDown, and neither states
        // the request body). The three trigger keys belong to features a plan may omit; an omitted
        // trigger is a disabled trigger, so the manifest reports the value the run behaves as.
        Logger.println("[APE-LLM-CONFIG]"
                + " model=" + model
                + " temperature=" + temperature
                + " top_p=" + topP
                + " top_k=" + topK
                + " max_tokens=" + maxTokens
                + " timeout_ms=" + timeoutMs
                + " prompt_variant=" + promptVariant
                + " llm_percentage=" + (llm.has("ape.llmPercentage")
                        ? llm.dbl("ape.llmPercentage") : 0.0)
                + " on_new_state=" + (llm.has("ape.llmOnNewState")
                        && llm.bool("ape.llmOnNewState"))
                + " on_stagnation=" + (llm.has("ape.llmOnStagnation")
                        && llm.bool("ape.llmOnStagnation"))
                + " stagnation_threshold=" + stagnationThreshold
                + " url=" + llm.url());
    }

    // -------------------------------------------------------------------------
    // Transport
    // -------------------------------------------------------------------------

    /**
     * Send one chat completion request.
     *
     * @param messages the prompt, as the builder assembled it
     * @param hasInputField whether the screen this request is about offers an input field; it picks
     *        the wire schema, and it is the same predicate the system message used, so the model is
     *        never offered a tool the prompt says does not exist (INV-LLM-11)
     * @return the response, or {@code null} when the call failed (INV-LLM-01)
     */
    public SglangClient.ChatResponse chat(List<SglangClient.Message> messages,
                                          boolean hasInputField) {
        return client.chat(messages, hasInputField ? toolsWithTypeText : toolsWithoutTypeText);
    }

    /**
     * Why the last {@link #chat} returned null.
     *
     * <p>Readable only immediately after that null return: {@code chat()} resets it at entry, so
     * the value belongs to exactly one call and any later read is stale (INV-LLM-08).
     *
     * @return the cause label, or {@code null} when the last call succeeded
     */
    public String getLastErrorCause() {
        return client.getLastErrorCause();
    }

    // -------------------------------------------------------------------------
    // Breaker
    // -------------------------------------------------------------------------

    /**
     * Consult the breaker exactly once — preserving {@code shouldAttempt()}'s OPEN&rarr;HALF_OPEN
     * probe transition — and log the breaker-OPEN line at the FIRST breaker-caused decline of each
     * open episode. The emission check uses the side-effect-free {@code isOpen()} (never a second
     * {@code shouldAttempt()}), and the latch resets when the breaker next allows an attempt
     * (leaves OPEN), so each open episode logs once regardless of how many decisions it declines.
     * Because callers reach this only after their earlier trigger conjuncts pass (short-circuit
     * {@code &&}), a decline here is unambiguously breaker-caused, not a mode or coin condition
     * returning false.
     *
     * @return whether an attempt may be made
     */
    public boolean allows() {
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

    /** Record that the pipeline completed, however its answer was judged. */
    public void recordSuccess() {
        breaker.recordSuccess();
    }

    /** Record that the pipeline failed, which is what eventually opens the breaker. */
    public void recordFailure() {
        breaker.recordFailure();
    }

    /** How many times the breaker has opened in this run. */
    public int getTripCount() {
        return breaker.getTripCount();
    }

    /** Expose the circuit breaker for testing. */
    LlmCircuitBreaker getBreaker() {
        return breaker;
    }

    // -------------------------------------------------------------------------
    // Wire schema
    // -------------------------------------------------------------------------

    /**
     * Build the OpenAI tools schema for the model.
     *
     * <p>The {@code ape_reasoning} variant adds an optional {@code reasoning} parameter to the
     * three targeted tools; {@code back} takes none under any variant, having nothing to reason
     * about.
     *
     * @param includeTypeText whether the screens this schema will be sent for have an input field
     * @param promptVariant the plan's prompt variant
     */
    static JSONArray buildToolsSchema(boolean includeTypeText, String promptVariant) {
        boolean addReasoning = ApePromptBuilder.VARIANT_APE_REASONING.equals(promptVariant);
        try {
            JSONArray tools = new JSONArray();
            tools.put(buildTool("click", "Tap on an element",
                    new String[]{"x", "y"}, new String[]{"integer", "integer"}, addReasoning));
            tools.put(buildTool("long_click", "Long press on an element",
                    new String[]{"x", "y"}, new String[]{"integer", "integer"}, addReasoning));
            if (includeTypeText) {
                tools.put(buildTool("type_text", "Type text into an input field",
                        new String[]{"x", "y", "text"},
                        new String[]{"integer", "integer", "string"}, addReasoning));
            }
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
}

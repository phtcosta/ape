package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.utils.Logger;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * Everything a run says about its LLM, and everything it counts.
 *
 * <p>Counting and recording are one unit here because they are the same act performed twice: every
 * counter this class holds has a sub-event that reports it, and every sub-event it emits moves a
 * counter. Splitting them would put the {@code cause:"parse"} sub-event in one place and the
 * increment of {@code parseErrorCount} in another, and the invariant that binds them — that the
 * seven cause counters partition the abandoned attempts (INV-RTR-11) — would then be a property of
 * two files agreeing rather than of one file's control flow.
 *
 * <p><b>Every attempt lands on the step that made it.</b> The sub-events go onto the record the
 * agent opened at the start of selection, so the attribution that used to be a {@code step=} key
 * shared by three line families is now structural. Nothing here states a step, an activity or the
 * prompt variant: the first two are the parent record's envelope and the third is run-constant in
 * the {@code RUN_START} plan echo.
 *
 * <p><b>The engine decides, this unit records.</b> The classification of an answer
 * ({@code matched}/{@code llm_tap}/{@code no_match} and its reason) is the engine's judgement,
 * because it is the engine that holds what the mapping returned; what arrives here is that verdict
 * plus the fields the line prints. That division is what lets the whole telemetry surface be
 * exercised without a device, a model or a screenshot.
 *
 * <p><b>What the counters mean, since the summary is read offline months later.</b>
 * {@code totalCalls} counts <em>attempts</em>, incremented before anything can fail (INV-RTR-07).
 * The seven cause counters partition the attempts abandoned before the mapping step: exactly one
 * fires per abandoned attempt, and {@code screenshotFailedCount} is a peer of the other six rather
 * than a subset of any aggregate, so per-app {@code FLAG_SECURE} degradation stays countable.
 * {@code repairedCount} and {@code deadPairCount} are <b>overlays</b>, not causes: a repaired
 * decision is already counted under matched/llm_tap/no_match (INV-RTR-13) and a banned one under
 * {@code noMatchCount}, and neither joins the decisions denominator a second time.
 */
public final class LlmTelemetry {

    /** Where the sub-events go — the record of the step whose selection is running. */
    private final EventSink sink;

    /**
     * Whether the prompt and response text ride the sub-event.
     *
     * <p>Default on, which is recordability parity with the unconditional dump lines it replaces.
     * It is a volume lever and nothing else: with it off the sub-event omits four fields and every
     * other field, counter and decision is identical.
     */
    private final boolean promptDump;

    /**
     * The breaker's trip count, asked for when the summary is printed.
     *
     * <p>A function rather than a mirrored field, because the trip count belongs to
     * {@link LlmClient} (design D5) and a mirror can only ever restate it. A field refreshed at
     * each failure site would report the count as of the last failure; since only a failure can
     * raise it, that is the same number this reads, and a supplier keeps this unit testable
     * without a client.
     */
    private final IntSupplier breakerTrips;

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
    // parse-envelope) are read once from the client's error seam at the chat()-null site;
    // engine-observed causes (parse tool-call, image, internal) are attributed directly.
    private int timeoutCount    = 0;
    private int httpErrorCount  = 0;
    private int connErrorCount  = 0;
    private int parseErrorCount = 0;
    private int imageErrorCount = 0;
    private int internalErrorCount = 0;
    private int screenshotFailedCount = 0;
    // Dead-pair overlay counter: each banned decision is already counted under noMatchCount, and is
    // counted again here so bucket D — the ban's falsification gate — is readable from the summary
    // line alone, without joining the per-decision [APE-LLM-TEL] stream.
    private int deadPairCount   = 0;

    // The prompt of the attempt in flight, held only because the response arrives later and the
    // sub-event carries the pair. Overwritten by the next attempt's prompt and never read after it.
    private String pendingSystem;
    private String pendingUser;

    /**
     * @param breakerTrips how many times the run's breaker has opened, asked at summary time
     * @param sink where the per-attempt sub-events go
     * @param promptDump whether the prompt and response text ride each sub-event
     */
    public LlmTelemetry(IntSupplier breakerTrips, EventSink sink, boolean promptDump) {
        this.breakerTrips = breakerTrips;
        this.sink = sink;
        this.promptDump = promptDump;
    }

    // -------------------------------------------------------------------------
    // The attempt
    // -------------------------------------------------------------------------

    /**
     * Count one attempt, before anything about it is known.
     *
     * <p>Placed at the very top of the engine's pipeline on purpose (INV-RTR-07): {@code calls} on
     * the summary line is how many times the LLM was asked, so an attempt that dies at the
     * screenshot must still be one of them, and the {@code call=} field of a decision line is the
     * ordinal of that attempt.
     */
    public void countAttempt() {
        totalCalls++;
    }

    // -------------------------------------------------------------------------
    // Failure causes — exactly one per abandoned attempt
    // -------------------------------------------------------------------------

    /**
     * The screen could not be read, which on this corpus is the LLM arm's dominant failure.
     *
     * <p>The branch used to be silent, and that silence hid the mode: 147 capture failures
     * concentrated in 4 {@code FLAG_SECURE} APKs, co-located with 100% of the 57 breaker trips —
     * the breaker silently disabled the LLM with nothing in the trace to attribute it to. The
     * activity name is what makes per-app degradation countable offline (INV-RTR-20).
     *
     * <p>This is the one abandoned attempt that produces no {@code error} sub-event: it is counted
     * under its own peer counter and said on the free-text line, which is where its activity and
     * capture stage stay readable.
     *
     * @param activity the activity the failure happened on, or {@code unknown}
     * @param stage which capture path failed last, or {@code null} when the capture named none
     */
    public void screenshotFailed(String activity, String stage) {
        screenshotFailedCount++;
        Logger.println("[APE-RV] LLM screenshot capture failed, skipping LLM step"
                + " activity=" + activity
                + " detail=" + (stage != null ? stage : "unknown"));
    }

    /**
     * The capture could not be resized or encoded — a peer cause of the capture failure itself, and
     * deliberately not folded into it: the two fail for unrelated reasons.
     */
    public void imageFailed() {
        imageErrorCount++;
        Logger.println("[APE-RV] LLM image processing failed, skipping LLM step");
        sink.llmError("image", "image processing returned null");
    }

    /**
     * The call did not come back, with the cause the client attributed to it.
     *
     * <p>The four client-observed causes are what the transport can tell apart; anything it cannot
     * name is a connection failure, which is also what an absent cause means — a null here is the
     * client declining to discriminate, not an unknown fifth kind.
     *
     * @param cause the label from the client's error seam, read once at the null return
     *        (INV-LLM-08); {@code null} is recorded as {@code connection}
     */
    public void transportFailed(String cause) {
        String label = cause != null ? cause : "connection";
        if (label.startsWith("http_"))      httpErrorCount++;
        else if ("timeout".equals(label))   timeoutCount++;
        else if ("parse".equals(label))     parseErrorCount++;
        else                                connErrorCount++;
        Logger.println("[APE-RV] LLM call failed: null response from SGLang");
        sink.llmError(label, "null response from SGLang");
    }

    /**
     * A response arrived but carried no extractable tool call.
     *
     * <p>Counted under the same {@code parse} label as an unparseable envelope, and that conflation
     * is deliberate — both mean "the model's answer could not be turned into an action" — but the
     * client's error seam is stale by now and MUST NOT be consulted: the call itself succeeded.
     */
    public void parseFailed() {
        parseErrorCount++;
        Logger.println("[APE-RV] LLM response parse failed, no action extracted");
        sink.llmError("parse", "no tool call extracted");
    }

    /**
     * Something threw where nothing was expected to.
     *
     * <p>This is the cause of last resort behind the engine's never-throws contract (INV-RTR-02):
     * an attempt that reaches it is still counted, still attributed, and still leaves through the
     * declared fallback rather than up the stack.
     *
     * @param detail the exception's message
     */
    public void internalError(String detail) {
        internalErrorCount++;
        Logger.println("[APE-RV] LLM unexpected error in selectAction: " + detail);
        sink.llmError("internal", detail);
    }

    // -------------------------------------------------------------------------
    // The successful path
    // -------------------------------------------------------------------------

    /**
     * Stage the prompt, minus the image, for whatever sub-event this attempt produces.
     *
     * <p>The base64 screenshot is omitted because it is reconstructible from the capture and would
     * otherwise be the overwhelming majority of a trace's bytes.
     *
     * <p>Staging rather than emitting is what lets an attempt that dies at the transport still
     * carry the prompt that produced it: the sink holds the text until the attempt's sub-event
     * exists, whatever that sub-event turns out to be.
     *
     * @param messages the messages as the builder assembled them; a shape with fewer than two is
     *        not a prompt this can describe and is passed over
     */
    public void logPrompt(List<SglangClient.Message> messages) {
        if (!promptDump || messages == null || messages.size() < 2) {
            return;
        }
        pendingSystem = messages.get(0).getTextContent();
        pendingUser = null;
        SglangClient.Message userMsg = messages.get(1);
        if (userMsg.getContentParts() != null) {
            for (SglangClient.ContentPart part : userMsg.getContentParts()) {
                if ("text".equals(part.getType())) {
                    pendingUser = part.getText();
                }
            }
        }
        sink.llmDump(pendingSystem, pendingUser, null, null);
    }

    /**
     * Announce which model actually served the run, once (INV-RTR-12).
     *
     * <p>The plan echo records the model the run asked for; this records what answered. They differ
     * whenever a server is loaded with something other than what the campaign believes, which is a
     * failure mode no other record can reveal. The once-per-run cap lives in the sink, which is the
     * only thing that can enforce it for the whole run.
     *
     * @param response the first successful response of the run
     */
    public void acknowledge(SglangClient.ChatResponse response) {
        sink.llmAck(response.getModel() != null ? response.getModel() : "unknown");
    }

    /**
     * Stage the raw response alongside the prompt it answered, before anything is made of it.
     *
     * @param response the response the client returned
     */
    public void logResponse(SglangClient.ChatResponse response) {
        if (!promptDump) {
            return;
        }
        // The prompt travels again because staging is a whole replacement, not an accumulation: the
        // sub-event this attempt produces carries the pair or neither half of it.
        sink.llmDump(pendingSystem, pendingUser,
                response.getContent() != null ? response.getContent() : "null",
                String.valueOf(response.getToolCalls().size()));
    }

    /**
     * Account for a completed decision and emit its line.
     *
     * <p>One call rather than a counter call and an emission call, because the two cannot disagree
     * if they are one statement: the outcome that increments {@code matchedCount} is the same
     * string the sub-event carries as {@code result}. The repair overlay increments here, inside the
     * emission, which is where it has always incremented — it is a property of the sub-event,
     * counted only when the sub-event carries {@code repair}.
     *
     * @param mode which hook asked — {@code new-state}, {@code stagnation} or {@code random}
     * @param parsed what the model answered, as the parser recovered it
     * @param pixelX the answer's x in device pixels
     * @param pixelY the answer's y
     * @param result the engine's classification: {@code matched}, {@code llm_tap} or
     *        {@code no_match}
     * @param noMatchReason why nothing was selected — {@code dead_pair}, {@code degenerate} or
     *        {@code boundary} — and {@code null} for the two selecting outcomes
     * @param matchedClass the simple class name of the widget the answer resolved to, or
     *        {@code none}
     * @param nearest what the screen offered around the coordinate
     * @param response the response, for its token counts
     * @param elapsedMs how long the whole attempt took
     */
    public void decision(String mode,
                         ToolCallParser.ParsedAction parsed,
                         int pixelX, int pixelY,
                         String result, String noMatchReason, String matchedClass,
                         CoordinateMapper.Nearest nearest,
                         SglangClient.ChatResponse response, long elapsedMs) {
        totalTimeMs   += elapsedMs;
        totalTokensIn += response.getPromptTokens();
        totalTokensOut += response.getCompletionTokens();

        if ("llm_tap".equals(result)) {
            llmTapCount++;
        } else if ("matched".equals(result)) {
            matchedCount++;
        } else {
            noMatchCount++;
            if ("dead_pair".equals(noMatchReason)) {
                deadPairCount++;
            }
        }

        // Repair-form overlay (INV-RTR-13): additive, recorded once, only when the model's tool
        // call needed a pre-parse repair. Never suppresses an error sub-event, never touches
        // cause=parse. Orthogonal to result, so it is carried as its own field rather than folded
        // into the outcome.
        String repair = null;
        if (!"none".equals(parsed.getRepairForm())) {
            repair = parsed.getRepairForm();
            repairedCount++;
        }
        String text = parsed.getText() != null && !parsed.getText().isEmpty()
                ? parsed.getText() : null;
        sink.llmCall(totalCalls, mode, parsed.getActionType(), parsed.getX(), parsed.getY(),
                pixelX, pixelY, result, noMatchReason, repair, matchedClass, nearest.className,
                nearest.distance, nearest.widgetCount, response.getPromptTokens(),
                response.getCompletionTokens(), elapsedMs, text);
    }

    // -------------------------------------------------------------------------
    // Summary
    // -------------------------------------------------------------------------

    /**
     * Print what the run's LLM did, once, at teardown.
     */
    public void printSummary() {
        // The seven cause counters partition the retired single nullCount (INV-RTR-11), so the
        // decisions denominator is value-identical to the pre-change matched + llm_tap + no_match +
        // null. screenshot_failed is a peer cause (no longer a subset of an aggregate); llmTapCount is
        // a completed mapping outcome inside decisions; the numerator stays matchedCount (match rate).
        int failureCount = getFailureCount();
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
                + " dead_pair=" + deadPairCount
                + " repaired=" + repairedCount
                + " timeout=" + timeoutCount
                + " http_error=" + httpErrorCount
                + " conn_error=" + connErrorCount
                + " parse_error=" + parseErrorCount
                + " image_error=" + imageErrorCount
                + " internal_error=" + internalErrorCount
                + " screenshot_failed=" + screenshotFailedCount
                + " breaker_trips=" + breakerTrips.getAsInt());
        Logger.println(String.format("[APE-RV] LLM Decision ratio: %.1f%% (%d/%d)",
                matchRate, matchedCount, decisions));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Attempts made, whether or not they produced a decision. */
    public int getCallCount() { return totalCalls; }

    /** Answers that resolved to a widget action. */
    public int getMatchedCount() { return matchedCount; }

    /** Synthesized off-tree coordinate taps (MODEL_LLM_TAP). */
    public int getLlmTapCount() { return llmTapCount; }

    /** Completed decisions that selected nothing. */
    public int getNoMatchCount() { return noMatchCount; }

    /**
     * Total attempts abandoned before the mapping step — the sum of the seven cause counters
     * (partition of the retired {@code nullCount}, INV-RTR-11). {@code no_match} outcomes are
     * excluded (they emit an {@code [APE-LLM-TEL]} line and are counted by {@link #getNoMatchCount()}).
     */
    public int getFailureCount() {
        return timeoutCount + httpErrorCount + connErrorCount + parseErrorCount
                + imageErrorCount + internalErrorCount + screenshotFailedCount;
    }

    /** Attempts abandoned because the screen could not be read (peer cause counter). */
    public int getScreenshotFailedCount() { return screenshotFailedCount; }
}

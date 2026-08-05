package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.MopData;

import java.util.List;

/**
 * One LLM decision, from the screen to the action the agent executes.
 *
 * <p>The ten steps of the pipeline are here and almost nothing else: each one asks a unit and reads
 * its answer, so this class holds the <em>sequence</em> and the units hold the work. That division
 * is what the sequence is worth stating for — the order the steps run in is behaviour, not style.
 * The attempt is counted before anything can fail, the breaker is told about a failure at the three
 * sites that are failures and about success at the one site that is not, the error seam is read at
 * exactly one of them, and the decision line is emitted last, after the answer has been judged.
 *
 * <p><b>What this class decides is what an answer was.</b> Everything else it delegates. The
 * judgement — {@code matched}, {@code llm_tap} or {@code no_match} with its reason — needs the
 * mapping's result and the ban's verdict together, which is why it lives at the site that holds
 * both, and it is handed to {@link LlmTelemetry} rather than counted here: deciding and recording
 * are different acts and only the first one needs a screen.
 *
 * <p><b>It never throws</b> (INV-RTR-02). Anything unexpected is the {@code internal} cause and
 * leaves through the same {@code null} the declared fallback reads, so a failing model can never
 * take the agent's step with it. Large temporaries — the capture, its encoding, the messages — are
 * released in a {@code finally} so a decision's memory does not outlive it (INV-RTR-06).
 *
 * <p>The class is left open, unlike the four units it composes, because the parity oracle
 * substitutes a scripted engine per LLM hook to replay a recorded decision without a screen or a
 * server.
 */
public class LlmEngine {

    private final ScreenshotStep screenshot;
    private final ApePromptBuilder promptBuilder;
    private final LlmClient client;
    private final ToolCallParser parser;
    private final CoordinateMapper mapper;
    private final LlmTelemetry telemetry;

    /**
     * @param screenshot the screen-to-string step
     * @param promptBuilder the prompt assembler
     * @param client the run's single door to the model, breaker included
     * @param parser the tool-call extractor, repair pipeline included
     * @param mapper coordinate mapping, nearest-widget geometry and the dead-pair ban
     * @param telemetry the counters and the {@code [APE-LLM-*]} lines
     */
    public LlmEngine(ScreenshotStep screenshot, ApePromptBuilder promptBuilder, LlmClient client,
                     ToolCallParser parser, CoordinateMapper mapper, LlmTelemetry telemetry) {
        this.screenshot = screenshot;
        this.promptBuilder = promptBuilder;
        this.client = client;
        this.parser = parser;
        this.mapper = mapper;
        this.telemetry = telemetry;
    }

    /**
     * Run the pipeline and return the action the model asked for, or null.
     *
     * <p>Null is an ordinary outcome, not an error: the LLM declined, failed, or was refused, and
     * the remainder of the assembled pipeline decides the step (INV-DP-11).
     *
     * <p><b>The five per-step values arrive as arguments and that is deliberate.</b> This object is
     * built once per run and owned by {@code RunContext}, while {@code mopData} and
     * {@code recentActions} belong to the step the calling stage is deciding; sourcing them here
     * would mean holding the agent or its step view, which is exactly the coupling design D2 exists
     * to prevent.
     *
     * @param tree the current tree, for the device dimensions and the prompt
     * @param state the current abstract state
     * @param actions the candidate actions the state offers
     * @param mopData MOP reachability data for the prompt; may be null
     * @param recentActions the action-history ring the prompt shows; may be null or empty
     * @param mode which hook asked — {@code new-state}, {@code stagnation} or {@code random} — as
     *        the decision sub-event reports it
     * @return the selected action, or null when nothing was selected
     */
    public ModelAction selectAction(GUITree tree,
                                    State state,
                                    List<ModelAction> actions,
                                    MopData mopData,
                                    List<ApePromptBuilder.ActionHistoryEntry> recentActions,
                                    String mode) {
        telemetry.countAttempt();
        long startMs = System.currentTimeMillis();

        int[] dimensions = screenshot.deviceDimensions(tree);
        int deviceWidth = dimensions[0];
        int deviceHeight = dimensions[1];

        // Temporaries — released in finally for GC
        byte[] pngBytes = null;
        String base64 = null;
        List<SglangClient.Message> messages = null;

        try {
            // Step 2: read the screen. A null capture (secure window) is a failure, not a free
            // retry: recording it is what eventually opens the breaker on an app that never yields
            // a screen, and stops the run paying per-step for a call that cannot work.
            pngBytes = screenshot.capture(deviceWidth, deviceHeight);
            if (pngBytes == null) {
                client.recordFailure();
                telemetry.screenshotFailed(activityOf(state), screenshot.getLastFailureStage());
                return null;
            }

            // Step 3: resize and encode. Not a breaker failure — the screen was readable and the
            // server was never asked, so nothing about the model's health was learned.
            base64 = screenshot.encode(pngBytes);
            if (base64 == null) {
                telemetry.imageFailed();
                return null;
            }

            // Step 4: build the prompt and record it
            messages = promptBuilder.build(tree, state, actions, mopData, base64, recentActions);
            telemetry.logPrompt(messages);

            // Step 5: ask the model. The wire schema is chosen by the same hasInputField predicate
            // that decided whether the system message lists type_text, so the model is never
            // offered a tool the prompt says does not exist (INV-LLM-11).
            SglangClient.ChatResponse response = client.chat(messages,
                    ApePromptBuilder.hasInputField(actions));
            if (response == null) {
                client.recordFailure();
                // The only site that may read the client's error seam: chat() reset it at entry, so
                // its value belongs to exactly this call and any later read is stale (INV-LLM-08).
                telemetry.transportFailed(client.getLastErrorCause());
                return null;
            }

            // Step 6: announce the serving model once, then record what came back
            telemetry.acknowledge(response);
            telemetry.logResponse(response);

            // Step 7: extract the tool call, repairs included. The client's seam is stale by now —
            // the call succeeded and the failure is extraction — so it MUST NOT be consulted.
            ToolCallParser.ParsedAction parsed = parser.parse(response);
            if (parsed == null) {
                client.recordFailure();
                telemetry.parseFailed();
                return null;
            }

            // Step 8: the answer becomes a pixel, the pixel becomes an action, and the ban decides
            // whether that action is still worth executing. A banned answer is a refused answer: it
            // leaves by the same null path as no_match and the breaker still records success below,
            // because the pipeline did not fail — only its answer was declined (INV-RTR-15/16).
            int[] pixels = mapper.toPixels(parsed.getX(), parsed.getY(), deviceWidth, deviceHeight);
            ModelAction match = mapper.map(pixels[0], pixels[1], parsed.getActionType(),
                    parsed.getText(), actions, state, deviceWidth, deviceHeight);
            boolean banned = false;
            if (match != null && mapper.isDeadPair(mapper.banKey(match))) {
                banned = true;
                match = null;
            }

            CoordinateMapper.Nearest nearest = mapper.nearest(actions, pixels[0], pixels[1]);

            // Step 9: the pipeline completed. Judge the answer, apply the typed text a matched
            // input widget is owed, and hand the verdict over to be counted and said.
            client.recordSuccess();
            long elapsedMs = System.currentTimeMillis() - startMs;
            Verdict verdict = classify(match, banned, parsed);
            if ("matched".equals(verdict.result)) {
                applyTypedText(match, parsed);
            }
            telemetry.decision(mode, parsed, pixels[0], pixels[1],
                    verdict.result, verdict.noMatchReason, verdict.matchedClass,
                    nearest, response, elapsedMs);

            // Step 10
            return match;

        } catch (Exception e) {
            telemetry.internalError(e.getMessage());
            return null;
        } finally {
            // Memory cleanup — these objects can be large
            pngBytes = null;
            base64 = null;
            messages = null;
        }
    }

    // -------------------------------------------------------------------------
    // The judgement
    // -------------------------------------------------------------------------

    /** What the engine made of an answer: what it was, why nothing was selected, and on what. */
    static final class Verdict {

        /** {@code matched}, {@code llm_tap} or {@code no_match}. */
        final String result;

        /**
         * Why nothing was selected — {@code dead_pair}, {@code degenerate} or {@code boundary} —
         * and null for the two outcomes that selected something.
         */
        final String noMatchReason;

        /** Simple class name of the widget the answer resolved to, or {@code none}. */
        final String matchedClass;

        Verdict(String result, String noMatchReason, String matchedClass) {
            this.result = result;
            this.noMatchReason = noMatchReason;
            this.matchedClass = matchedClass;
        }
    }

    /**
     * Decide what an answer was, from the mapping's result and the ban's verdict.
     *
     * <p>The three fields are computed together because they are one branch: an off-tree tap has no
     * widget class to report and no reason to give, a match has a class and no reason, and a refusal
     * has a reason and no class. Computing them apart would mean walking the same branch three
     * times and letting the three walks disagree.
     *
     * <p>The three {@code no_match} reasons are what an offline reader separates the mechanisms by:
     * a ban refused an answer that mapped, a {@code (0,0)} emission is the model degenerating, and
     * everything else is a coordinate the boundary bands or the snap tolerance turned down.
     *
     * @param match what the mapping returned, already nulled if the ban refused it
     * @param banned whether that null came from the ban rather than from the mapping
     * @param parsed the model's answer, for the degenerate-coordinate test
     */
    static Verdict classify(ModelAction match, boolean banned, ToolCallParser.ParsedAction parsed) {
        if (match instanceof LlmTapAction) {
            return new Verdict("llm_tap", null, "none");
        }
        if (match != null) {
            String matchedClass = "none";
            try {
                GUITreeNode node = match.getResolvedNode();
                if (node != null) {
                    String className = node.getClassName();
                    matchedClass = className != null
                            ? className.substring(className.lastIndexOf('.') + 1) : "View";
                }
            } catch (Exception ignored) { /* keep the placeholder */ }
            return new Verdict("matched", null, matchedClass);
        }
        if (banned) {
            return new Verdict("no_match", "dead_pair", "none");
        }
        return new Verdict("no_match",
                (parsed.getX() == 0 && parsed.getY() == 0) ? "degenerate" : "boundary", "none");
    }

    /**
     * Give a matched input widget the text the model typed.
     *
     * <p>The text rides on the node rather than on the action because that is where APE's dispatch
     * reads it: a {@code MODEL_CLICK} on a node carrying {@code inputText} types it. A node that
     * refuses the text costs the decision its text, not the decision itself — the action still
     * executes, and the failure is said rather than thrown.
     *
     * @param match the action the answer resolved to
     * @param parsed the model's answer, whose text is applied only for {@code type_text}
     */
    static void applyTypedText(ModelAction match, ToolCallParser.ParsedAction parsed) {
        if (!"type_text".equals(parsed.getActionType()) || parsed.getText() == null) {
            return;
        }
        try {
            GUITreeNode node = match.getResolvedNode();
            if (node != null) {
                node.setInputText(parsed.getText());
            }
        } catch (Exception e) {
            Logger.println("[APE-RV] LLM setInputText failed: " + e.getMessage());
        }
    }

    /**
     * The activity a decision was taken on, or {@code unknown}.
     *
     * <p>A placeholder rather than a propagated failure: the name is what makes per-app degradation
     * countable offline, and a decision whose activity could not be read is still a decision worth
     * counting.
     */
    private static String activityOf(State state) {
        try {
            if (state != null) {
                return state.getActivity();
            }
        } catch (Exception ignored) { /* keep the placeholder */ }
        return "unknown";
    }
}

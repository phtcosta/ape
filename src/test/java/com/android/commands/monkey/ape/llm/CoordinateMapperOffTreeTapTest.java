package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for the off-tree coordinate-tap branch of {@link CoordinateMapper#map}
 * (llm-coordinate-tap, §4.1 / llm-routing spec). Exercised purely in the JVM: the candidate
 * actions carry no resolved node (unresolved, off-device), so both the bounds-containment and
 * Euclidean passes skip them and the mapping reaches the end-of-function off-tree branch — the
 * exact condition the change targets (an in-bounds coordinate matching no widget).
 *
 * <p>The full decision pipeline (screenshot → HTTP → parse → classify) loads AndroidDevice and is
 * device-gated, so the per-decision {@code [APE-LLM-TEL]} line is validated by the device smoke
 * (tasks.md 6.3) rather than here.
 */
public class CoordinateMapperOffTreeTapTest {

    /**
     * A mapper wired from a plan carrying only the LLM feature, so the snap floor and the two
     * boundary bands are the jar defaults every assertion here was written against.
     */
    private static CoordinateMapper newMapper() {
        return new CoordinateMapper(
                TestRunSpecs.spec("ape.llmUrl", "http://localhost:9999/v1").llm());
    }

    private static final int W = 1080;
    private static final int H = 1794;

    /** One candidate action with no resolved node — skipped by both matching passes. */
    private static List<ModelAction> unresolvedActions() {
        List<ModelAction> actions = new ArrayList<>();
        actions.add(new ModelAction(null, ActionType.MODEL_CLICK)); // resolvedNode == null → skipped
        return actions;
    }

    @Test
    public void offTreeClickBuildsTap() {
        CoordinateMapper mapper = newMapper();
        ModelAction result = mapper.map(
                600, 900, "click", null, unresolvedActions(), null, W, H);
        assertTrue("off-tree click must synthesize an LlmTapAction", result instanceof LlmTapAction);
        LlmTapAction tap = (LlmTapAction) result;
        assertEquals(ActionType.MODEL_LLM_TAP, tap.getType());
        assertEquals(600, tap.getPixelX());
        assertEquals(900, tap.getPixelY());
        assertFalse(tap.isLongClick());
        assertFalse("off-tree tap is targetless", tap.requireTarget());
        assertNull(tap.getTarget());
    }

    @Test
    public void offTreeLongClickBuildsLongPressTap() {
        CoordinateMapper mapper = newMapper();
        ModelAction result = mapper.map(
                600, 900, "long_click", null, unresolvedActions(), null, W, H);
        assertTrue(result instanceof LlmTapAction);
        assertTrue("long_click off-tree must be a long-press tap", ((LlmTapAction) result).isLongClick());
    }

    @Test
    public void offTreeTypeTextStaysNoMatch() {
        CoordinateMapper mapper = newMapper();
        // A raw coordinate has no EditText node to receive input — no off-tree tap is synthesized.
        assertNull(mapper.map(
                600, 900, "type_text", "hello", unresolvedActions(), null, W, H));
    }

    @Test
    public void boundaryRejectSynthesizesNoTap() {
        CoordinateMapper mapper = newMapper();
        // Nav-band coordinate: pixelY > H*0.94 (1794*0.94 = 1686). Boundary reject runs before the
        // off-tree branch, so null is returned and NO tap is constructed.
        ModelAction result = mapper.map(
                600, 1750, "click", null, unresolvedActions(), null, W, H);
        assertNull("boundary-band coordinate must not become a tap", result);
    }

    @Test
    public void topBandRejectSynthesizesNoTap() {
        CoordinateMapper mapper = newMapper();
        // Status-band coordinate: pixelY < H*0.05 (1794*0.05 = 89.7). The candidate is unresolved,
        // so without the reject this point would reach the off-tree branch and become a tap — which
        // is what makes the null here attributable to the top band and not to an absent match.
        ModelAction result = mapper.map(
                600, 80, "click", null, unresolvedActions(), null, W, H);
        assertNull("top-band coordinate must not become a tap", result);
    }

    @Test
    public void backActionTakesUnchangedPath() {
        CoordinateMapper mapper = newMapper();
        // "back" is dispatched to state.getBackAction() before any coordinate matching; with a null
        // state the NPE is caught internally → null (unchanged behavior). No off-tree tap for back.
        assertNull(mapper.map(0, 0, "back", null, unresolvedActions(), null, W, H));
    }
}

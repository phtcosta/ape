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
                600, 900, "click", null, unresolvedActions(), null, W, H, false);
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
                600, 900, "long_click", null, unresolvedActions(), null, W, H, false);
        assertTrue(result instanceof LlmTapAction);
        assertTrue("long_click off-tree must be a long-press tap", ((LlmTapAction) result).isLongClick());
    }

    @Test
    public void offTreeTypeTextStaysNoMatch() {
        CoordinateMapper mapper = newMapper();
        // A raw coordinate has no EditText node to receive input — no off-tree tap is synthesized.
        assertNull(mapper.map(
                600, 900, "type_text", "hello", unresolvedActions(), null, W, H, false));
    }

    @Test
    public void boundaryRejectSynthesizesNoTap() {
        CoordinateMapper mapper = newMapper();
        // Nav-band coordinate: pixelY > H*0.94 (1794*0.94 = 1686). Boundary reject runs before the
        // off-tree branch, so null is returned and NO tap is constructed.
        ModelAction result = mapper.map(
                600, 1750, "click", null, unresolvedActions(), null, W, H, false);
        assertNull("boundary-band coordinate must not become a tap", result);
    }

    @Test
    public void topBandRejectSynthesizesNoTap() {
        CoordinateMapper mapper = newMapper();
        // Status-band coordinate: pixelY < H*0.05 (1794*0.05 = 89.7). The candidate is unresolved,
        // so without the reject this point would reach the off-tree branch and become a tap — which
        // is what makes the null here attributable to the top band and not to an absent match.
        ModelAction result = mapper.map(
                600, 80, "click", null, unresolvedActions(), null, W, H, false);
        assertNull("top-band coordinate must not become a tap", result);
    }

    @Test
    public void backActionTakesUnchangedPath() {
        CoordinateMapper mapper = newMapper();
        // "back" is dispatched to state.getBackAction() before any coordinate matching; with a null
        // state the NPE is caught internally → null (unchanged behavior). No off-tree tap for back.
        assertNull(mapper.map(0, 0, "back", null, unresolvedActions(), null, W, H, false));
    }

    // -------------------------------------------------------------------------
    // Opaque screens with the bands: the same mapping as any other step
    // -------------------------------------------------------------------------

    /** A game canvas's candidates: {@code MODEL_BACK} and {@code MODEL_MENU}, nothing targetable. */
    private static List<ModelAction> opaqueActions() {
        List<ModelAction> actions = new ArrayList<>();
        actions.add(new ModelAction(null, ActionType.MODEL_BACK));
        actions.add(new ModelAction(null, ActionType.MODEL_MENU));
        return actions;
    }

    @Test
    public void opaqueClickMidScreenBuildsTap() {
        ModelAction result = newMapper().map(
                600, H / 2, "click", null, opaqueActions(), null, W, H, false);
        assertTrue("with no widget on the screen a click is an off-tree tap",
                result instanceof LlmTapAction);
        assertEquals(H / 2, ((LlmTapAction) result).getPixelY());
    }

    @Test
    public void withTheBandsTheBottomBandStillRejectsAnOpaqueClick() {
        // Without edgeBandsOff — every step that is not opaque dynamic with opaque routing on —
        // the bands apply as before and the answer is counted as reason:"boundary".
        int pixelY = (int) (H * 0.97);
        ModelAction result = newMapper().map(
                600, pixelY, "click", null, opaqueActions(), null, W, H, false);
        assertNull(result);

        LlmEngine.Verdict verdict = LlmEngine.classify(result, false,
                new ToolCallParser.ParsedAction("click", 600, pixelY, null, null, "none"),
                600, pixelY, false);
        assertEquals("no_match", verdict.result);
        assertEquals("boundary", verdict.noMatchReason);
    }

    // -------------------------------------------------------------------------
    // Opaque dynamic steps: no bands, a zero on either axis rejected (INV-RTR-27)
    // -------------------------------------------------------------------------

    @Test
    public void withoutTheBandsAClickAtTheBottomEdgeBuildsTap() {
        int pixelY = (int) (H * 0.97);
        ModelAction result = newMapper().map(
                600, pixelY, "click", null, opaqueActions(), null, W, H, true);
        assertTrue("the bottom band is lifted on an opaque dynamic step",
                result instanceof LlmTapAction);
        assertEquals(pixelY, ((LlmTapAction) result).getPixelY());
    }

    @Test
    public void withoutTheBandsAClickAtTheTopEdgeBuildsTap() {
        int pixelY = (int) (H * 0.02);
        ModelAction result = newMapper().map(
                600, pixelY, "click", null, opaqueActions(), null, W, H, true);
        assertTrue("the top band is lifted on an opaque dynamic step",
                result instanceof LlmTapAction);
        assertEquals(pixelY, ((LlmTapAction) result).getPixelY());
    }

    @Test
    public void withoutTheBandsAZeroOnEitherAxisIsRejected() {
        CoordinateMapper mapper = newMapper();
        for (int[] xy : new int[][] {{0, 0}, {540, 0}, {0, 900}}) {
            assertNull("(" + xy[0] + ", " + xy[1] + ")",
                    mapper.map(xy[0], xy[1], "click", null, opaqueActions(), null, W, H, true));
        }
    }

    @Test
    public void aFlaggedZeroAxisRejectionIsDegenerate() {
        for (int[] xy : new int[][] {{0, 0}, {540, 0}, {0, 900}}) {
            LlmEngine.Verdict verdict = LlmEngine.classify(null, false,
                    new ToolCallParser.ParsedAction("click", xy[0], xy[1], null, null, "none"),
                    xy[0], xy[1], true);
            assertEquals("no_match", verdict.result);
            assertEquals("(" + xy[0] + ", " + xy[1] + ")", "degenerate", verdict.noMatchReason);
        }
    }

    @Test
    public void anUnflaggedZeroOnOneAxisStaysBoundary() {
        // Without the flag only (0, 0) is degenerate, as before; (540, 0) is the top band's.
        LlmEngine.Verdict verdict = LlmEngine.classify(null, false,
                new ToolCallParser.ParsedAction("click", 540, 0, null, null, "none"),
                540, 0, false);
        assertEquals("no_match", verdict.result);
        assertEquals("boundary", verdict.noMatchReason);
    }

    @Test
    public void aFlaggedAnswerThatNormalizesToPixelZeroIsDegenerate() {
        // The mapper rejects on pixels; CoordinateNormalizer truncates and clamps, so a negative
        // answer, or one under 1000/width on a narrow axis, reaches pixel 0 from a non-zero parsed
        // value. The label must name the rejection the mapper made (review WR-01).
        int[][] cases = {{-5, 500, W, H}, {1, 500, 720, 1280}};
        for (int[] c : cases) {
            int[] px = CoordinateNormalizer.normalize(c[0], c[1], c[2], c[3]);
            assertEquals("pixel x", 0, px[0]);
            assertNull(newMapper().map(px[0], px[1], "click", null, opaqueActions(), null,
                    c[2], c[3], true));
            LlmEngine.Verdict verdict = LlmEngine.classify(null, false,
                    new ToolCallParser.ParsedAction("click", c[0], c[1], null, null, "none"),
                    px[0], px[1], true);
            assertEquals("no_match", verdict.result);
            assertEquals("parsed (" + c[0] + ", " + c[1] + ")", "degenerate",
                    verdict.noMatchReason);
        }
    }

    @Test
    public void aFlaggedNullOffTheZeroAxesStaysBoundary() {
        // A type_text with no input field maps to null at a non-zero pixel: not degenerate.
        assertNull(newMapper().map(600, 900, "type_text", "x", opaqueActions(), null, W, H, true));
        LlmEngine.Verdict verdict = LlmEngine.classify(null, false,
                new ToolCallParser.ParsedAction("type_text", 556, 502, "x", null, "none"),
                600, 900, true);
        assertEquals("no_match", verdict.result);
        assertEquals("boundary", verdict.noMatchReason);
    }
}

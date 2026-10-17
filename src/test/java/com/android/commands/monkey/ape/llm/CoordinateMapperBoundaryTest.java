package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;

import org.junit.Test;

import java.util.List;

import static com.android.commands.monkey.ape.llm.PromptFixtures.H;
import static com.android.commands.monkey.ape.llm.PromptFixtures.W;
import static org.junit.Assert.*;

/**
 * llm-routing INV-RTR-22: the boundary reject in the display base. One top band, the status bar
 * ({@code ape.llmBoundaryTopPct}, default 0.035 = 63 px of the 1794 px campaign display), no lower
 * band, and an answer outside [0,999] refused before it is mapped ({@link CoordinateMapper#inAnswerSpace}).
 */
public class CoordinateMapperBoundaryTest {

    private static CoordinateMapper mapper(String... keyValues) {
        String[] keys = new String[keyValues.length + 4];
        keys[0] = "ape.llm";
        keys[1] = "true";
        keys[2] = "ape.llmUrl";
        keys[3] = "http://localhost:9999/v1";
        System.arraycopy(keyValues, 0, keys, 4, keyValues.length);
        return new CoordinateMapper(TestRunSpecs.spec(keys).llm());
    }

    /** One action with no resolved node: without a reject, its point would become an off-tree tap. */
    private static List<ModelAction> unresolved() {
        return PromptFixtures.list(new ModelAction(null, ActionType.MODEL_CLICK));
    }

    private static ModelAction toolbar() {
        return PromptFixtures.click("androidx.appcompat.widget.Toolbar", "Toolbar", new Rect(0, 63, 1080, 210));
    }

    /** Scenario "Boundary reject — status bar". */
    @Test
    public void statusBarAnswerIsRejected() {
        ModelAction result = mapper().map(540, 40, "click", null, unresolved(), null, W, H);
        assertNull(result);
        assertFalse(result instanceof LlmTapAction);
    }

    /** Scenario "toolbar below the status bar is not rejected". */
    @Test
    public void toolbarBelowTheStatusBarIsMatched() {
        ModelAction toolbar = toolbar();
        assertSame(toolbar, mapper().map(540, 80, "click", null, PromptFixtures.list(toolbar), null, W, H));
    }

    /** Scenario "Boundary reject — navigation bar": there is no lower band on the display. */
    @Test
    public void bottomNavigationItemIsMatched() {
        ModelAction item = PromptFixtures.click("android.widget.FrameLayout", "Home", new Rect(0, 1647, 270, 1794));
        assertSame(item, mapper().map(135, 1720, "click", null, PromptFixtures.list(item), null, W, H));
    }

    /** The bottom edge of the display: an off-tree tap, not a reject. */
    @Test
    public void bottomEdgeOfTheDisplayIsNotRejected() {
        assertTrue(mapper().map(600, H - 1, "click", null, unresolved(), null, W, H) instanceof LlmTapAction);
    }

    /** A degenerate (0,0) emission is still caught by the top band. */
    @Test
    public void degenerateOriginIsRejected() {
        assertNull(mapper().map(0, 0, "click", null, unresolved(), null, W, H));
    }

    /** Scenario "Band configurable without rebuild". */
    @Test
    public void topBandIsConfigurable() {
        ModelAction toolbar = toolbar();
        assertNull(mapper("ape.llmBoundaryTopPct", "0.05")
                .map(540, 80, "click", null, PromptFixtures.list(toolbar), null, W, H));
    }

    @Test
    public void answerSpaceEdges() {
        assertTrue(CoordinateMapper.inAnswerSpace(0, 0));
        assertTrue(CoordinateMapper.inAnswerSpace(999, 999));
        assertFalse(CoordinateMapper.inAnswerSpace(1000, 500));
        assertFalse(CoordinateMapper.inAnswerSpace(500, 1000));
        assertFalse(CoordinateMapper.inAnswerSpace(-1, 500));
        assertFalse(CoordinateMapper.inAnswerSpace(500, -1));
    }
}

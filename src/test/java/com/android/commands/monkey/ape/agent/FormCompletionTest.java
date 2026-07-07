package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * form-completion (#1) — pure-logic coverage of {@link FormCompletion#selectSubmitCandidate}.
 *
 * <p>INV-FORM-05 (the MOP-boosted target is the submit candidate, highest {@code mopBoost} wins)
 * and the no-candidate case (INV-FORM-04) are testable without an Android runtime: the MOP branch
 * reads only {@code ModelAction.getMopBoost()}. The node-dependent paths
 * ({@code isUnfilledEditText}, {@code hasUnfilledEditText}, and the lone-Button / submit-word
 * heuristic) require a live {@code GUITreeNode} and are device-validated per
 * {@code docs/20260622_investigacao_mop.md} §7.5.
 *
 * <p>{@link State} is built via reflection (its normal constructor needs Android's ComponentName),
 * mirroring {@code UICoverageTrackerTest}; the MOP branch of {@code selectSubmitCandidate} only
 * calls {@code state.getActions()}.
 */
public class FormCompletionTest {

    private static final int TS = 1;

    private static ModelAction clickAction(int mopBoost) {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setMopBoost(mopBoost);
        return a;
    }

    private static State stateWith(ModelAction... actions) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        State state = (State) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, State.class);
        Field actionsField = State.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        actionsField.set(state, actions);
        return state;
    }

    @Test
    public void testMopBoostedTargetIsSubmitCandidate_highestWins() throws Exception {
        ModelAction weak = clickAction(300);
        ModelAction strong = clickAction(500);
        State state = stateWith(weak, strong, clickAction(0));
        assertSame("highest-mopBoost target is the submit candidate (INV-FORM-05)",
                strong, FormCompletion.selectSubmitCandidate(state, TS));
    }

    @Test
    public void testNoMopAndNoResolvedClickable_returnsNull() throws Exception {
        // No mopBoost>0, and the clickables are unresolved (isResolvedAt(TS)==false), so the
        // node-based heuristic matches nothing → null (INV-FORM-04, submit=none).
        State state = stateWith(clickAction(0), clickAction(0));
        assertNull(FormCompletion.selectSubmitCandidate(state, TS));
    }

    @Test
    public void testNullStateReturnsNull() {
        assertNull(FormCompletion.selectSubmitCandidate(null, TS));
        assertFalse(FormCompletion.hasUnfilledEditText(null, TS));
    }
}

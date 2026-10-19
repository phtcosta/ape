package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.llm.PromptFixtures.TestName;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * form-completion (#1) — pure-logic coverage of {@link FormCompletion#selectSubmitCandidate}: the
 * minimal heuristic (a lone enabled {@code Button}, else a submit-word clickable), which reads no
 * scorer's field (INV-FORM-05, design D-B14.10), and the no-candidate case (INV-FORM-04).
 *
 * <p>{@link State} is built via reflection (its normal constructor needs Android's ComponentName),
 * mirroring {@code UICoverageTrackerTest}; {@code selectSubmitCandidate} only calls
 * {@code state.getActions()}. The actions are resolved at {@link #TS} on plain nodes.
 */
public class FormCompletionTest {

    private static final int TS = 1;

    private static ModelAction clickAction(int mopBoost) {
        ModelAction a = new ModelAction(null, ActionType.MODEL_CLICK);
        a.setMopBoost(mopBoost);
        return a;
    }

    /** A valid click resolved at {@link #TS} on an enabled node of {@code className}. */
    private static ModelAction resolvedClick(String xpath, String className, String text,
            int mopBoost) {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName(className);
        node.setText(text);
        node.setEnabled(true);
        ModelAction a = new ModelAction(null, new TestName(xpath), ActionType.MODEL_CLICK);
        a.resolveAt(TS, 0, null, node, new GUITreeNode[] {node});
        a.setValid(true);
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

    /** Scenario "MOP-boosted target is the submit candidate": the lone Button wins, not the boost. */
    @Test
    public void theLoneButtonIsTheCandidateWhateverTheMopBoost() throws Exception {
        ModelAction image = resolvedClick("//image", "android.widget.ImageView", null, 500);
        ModelAction button = resolvedClick("//button", "android.widget.Button", "Go", 0);
        assertSame("the choice reads no scorer field (INV-FORM-05)",
                button, FormCompletion.selectSubmitCandidate(stateWith(image, button), TS));
        // The same screen without MOP fields yields the same candidate.
        image.setMopBoost(0);
        assertSame(button, FormCompletion.selectSubmitCandidate(stateWith(image, button), TS));
    }

    /** Scenario "Submit-word clickable is the candidate when several buttons exist". */
    @Test
    public void theSubmitWordWinsAmongSeveralButtons() throws Exception {
        ModelAction cancel = resolvedClick("//cancel", "android.widget.Button", "Cancel", 0);
        ModelAction encrypt = resolvedClick("//encrypt", "android.widget.Button", "Encrypt", 0);
        assertSame(encrypt, FormCompletion.selectSubmitCandidate(stateWith(cancel, encrypt), TS));
    }

    /** Scenario "No identifiable submit candidate": a MOP boost alone names no candidate. */
    @Test
    public void aMopBoostAloneNamesNoCandidate() throws Exception {
        State state = stateWith(clickAction(500), clickAction(300), clickAction(0));
        assertNull(FormCompletion.selectSubmitCandidate(state, TS));
    }

    @Test
    public void testNullStateReturnsNull() {
        assertNull(FormCompletion.selectSubmitCandidate(null, TS));
        assertFalse(FormCompletion.hasUnfilledEditText(null, TS));
    }
}

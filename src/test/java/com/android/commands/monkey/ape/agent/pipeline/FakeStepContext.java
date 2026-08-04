package com.android.commands.monkey.ape.agent.pipeline;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.android.commands.monkey.ape.llm.ApePromptBuilder;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.Graph;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.utils.ActivityBudgetTracker;
import com.android.commands.monkey.ape.utils.MopData;

/**
 * A step, stated as values, for the stage tests.
 *
 * <p>Every field is settable and starts at the value a stage should read as "nothing here". The
 * point of the stages was to make a predicate assertable without an agent, a device or a live model,
 * and this is what cashes that in: a test says what the step is and asks the stage what it decides.
 *
 * <p>Note what it does <em>not</em> do: it never fails on an unread member. A test that needs to
 * assert a stage read nothing it was not entitled to — the trivial-activity search is the case, since
 * consulting it early changes a step's trace — states that with its own context, as
 * {@link BudgetStageTest} does.
 */
class FakeStepContext implements StepContext {

    State newState;
    GUITree newGUITree;
    boolean isNewState;
    int actionBufferSize;
    int graphStableCounter;
    int timestamp = 1;
    Random random;
    MopData mopData;
    Graph graph;
    ActivityBudgetTracker budgetTracker;
    List<ApePromptBuilder.ActionHistoryEntry> actionHistory = new ArrayList<>();
    int graphStableCounterResets;

    @Override public State newState() { return newState; }
    @Override public GUITree newGUITree() { return newGUITree; }
    @Override public boolean isNewState() { return isNewState; }
    @Override public int actionBufferSize() { return actionBufferSize; }
    @Override public int graphStableCounter() { return graphStableCounter; }
    @Override public int timestamp() { return timestamp; }
    @Override public Random random() { return random; }
    @Override public MopData mopData() { return mopData; }
    @Override public Graph graph() { return graph; }
    @Override public ActivityBudgetTracker budgetTracker() { return budgetTracker; }
    @Override public List<ApePromptBuilder.ActionHistoryEntry> actionHistory() { return actionHistory; }

    @Override
    public void resetGraphStableCounter() {
        graphStableCounterResets++;
        graphStableCounter = 0;
    }

    /**
     * A state on {@code activity} offering {@code actionCount} click actions.
     *
     * <p>Allocated rather than constructed: {@code StateKey}'s constructor needs an
     * {@code android.content.ComponentName}, which the surefire classpath does not carry.
     */
    static State stateWith(String activity, int actionCount) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);

        StateKey stateKey = (StateKey) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, StateKey.class);
        setField(stateKey, "activity", activity);
        State state = (State) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, State.class);
        setField(state, "stateKey", stateKey);

        ModelAction[] actions = new ModelAction[actionCount];
        for (int i = 0; i < actionCount; i++) {
            actions[i] = new ModelAction(state, ActionType.MODEL_CLICK);
        }
        setField(state, "actions", actions);
        return state;
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

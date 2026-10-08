package com.android.commands.monkey.ape.agent;

import android.graphics.Rect;

import com.android.commands.monkey.ape.llm.ApePromptBuilder.ActionHistoryEntry;
import com.android.commands.monkey.ape.llm.PromptFixtures;
import com.android.commands.monkey.ape.model.Action;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.LlmTapAction;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.model.StateKey;
import com.android.commands.monkey.ape.model.StateTransition;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.tree.GUITreeNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedList;

import static org.junit.Assert.*;

/**
 * exploration "StatefulAgent — Action History Ring Buffer" (llm-coordinate-single-base D5, D9,
 * D12, D13): what {@code recordActionHistory} stores, how the next state update fills the
 * entry's result with the outcome of the entry's own action, and how {@code checkRestart} removes
 * the entry of an action it replaces.
 *
 * <p>The agent is {@code Unsafe}-allocated as in {@code StatefulAgentTearDownTest}: no constructor
 * runs, and the fields these two methods read are set by hand. {@code updateStateInternal} itself
 * is not reachable from the JVM (it builds the state from an {@code AccessibilityNodeInfo}), so
 * {@link #update} replays the two pointer moves it makes before it fills the result
 * ({@code _stateBeforeLast = _lastState; _lastState = currentState}) and {@link #select} the
 * pointer move of {@code moveForward}. The order of the fill inside {@code updateStateInternal} is
 * checked by inspection and by the device trace check (task 8.2).
 */
public class StatefulAgentActionHistoryTest {

    private SataAgent agent;

    @Before
    public void setUp() throws Exception {
        TestRunSpecs.install("ape.llmUrl", "http://127.0.0.1:30000/v1");
        agent = allocate(SataAgent.class);
        agent._actionHistory = new ArrayList<>();
    }

    @After
    public void tearDown() {
        RunContext.resetForTest();
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** A state distinguished from the others by its activity (State.equals compares the key). */
    private static State state(String activity) throws Exception {
        StateKey key = allocate(StateKey.class);
        setField(key, "activity", activity);
        setField(key, "widgets", new Name[0]);
        State state = allocate(State.class);
        setField(state, "stateKey", key);
        return state;
    }

    /** The pointer moves {@code updateStateInternal} makes before it fills the last result. */
    private void update(State resolved) {
        agent._stateBeforeLast = agent._lastState;
        agent._lastState = agent.currentState;
        agent.newState = resolved;
        agent.fillLastHistoryResult();
    }

    /** A model action selected on the current update: recorded, then {@code moveForward}'s pointer move. */
    private void select(ModelAction action) {
        agent.recordActionHistory(action);
        agent.currentState = agent.newState;
    }

    private ActionHistoryEntry last() {
        return agent._actionHistory.get(agent._actionHistory.size() - 1);
    }

    private static ModelAction button(String text, Rect bounds) {
        return PromptFixtures.click("android.widget.Button", text, bounds);
    }

    private static void assertBounds(int l, int t, int r, int b, Rect actual) {
        assertNotNull(actual);
        assertEquals(l, actual.left);
        assertEquals(t, actual.top);
        assertEquals(r, actual.right);
        assertEquals(b, actual.bottom);
    }

    // -------------------------------------------------------------------------
    // What is recorded (D5, D9, D13)
    // -------------------------------------------------------------------------

    /** Scenarios "Action history populated after action" (selection half) and "entry carries the pixel bounds". */
    @Test
    public void nodeClickRecordsSimpleClassTextAndACopyOfThePixelBounds() {
        ModelAction login = button("Login", new Rect(0, 105, 1080, 252));
        agent.recordActionHistory(login);

        ActionHistoryEntry entry = last();
        assertEquals("click", entry.actionType);
        assertEquals("Button", entry.widgetClass);
        assertEquals("Login", entry.widgetText);
        assertEquals("", entry.result);
        assertBounds(0, 105, 1080, 252, entry.bounds);

        login.getResolvedNode().setBoundsInScreen(new Rect(1, 2, 3, 4));
        assertBounds(0, 105, 1080, 252, entry.bounds);
    }

    /** Scenario "icon with only a content-description". */
    @Test
    public void iconWithOnlyAContentDescription() {
        GUITreeNode icon = PromptFixtures.node("android.widget.ImageButton", null, new Rect(960, 70, 1060, 170));
        icon.setContentDesc("Settings");
        agent.recordActionHistory(PromptFixtures.action(new PromptFixtures.TestName("//icon"),
                ActionType.MODEL_CLICK, icon));

        assertEquals("ImageButton", last().widgetClass);
        assertEquals("Settings", last().widgetText);
    }

    /** Scenario "off-tree LLM tap recorded at its pixel". */
    @Test
    public void offTreeLlmTapIsRecordedAtItsPixel() {
        agent.recordActionHistory(new LlmTapAction(null, 600, 900, false));
        assertEquals("click", last().actionType);
        assertEquals("", last().widgetClass);
        assertBounds(600, 900, 601, 901, last().bounds);

        agent.recordActionHistory(new LlmTapAction(null, 10, 20, true));
        assertEquals("long_click", last().actionType);
        assertBounds(10, 20, 11, 21, last().bounds);
    }

    /** Scenario "menu press recorded as menu". */
    @Test
    public void menuPressIsRecordedAsMenu() {
        agent.recordActionHistory(new ModelAction(null, ActionType.MODEL_MENU));
        assertEquals("menu", last().actionType);
        assertNull(last().bounds);
        assertEquals("", last().widgetClass);
    }

    @Test
    public void backIsRecordedWithoutBounds() {
        agent.recordActionHistory(new ModelAction(null, ActionType.MODEL_BACK));
        assertEquals("back", last().actionType);
        assertNull(last().bounds);
    }

    /** Scenario "Ring buffer evicts oldest entry". */
    @Test
    public void ringBufferKeepsTheLastFive() {
        for (int i = 0; i < 6; i++) {
            agent.recordActionHistory(button("B" + i, new Rect(0, 105, 1080, 252)));
        }
        assertEquals(5, agent._actionHistory.size());
        assertEquals("B1", agent._actionHistory.get(0).widgetText);
        assertEquals("B5", last().widgetText);
    }

    // -------------------------------------------------------------------------
    // Whose outcome an entry carries (D12)
    // -------------------------------------------------------------------------

    /** Scenario "each entry carries its own action's outcome", read from the prompt built at that update. */
    @Test
    public void eachEntryCarriesItsOwnActionsOutcome() throws Exception {
        State s1 = state("S1");
        State s2 = state("S2");
        agent.currentState = s1;
        agent.newState = s1;

        select(button("A1", new Rect(0, 105, 1080, 252)));
        update(s2);
        select(button("A2", new Rect(0, 300, 1080, 400)));
        update(s2);

        assertEquals("new screen", agent._actionHistory.get(0).result);
        assertEquals("same", agent._actionHistory.get(1).result);

        String user = PromptFixtures.userText("v13", PromptFixtures.W, PromptFixtures.H, null,
                agent._actionHistory);
        assertTrue(user, user.contains("Button \"A1\" → new screen"));
        assertTrue(user, user.contains("Button \"A2\" → same"));
    }

    /** Scenario "Action history populated after action": the update after the click fills its result. */
    @Test
    public void nextUpdateFillsTheResult() throws Exception {
        agent.currentState = state("S0");
        agent.newState = agent.currentState;
        agent._lastState = state("S-1");

        select(button("Login", new Rect(0, 105, 1080, 252)));
        assertEquals("", last().result);
        update(state("S1"));
        assertEquals("new screen", last().result);
    }

    /** Scenario "Action returns to previous screen". */
    @Test
    public void backToTheStateBeforeIsPreviousScreen() throws Exception {
        State s1 = state("S1");
        State s2 = state("S2");
        agent.currentState = s1;
        agent.newState = s1;
        select(button("Open", new Rect(0, 105, 1080, 252)));
        update(s2);
        select(new ModelAction(null, ActionType.MODEL_BACK));
        update(s1);

        assertEquals("previous screen", last().result);
        assertEquals("back", last().actionType);
    }

    /** Scenario "a step without a model action does not shift an outcome". */
    @Test
    public void aStepWithoutAModelActionDoesNotShiftAnOutcome() throws Exception {
        State s1 = state("S1");
        agent.currentState = s1;
        agent.newState = s1;
        select(button("A1", new Rect(0, 105, 1080, 252)));
        update(state("S2"));
        assertEquals("new screen", last().result);

        // that step selects a non-model action: nothing is appended, moveForward does not run
        update(s1);
        assertEquals("new screen", last().result);
        assertEquals(1, agent._actionHistory.size());
    }

    @Test
    public void fillOnAnEmptyHistoryDoesNothing() throws Exception {
        update(state("S1"));
        assertTrue(agent._actionHistory.isEmpty());
    }

    // -------------------------------------------------------------------------
    // An action a restart replaces leaves no entry (D12, review WR-01)
    // -------------------------------------------------------------------------

    /**
     * {@code checkRestart} replaces the selected action with {@code getStartAction}, which reads
     * {@code ape.randomlyPickMainApp()}; the subclass answers without a device.
     */
    static class RestartingAgent extends SataAgent {
        RestartingAgent() {
            super(null, null);
        }

        @Override
        protected Action getStartAction(ActionType actionType) {
            return new Action(actionType);
        }
    }

    /** Swaps in an agent whose {@code checkRestart} can run; {@code restart} requests one. */
    private void restartingAgent(boolean restart) throws Exception {
        agent = allocate(RestartingAgent.class);
        agent._actionHistory = new ArrayList<>();
        setField(agent, "actionBuffer", new LinkedList<StateTransition>());
        setField(agent, "restart", restart);
        setField(agent, "nextRestartThreshold", Integer.MAX_VALUE);
    }

    /** Scenario "a model action replaced by a restart leaves no entry". */
    @Test
    public void aModelActionReplacedByARestartLeavesNoEntry() throws Exception {
        restartingAgent(true);
        State s1 = state("S1");
        agent.currentState = s1;
        agent.newState = s1;
        select(button("A1", new Rect(0, 105, 1080, 252)));
        update(state("S2"));
        ModelAction a2 = button("A2", new Rect(0, 300, 1080, 400));
        select(a2);

        Action dispatched = agent.checkRestart(a2);

        assertNotSame(a2, dispatched);
        assertEquals(1, agent._actionHistory.size());
        assertEquals("A1", last().widgetText);
        assertEquals("new screen", last().result);
    }

    /** Scenario "a restart that replaces no model action removes nothing". */
    @Test
    public void aRestartThatReplacesNoModelActionRemovesNothing() throws Exception {
        restartingAgent(true);
        State s1 = state("S1");
        agent.currentState = s1;
        agent.newState = s1;
        select(button("A1", new Rect(0, 105, 1080, 252)));
        update(state("S2"));

        Action origin = new Action(ActionType.EVENT_START);
        Action dispatched = agent.checkRestart(origin);

        assertNotSame(origin, dispatched);
        assertEquals(1, agent._actionHistory.size());
        assertEquals("new screen", last().result);
    }

    @Test
    public void noRestartKeepsTheEntry() throws Exception {
        restartingAgent(false);
        ModelAction a1 = button("A1", new Rect(0, 105, 1080, 252));
        agent.currentState = state("S1");
        select(a1);

        assertSame(a1, agent.checkRestart(a1));
        assertEquals(1, agent._actionHistory.size());
        assertEquals("", last().result);
    }

    /** A recording that failed leaves the filled entry of the previous action last: it stays. */
    @Test
    public void aRestartAfterAFailedRecordingRemovesNothing() throws Exception {
        restartingAgent(true);
        State s1 = state("S1");
        agent.currentState = s1;
        agent.newState = s1;
        select(button("A1", new Rect(0, 105, 1080, 252)));
        update(state("S2"));

        // A2 is selected but never recorded
        agent.checkRestart(button("A2", new Rect(0, 300, 1080, 400)));

        assertEquals(1, agent._actionHistory.size());
        assertEquals("A1", last().widgetText);
        assertEquals("new screen", last().result);
    }
}

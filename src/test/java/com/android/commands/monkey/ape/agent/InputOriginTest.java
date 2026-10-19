package com.android.commands.monkey.ape.agent;

import com.android.commands.monkey.ape.llm.PromptFixtures.TestName;
import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.runtime.RunContext;
import com.android.commands.monkey.ape.runtime.RunSpec;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.InputValueGenerator;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * heuristic-input INV-INP-10 and form-completion INV-FORM-03/08 (tasks 8.9.3, 8.9.4): the origin
 * {@code checkInput} records as {@code dec.inp}, with the precedence {@code llm} > {@code form} >
 * the generator, and the deterministic form fill that applies only when {@code FORM_COMPLETION} is
 * mounted.
 *
 * <p>The agent is {@code Unsafe}-allocated; the fields {@code checkInput} reads are set by hand.
 * The {@code typed} origin needs the device's foreground activity and is covered by the device
 * smoke; its precedence over the other generators is the order of {@code generateInputText}.
 */
public class InputOriginTest {

    private static final int TS = 1;

    private final List<String> origins = new ArrayList<>();
    private SataAgent agent;

    private void install(String... keyValues) throws Exception {
        RunSpec spec = TestRunSpecs.spec(keyValues);
        EventSink recording = (EventSink) Proxy.newProxyInstance(
                EventSink.class.getClassLoader(), new Class<?>[] {EventSink.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("input")) {
                        origins.add((String) args[0]);
                    }
                    return null;
                });
        RunContext.installForTest(spec, recording);
        agent = allocate(SataAgent.class);
        Field generator = ApeAgent.class.getDeclaredField("inputValueGenerator");
        generator.setAccessible(true);
        generator.set(agent, new InputValueGenerator());
        agent.timestamp = TS;
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

    /** A valid click resolved at {@link #TS} on an empty, enabled EditText. */
    private static ModelAction editText(String xpath) {
        GUITreeNode node = new GUITreeNode(null);
        node.setClassName("android.widget.EditText");
        node.setResourceID("com.x:id/" + xpath);
        node.setEnabled(true);
        ModelAction action = new ModelAction(null, new TestName("//" + xpath), ActionType.MODEL_CLICK);
        action.resolveAt(TS, 0, null, node, new GUITreeNode[] {node});
        action.setValid(true);
        action.setDecisionSource(ModelAction.DecisionSource.SATA);
        return action;
    }

    private static State stateWith(ModelAction... actions) throws Exception {
        State state = allocate(State.class);
        Field f = State.class.getDeclaredField("actions");
        f.setAccessible(true);
        f.set(state, actions);
        return state;
    }

    /** Scenario "Deterministic fill in form-completion context": every fill is {@code form}. */
    @Test
    public void aFormContextFillsDeterministicallyAsForm() throws Exception {
        install("ape.addons", "true");
        ModelAction first = editText("user");
        ModelAction second = editText("pass");
        agent.currentState = stateWith(first, second);
        for (int i = 0; i < 20; i++) {
            ModelAction field = i % 2 == 0 ? first : second;
            field.getResolvedNode().setInputText(null);
            agent.checkInput(field);
            assertNotNull("filled with probability 1 (INV-FORM-03)",
                    field.getResolvedNode().getInputText());
        }
        assertEquals(20, origins.size());
        for (String origin : origins) {
            assertEquals("form", origin);
        }
    }

    /** Scenario "Pass and deterministic fill both disabled when the flag is off" (8.9.4). */
    @Test
    public void noFormFillWithoutTheFeature() throws Exception {
        install("ape.addons", "true", "ape.formCompletionEnabled", "false");
        ModelAction field = editText("user");
        agent.currentState = stateWith(field);
        assertFalse(agent.inFormCompletionContext());

        install(); // the base cell: no ADDONS at all
        agent.currentState = stateWith(field);
        assertFalse(agent.inFormCompletionContext());
        for (int i = 0; i < 50; i++) {
            field.getResolvedNode().setInputText(null);
            agent.checkInput(field);
        }
        assertFalse("no step carries dec.inp:form", origins.contains("form"));
        assertTrue("the inputRate toss still fills some", origins.contains("random"));
        assertTrue("the toss gates the rest", origins.size() < 50);
    }

    /** An LLM decision's text entry is {@code llm}, over the form context. */
    @Test
    public void anLlmTextEntryIsLlm() throws Exception {
        install("ape.addons", "true");
        ModelAction field = editText("user");
        field.setDecisionSource(ModelAction.DecisionSource.LLM);
        agent.currentState = stateWith(field);
        agent.checkInput(field);
        assertEquals(java.util.Arrays.asList("llm"), origins);
    }

    /** A node that already holds text is not re-filled, and records nothing. */
    @Test
    public void aFilledNodeRecordsNothing() throws Exception {
        install("ape.addons", "true");
        ModelAction field = editText("user");
        field.getResolvedNode().setInputText("kept");
        agent.currentState = stateWith(field);
        agent.checkInput(field);
        assertEquals("kept", field.getResolvedNode().getInputText());
        assertTrue(origins.isEmpty());
    }

    /** Scenarios "Heuristic input enabled" / "disabled": the generator names its origin. */
    @Test
    public void theGeneratorNamesItsOrigin() throws Exception {
        GUITreeNode node = editText("email").getResolvedNode();
        install("ape.addons", "true");
        assertEquals("heuristic", agent.generateInputText(node).origin);
        install("ape.addons", "true", "ape.heuristicInput", "false");
        assertEquals("random", agent.generateInputText(node).origin);
        install();
        ApeAgent.InputText random = agent.generateInputText(node);
        assertEquals("random", random.origin);
        assertNotNull(random.text);
    }
}

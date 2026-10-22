package com.android.commands.monkey.ape.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * The {@code dec.fz} kind of each fuzzer event class (gh-tbd-base-equals-ape D14). The events are
 * allocated without their constructors, which take Android types absent from the JVM classpath;
 * the kind depends on the class alone.
 */
public class ApeEventsFuzzKindTest {

    @Test
    public void eachFuzzerEventClassHasItsKind() throws Exception {
        assertEquals("rotation", ApeEvents.fuzzKind(allocate(ApeRotationEvent.class)));
        assertEquals("app_switch", ApeEvents.fuzzKind(allocate(ApeAppSwitchEvent.class)));
        assertEquals("key", ApeEvents.fuzzKind(allocate(ApeKeyEvent.class)));
        assertEquals("trackball", ApeEvents.fuzzKind(allocate(ApeTrackballEvent.class)));
        assertEquals("drag", ApeEvents.fuzzKind(allocate(ApeDragEvent.class)));
        assertEquals("pinch", ApeEvents.fuzzKind(allocate(ApePinchOrZoomEvent.class)));
        assertEquals("click", ApeEvents.fuzzKind(allocate(ApeClickEvent.class)));
    }

    @Test
    public void theKindsFollowTheEventOrder() throws Exception {
        List<ApeEvent> events = new ArrayList<>();
        events.add(allocate(ApeKeyEvent.class));
        events.add(allocate(ApeDragEvent.class));
        events.add(allocate(ApeKeyEvent.class));
        events.add(allocate(ApeRotationEvent.class));
        assertEquals(Arrays.asList("key", "drag", "key", "rotation"), ApeEvents.fuzzKinds(events));
    }

    @Test
    public void noEventIsNoKind() {
        assertTrue(ApeEvents.fuzzKinds(Collections.<ApeEvent>emptyList()).isEmpty());
    }

    @Test
    public void anEventTheFuzzerDoesNotProduceIsNamedByItsClass() {
        ApeEvent other = new ApeEvent() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.util.List<com.android.commands.monkey.MonkeyEvent> generateMonkeyEvents() {
                return Collections.emptyList();
            }
        };
        assertEquals(other.getClass().getName(), ApeEvents.fuzzKind(other));
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }
}

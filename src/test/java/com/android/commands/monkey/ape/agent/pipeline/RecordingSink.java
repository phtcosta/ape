package com.android.commands.monkey.ape.agent.pipeline;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.android.commands.monkey.ape.telemetry.EventSink;
import com.android.commands.monkey.ape.telemetry.RunCounter;

/**
 * An {@link EventSink} that records the calls the pipeline and its stages make, for the stage
 * tests: every call is kept with its arguments, and the two the routing writes — {@code owner} and
 * {@code count} — have readers of their own.
 */
final class RecordingSink {

    /** One recorded call. */
    static final class Call {
        final String method;
        final List<Object> args;

        Call(String method, Object[] args) {
            this.method = method;
            this.args = args == null ? new ArrayList<>() : Arrays.asList(args);
        }
    }

    /** The {@code dec.own} members of one {@code owner} call. */
    static final class Own {
        final String i;
        final String trg;
        final double uL;
        final double uM;
        final String g;
        final String fb;

        Own(List<Object> a) {
            i = (String) a.get(0);
            trg = (String) a.get(1);
            uL = (Double) a.get(2);
            uM = (Double) a.get(3);
            g = (String) a.get(4);
            fb = (String) a.get(5);
        }
    }

    final List<Call> calls = new ArrayList<>();
    final EventSink sink = (EventSink) Proxy.newProxyInstance(EventSink.class.getClassLoader(),
            new Class<?>[] {EventSink.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return method.invoke(this, args);
                }
                calls.add(new Call(method.getName(), args));
                return null;
            });

    /** Every {@code owner} call, in order. */
    List<Own> owners() {
        List<Own> owners = new ArrayList<>();
        for (Call call : calls) {
            if (call.method.equals("owner")) {
                owners.add(new Own(call.args));
            }
        }
        return owners;
    }

    /** The last {@code owner} call. */
    Own lastOwner() {
        List<Own> owners = owners();
        return owners.get(owners.size() - 1);
    }

    /** How many times {@code counter} was counted. */
    int count(RunCounter counter) {
        int n = 0;
        for (Call call : calls) {
            if (call.method.equals("count") && call.args.get(0) == counter) {
                n++;
            }
        }
        return n;
    }
}

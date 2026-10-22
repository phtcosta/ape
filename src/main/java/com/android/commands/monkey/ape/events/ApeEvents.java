/*
 * Copyright 2020 Advanced Software Technologies Lab at ETH Zurich, Switzerland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.commands.monkey.ape.events;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

public class ApeEvents {

    /**
     * The {@code dec.fz} kind of a gesture event: {@code rotation}, {@code app_switch}, {@code key}
     * (every key event, navigation and system alike), {@code trackball}, {@code drag},
     * {@code pinch} or {@code click}.
     *
     * @param event an event of the fuzzer
     * @return its kind; the class name for an event class the fuzzer does not produce
     */
    public static String fuzzKind(ApeEvent event) {
        if (event instanceof ApeRotationEvent) {
            return "rotation";
        }
        if (event instanceof ApeAppSwitchEvent) {
            return "app_switch";
        }
        if (event instanceof ApeKeyEvent) {
            return "key";
        }
        if (event instanceof ApeTrackballEvent) {
            return "trackball";
        }
        if (event instanceof ApeDragEvent) {
            return "drag";
        }
        if (event instanceof ApePinchOrZoomEvent) {
            return "pinch";
        }
        if (event instanceof ApeClickEvent) {
            return "click";
        }
        // Not an event the fuzzer produces today; the record names it rather than failing the step.
        return event.getClass().getName();
    }

    /**
     * The {@code dec.fz} kinds of a fuzz action's events, in order. Reads the events only.
     *
     * @param events the events the fuzz action queues
     * @return one kind per event
     */
    public static List<String> fuzzKinds(List<ApeEvent> events) {
        List<String> kinds = new ArrayList<String>(events.size());
        for (ApeEvent event : events) {
            kinds.add(fuzzKind(event));
        }
        return kinds;
    }

    public static ApeEvent toApeEvent(JSONObject jEvent) throws JSONException {
        String type = jEvent.getString("type");
        if (type.equals(ApeClickEvent.class.getSimpleName())) {
            return ApeClickEvent.fromJSONObject(jEvent);
        }
        if (type.equals(ApeAppSwitchEvent.class.getSimpleName())) {
            return ApeAppSwitchEvent.fromJSONObject(jEvent);
        }
        if (type.equals(ApeDragEvent.class.getSimpleName())) {
            return ApeDragEvent.fromJSONObject(jEvent);
        }
        if (type.equals(ApePinchOrZoomEvent.class.getSimpleName())) {
            return ApePinchOrZoomEvent.fromJSONObject(jEvent);
        }
        if (type.equals(ApeKeyEvent.class.getSimpleName())) {
            return ApeKeyEvent.fromJSONObject(jEvent);
        }
        if (type.equals(ApeRotationEvent.class.getSimpleName())) {
            return ApeRotationEvent.fromJSONObject(jEvent);
        }
        if (type.equals(ApeTrackballEvent.class.getSimpleName())) {
            return ApeTrackballEvent.fromJSONObject(jEvent);
        }
        throw new IllegalArgumentException("Unknown event type: " + type);
    }
}

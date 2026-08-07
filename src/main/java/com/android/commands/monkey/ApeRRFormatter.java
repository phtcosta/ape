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
package com.android.commands.monkey;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.json.JSONObject;

import com.android.commands.monkey.ape.utils.Logger;
import com.android.commands.monkey.ape.utils.Utils;

/**
 * Reader for the record-and-replay action log that {@code ReplayAgent} consumes.
 *
 * <p>This class is a reader and nothing else. The tool does not write this format: the log it
 * parses is supplied from outside the run, through {@code ape.replayLog}, and replay is the only
 * path that reads it. The observational record of a run is the NDJSON trace
 * ({@code ape.telemetry.EventSink}), which is a different format with a different purpose.
 *
 * <p>Both halves of the parse are deliberately lenient, because the input is a user-supplied file
 * rather than something this process produced: a line whose head is not a decimal clock stamp is
 * skipped silently, a line whose tail does not parse as JSON is reported and skipped, and an
 * unreadable file yields an empty list rather than an exception. A replay of a truncated log is a
 * shorter replay, not a failed run.
 *
 * @author txgu
 */
public class ApeRRFormatter {

    private static final Pattern decimalNumber = Pattern.compile("[0-9]+");

    /**
     * @param jsonString one serialized action, the tail of a log line
     * @return null if jsonString is invalid
     */
    public static JSONObject readAction(String jsonString) {
        return Utils.toJSON(jsonString);
    }

    /**
     * Parses a replay log into its actions, in file order.
     *
     * <p>A log line is {@code <clockTime> <json>}; anything else is not an action line and is
     * skipped without comment, which is what lets the format tolerate the free-text lines a
     * capture may carry around it.
     *
     * @param logfile path to the externally supplied replay log
     * @return the parsed actions; empty if the file is unreadable
     */
    public static List<JSONObject> readActions(String logfile) {
        List<JSONObject> actions = new ArrayList<JSONObject>();
        try (BufferedReader br = new BufferedReader(new FileReader(logfile))) {
            String line;
            while ((line = br.readLine()) != null) {
                int index = line.indexOf(' ');
                if (index != -1) {
                    String head = line.substring(0, index);
                    if (decimalNumber.matcher(head).matches()) {
                        String tail = line.substring(index + 1);
                        JSONObject action = readAction(tail);
                        if (action == null) {
                            Logger.wformat("Fail to parse action line: %s", line);
                            continue;
                        }
                        actions.add(action);
                    }
                }
            }
        } catch (IOException e) {

        }
        return actions;
    }
}

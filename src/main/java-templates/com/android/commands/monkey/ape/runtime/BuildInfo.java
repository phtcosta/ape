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
package com.android.commands.monkey.ape.runtime;

/**
 * Which jar is running, answered by the jar itself.
 *
 * <p>This file is a template: Maven filters it out of {@code src/main/java-templates} into
 * {@code target/generated-sources/java-templates} at {@code generate-sources}, substituting the
 * two properties that {@code git-commit-id-maven-plugin} resolved from {@code .git} one phase
 * earlier. Editing the generated copy has no effect — edit this one.
 *
 * <p>The values are compiled constants rather than a packaged resource because {@code d8} dexes
 * only {@code .class} entries: a properties file bundled into the intermediate jar would be absent
 * from {@code ape-rv.jar} and would read back {@code null} on device.
 *
 * <p>A tree built without a {@code .git} directory still builds, and both constants read
 * {@code unknown}. That is a legitimate value, not a failure — but a run reporting it cannot be
 * traced back to a revision.
 *
 * <p>Nothing here is logged on its own. The single emitter of this fact is the {@code RUN_START}
 * trace line, which carries these constants as its {@code build} field.
 *
 * <p>This exists because of a real campaign: a stale jar once shipped, its MOP boost fired zero
 * times across 147,153 evaluations, and the mismatch surfaced only in post-hoc analysis of a
 * 2,028-task run. The stamp identifies a jar that is <em>wrong</em>; it says nothing about one that
 * is merely worse.
 */
public final class BuildInfo {

    /** Abbreviated commit of the tree this jar was built from, or {@code unknown}. */
    public static final String GIT_SHA = "${git.commit.id.abbrev}";

    /** Build time in UTC as {@code yyyy-MM-dd'T'HH:mm:ss'Z'}, or {@code unknown}. */
    public static final String JAR_BUILT = "${git.build.time}";

    private BuildInfo() {
    }
}

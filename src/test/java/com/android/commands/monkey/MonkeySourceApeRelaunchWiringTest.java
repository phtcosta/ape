package com.android.commands.monkey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The call sites of exploration INV-EXPL-33: both relaunch paths of {@code MonkeySourceApe}
 * ({@code randomlyPickMainApp} and the null-tree {@code restartPackage}) read {@code launchers()},
 * which applies {@code RelaunchTargets} and logs a list other than the startup one;
 * {@code getMainApp} keeps the startup launcher; {@code Monkey} resolves the startup list through
 * the shared {@code LauncherQuery.query} and passes the same query to {@code MonkeySourceApe}.
 *
 * <p>Asserted over the source because {@code MonkeySourceApe} cannot be class-loaded off-device
 * (its {@code UiAutomation} field pulls in classes absent from the JVM test classpath), and
 * {@code Monkey} needs the Android services. The decision itself is tested in
 * {@code RelaunchTargetsTest}.
 */
public class MonkeySourceApeRelaunchWiringTest {

    private static final String SOURCE_APE =
            "src/main/java/com/android/commands/monkey/MonkeySourceApe.java";
    private static final String SOURCE_MONKEY =
            "src/main/java/com/android/commands/monkey/Monkey.java";

    @Test
    public void bothRelaunchPathsReadTheLaunchers() throws IOException {
        String source = stripped(SOURCE_APE);
        String pick = body(source, "public ComponentName randomlyPickMainApp()");
        assertTrue(pick, pick.contains("launchers()"));
        assertTrue("one draw over the resolved list: " + pick, pick.contains("mRandom.nextInt(launchers.size())"));
        assertFalse(pick, pick.contains("mMainApps"));
        String events = body(source, "protected void generateEvents()");
        assertTrue(events.contains("restartPackage(launchers().get(0), false, \"null tree\");"));
    }

    @Test
    public void getMainAppKeepsTheStartupLauncher() throws IOException {
        String main = body(stripped(SOURCE_APE), "public ComponentName getMainApp()");
        assertTrue(main, main.contains("return mMainApps.get(0);"));
    }

    @Test
    public void launchersLogsADifferentList() throws IOException {
        String launchers = body(stripped(SOURCE_APE), "private List<ComponentName> launchers()");
        assertTrue(launchers, launchers.contains(
                "RelaunchTargets.of(RunContext.current().spec(), mMainApps, mLauncherQuery)"));
        assertTrue(launchers, launchers.contains("Logger.iformat(\"[APE-RV] relaunch launcher %s\""));
    }

    @Test
    public void monkeyUsesTheSharedQuery() throws IOException {
        String source = stripped(SOURCE_MONKEY);
        String startup = body(source, "private boolean getMainApps()");
        assertTrue(startup, startup.contains("LauncherQuery.query(mPm, mMainCategories)"));
        assertFalse("the query loop lives only in LauncherQuery", source.contains("queryIntentActivities"));
        assertFalse(source.contains("mUids"));
        assertTrue("MonkeySourceApe receives the same query for its relaunches",
                source.replaceAll("\\s+", " ").contains(
                        "new MonkeySourceApe(mRandom, mMainApps, () -> LauncherQuery.query(mPm, mMainCategories),"));
    }

    /** The source, comments removed. */
    private static String stripped(String path) throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    /** The body of the method declared by {@code signature}, brace-matched. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(signature + " not found -- it was renamed or removed", start >= 0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }
}

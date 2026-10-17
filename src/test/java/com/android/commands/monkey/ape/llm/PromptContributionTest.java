package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ActionType;
import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.model.State;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.runtime.MopScoring;
import com.android.commands.monkey.ape.runtime.TestRunSpecs;
import com.android.commands.monkey.ape.telemetry.NoopSink;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.MopContext;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.StaticArtifactLoader;
import com.android.commands.monkey.ape.utils.MopRetirement;
import com.android.commands.monkey.ape.utils.StaticGuiModel;
import com.android.commands.monkey.ape.utils.TestMopContexts;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * llm-prompt "Prompt Built From Group Contributions" (isolate-guidance-groups, tasks 9.3): the
 * prompt is the base prompt plus what the mounted contributions add, and the builder never asks
 * whether a group's data is present.
 *
 * <p>The fixture screen is the one of {@link ApePromptBuilderMopMarkerTest}'s cases put together:
 * BACK and MENU, a direct and a transitive button, a label flagged through its container, an
 * EditText whose click and long-click collapse into one {@code v13}/{@code v17} line, a Spinner and
 * an icon carrying only static metadata, and an unresolved action; plus a {@code distance} screen
 * with a retired target, and an empty one. The golden
 * {@code src/test/resources/prompts/mop-cell.golden.txt} is the prompt the builder wrote for these
 * cases before the contributions existed, with the MOP context as a {@code build} argument.
 */
public class PromptContributionTest {

    private static final String ACT = "com.example.MainActivity";

    private static final String[] VARIANTS = {
            ApePromptBuilder.VARIANT_APE_CURRENT, ApePromptBuilder.VARIANT_APE_REASONING,
            ApePromptBuilder.VARIANT_COMPACT_V1, ApePromptBuilder.VARIANT_V13,
            ApePromptBuilder.VARIANT_V17, ApePromptBuilder.VARIANT_VISUAL_ONLY};

    @Before
    public void installThePlan() {
        TestRunSpecs.install();
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private int nextTop = 100;

    private GUITreeNode node(GUITreeNode parent, String className, String text, String shortId) {
        GUITreeNode n = new GUITreeNode(parent);
        n.setClassName(className);
        if (text != null) n.setText(text);
        if (shortId != null) n.setResourceID("com.example:id/" + shortId);
        n.setBoundsInScreen(new Rect(0, nextTop, 1080, nextTop + 100));
        nextTop += 120;
        if (parent != null) parent.addChild(n);
        return n;
    }

    private static ModelAction act(ActionType type, GUITreeNode node) {
        return PromptFixtures.action(new PromptFixtures.TestName("//" + node.getText()), type, node);
    }

    private static StaticArtifactLoader.Loaded artifact(String widgetsOfAct) throws Exception {
        String json = "{\"formatVersion\":2,\"targets\":30,"
                + "\"package\":\"com.example\",\"mainActivity\":\"" + ACT + "\","
                + "\"widgets\":{\"" + ACT + "\":{" + widgetsOfAct + "}},"
                + "\"mopActivities\":[\"" + ACT + "\"],\"handlers\":{}}";
        File f = File.createTempFile("contribution", ".json");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write(json);
        }
        StaticArtifactLoader.Loaded loaded =
                StaticArtifactLoader.load(f.getAbsolutePath(), null, null, new NoopSink());
        assertNotNull("synthetic artifact must load", loaded);
        return loaded;
    }

    /** One screen to prompt about, and the run data the contributions bind. */
    private static final class Case {
        final String name;
        final MopContext mop;
        final State state;
        final List<ModelAction> actions;
        final List<ApePromptBuilder.ActionHistoryEntry> recent;

        Case(String name, MopContext mop, State state, List<ModelAction> actions,
                List<ApePromptBuilder.ActionHistoryEntry> recent) {
            this.name = name;
            this.mop = mop;
            this.state = state;
            this.actions = actions;
            this.recent = recent;
        }
    }

    /** The MOP cases of the golden, in its order. */
    private List<Case> mopCases() throws Exception {
        List<Case> cases = new ArrayList<>();
        List<ModelAction> actions = screenActions();
        MopContext flag = TestMopContexts.defaults(screenData);
        cases.add(new Case("flag-new", flag, ApePromptBuilderMopMarkerTest.state(0, actions),
                actions, history()));
        cases.add(new Case("flag-revisited", flag, ApePromptBuilderMopMarkerTest.state(3, actions),
                actions, null));

        StaticArtifactLoader.Loaded loaded = artifact(
                "\"w_near\":{\"mop\":{\"click\":\"transitive\"},\"dist\":{\"click\":[[4,0]]}},"
                + "\"w_one\":{\"mop\":{\"click\":\"transitive\"},\"dist\":{\"click\":[[2,1]]}},"
                + "\"w_ret\":{\"mop\":{\"click\":\"transitive\"},\"dist\":{\"click\":[[5,0]]},"
                + "\"inputType\":\"number\"},"
                + "\"w_far\":{\"mop\":{\"click\":\"transitive\"},\"dist\":{\"click\":[[7,5]]}}");
        MopData data = loaded.mopData;
        MopRetirement retirement = new MopRetirement(data.targetCount(), 3);
        MopContext distance = new MopContext(data, loaded.staticModel, MopScoring.DISTANCE, 500, 300, 500, 400, 300,
                3, 6, retirement);
        GUITreeNode retired = node(null, "android.widget.Button", "Ret", "w_ret");
        List<ModelAction> distanceActions = PromptFixtures.list(
                act(ActionType.MODEL_CLICK, node(null, "android.widget.Button", "Near", "w_near")),
                act(ActionType.MODEL_CLICK, node(null, "android.widget.Button", "One", "w_one")),
                act(ActionType.MODEL_CLICK, retired),
                act(ActionType.MODEL_CLICK, node(null, "android.widget.Button", "Far", "w_far")));
        for (int k = 0; k < 3; k++) {
            retirement.onFirstInteraction(data.lookup(ACT, retired, "click"));
        }
        cases.add(new Case("distance-revisited", distance,
                ApePromptBuilderMopMarkerTest.state(2, distanceActions), distanceActions, history()));

        List<ModelAction> none = new ArrayList<>();
        cases.add(new Case("empty", flag, ApePromptBuilderMopMarkerTest.state(0, none), none, null));
        return cases;
    }

    /** The artifact of {@link #screenActions}'s screen, set by it. */
    private StaticArtifactLoader.Loaded screenData;

    private List<ModelAction> screenActions() throws Exception {
        screenData = artifact(
                "\"btn_encrypt\":{\"mop\":{\"click\":\"direct\"},\"hint\":\"tap me\"},"
                + "\"btn_settings\":{\"mop\":{\"click\":\"transitive\"}},"
                + "\"item_row\":{\"mop\":{\"click\":\"direct\"}},"
                + "\"edit_name\":{\"mop\":{\"click\":\"transitive\",\"longClick\":\"direct\"},"
                + "\"inputType\":\"textEmailAddress\",\"hint\":\"Your \\\"name\\\"\","
                + "\"entries\":[\"a\",\"b\"]},"
                + "\"spin\":{\"prompt\":\"Pick one\",\"spinnerMode\":\"dropdown\","
                + "\"entries\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"11\"],"
                + "\"tooltipText\":\"tip\",\"contentDescription\":\"cd\"}");
        GUITreeNode encrypt = node(null, "android.widget.Button", "Encrypt", "btn_encrypt");
        GUITreeNode settings = node(null, "android.widget.Button", "Settings", "btn_settings");
        GUITreeNode row = node(null, "android.widget.LinearLayout", null, "item_row");
        GUITreeNode label = node(row, "android.widget.TextView", "Row label", "row_label");
        GUITreeNode edit = node(null, "android.widget.EditText", "Name", "edit_name");
        GUITreeNode spin = node(null, "android.widget.Spinner", null, "spin");
        GUITreeNode icon = node(null, "android.widget.ImageView", null, "plain_icon");
        Name editTarget = new PromptFixtures.TestName("//edit_name");
        return PromptFixtures.list(
                new ModelAction(null, ActionType.MODEL_BACK),
                new ModelAction(null, ActionType.MODEL_MENU),
                act(ActionType.MODEL_CLICK, encrypt),
                PromptFixtures.visited(act(ActionType.MODEL_CLICK, settings), 2),
                act(ActionType.MODEL_CLICK, label),
                PromptFixtures.action(editTarget, ActionType.MODEL_CLICK, edit),
                PromptFixtures.visited(
                        PromptFixtures.action(editTarget, ActionType.MODEL_LONG_CLICK, edit), 6),
                act(ActionType.MODEL_CLICK, spin),
                act(ActionType.MODEL_CLICK, icon),
                PromptFixtures.unresolvedClick());
    }

    private static List<ApePromptBuilder.ActionHistoryEntry> history() {
        return Arrays.asList(
                new ApePromptBuilder.ActionHistoryEntry("click", "Button", "Encrypt",
                        new Rect(0, 100, 1080, 200), null, "same"),
                new ApePromptBuilder.ActionHistoryEntry("back", null, null, null, null,
                        "previous screen"));
    }

    // -------------------------------------------------------------------------
    // Mounting, as RunContext mounts the groups: STATIC before MOP
    // -------------------------------------------------------------------------

    private static StaticPromptContribution staticContribution(StaticGuiModel model) {
        StaticPromptContribution contribution = new StaticPromptContribution();
        contribution.bind(model);
        return contribution;
    }

    private static MopPromptContribution mopContribution(MopContext mop) {
        MopPromptContribution contribution = new MopPromptContribution();
        contribution.bind(mop);
        return contribution;
    }

    private static List<SglangClient.Message> build(String variant, Case c,
            PromptContribution... contributions) {
        return new ApePromptBuilder(variant, Arrays.asList(contributions))
                .build(PromptFixtures.W, PromptFixtures.H, c.state, c.actions, "data", c.recent);
    }

    private static String system(List<SglangClient.Message> messages) {
        return messages.get(0).getTextContent();
    }

    private static String user(List<SglangClient.Message> messages) {
        for (SglangClient.ContentPart part : messages.get(1).getContentParts()) {
            if ("text".equals(part.getType())) return part.getText();
        }
        return "";
    }

    /** {@code [DM]}, {@code [M]}, {@code MOP} or {@code monitored} in {@code text}, or null. */
    private static String mopToken(String text) {
        for (String token : new String[] {"[DM]", "[M]", "MOP", "monitored"}) {
            if (text.contains(token)) return token;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // 9.3.1 — no contribution, no MOP token
    // -------------------------------------------------------------------------

    /** INV-PRM-07: the base prompt names no MOP, in any variant, system or user text. */
    @Test
    public void noContributionWritesNoMopToken() throws Exception {
        for (Case c : mopCases()) {
            for (String variant : VARIANTS) {
                List<SglangClient.Message> messages = build(variant, c);
                assertNull(c.name + "/" + variant + " system:\n" + system(messages),
                        mopToken(system(messages)));
                assertNull(c.name + "/" + variant + " user:\n" + user(messages),
                        mopToken(user(messages)));
            }
        }
    }

    // -------------------------------------------------------------------------
    // 9.3.2 — with MOP, today's prompt
    // -------------------------------------------------------------------------

    /**
     * With STATIC and MOP mounted, every variant's prompt equals, byte for byte, the one the
     * builder wrote before the contributions (the golden), the {@code v13} and {@code v17} prompts
     * included.
     */
    @Test
    public void withMopThePromptIsTodaysPrompt() throws Exception {
        StringBuilder rendered = new StringBuilder();
        for (Case c : mopCases()) {
            for (String variant : VARIANTS) {
                List<SglangClient.Message> messages = build(variant, c,
                        staticContribution(c.mop.staticModel()), mopContribution(c.mop));
                rendered.append("=== ").append(c.name).append(" / ").append(variant)
                        .append(" / system\n").append(system(messages)).append('\n');
                rendered.append("=== ").append(c.name).append(" / ").append(variant)
                        .append(" / user\n").append(user(messages)).append('\n');
            }
        }
        assertEquals(golden("prompts/mop-cell.golden.txt"), rendered.toString());
    }

    private static String golden(String resource) throws Exception {
        try (InputStream in = PromptContributionTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(resource, in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // -------------------------------------------------------------------------
    // 9.3.3 — STATIC alone: metadata, no marker
    // -------------------------------------------------------------------------

    /** Scenario "as_llm prompt carries no MOP text", in v13 and v17. */
    @Test
    public void staticAloneCarriesMetadataAndNoMarker() throws Exception {
        Case c = mopCases().get(0);
        for (String variant : new String[] {ApePromptBuilder.VARIANT_V13,
                ApePromptBuilder.VARIANT_V17}) {
            List<SglangClient.Message> messages = build(variant, c,
                    staticContribution(c.mop.staticModel()));
            String user = user(messages);
            assertTrue(variant + ":\n" + user, PromptFixtures.lineWith(user, "\"Name\"")
                    .endsWith(" entries=[a, b] inputType=\"textEmailAddress\" hint=\"Your \\\"name\\\"\""));
            assertTrue(variant + ":\n" + user, PromptFixtures.lineWith(user, "id=spin")
                    .contains(" prompt=\"Pick one\" spinnerMode=\"dropdown\""));
            assertNull(variant + " system", mopToken(system(messages)));
            assertNull(variant + " user:\n" + user, mopToken(user));
        }
    }

    /** Scenario "metadata comes from STATIC, not MOP": MOP alone marks and writes no metadata. */
    @Test
    public void mopAloneMarksWithoutMetadata() throws Exception {
        Case c = mopCases().get(0);
        String user = user(build(ApePromptBuilder.VARIANT_V13, c, mopContribution(c.mop)));
        assertEquals("  EditText \"Name\" @(500,351) [DM]", PromptFixtures.lineWith(user, "\"Name\""));
        assertEquals("  Spinner \"id=spin\" @(500,418)", PromptFixtures.lineWith(user, "id=spin"));
    }

    // -------------------------------------------------------------------------
    // 9.3.4 — v17 without MOP keeps the visit context
    // -------------------------------------------------------------------------

    /** Scenario "v17 without MOP keeps the visit context under NAVIGATION" (verification A8). */
    @Test
    public void v17WithoutMopKeepsTheVisitContext() throws Exception {
        List<Case> cases = mopCases();
        Case fresh = cases.get(0);
        Case revisited = cases.get(1);
        for (PromptContribution[] mounted : new PromptContribution[][] {
                {}, {staticContribution(fresh.mop.staticModel())}}) {
            String newUser = user(build(ApePromptBuilder.VARIANT_V17, fresh, mounted));
            assertTrue(newUser, newUser.contains("\nNAVIGATION:\nNEW state.\n"));
            assertTrue(newUser, newUser.contains(
                    "\nSCREEN: MainActivity | 2/7 elements tested | visits: 0\n"));
            assertTrue(newUser, newUser.contains("  Button \"Settings\" @(500,150) [TESTED-2x]\n"));
            assertTrue(newUser, newUser.endsWith(
                    "\nSelect action. Prioritize navigation to new screens."));
            assertNull(newUser, mopToken(newUser));

            String revisitedUser = user(build(ApePromptBuilder.VARIANT_V17, revisited, mounted));
            assertTrue(revisitedUser, revisitedUser.contains("\nNAVIGATION:\nVisited 3x.\n"));
            assertTrue(revisitedUser, revisitedUser.contains("elements tested | visits: 3\n"));
        }
    }

    /** The visit context of the other variants that show one stays in every cell, too. */
    @Test
    public void visitContextWithoutMopInTheOtherVariants() throws Exception {
        Case revisited = mopCases().get(1);
        for (String variant : new String[] {ApePromptBuilder.VARIANT_APE_CURRENT,
                ApePromptBuilder.VARIANT_VISUAL_ONLY}) {
            String user = user(build(variant, revisited));
            assertEquals(variant + ":\n" + user, "Visited 3x.",
                    PromptFixtures.lineWith(user, "Visited"));
        }
    }

    // -------------------------------------------------------------------------
    // MOP-free system wording (llm-prompt table)
    // -------------------------------------------------------------------------

    /** Scenario "v17 system message without MOP": five steps, renumbered; two PRIORITY lines. */
    @Test
    public void v17SystemWithoutMop() throws Exception {
        String system = system(build(ApePromptBuilder.VARIANT_V17, mopCases().get(0)));
        assertTrue(system, system.contains("REASONING STEPS:\n"
                + "1. SCREEN: Identify screen type (dialog, form, list, menu).\n"
                + "2. DIALOG: If blocking dialog present, handle it first.\n"
                + "3. NAVIGATION: Check for actions leading to unvisited screens.\n"
                + "4. ELEMENTS: Select unvisited element if no navigation target available.\n"
                + "5. ACTION: Call the action with normalized coordinates [0,1000).\n\n"));
        assertTrue(system, system.contains("PRIORITY:\n"
                + "- Actions leading to NEW screens > same-screen actions\n"
                + "- Unvisited (v:0) > visited\n\n"));
    }

    /** The table's MOP-free wording of the other variants. */
    @Test
    public void mopFreeWordingOfTheOtherVariants() throws Exception {
        Case c = mopCases().get(0);
        assertEquals("PRIORITY: unvisited (v:0) > visited.", PromptFixtures.lineWith(
                system(build(ApePromptBuilder.VARIANT_APE_CURRENT, c)), "PRIORITY"));
        assertEquals("PRIORITY: unvisited (v:0) > visited.", PromptFixtures.lineWith(
                system(build(ApePromptBuilder.VARIANT_APE_REASONING, c)), "PRIORITY"));
        assertEquals("3. Prioritize unvisited (v:0) > visited.", PromptFixtures.lineWith(
                system(build(ApePromptBuilder.VARIANT_COMPACT_V1, c)), "Prioritize"));
        assertEquals("PRIORITY: navigation to new screens.", PromptFixtures.lineWith(
                system(build(ApePromptBuilder.VARIANT_V13, c)), "PRIORITY"));
        assertTrue(user(build(ApePromptBuilder.VARIANT_V13, c))
                .endsWith("\nChoose ONE action to explore new UI states."));
    }

    // -------------------------------------------------------------------------
    // The contributions
    // -------------------------------------------------------------------------

    /** The MOP contribution has text only at the documented points; visual_only has none. */
    @Test
    public void mopContributionPoints() {
        MopPromptContribution mop = new MopPromptContribution();
        assertEquals("", mop.systemSentence(ApePromptBuilder.VARIANT_VISUAL_ONLY));
        assertEquals("", mop.systemSentence("v17"));
        assertEquals("[DM]/[M] elements > ", mop.systemSentence(ApePromptBuilder.VARIANT_V13));
        assertEquals(" or MOP", mop.systemSentence(ApePromptBuilder.POINT_V17_ELEMENTS));
        assertEquals("MOP NAVIGATION:", mop.section(null, null));
    }

    /**
     * An unbound contribution weighs nothing: no marker, a zero count. RunContext mounts it before
     * the agent binds its data.
     */
    @Test
    public void unboundContributionsAddNoData() throws Exception {
        Case c = mopCases().get(0);
        String user = user(build(ApePromptBuilder.VARIANT_V13, c,
                new StaticPromptContribution(), new MopPromptContribution()));
        assertEquals("  EditText \"Name\" @(500,351)", PromptFixtures.lineWith(user, "\"Name\""));
        assertEquals(" 0/8 MOP.", new MopPromptContribution().contextLine(c.state, c.actions));
    }

    /** The binding seam: the engine binds the agent's data into the mounted contributions. */
    @Test
    public void engineBindsTheMountedContributions() throws Exception {
        Case c = mopCases().get(0);
        ApePromptBuilder builder = new ApePromptBuilder(ApePromptBuilder.VARIANT_V13,
                Arrays.<PromptContribution>asList(new StaticPromptContribution(),
                        new MopPromptContribution()));
        LlmEngine engine = new LlmEngine(null, builder, null, null, null, null);
        engine.bindStaticModel(c.mop.staticModel());
        engine.bindMopContext(c.mop);
        String user = user(builder.build(PromptFixtures.W, PromptFixtures.H, c.state, c.actions,
                "data", null));
        assertEquals("  EditText \"Name\" @(500,351) [DM] entries=[a, b] "
                + "inputType=\"textEmailAddress\" hint=\"Your \\\"name\\\"\"",
                PromptFixtures.lineWith(user, "\"Name\""));
    }

    /** With no contribution mounted the binding calls change nothing. */
    @Test
    public void bindingWithoutMountedContributionsIsInert() throws Exception {
        Case c = mopCases().get(0);
        ApePromptBuilder builder = new ApePromptBuilder(ApePromptBuilder.VARIANT_V13,
                Collections.<PromptContribution>emptyList());
        LlmEngine engine = new LlmEngine(null, builder, null, null, null, null);
        engine.bindStaticModel(c.mop.staticModel());
        engine.bindMopContext(c.mop);
        String user = user(builder.build(PromptFixtures.W, PromptFixtures.H, c.state, c.actions,
                "data", null));
        assertNull(user, mopToken(user));
        assertEquals("  EditText \"Name\" @(500,351)", PromptFixtures.lineWith(user, "\"Name\""));
    }
}

package com.android.commands.monkey.ape.llm;

import android.graphics.Rect;

import com.android.commands.monkey.ape.model.ModelAction;
import com.android.commands.monkey.ape.utils.MopData;
import com.android.commands.monkey.ape.utils.StaticGuiModel;

import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for ApePromptBuilder.
 *
 * <p>ApePromptBuilder.build() depends on Android types (GUITree, State, ModelAction, Rect)
 * and MopData (which uses android.util.JsonReader). The android.graphics.Rect class is
 * available from the system-scope framework JAR on the test classpath and its constructor
 * works in the JVM. Android classes that require native libraries (Bitmap, BitmapFactory,
 * JsonReader) are NOT callable in pure JVM tests.
 *
 * <p>What we test here:
 * <ul>
 *   <li>ActionHistoryEntry construction and all field accessors (pure Java inner class).</li>
 *   <li>ActionHistoryEntry null-safety: null arguments are replaced by empty strings.</li>
 *   <li>build() with all-null / all-empty arguments — exercises the null-guarded paths and
 *       verifies the returned list shape (exactly 2 messages: system + user).</li>
 *   <li>System message content with and without type_text tool.</li>
 *   <li>Message structure: correct roles, image-first / text-second ordering in user message.</li>
 *   <li>base64Image null vs. non-null: data URI prefix is always emitted in the image part.</li>
 * </ul>
 */
public class ApePromptBuilderTest {

    // -------------------------------------------------------------------------
    // ActionHistoryEntry — pure-Java inner class
    // -------------------------------------------------------------------------

    @Test
    public void actionHistoryEntry_fieldsStoredCorrectly() {
        ApePromptBuilder.ActionHistoryEntry entry =
                new ApePromptBuilder.ActionHistoryEntry("click", "Button", "OK",
                        new Rect(0, 105, 1080, 252), null, "ok");

        assertEquals("click",  entry.actionType);
        assertEquals("Button", entry.widgetClass);
        assertEquals("OK",     entry.widgetText);
        assertEquals(0,        entry.bounds.left);
        assertEquals(105,      entry.bounds.top);
        assertEquals(1080,     entry.bounds.right);
        assertEquals(252,      entry.bounds.bottom);
        assertEquals("",       entry.typedText);   // null → ""
        assertEquals("ok",     entry.result);
    }

    @Test
    public void actionHistoryEntry_nullsReplaceWithEmptyString() {
        ApePromptBuilder.ActionHistoryEntry entry =
                new ApePromptBuilder.ActionHistoryEntry(null, null, null, null, null, null);

        assertEquals("", entry.actionType);
        assertEquals("", entry.widgetClass);
        assertEquals("", entry.widgetText);
        assertEquals("", entry.typedText);
        assertEquals("", entry.result);
        assertNull(entry.bounds);
    }

    @Test
    public void actionHistoryEntry_typeTextWithText() {
        ApePromptBuilder.ActionHistoryEntry entry =
                new ApePromptBuilder.ActionHistoryEntry(
                        "type_text", "EditText", "", new Rect(0, 800, 1080, 948), "hello@example.com", "ok");

        assertEquals("type_text",         entry.actionType);
        assertEquals("EditText",          entry.widgetClass);
        assertEquals("hello@example.com", entry.typedText);
        assertEquals(800,                 entry.bounds.top);
        assertEquals(948,                 entry.bounds.bottom);
    }

    @Test
    public void actionHistoryEntry_withResultCopiesEverythingElse() {
        ApePromptBuilder.ActionHistoryEntry entry = new ApePromptBuilder.ActionHistoryEntry(
                "click", "Button", "OK", new Rect(0, 105, 1080, 252), null, "");
        ApePromptBuilder.ActionHistoryEntry filled = entry.withResult("same");

        assertEquals("", entry.result);
        assertEquals("same", filled.result);
        assertEquals("click", filled.actionType);
        assertEquals("Button", filled.widgetClass);
        assertEquals("OK", filled.widgetText);
        assertNotSame(entry.bounds, filled.bounds);
        assertEquals(105, filled.bounds.top);
        assertEquals(252, filled.bounds.bottom);
    }

    /** The entry owns its bounds: a later change to the caller's Rect does not reach it. */
    @Test
    public void actionHistoryEntry_copiesItsBounds() {
        Rect bounds = new Rect(0, 105, 1080, 252);
        ApePromptBuilder.ActionHistoryEntry entry =
                new ApePromptBuilder.ActionHistoryEntry("click", "Button", "OK", bounds, null, "");
        bounds.left = 1;
        bounds.top = 2;

        assertNotSame(bounds, entry.bounds);
        assertEquals(0, entry.bounds.left);
        assertEquals(105, entry.bounds.top);
        assertEquals(1080, entry.bounds.right);
        assertEquals(252, entry.bounds.bottom);
    }

    @Test
    public void actionHistoryEntry_backAction() {
        ApePromptBuilder.ActionHistoryEntry entry =
                new ApePromptBuilder.ActionHistoryEntry("back", "", "", null, null, "ok");

        assertEquals("back", entry.actionType);
        assertNull(entry.bounds);
    }

    // -------------------------------------------------------------------------
    // build() with all-null arguments — validates null-safe code paths
    // -------------------------------------------------------------------------

    @Test
    public void build_withAllNulls_returnsExactlyTwoMessages() {
        ApePromptBuilder builder = new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT);
        List<SglangClient.Message> messages =
                builder.build(1080, 1920, null, null, null, null, null);

        assertNotNull("messages must not be null", messages);
        assertEquals("must produce exactly 2 messages", 2, messages.size());
    }

    @Test
    public void build_withAllNulls_firstMessageIsSystem() {
        ApePromptBuilder builder = new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT);
        List<SglangClient.Message> messages =
                builder.build(1080, 1920, null, null, null, null, null);

        SglangClient.Message systemMsg = messages.get(0);
        assertEquals("system", systemMsg.getRole());
        assertNotNull(systemMsg.getTextContent());
        assertFalse("system message must not be empty",
                systemMsg.getTextContent().isEmpty());
    }

    @Test
    public void build_withAllNulls_secondMessageIsUser() {
        ApePromptBuilder builder = new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT);
        List<SglangClient.Message> messages =
                builder.build(1080, 1920, null, null, null, null, null);

        SglangClient.Message userMsg = messages.get(1);
        assertEquals("user", userMsg.getRole());
        assertNotNull("user message must have content parts", userMsg.getContentParts());
        assertEquals("user message must have 2 content parts (image + text)", 2,
                userMsg.getContentParts().size());
    }

    @Test
    public void build_userMessage_imagePartFirst_textPartSecond() {
        ApePromptBuilder builder = new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT);
        List<SglangClient.Message> messages =
                builder.build(1080, 1920, null, null, null, null, null);

        List<SglangClient.ContentPart> parts = messages.get(1).getContentParts();
        assertEquals("image_url", parts.get(0).getType());
        assertEquals("text",      parts.get(1).getType());
    }

    @Test
    public void build_withNullBase64Image_imageUrlStillEmitsPrefix() {
        ApePromptBuilder builder = new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT);
        List<SglangClient.Message> messages =
                builder.build(1080, 1920, null, null, null, null, null);

        String imageUrl = messages.get(1).getContentParts().get(0).getImageUrl();
        assertNotNull(imageUrl);
        assertTrue("image URL must start with data URI prefix",
                imageUrl.startsWith("data:image/jpeg;base64,"));
    }

    @Test
    public void build_withBase64Image_imageUrlContainsData() {
        ApePromptBuilder builder = new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT);
        String fakeBase64 = "abc123";
        List<SglangClient.Message> messages =
                builder.build(1080, 1920, null, null, null, fakeBase64, null);

        String imageUrl = messages.get(1).getContentParts().get(0).getImageUrl();
        assertTrue("image URL must embed the base64 data",
                imageUrl.contains(fakeBase64));
    }

    // -------------------------------------------------------------------------
    // System message content
    // -------------------------------------------------------------------------

    @Test
    public void systemMessage_withoutTypeText_doesNotMentionTypeText() {
        // build() with null actions → hasInputField returns false → type_text omitted
        ApePromptBuilder builder = new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT);
        List<SglangClient.Message> messages =
                builder.build(1080, 1920, null, null, null, null, null);

        String systemText = messages.get(0).getTextContent();
        assertFalse("type_text should be absent when no input fields present",
                systemText.contains("type_text(x, y, text)"));
    }

    @Test
    public void systemMessage_alwaysContainsCoreTools() {
        ApePromptBuilder builder = new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT);
        List<SglangClient.Message> messages =
                builder.build(1080, 1920, null, null, null, null, null);

        String systemText = messages.get(0).getTextContent();
        assertTrue("system message must mention click",  systemText.contains("click(x, y)"));
        assertTrue("system message must mention back",   systemText.contains("back()"));
    }

    // -------------------------------------------------------------------------
    // llm-prompt "System Message of the v13 Variant" (design D14)
    // -------------------------------------------------------------------------

    private static final String V13_SYSTEM_HEAD =
            "You are an Android UI testing agent. Your task is to explore the app by interacting with UI elements.\n"
            + "DIALOG HANDLING: If you see a permission dialog, click Allow/Accept/OK.\n"
            + "  If you see an error or modal dialog, dismiss it before any other action.\n"
            + "PRIORITY: [DM]/[M] elements > navigation to new screens.\n"
            + "Available actions:\n"
            + "  click(x, y) \u2014 tap an element at normalized coordinates [0,1000)\n"
            + "  long_click(x, y) \u2014 long press at normalized coordinates\n";
    private static final String V13_SYSTEM_TYPE_TEXT =
            "  type_text(x, y, text) \u2014 type text into an input field\n";
    private static final String V13_SYSTEM_TAIL =
            "  back() \u2014 press the system back button\n"
            + "RULE: Do not click the same position twice in a row.\n"
            + "Respond with exactly one action as JSON: {\"name\": \"<action>\", \"arguments\": {<args>}}";

    /** Scenario "v13 PRIORITY line has no visit ranks". */
    @Test
    public void v13SystemMessage_priorityLineHasNoVisitRanks() {
        String system = PromptFixtures.systemText(ApePromptBuilder.VARIANT_V13, null);
        assertTrue(system, system.contains("PRIORITY: [DM]/[M] elements > navigation to new screens.\n"));
        assertFalse(system, system.contains("visited"));
    }

    /** Scenario "v13 system message offers type_text only with an input widget": the whole text. */
    @Test
    public void v13SystemMessage_wholeTextWithoutInputWidget() {
        List<ModelAction> actions = PromptFixtures.list(
                PromptFixtures.click("android.widget.Button", "OK", new Rect(0, 105, 1080, 252)));
        assertEquals(V13_SYSTEM_HEAD + V13_SYSTEM_TAIL,
                PromptFixtures.systemText(ApePromptBuilder.VARIANT_V13, actions));
    }

    @Test
    public void v13SystemMessage_wholeTextWithInputWidget() {
        List<ModelAction> actions = PromptFixtures.list(
                PromptFixtures.click("android.widget.EditText", "Email", new Rect(0, 480, 1080, 596)));
        assertEquals(V13_SYSTEM_HEAD + V13_SYSTEM_TYPE_TEXT + V13_SYSTEM_TAIL,
                PromptFixtures.systemText(ApePromptBuilder.VARIANT_V13, actions));
    }

    // -------------------------------------------------------------------------
    // Recent action history
    // -------------------------------------------------------------------------

    @Test
    public void build_withRecentActions_includesRecentSectionInUserText() {
        ApePromptBuilder builder = new ApePromptBuilder(ApePromptBuilder.VARIANT_APE_CURRENT);
        ApePromptBuilder.ActionHistoryEntry histEntry =
                new ApePromptBuilder.ActionHistoryEntry("click", "Button", "Login",
                        new Rect(0, 105, 1080, 252), null, "ok");

        List<SglangClient.Message> messages =
                builder.build(1080, 1920, null, null, null, null,
                        Collections.singletonList(histEntry));

        String userText = messages.get(1).getContentParts().get(1).getText();
        assertNotNull(userText);
        assertTrue("user text should contain 'Recent:' section",
                userText.contains("Recent:"));
    }

    // =========================================================================
    // gh13 §20 — widget metadata in LLM context (T1.1) via ApePromptBuilder.widgetMetadata
    // =========================================================================

    /** Static widget metadata with only the labels, the input fields and the entries given. */
    private static StaticGuiModel.WidgetMeta meta(String hint, String inputType,
            List<String> entries, String contentDescription, String tooltipText) {
        return new StaticGuiModel.WidgetMeta(null, hint, inputType, entries, null, null,
                contentDescription, tooltipText);
    }

    @Test // 20.1
    public void testWidgetMetadataAppearsInPrompt() {
        StaticGuiModel.WidgetMeta w = meta(null, null, null, "Encrypt button", "Tap to encrypt");
        String m = ApePromptBuilder.widgetMetadata(w);
        assertTrue(m.contains("contentDescription=\"Encrypt button\""));
        assertTrue(m.contains("tooltipText=\"Tap to encrypt\""));
        assertFalse(m.contains("prompt="));
        assertFalse(m.contains("spinnerMode="));
        assertFalse(m.contains("entries="));
        assertFalse(m.contains("inputType="));
    }

    @Test // 20.2
    public void testNullWidgetMetadataOmittedFromPrompt() {
        String m = ApePromptBuilder.widgetMetadata(meta(null, null, null, null, null));
        assertEquals("", m);
    }

    @Test // 20.3
    public void testWidgetMetadataTruncatedAt80Chars() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) big.append('x');
        StaticGuiModel.WidgetMeta w = meta(null, null, null, big.toString(), null);
        String m = ApePromptBuilder.widgetMetadata(w);
        assertTrue("ellipsis on overflow", m.contains("…\""));
        // value capped at 80 chars (between the opening quote and the ellipsis)
        int start = m.indexOf("contentDescription=\"") + "contentDescription=\"".length();
        int ell = m.indexOf("…", start);
        assertEquals(80, ell - start);
    }

    @Test // 20.4
    public void testSpinnerEntriesAppearInPromptCappedAt10() {
        StaticGuiModel.WidgetMeta w = meta(null, null,
                Arrays.asList("MD2", "MD5", "SHA-1", "SHA-256", "SHA-512"), null, null);
        assertTrue(ApePromptBuilder.widgetMetadata(w)
                .contains("entries=[MD2, MD5, SHA-1, SHA-256, SHA-512]"));

        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 15; i++) entries.add("e" + i);
        StaticGuiModel.WidgetMeta w15 = meta(null, null, entries, null, null);
        String m = ApePromptBuilder.widgetMetadata(w15);
        assertTrue("capped with trailing ellipsis", m.contains(", …]"));
        assertTrue(m.contains("e9"));
        assertFalse("11th entry not shown", m.contains("e10"));
    }

    @Test // 20.5
    public void testMetadataNewlinesFlattened() {
        StaticGuiModel.WidgetMeta w = meta(null, null, null, "line1\nline2", null);
        assertTrue(ApePromptBuilder.widgetMetadata(w).contains("line1 line2"));
    }

    @Test // 20.6
    public void testInputTypeAndHintAppearInPrompt() {
        StaticGuiModel.WidgetMeta w = meta("Your password", "textPassword", null, null, null);
        String m = ApePromptBuilder.widgetMetadata(w);
        assertTrue(m.contains("inputType=\"textPassword\""));
        assertTrue(m.contains("hint=\"Your password\""));
    }

    @Test // 20.7
    public void testSpecialCharsInMetadataDoNotBreakPrompt() {
        StaticGuiModel.WidgetMeta w = meta(null, null, null, "a\"b[c]d\\e", null);
        String m = ApePromptBuilder.widgetMetadata(w);
        assertTrue("embedded quote escaped", m.contains("\\\""));
        // field stays well-formed: opens and closes with a quote
        assertTrue(m.trim().startsWith("contentDescription=\""));
        assertTrue(m.endsWith("\""));
    }
}

package com.android.commands.monkey.ape.llm;

import com.android.commands.monkey.ape.utils.MopData;

import org.junit.Test;

import java.util.Arrays;
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
                new ApePromptBuilder.ActionHistoryEntry("click", "Button", "OK", 500, 750, null, "ok");

        assertEquals("click",  entry.actionType);
        assertEquals("Button", entry.widgetClass);
        assertEquals("OK",     entry.widgetText);
        assertEquals(500,      entry.normX);
        assertEquals(750,      entry.normY);
        assertEquals("",       entry.typedText);   // null → ""
        assertEquals("ok",     entry.result);
    }

    @Test
    public void actionHistoryEntry_nullsReplaceWithEmptyString() {
        ApePromptBuilder.ActionHistoryEntry entry =
                new ApePromptBuilder.ActionHistoryEntry(null, null, null, 0, 0, null, null);

        assertEquals("", entry.actionType);
        assertEquals("", entry.widgetClass);
        assertEquals("", entry.widgetText);
        assertEquals("", entry.typedText);
        assertEquals("", entry.result);
    }

    @Test
    public void actionHistoryEntry_typeTextWithText() {
        ApePromptBuilder.ActionHistoryEntry entry =
                new ApePromptBuilder.ActionHistoryEntry(
                        "type_text", "EditText", "", 300, 400, "hello@example.com", "ok");

        assertEquals("type_text",         entry.actionType);
        assertEquals("EditText",          entry.widgetClass);
        assertEquals("hello@example.com", entry.typedText);
        assertEquals(300,                 entry.normX);
        assertEquals(400,                 entry.normY);
    }

    @Test
    public void actionHistoryEntry_backAction() {
        ApePromptBuilder.ActionHistoryEntry entry =
                new ApePromptBuilder.ActionHistoryEntry("back", "", "", 0, 0, null, "ok");

        assertEquals("back", entry.actionType);
        assertEquals(0,      entry.normX);
        assertEquals(0,      entry.normY);
    }

    // -------------------------------------------------------------------------
    // build() with all-null arguments — validates null-safe code paths
    // -------------------------------------------------------------------------

    @Test
    public void build_withAllNulls_returnsExactlyTwoMessages() {
        ApePromptBuilder builder = new ApePromptBuilder();
        List<SglangClient.Message> messages =
                builder.build(null, null, null, null, null, null);

        assertNotNull("messages must not be null", messages);
        assertEquals("must produce exactly 2 messages", 2, messages.size());
    }

    @Test
    public void build_withAllNulls_firstMessageIsSystem() {
        ApePromptBuilder builder = new ApePromptBuilder();
        List<SglangClient.Message> messages =
                builder.build(null, null, null, null, null, null);

        SglangClient.Message systemMsg = messages.get(0);
        assertEquals("system", systemMsg.getRole());
        assertNotNull(systemMsg.getTextContent());
        assertFalse("system message must not be empty",
                systemMsg.getTextContent().isEmpty());
    }

    @Test
    public void build_withAllNulls_secondMessageIsUser() {
        ApePromptBuilder builder = new ApePromptBuilder();
        List<SglangClient.Message> messages =
                builder.build(null, null, null, null, null, null);

        SglangClient.Message userMsg = messages.get(1);
        assertEquals("user", userMsg.getRole());
        assertNotNull("user message must have content parts", userMsg.getContentParts());
        assertEquals("user message must have 2 content parts (image + text)", 2,
                userMsg.getContentParts().size());
    }

    @Test
    public void build_userMessage_imagePartFirst_textPartSecond() {
        ApePromptBuilder builder = new ApePromptBuilder();
        List<SglangClient.Message> messages =
                builder.build(null, null, null, null, null, null);

        List<SglangClient.ContentPart> parts = messages.get(1).getContentParts();
        assertEquals("image_url", parts.get(0).getType());
        assertEquals("text",      parts.get(1).getType());
    }

    @Test
    public void build_withNullBase64Image_imageUrlStillEmitsPrefix() {
        ApePromptBuilder builder = new ApePromptBuilder();
        List<SglangClient.Message> messages =
                builder.build(null, null, null, null, null, null);

        String imageUrl = messages.get(1).getContentParts().get(0).getImageUrl();
        assertNotNull(imageUrl);
        assertTrue("image URL must start with data URI prefix",
                imageUrl.startsWith("data:image/jpeg;base64,"));
    }

    @Test
    public void build_withBase64Image_imageUrlContainsData() {
        ApePromptBuilder builder = new ApePromptBuilder();
        String fakeBase64 = "abc123";
        List<SglangClient.Message> messages =
                builder.build(null, null, null, null, fakeBase64, null);

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
        ApePromptBuilder builder = new ApePromptBuilder();
        List<SglangClient.Message> messages =
                builder.build(null, null, null, null, null, null);

        String systemText = messages.get(0).getTextContent();
        assertFalse("type_text should be absent when no input fields present",
                systemText.contains("type_text(x, y, text)"));
    }

    @Test
    public void systemMessage_alwaysContainsCoreTools() {
        ApePromptBuilder builder = new ApePromptBuilder();
        List<SglangClient.Message> messages =
                builder.build(null, null, null, null, null, null);

        String systemText = messages.get(0).getTextContent();
        assertTrue("system message must mention click",  systemText.contains("click(x, y)"));
        assertTrue("system message must mention back",   systemText.contains("back()"));
    }

    // -------------------------------------------------------------------------
    // Recent action history
    // -------------------------------------------------------------------------

    @Test
    public void build_withRecentActions_includesRecentSectionInUserText() {
        ApePromptBuilder builder = new ApePromptBuilder();
        ApePromptBuilder.ActionHistoryEntry histEntry =
                new ApePromptBuilder.ActionHistoryEntry("click", "Button", "Login",
                        500, 600, null, "ok");

        List<SglangClient.Message> messages =
                builder.build(null, null, null, null, null,
                        Collections.singletonList(histEntry));

        String userText = messages.get(1).getContentParts().get(1).getText();
        assertNotNull(userText);
        assertTrue("user text should contain 'Recent:' section",
                userText.contains("Recent:"));
    }

    // =========================================================================
    // gh13 §20 — widget metadata in LLM context (T1.1) via ApePromptBuilder.widgetMetadata
    // =========================================================================

    @Test // 20.1
    public void testWidgetMetadataAppearsInPrompt() {
        MopData.Widget w = new MopData.Widget();
        w.contentDescription = "Encrypt button";
        w.tooltipText = "Tap to encrypt";
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
        String m = ApePromptBuilder.widgetMetadata(new MopData.Widget());
        assertEquals("", m);
    }

    @Test // 20.3
    public void testWidgetMetadataTruncatedAt80Chars() {
        MopData.Widget w = new MopData.Widget();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200; i++) big.append('x');
        w.contentDescription = big.toString();
        String m = ApePromptBuilder.widgetMetadata(w);
        assertTrue("ellipsis on overflow", m.contains("…\""));
        // value capped at 80 chars (between the opening quote and the ellipsis)
        int start = m.indexOf("contentDescription=\"") + "contentDescription=\"".length();
        int ell = m.indexOf("…", start);
        assertEquals(80, ell - start);
    }

    @Test // 20.4
    public void testSpinnerEntriesAppearInPromptCappedAt10() {
        MopData.Widget w = new MopData.Widget();
        w.entries.addAll(Arrays.asList("MD2", "MD5", "SHA-1", "SHA-256", "SHA-512"));
        assertTrue(ApePromptBuilder.widgetMetadata(w)
                .contains("entries=[MD2, MD5, SHA-1, SHA-256, SHA-512]"));

        MopData.Widget w15 = new MopData.Widget();
        for (int i = 0; i < 15; i++) w15.entries.add("e" + i);
        String m = ApePromptBuilder.widgetMetadata(w15);
        assertTrue("capped with trailing ellipsis", m.contains(", …]"));
        assertTrue(m.contains("e9"));
        assertFalse("11th entry not shown", m.contains("e10"));
    }

    @Test // 20.5
    public void testMetadataNewlinesFlattened() {
        MopData.Widget w = new MopData.Widget();
        w.contentDescription = "line1\nline2";
        assertTrue(ApePromptBuilder.widgetMetadata(w).contains("line1 line2"));
    }

    @Test // 20.6
    public void testInputTypeAndHintAppearInPrompt() {
        MopData.Widget w = new MopData.Widget();
        w.type = "android.widget.EditText";
        w.inputType = "textPassword";
        w.hint = "Your password";
        String m = ApePromptBuilder.widgetMetadata(w);
        assertTrue(m.contains("inputType=\"textPassword\""));
        assertTrue(m.contains("hint=\"Your password\""));
    }

    @Test // 20.7
    public void testSpecialCharsInMetadataDoNotBreakPrompt() {
        MopData.Widget w = new MopData.Widget();
        w.contentDescription = "a\"b[c]d\\e";
        String m = ApePromptBuilder.widgetMetadata(w);
        assertTrue("embedded quote escaped", m.contains("\\\""));
        // field stays well-formed: opens and closes with a quote
        assertTrue(m.trim().startsWith("contentDescription=\""));
        assertTrue(m.endsWith("\""));
    }
}

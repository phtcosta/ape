package com.android.commands.monkey.ape.llm;

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

    // -------------------------------------------------------------------------
    // Screen dimensions in system message
    // -------------------------------------------------------------------------

    @Test
    public void systemMessage_containsScreenDimensions() {
        // build() with null tree → defaults to 1080x1920
        ApePromptBuilder builder = new ApePromptBuilder();
        List<SglangClient.Message> messages =
                builder.build(null, null, null, null, null, null);

        String systemText = messages.get(0).getTextContent();
        assertTrue("system message must contain screen dimensions",
                systemText.contains("Screen: 1080x1920 pixels."));
    }
}

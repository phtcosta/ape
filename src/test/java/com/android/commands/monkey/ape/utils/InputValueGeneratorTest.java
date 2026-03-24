package com.android.commands.monkey.ape.utils;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.android.commands.monkey.ape.utils.InputValueGenerator.InputCategory;

import static org.junit.Assert.*;

/**
 * Unit tests for InputValueGenerator.
 *
 * Tests the string-based overloads (detectCategory and generate) which have
 * ZERO Android dependencies. The GUITreeNode-based methods are thin wrappers
 * that extract the same three fields (isPassword, resourceId, contentDesc).
 *
 * Covers: INV-INP-01 (never null), INV-INP-03 (case-insensitive), all
 * detection scenarios, rotation, and generic fallback.
 *
 * INV-INP-02 (heuristicInput=false uses StringCache) is an integration
 * concern tested at the ApeAgent level.
 */
public class InputValueGeneratorTest {

    private InputValueGenerator generator;

    @BeforeClass
    public static void setUpClass() {
        // Seed StringCache.stringList so that StringCache.nextString() works in
        // test context (no /sdcard/ape.strings file available on the JVM).
        // stringList is package-private, accessible from same package.
        if (StringCache.stringList.isEmpty()) {
            StringCache.stringList.add("fallback_string");
        }
    }

    @Before
    public void setUp() {
        generator = new InputValueGenerator();
    }

    // -----------------------------------------------------------------------
    // Category Detection: isPassword flag (highest priority)
    // -----------------------------------------------------------------------

    @Test
    public void testDetectCategory_passwordByIsPasswordFlag() {
        // INV-INP: isPassword() takes top priority, even if resourceId says "email"
        InputCategory cat = generator.detectCategory(true, "com.example:id/input_email", null);
        assertEquals(InputCategory.PASSWORD, cat);
    }

    @Test
    public void testDetectCategory_passwordByIsPasswordFlagAlone() {
        InputCategory cat = generator.detectCategory(true, null, null);
        assertEquals(InputCategory.PASSWORD, cat);
    }

    // -----------------------------------------------------------------------
    // Category Detection: resourceId keywords
    // -----------------------------------------------------------------------

    @Test
    public void testDetectCategory_emailByResourceId() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/input_email", null);
        assertEquals(InputCategory.EMAIL, cat);
    }

    @Test
    public void testDetectCategory_passwordByResourceId() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/user_password", null);
        assertEquals(InputCategory.PASSWORD, cat);
    }

    @Test
    public void testDetectCategory_passwordByPasswdKeyword() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/passwd_field", null);
        assertEquals(InputCategory.PASSWORD, cat);
    }

    @Test
    public void testDetectCategory_phoneByResourceId() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/phone_number", null);
        assertEquals(InputCategory.PHONE, cat);
    }

    @Test
    public void testDetectCategory_phoneByTelKeyword() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/tel_input", null);
        assertEquals(InputCategory.PHONE, cat);
    }

    @Test
    public void testDetectCategory_urlByResourceId() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/url_field", null);
        assertEquals(InputCategory.URL, cat);
    }

    @Test
    public void testDetectCategory_urlByWebsiteKeyword() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/website_input", null);
        assertEquals(InputCategory.URL, cat);
    }

    @Test
    public void testDetectCategory_urlByUriKeyword() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/uri_field", null);
        assertEquals(InputCategory.URL, cat);
    }

    @Test
    public void testDetectCategory_numberByResourceId() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/number_input", null);
        assertEquals(InputCategory.NUMBER, cat);
    }

    @Test
    public void testDetectCategory_numberByAmountKeyword() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/amount", null);
        assertEquals(InputCategory.NUMBER, cat);
    }

    @Test
    public void testDetectCategory_numberByQuantityKeyword() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/quantity", null);
        assertEquals(InputCategory.NUMBER, cat);
    }

    @Test
    public void testDetectCategory_numberByPriceKeyword() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/price_field", null);
        assertEquals(InputCategory.NUMBER, cat);
    }

    @Test
    public void testDetectCategory_numberByCountKeyword() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/item_count", null);
        assertEquals(InputCategory.NUMBER, cat);
    }

    @Test
    public void testDetectCategory_searchByResourceId() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/search_bar", null);
        assertEquals(InputCategory.SEARCH, cat);
    }

    // -----------------------------------------------------------------------
    // Category Detection: contentDesc keywords (fallback from resourceId)
    // -----------------------------------------------------------------------

    @Test
    public void testDetectCategory_urlByContentDesc() {
        // Scenario from spec: resourceId null, contentDesc "Enter website URL"
        InputCategory cat = generator.detectCategory(false, null, "Enter website URL");
        assertEquals(InputCategory.URL, cat);
    }

    @Test
    public void testDetectCategory_emailByContentDesc() {
        InputCategory cat = generator.detectCategory(false, null, "Enter your email address");
        assertEquals(InputCategory.EMAIL, cat);
    }

    @Test
    public void testDetectCategory_phoneByContentDesc() {
        InputCategory cat = generator.detectCategory(false, null, "Phone number");
        assertEquals(InputCategory.PHONE, cat);
    }

    @Test
    public void testDetectCategory_contentDescUsedOnlyWhenResourceIdHasNoMatch() {
        // resourceId has no keywords, contentDesc does
        InputCategory cat = generator.detectCategory(false, "com.example:id/generic_field", "Enter email");
        assertEquals(InputCategory.EMAIL, cat);
    }

    @Test
    public void testDetectCategory_resourceIdTakesPriorityOverContentDesc() {
        // resourceId matches phone, contentDesc matches email — resourceId wins
        InputCategory cat = generator.detectCategory(false, "com.example:id/phone_input", "Enter email");
        assertEquals(InputCategory.PHONE, cat);
    }

    // -----------------------------------------------------------------------
    // Category Detection: GENERIC fallback
    // -----------------------------------------------------------------------

    @Test
    public void testDetectCategory_genericWhenNoMatchAndBothNull() {
        InputCategory cat = generator.detectCategory(false, null, null);
        assertEquals(InputCategory.GENERIC, cat);
    }

    @Test
    public void testDetectCategory_genericWhenNoKeywordMatch() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/some_field", "Enter value");
        assertEquals(InputCategory.GENERIC, cat);
    }

    // -----------------------------------------------------------------------
    // INV-INP-03: Case-insensitive keyword matching
    // -----------------------------------------------------------------------

    @Test
    public void testDetectCategory_caseInsensitive_upperCase() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/INPUT_EMAIL", null);
        assertEquals(InputCategory.EMAIL, cat);
    }

    @Test
    public void testDetectCategory_caseInsensitive_mixedCase() {
        InputCategory cat = generator.detectCategory(false, "com.example:id/UserPassword", null);
        assertEquals(InputCategory.PASSWORD, cat);
    }

    @Test
    public void testDetectCategory_caseInsensitive_contentDesc() {
        InputCategory cat = generator.detectCategory(false, null, "ENTER PHONE NUMBER");
        assertEquals(InputCategory.PHONE, cat);
    }

    // -----------------------------------------------------------------------
    // Value Generation: first visit
    // -----------------------------------------------------------------------

    @Test
    public void testGenerate_emailFirstVisit() {
        String value = generator.generate(false, "com.example:id/email", null, "widget_email");
        assertEquals("test@example.com", value);
    }

    @Test
    public void testGenerate_passwordFirstVisit() {
        String value = generator.generate(true, null, null, "widget_pw");
        assertEquals("Test1234!", value);
    }

    @Test
    public void testGenerate_numberFirstVisit() {
        String value = generator.generate(false, "com.example:id/number", null, "widget_num");
        assertEquals("42", value);
    }

    @Test
    public void testGenerate_phoneFirstVisit() {
        String value = generator.generate(false, "com.example:id/phone", null, "widget_phone");
        assertEquals("+5561999990000", value);
    }

    @Test
    public void testGenerate_urlFirstVisit() {
        String value = generator.generate(false, "com.example:id/url", null, "widget_url");
        assertEquals("https://example.com", value);
    }

    @Test
    public void testGenerate_searchFirstVisit() {
        String value = generator.generate(false, "com.example:id/search", null, "widget_search");
        assertEquals("test", value);
    }

    // -----------------------------------------------------------------------
    // Value Generation: rotation on repeated visits
    // -----------------------------------------------------------------------

    @Test
    public void testGenerate_emailRotation() {
        String v1 = generator.generate(false, "com.example:id/email", null, "w1");
        String v2 = generator.generate(false, "com.example:id/email", null, "w1");
        String v3 = generator.generate(false, "com.example:id/email", null, "w1");
        String v4 = generator.generate(false, "com.example:id/email", null, "w1");

        assertEquals("test@example.com", v1);
        assertEquals("user@test.org", v2);
        assertEquals("a@b.c", v3);
        // Wraps around
        assertEquals("test@example.com", v4);
    }

    @Test
    public void testGenerate_passwordRotation() {
        String v1 = generator.generate(true, null, null, "pw1");
        String v2 = generator.generate(true, null, null, "pw1");
        String v3 = generator.generate(true, null, null, "pw1");

        assertEquals("Test1234!", v1);
        assertEquals("Password123", v2);
        assertEquals("Aa1!aaaa", v3);
    }

    @Test
    public void testGenerate_phoneRotation() {
        String v1 = generator.generate(false, "com.example:id/phone", null, "ph1");
        String v2 = generator.generate(false, "com.example:id/phone", null, "ph1");
        String v3 = generator.generate(false, "com.example:id/phone", null, "ph1");

        assertEquals("+5561999990000", v1);
        assertEquals("123456789", v2);
        // Wraps after 2 values
        assertEquals("+5561999990000", v3);
    }

    // -----------------------------------------------------------------------
    // Value Generation: different widgets have independent counters
    // -----------------------------------------------------------------------

    @Test
    public void testGenerate_independentCountersPerWidget() {
        String v1 = generator.generate(false, "com.example:id/email", null, "widget_A");
        String v2 = generator.generate(false, "com.example:id/email", null, "widget_B");

        // Both should get the first value since they're different widgets
        assertEquals("test@example.com", v1);
        assertEquals("test@example.com", v2);
    }

    // -----------------------------------------------------------------------
    // INV-INP-01: generateForNode (and generate) SHALL NOT return null
    // -----------------------------------------------------------------------

    @Test
    public void testGenerate_neverReturnsNull_email() {
        assertNotNull(generator.generate(false, "email_field", null, "w"));
    }

    @Test
    public void testGenerate_neverReturnsNull_password() {
        assertNotNull(generator.generate(true, null, null, "w"));
    }

    @Test
    public void testGenerate_neverReturnsNull_generic() {
        // GENERIC delegates to StringCache.nextString()
        String value = generator.generate(false, null, null, "w");
        assertNotNull(value);
    }

    @Test
    public void testGenerate_neverReturnsNull_allNulls() {
        String value = generator.generate(false, null, null, "unknown");
        assertNotNull(value);
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    public void testDetectCategory_emptyResourceId() {
        InputCategory cat = generator.detectCategory(false, "", null);
        assertEquals(InputCategory.GENERIC, cat);
    }

    @Test
    public void testDetectCategory_emptyContentDesc() {
        InputCategory cat = generator.detectCategory(false, null, "");
        assertEquals(InputCategory.GENERIC, cat);
    }
}

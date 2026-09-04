package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The boundary between a theme file and Orbit.
 *
 * <p>Two claims are being tested here, and they pull in opposite directions. The first is that a
 * theme Orbit wrote survives the trip out and back with every visible decision intact, because a
 * portable format nobody can reliably read back is worse than none. The second is that a file Orbit
 * did not write gets nothing: it cannot be a built-in, cannot replace a saved theme, cannot be
 * enormous, cannot be somebody else's JSON, and cannot make Theme Studio throw. Most of what
 * follows is the second claim, stated one refusal at a time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OrbitThemeFileTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
    }

    // ---- round trip --------------------------------------------------------------------------

    /**
     * The guarantee Beta 3 makes: what you export, you can import.
     *
     * <p>Every built-in is checked rather than one, because the presets are deliberately different
     * shapes — all-classic tokens, named palette colours, raw hex, AMOLED on and off — and a codec
     * that handled only the shape it was written against would fail on exactly the theme somebody
     * shared.
     */
    @Test public void everyBuiltInSurvivesTheRoundTrip() throws Exception {
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            String document = OrbitThemeFileCodec.encode(preset);
            assertFalse(preset.name + " produced no document", document.isEmpty());
            OrbitTheme back = OrbitThemeFileCodec.decode(document);
            assertTrue(preset.name + " did not survive the round trip", preset.sameColours(back));
            assertEquals(preset.name, back.name);
            assertEquals(preset.amoled, back.amoled);
        }
    }

    /** Each of the six decisions individually, so a dropped field cannot hide behind the others. */
    @Test public void aCustomThemeRoundTripsEveryDecision() throws Exception {
        OrbitTheme made = OrbitTheme.custom("Deep Sea", "#1188CC", "#123456", "accent",
                "#101418", "#04060A", true);
        OrbitTheme back = OrbitThemeFileCodec.decode(OrbitThemeFileCodec.encode(made));

        assertEquals("#1188CC", back.accent);
        assertEquals("#123456", back.userBubble);
        assertEquals(OrbitTheme.ACCENT, back.assistantBubble);
        assertEquals("#101418", back.surface);
        assertEquals("#04060A", back.background);
        assertTrue(back.amoled);
        assertEquals("Deep Sea", back.name);
        assertTrue(made.sameColours(back));
    }

    /** The resolved appearance, not just the tokens: a round trip must look the same too. */
    @Test public void theImportedAppearanceMatchesTheExportedOne() throws Exception {
        OrbitTheme made = OrbitTheme.custom("Amberline", "#FF8A5B", "#4A2A1F", "#2A1D1A",
                "#241A18", "#120B0A", false);
        OrbitThemeTokens before = OrbitThemeTokens.resolve(context, made);
        OrbitThemeTokens after = OrbitThemeTokens.resolve(context,
                OrbitThemeFileCodec.decode(OrbitThemeFileCodec.encode(made)));

        assertEquals(before.accent, after.accent);
        assertEquals(before.background, after.background);
        assertEquals(before.surface, after.surface);
        assertEquals(before.userBubble, after.userBubble);
        assertEquals(before.assistantBubble, after.assistantBubble);
        assertEquals(before.link, after.link);
    }

    /** Reading through a stream is the path the picker actually takes. */
    @Test public void aThemeCanBeReadFromAStream() throws Exception {
        String document = OrbitThemeFileCodec.encode(OrbitTheme.builtIn(OrbitTheme.ID_NOVA_AMOLED));
        OrbitTheme back = OrbitThemeFileCodec.read(
                new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
        assertTrue(OrbitTheme.builtIn(OrbitTheme.ID_NOVA_AMOLED).sameColours(back));
    }

    // ---- format validation ---------------------------------------------------------------------

    @Test public void anEmptyObjectIsNotATheme() {
        assertRejected("{}");
    }

    @Test public void anotherApplicationsJsonIsNotATheme() {
        assertRejected("{\"format\":\"something.else\",\"schema\":2,\"name\":\"Nope\","
                + "\"accent\":\"violet\",\"userBubble\":\"classic\",\"assistantBubble\":\"classic\","
                + "\"surface\":\"classic\",\"background\":\"classic\",\"amoled\":false}");
    }

    @Test public void aMissingFormatFieldIsNotATheme() {
        assertRejected("{\"schema\":2,\"name\":\"Nope\",\"accent\":\"violet\","
                + "\"userBubble\":\"classic\",\"assistantBubble\":\"classic\","
                + "\"surface\":\"classic\",\"background\":\"classic\",\"amoled\":false}");
    }

    /**
     * The preset library uses its own format identifier, and must not import as one theme.
     *
     * <p>It is the nearest miss there is: same author, same directory, one suffix apart. An
     * {@code equals} on the parsed value is what separates them, and this is why it is not a
     * {@code startsWith}.
     */
    @Test public void theSavedPresetLibraryIsNotASingleTheme() {
        assertRejected("{\"format\":\"orbit.theme.library\",\"schema\":1,\"themes\":[]}");
    }

    @Test public void aValidDocumentIsAccepted() throws Exception {
        OrbitTheme theme = OrbitThemeFileCodec.decode(
                "{\"format\":\"orbit.theme\",\"schema\":2,\"name\":\"Shared\",\"accent\":\"mint\","
                + "\"userBubble\":\"classic\",\"assistantBubble\":\"classic\","
                + "\"surface\":\"classic\",\"background\":\"classic\",\"amoled\":true}");
        assertEquals("Shared", theme.name);
        assertEquals("mint", theme.accent);
        assertTrue(theme.amoled);
    }

    @Test public void arbitraryJsonIsNotATheme() {
        assertRejected("[1,2,3]");
        assertRejected("\"just a string\"");
        assertRejected("{\"hello\":\"world\"}");
    }

    @Test public void malformedAndEmptyFilesAreRejected() {
        assertRejected("");
        assertRejected("   ");
        assertRejected("{\"format\":\"orbit.theme\",\"schema\":2,");
        assertRejected("<html><body>not json</body></html>");
        try {
            OrbitThemeFileCodec.decode((byte[]) null);
            fail("a null document must be refused");
        } catch (OrbitThemeFileCodec.ThemeFileException expected) {
            assertEquals("This isn't a supported Orbit theme.", expected.getMessage());
        }
        try {
            OrbitThemeFileCodec.decode(new byte[0]);
            fail("an empty file must be refused");
        } catch (OrbitThemeFileCodec.ThemeFileException expected) {
            assertFalse(expected.newerVersion);
        }
    }

    /**
     * A document that announces itself and then describes nothing.
     *
     * <p>Without the structural check this imports as an unnamed copy of Orbit Default, because
     * every field would fall back to its own default. That is the one failure mode where refusing
     * is clearly better than normalising.
     */
    @Test public void aThemeMustActuallyDescribeATheme() {
        assertRejected("{\"format\":\"orbit.theme\",\"schema\":2}");
        assertRejected("{\"format\":\"orbit.theme\",\"schema\":2,\"name\":\"Half\","
                + "\"accent\":\"violet\",\"userBubble\":\"classic\"}");
        assertRejected("{\"format\":\"orbit.theme\",\"schema\":2,\"name\":null,"
                + "\"accent\":\"violet\",\"userBubble\":\"classic\",\"assistantBubble\":\"classic\","
                + "\"surface\":\"classic\",\"background\":\"classic\",\"amoled\":false}");
    }

    /** An editor that adds a byte-order mark has not made the file stop being a theme. */
    @Test public void aByteOrderMarkDoesNotBreakImport() throws Exception {
        String document = "\uFEFF" + OrbitThemeFileCodec.encode(OrbitTheme.orbitDefault());
        assertNotNull(OrbitThemeFileCodec.decode(document));
    }

    // ---- schema ------------------------------------------------------------------------------

    /** Beta 1 wrote schema 1 documents. Beta 3 being the first to read one is not their problem. */
    @Test public void aSchemaOneThemeIsStillReadable() throws Exception {
        OrbitTheme theme = OrbitThemeFileCodec.decode(
                "{\"format\":\"orbit.theme\",\"schema\":1,\"id\":\"t_old\",\"name\":\"Beta One\","
                + "\"builtIn\":false,\"accent\":\"#8B7CFF\",\"userBubble\":\"#3A2E63\","
                + "\"assistantBubble\":\"classic\",\"surface\":\"classic\","
                + "\"background\":\"classic\",\"amoled\":false}");
        assertEquals("Beta One", theme.name);
        // The value is unchanged; schema 2 only gave Orbit's own colour its name back, and the
        // model does that on construction whatever schema the file claimed.
        assertEquals("violet", theme.accent);
    }

    @Test public void theCurrentSchemaIsAccepted() throws Exception {
        assertNotNull(OrbitThemeFileCodec.decode(document(OrbitTheme.SCHEMA)));
    }

    @Test public void schemaZeroAndBelowAreRefused() {
        assertRejected(document(0));
        assertRejected(document(-1));
    }

    /** A future schema gets its own sentence, because "not a theme" would be untrue and unhelpful. */
    @Test public void aFutureSchemaSaysSoRatherThanPretendingItIsNotATheme() {
        try {
            OrbitThemeFileCodec.decode(document(OrbitTheme.SCHEMA + 1));
            fail("a newer schema must be refused");
        } catch (OrbitThemeFileCodec.ThemeFileException expected) {
            assertTrue(expected.newerVersion);
            assertEquals("This theme was made for a newer version of Orbit.", expected.getMessage());
        }
        try {
            OrbitThemeFileCodec.decode(document(9999));
            fail("a much newer schema must be refused too");
        } catch (OrbitThemeFileCodec.ThemeFileException expected) {
            assertTrue(expected.newerVersion);
        }
    }

    // ---- identity ----------------------------------------------------------------------------

    /**
     * The rule an external file cannot argue with.
     *
     * <p>This document claims to be Orbit's own AMOLED preset, by id and by flag. It imports as a
     * theme the user owns with an id of Orbit's choosing, and the real preset is untouched.
     */
    @Test public void anImportedFileCannotClaimBuiltInIdentity() throws Exception {
        OrbitTheme imported = OrbitThemeFileCodec.decode(
                "{\"format\":\"orbit.theme\",\"schema\":2,\"id\":\"" + OrbitTheme.ID_AMOLED + "\","
                + "\"name\":\"Orbit AMOLED\",\"builtIn\":true,\"accent\":\"dynamic\","
                + "\"userBubble\":\"classic\",\"assistantBubble\":\"classic\","
                + "\"surface\":\"classic\",\"background\":\"classic\",\"amoled\":true}");

        assertFalse("an external file may not declare itself built in", imported.builtIn);
        assertNotEquals(OrbitTheme.ID_AMOLED, imported.id);
        assertFalse("nor take a reserved built-in id", OrbitTheme.isBuiltInId(imported.id));
        assertNotNull("Orbit's own preset is still there", OrbitTheme.builtIn(OrbitTheme.ID_AMOLED));
        assertTrue(OrbitTheme.builtIn(OrbitTheme.ID_AMOLED).builtIn);
    }

    @Test public void everyImportGetsAFreshLocalId() throws Exception {
        String document = OrbitThemeFileCodec.encode(
                OrbitTheme.custom("Twice", "violet", "classic", "classic", "classic",
                        "classic", false));
        OrbitTheme first = OrbitThemeFileCodec.decode(document);
        OrbitTheme second = OrbitThemeFileCodec.decode(document);
        assertNotEquals(first.id, second.id);
        assertTrue(first.sameColours(second));
    }

    /**
     * Importing a file whose id matches a theme the user already saved adds a theme; it does not
     * replace one. The stored copy keeps its own colours.
     */
    @Test public void anImportCannotOverwriteASavedThemeById() throws Exception {
        OrbitTheme mine = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Mine", "mint", "classic", "classic", "classic", "classic", false));
        assertNotNull(mine);

        OrbitTheme hostile = OrbitThemeFileCodec.decode(
                "{\"format\":\"orbit.theme\",\"schema\":2,\"id\":\"" + mine.id + "\","
                + "\"name\":\"Mine\",\"builtIn\":false,\"accent\":\"#FF0000\","
                + "\"userBubble\":\"classic\",\"assistantBubble\":\"classic\","
                + "\"surface\":\"classic\",\"background\":\"classic\",\"amoled\":false}");
        assertNotEquals(mine.id, hostile.id);
        assertNotNull(OrbitThemeStore.savePreset(context, hostile));

        List<OrbitTheme> saved = OrbitThemeStore.customPresets(context);
        assertEquals(2, saved.size());
        assertEquals("the original is untouched", "mint",
                OrbitThemeStore.preset(context, mine.id).accent);
    }

    /** Two themes may be called the same thing. Identity has never come from the name. */
    @Test public void duplicateNamesAreAllowed() throws Exception {
        String document = OrbitThemeFileCodec.encode(
                OrbitTheme.custom("Night", "blue", "classic", "classic", "classic",
                        "classic", true));
        assertNotNull(OrbitThemeStore.savePreset(context, OrbitThemeFileCodec.decode(document)));
        assertNotNull(OrbitThemeStore.savePreset(context, OrbitThemeFileCodec.decode(document)));

        List<OrbitTheme> saved = OrbitThemeStore.customPresets(context);
        assertEquals(2, saved.size());
        assertEquals("Night", saved.get(0).name);
        assertEquals("Night", saved.get(1).name);
        assertNotEquals(saved.get(0).id, saved.get(1).id);
    }

    // ---- names and values ---------------------------------------------------------------------

    @Test public void blankAndOverlongNamesNormalise() throws Exception {
        assertEquals("Custom theme", named("").name);
        assertEquals("Custom theme", named("     ").name);

        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < 400; i++) long_.append('N');
        OrbitTheme trimmed = named(long_.toString());
        assertEquals(OrbitTheme.MAX_NAME_LENGTH, trimmed.name.length());
    }

    @Test public void namedTokensAndCustomHexBothSurvive() throws Exception {
        assertEquals("violet", named("A", "violet").accent);
        assertEquals("#4488AA", named("B", "#4488aa").accent);
    }

    /**
     * Nothing a value can be makes this throw.
     *
     * <p>The structure is valid in every case here, so the file is a theme; only the values are
     * nonsense. Those normalise through the same path a hand-edited preset file already took, which
     * is the whole reason the model normalises at construction rather than at each call site.
     */
    @Test public void malformedValuesNormaliseRatherThanCrash() throws Exception {
        assertEquals(OrbitTheme.DYNAMIC, named("A", "#GGGGGG").accent);
        assertEquals(OrbitTheme.DYNAMIC, named("A", "#12345").accent);
        assertEquals(OrbitTheme.DYNAMIC, named("A", "rgb(1,2,3)").accent);
        assertEquals(OrbitTheme.DYNAMIC, named("A", "").accent);
        assertEquals(OrbitTheme.DYNAMIC, named("A", " ").accent);

        OrbitTheme odd = OrbitThemeFileCodec.decode(
                "{\"format\":\"orbit.theme\",\"schema\":2,\"name\":\"Odd\",\"accent\":17,"
                + "\"userBubble\":true,\"assistantBubble\":{\"nested\":1},"
                + "\"surface\":[1,2],\"background\":\"#nothex\",\"amoled\":\"yes\"}");
        assertNotNull(odd);
        assertEquals(OrbitTheme.DYNAMIC, odd.accent);
        assertEquals(OrbitTheme.CLASSIC, odd.surface);
        assertEquals(OrbitTheme.CLASSIC, odd.background);
        // Resolving it must not throw either; a theme that parses is a theme that can be drawn.
        assertNotNull(OrbitThemeTokens.resolve(context, odd));
    }

    // ---- size bound ---------------------------------------------------------------------------

    @Test public void anOversizedFileIsRefusedBeforeItIsParsed() {
        StringBuilder padding = new StringBuilder();
        while (padding.length() < OrbitThemeFileCodec.MAX_FILE_BYTES + 1024) padding.append('x');
        String huge = "{\"format\":\"orbit.theme\",\"schema\":2,\"name\":\"" + padding
                + "\",\"accent\":\"violet\",\"userBubble\":\"classic\","
                + "\"assistantBubble\":\"classic\",\"surface\":\"classic\","
                + "\"background\":\"classic\",\"amoled\":false}";
        try {
            OrbitThemeFileCodec.decode(huge.getBytes(StandardCharsets.UTF_8));
            fail("an oversized theme file must be refused");
        } catch (OrbitThemeFileCodec.ThemeFileException expected) {
            assertFalse(expected.newerVersion);
            assertEquals("This file is too large to be an Orbit theme.", expected.getMessage());
        }
    }

    /** The bound applies while reading, so a huge stream is never held in memory whole. */
    @Test public void anOversizedStreamIsRefusedWhileReading() {
        byte[] huge = new byte[OrbitThemeFileCodec.MAX_FILE_BYTES * 4];
        java.util.Arrays.fill(huge, (byte) 'x');
        try {
            OrbitThemeFileCodec.read(new ByteArrayInputStream(huge));
            fail("an oversized stream must be refused");
        } catch (OrbitThemeFileCodec.ThemeFileException expected) {
            assertEquals("This file is too large to be an Orbit theme.", expected.getMessage());
        }
    }

    /** A real theme is nowhere near the limit, which is what makes the limit safe to impose. */
    @Test public void aRealThemeIsTinyComparedToTheBound() {
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            int size = OrbitThemeFileCodec.encode(preset)
                    .getBytes(StandardCharsets.UTF_8).length;
            assertTrue(preset.name + " is " + size + " bytes",
                    size < OrbitThemeFileCodec.MAX_FILE_BYTES / 20);
        }
    }

    // ---- filenames ----------------------------------------------------------------------------

    @Test public void exportFilenamesAreFriendlyAndSafe() {
        assertEquals("Nova-AMOLED" + OrbitThemeFileCodec.FILE_SUFFIX,
                OrbitThemeFileCodec.fileNameFor(OrbitTheme.builtIn(OrbitTheme.ID_NOVA_AMOLED)));
        assertEquals("My-theme" + OrbitThemeFileCodec.FILE_SUFFIX,
                OrbitThemeFileCodec.fileNameFor("My / theme"));
        assertEquals("Custom-theme" + OrbitThemeFileCodec.FILE_SUFFIX,
                OrbitThemeFileCodec.fileNameFor("   "));
        assertEquals("Orbit-theme" + OrbitThemeFileCodec.FILE_SUFFIX,
                OrbitThemeFileCodec.fileNameFor("///"));
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            String name = OrbitThemeFileCodec.fileNameFor(preset);
            assertTrue(name.endsWith(OrbitThemeFileCodec.FILE_SUFFIX));
            assertFalse("a filename may not contain a path separator", name.contains("/"));
            assertFalse(name.contains("\\"));
            assertFalse(name.startsWith("-"));
        }
    }

    /** Import must not depend on the name a provider reports, only on what is inside the file. */
    @Test public void importDoesNotDependOnTheFilename() throws Exception {
        String document = OrbitThemeFileCodec.encode(OrbitTheme.builtIn(OrbitTheme.ID_TIDE));
        assertNotNull("a theme named anything at all still imports",
                OrbitThemeFileCodec.decode(document));
    }

    // ---- the file Orbit writes -----------------------------------------------------------------

    /** The exported document is the canonical representation, not a second format invented here. */
    @Test public void theExportedDocumentIsSelfDescribing() throws Exception {
        JSONObject json = new JSONObject(
                OrbitThemeFileCodec.encode(OrbitTheme.builtIn(OrbitTheme.ID_NEBULA)));
        assertEquals(OrbitTheme.FORMAT, json.getString("format"));
        assertEquals(OrbitTheme.SCHEMA, json.getInt("schema"));
        assertEquals("Nebula", json.getString("name"));
        assertTrue(json.has("accent"));
        assertTrue(json.has("userBubble"));
        assertTrue(json.has("assistantBubble"));
        assertTrue(json.has("surface"));
        assertTrue(json.has("background"));
        assertTrue(json.has("amoled"));
    }

    /**
     * A theme file carries appearance and nothing else.
     *
     * <p>Stated as a test because it is the security claim, and a future field added to the model
     * without thinking about this file is exactly how it would stop being true.
     */
    @Test public void aThemeFileCarriesNothingButAppearance() throws Exception {
        JSONObject json = new JSONObject(
                OrbitThemeFileCodec.encode(OrbitTheme.builtIn(OrbitTheme.ID_NOVA_AMOLED)));
        assertEquals(11, json.length());
        for (String forbidden : new String[]{"url", "uri", "intent", "action", "package",
                "component", "permission", "provider", "apiKey", "token", "secret", "routine",
                "memory", "deck", "update", "class", "code", "script"}) {
            assertFalse("a theme file must not carry " + forbidden, json.has(forbidden));
        }
    }

    /** Encoding never throws, whatever the theme, so export cannot fail for a legal appearance. */
    @Test public void encodingNeverFailsForALegalTheme() {
        assertFalse(OrbitThemeFileCodec.encode(
                OrbitTheme.custom(" odd name", "#000000", "#FFFFFF", "accent",
                        "#010203", "#040506", true)).isEmpty());
        assertEquals("", OrbitThemeFileCodec.encode(null));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static String document(int schema) {
        return "{\"format\":\"orbit.theme\",\"schema\":" + schema + ",\"name\":\"Anything\","
                + "\"accent\":\"violet\",\"userBubble\":\"classic\",\"assistantBubble\":\"classic\","
                + "\"surface\":\"classic\",\"background\":\"classic\",\"amoled\":false}";
    }

    private static OrbitTheme named(String name) throws Exception {
        return named(name, "violet");
    }

    private static OrbitTheme named(String name, String accent) throws Exception {
        return OrbitThemeFileCodec.decode(new JSONObject()
                .put("format", OrbitTheme.FORMAT)
                .put("schema", OrbitTheme.SCHEMA)
                .put("name", name)
                .put("accent", accent)
                .put("userBubble", OrbitTheme.CLASSIC)
                .put("assistantBubble", OrbitTheme.CLASSIC)
                .put("surface", OrbitTheme.CLASSIC)
                .put("background", OrbitTheme.CLASSIC)
                .put("amoled", false)
                .toString());
    }

    private static void assertRejected(String document) {
        try {
            OrbitTheme theme = OrbitThemeFileCodec.decode(document);
            fail("this must not import as a theme: " + document + " (got " + theme.name + ")");
        } catch (OrbitThemeFileCodec.ThemeFileException expected) {
            assertFalse("the message must not leak a parser error",
                    expected.getMessage().contains("JSON"));
            assertTrue("the message must be one Orbit wrote",
                    expected.getMessage().startsWith("This "));
        }
    }
}

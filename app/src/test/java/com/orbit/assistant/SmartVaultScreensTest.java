package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The Smart Vault parts of the Vault list, the item page and the Smart Vault screen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SmartVaultScreensTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OrbitVaultStore.prefs(context).edit().clear().commit();
        Prefs.get(context).edit().clear().commit();
        SmartVaultDb.resetForTest();
        context.deleteDatabase(SmartVaultDb.NAME);
        SmartVaultIndex.invalidate();
        TestWorkManager.ensureInitialized(context);
        File[] files = OrbitVaultMedia.directory(context).listFiles();
        if (files != null) for (File file : files) file.delete();
        AiProviders.installForTest(new SmartVaultBehaviourTest.FakeProvider());
    }

    @After public void tearDown() {
        AiProviders.installForTest(null);
        SmartVaultDb.resetForTest();
    }

    private static List<String> texts(View view) {
        List<String> out = new ArrayList<>();
        collect(view, out);
        return out;
    }

    private static void collect(View view, List<String> out) {
        if (view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            if (t != null && t.length() > 0) out.add(t.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static boolean anyContains(List<String> texts, String needle) {
        for (String t : texts) if (t.contains(needle)) return true;
        return false;
    }

    private static EditText firstEditText(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText found = firstEditText(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void theVaultLooksAsItDidUntilSmartVaultIsUsed() {
        OrbitVaultStore.saveText(context, "", "One note", "t");
        OrbitVaultActivity vault = Robolectric.buildActivity(OrbitVaultActivity.class).setup().get();
        List<String> shown = texts(vault.getWindow().getDecorView());
        assertFalse("no introduction for a nearly empty Vault",
                anyContains(shown, OrbitVaultActivity.INTRO_TITLE));
        assertFalse("no topic selector without topics",
                anyContains(shown, OrbitVaultActivity.TOPIC_ANY));
        assertFalse(anyContains(shown, "nearly full"));
    }

    @Test public void theIntroductionAppearsOnceThereIsSomethingToOrganise() {
        for (int i = 0; i < 3; i++) OrbitVaultStore.saveText(context, "", "Note " + i, "t");
        OrbitVaultActivity vault = Robolectric.buildActivity(OrbitVaultActivity.class).setup().get();
        assertTrue(anyContains(texts(vault.getWindow().getDecorView()),
                OrbitVaultActivity.INTRO_TITLE));
        Prefs.get(context).edit().putBoolean(Prefs.SMART_VAULT_INTRO_DISMISSED, true).commit();
        OrbitVaultActivity again = Robolectric.buildActivity(OrbitVaultActivity.class).setup().get();
        assertFalse(anyContains(texts(again.getWindow().getDecorView()),
                OrbitVaultActivity.INTRO_TITLE));
    }

    @Test public void aNearlyFullVaultSaysSo() {
        for (int i = 0; i < OrbitVaultStore.WARN_AT; i++) {
            OrbitVaultStore.saveText(context, "", "n" + i, "t");
        }
        OrbitVaultActivity vault = Robolectric.buildActivity(OrbitVaultActivity.class).setup().get();
        assertTrue(anyContains(texts(vault.getWindow().getDecorView()),
                OrbitVaultActivity.NEARLY_FULL_TITLE));
    }

    @Test public void aSmartSearchIsRankedAndOffersAskVault() {
        OrbitVaultStore.saveText(context, "Salmon", "Bake the salmon at 200C", "t");
        OrbitVaultStore.saveText(context, "Groceries", "salmon, rice, miso", "t");
        OrbitVaultStore.saveText(context, "Car", "tyres", "t");
        SmartVault.enable(context, false, false, false, false);
        SmartVaultIndex.build(context);

        OrbitVaultActivity vault = Robolectric.buildActivity(OrbitVaultActivity.class).setup().get();
        EditText search = firstEditText(vault.getWindow().getDecorView());
        assertNotNull(search);
        search.setText("salmon");
        ShadowLooper.idleMainLooper();
        List<String> shown = texts(vault.getWindow().getDecorView());
        assertTrue(anyContains(shown, OrbitVaultActivity.BEST_MATCHES_HEADING));
        assertTrue(anyContains(shown, OrbitVaultActivity.ASK_VAULT_LABEL));
        assertTrue(anyContains(shown, "2 matches"));
        assertFalse(anyContains(shown, "tyres"));
    }

    @Test public void topicsAppearAsAThirdSelector() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "", "Salmon", "t");
        OrbitVaultStore.setTopics(context, item.id, Collections.singletonList("recipes"));
        OrbitVaultActivity vault = Robolectric.buildActivity(OrbitVaultActivity.class).setup().get();
        assertTrue(anyContains(texts(vault.getWindow().getDecorView()),
                OrbitVaultActivity.TOPIC_ANY));
    }

    @Test public void theItemPageShowsSuggestionsAsOrbitsAndRelatedItems() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "", "Bake salmon with miso", "t");
        OrbitVaultStore.saveText(context, "", "Miso soup with salmon", "t");
        OrbitVaultStore.applySuggestions(context, item.id, item.contentFingerprint(),
                new VaultSuggestions("Miso salmon", "A baked salmon recipe.",
                        Arrays.asList("recipes"), null, "", 1L, "FakeAI"));
        SmartVault.enable(context, false, false, false, false);
        SmartVaultIndex.build(context);

        Intent intent = new Intent(context, OrbitVaultItemActivity.class)
                .putExtra(OrbitVaultItemActivity.EXTRA_ITEM_ID, item.id);
        OrbitVaultItemActivity page = Robolectric.buildActivity(OrbitVaultItemActivity.class,
                intent).setup().get();
        List<String> shown = texts(page.getWindow().getDecorView());
        assertTrue(anyContains(shown, OrbitVaultItemActivity.SUGGESTED_HEADING));
        assertTrue(anyContains(shown, "Suggested title"));
        assertTrue(anyContains(shown, "A baked salmon recipe."));
        assertTrue(anyContains(shown, "✦ recipes"));
        assertTrue(anyContains(shown, OrbitVaultItemActivity.RELATED_HEADING));
        assertTrue(anyContains(shown, "Miso soup with salmon"));
    }

    @Test public void theSmartVaultScreenExplainsEachChoiceBeforeAnythingHappens() {
        SmartVaultActivity screen = Robolectric.buildActivity(SmartVaultActivity.class).setup().get();
        List<String> shown = texts(screen.getWindow().getDecorView());
        for (String label : new String[]{SmartVaultActivity.OCR_LABEL,
                SmartVaultActivity.MEANING_LABEL, SmartVaultActivity.LINKS_LABEL,
                SmartVaultActivity.AI_LABEL, SmartVaultActivity.TURN_ON}) {
            assertTrue(label, anyContains(shown, label));
        }
        assertTrue(anyContains(shown, "Google Play services"));
        assertTrue(anyContains(shown, "Hugging Face"));
        assertTrue(anyContains(shown, "contacts that website"));
        assertTrue(anyContains(shown, "AI allowance"));
        assertFalse("viewing the screen changes nothing", Prefs.smartVaultEnabled(context));
        assertFalse(SmartVault.databaseExists(context));
    }

    @Test public void theSmartVaultScreenWorksOnceOn() {
        OrbitVaultStore.saveText(context, "", "One note", "t");
        SmartVault.enable(context, true, false, false, false);
        SmartVaultActivity screen = Robolectric.buildActivity(SmartVaultActivity.class).setup().get();
        List<String> shown = texts(screen.getWindow().getDecorView());
        assertTrue(anyContains(shown, SmartVaultActivity.TURN_OFF));
        assertTrue(anyContains(shown, "Delete Smart Vault data"));
        assertTrue(anyContains(shown, "1 item has no suggestions"));
    }
}

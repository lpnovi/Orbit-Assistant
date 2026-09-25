package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
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
import org.robolectric.shadows.ShadowValueAnimator;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Smart Vault v0.8.1.0-beta.2: search snippets that explain the match, switches that behave like
 * every other Orbit switch, the model download's states, the Vault shortcut and the regrouped page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SmartVaultBeta2Test {

    /** A screenshot whose header says "Orbit" long before the line the user is looking for. */
    private static final String SCREENSHOT = "Orbit Assistant 12:04\nSettings Accounts Privacy\n"
            + "Notifications are grouped by app and sorted by time received today yesterday and "
            + "earlier this week with quiet delivery for anything you have muted before now and "
            + "a summary at the top of the shade every morning at eight and evening at six\n"
            + "ORBIT\nPURPLE 7294\nTap to copy";

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OrbitVaultStore.prefs(context).edit().clear().commit();
        Prefs.get(context).edit().clear().commit();
        SmartVaultDb.resetForTest();
        context.deleteDatabase(SmartVaultDb.NAME);
        SmartVaultIndex.invalidate();
        TestWorkManager.ensureInitialized(context);
        SmartVaultModel.delete(context);
        AiProviders.installForTest(new SmartVaultBehaviourTest.FakeProvider());
    }

    @After public void tearDown() throws Exception {
        setDurationScale(1f);
        SmartVaultModel.delete(context);
        AiProviders.installForTest(null);
        SmartVaultDb.resetForTest();
    }

    // ---- snippets ---------------------------------------------------------------------------------

    private static SmartVaultRanker.Doc shot(String derived, float[]... vectors) {
        return new SmartVaultRanker.Doc("shot", 1, "Screenshot", "Screenshot", derived,
                Collections.singletonList(derived), vectors.length == 0 ? null : vectors,
                Collections.emptyList());
    }

    private static SmartVaultRanker.Doc note(String id, String text, float[]... vectors) {
        return new SmartVaultRanker.Doc(id, 2, id, id + "\n" + text, "",
                Collections.singletonList(text), vectors.length == 0 ? null : vectors,
                Collections.emptyList());
    }

    @Test public void anOcrSnippetShowsThePassageThatMatchedRatherThanTheFirstWord() {
        List<SmartVaultRanker.Result> ranked = SmartVaultRanker.rank("orbit purple",
                Arrays.asList(shot(SCREENSHOT), note("Car", "tyres")), null);
        assertEquals("shot", ranked.get(0).id);
        SmartVaultRanker.Result top = ranked.get(0);
        assertEquals(SmartVaultRanker.Reason.RECOGNIZED, top.reason);
        assertTrue("the phrase spans OCR lines and is still the phrase", top.exact);
        assertTrue(top.excerpt, top.excerpt.contains("ORBIT PURPLE 7294"));
        assertFalse("not the header that merely says Orbit", top.excerpt.contains("Settings"));
    }

    @Test public void wordsFoundApartAreShownTogetherButNotClaimedAsThePhrase() {
        List<SmartVaultRanker.Result> ranked = SmartVaultRanker.rank("7294 orbit",
                Collections.singletonList(shot(SCREENSHOT)), null);
        SmartVaultRanker.Result top = ranked.get(0);
        assertEquals(SmartVaultRanker.Reason.RECOGNIZED, top.reason);
        assertFalse("the phrase as typed is not in the picture", top.exact);
        assertTrue(top.excerpt, top.excerpt.contains("PURPLE 7294"));
        CharSequence line = OrbitVaultActivity.matchLine(top, "7294 orbit");
        assertTrue(line.toString().startsWith(OrbitVaultActivity.MATCH_RECOGNIZED_WORDS));
    }

    @Test public void theExactPhraseIsLabelledAndBold() {
        SmartVaultRanker.Result top = SmartVaultRanker.rank("orbit purple",
                Collections.singletonList(shot(SCREENSHOT)), null).get(0);
        CharSequence line = OrbitVaultActivity.matchLine(top, "orbit purple");
        assertTrue(line.toString().startsWith(OrbitVaultActivity.MATCH_RECOGNIZED + ": "));
        Spanned spanned = (Spanned) line;
        StyleSpan[] bold = spanned.getSpans(0, spanned.length(), StyleSpan.class);
        assertEquals(1, bold.length);
        assertEquals("ORBIT PURPLE", spanned.subSequence(spanned.getSpanStart(bold[0]),
                spanned.getSpanEnd(bold[0])).toString());
    }

    @Test public void aMeaningResultNeverClaimsTheWordsWereFound() {
        float[] query = SmartVaultEmbedder.normalize(new float[]{1, 0, 0});
        List<SmartVaultRanker.Result> ranked = SmartVaultRanker.rank("fish dinner",
                Collections.singletonList(note("Salmon", "Bake the salmon at 200C",
                        SmartVaultEmbedder.normalize(new float[]{1, 0.1f, 0}))), query);
        SmartVaultRanker.Result top = ranked.get(0);
        assertEquals(SmartVaultRanker.Reason.MEANING, top.reason);
        assertFalse(top.exact);
        CharSequence line = OrbitVaultActivity.matchLine(top, "fish dinner");
        assertTrue(line.toString().startsWith(OrbitVaultActivity.MATCH_MEANING));
        if (line instanceof Spanned) {
            assertEquals("nothing is bolded as if it matched", 0,
                    ((Spanned) line).getSpans(0, line.length(), StyleSpan.class).length);
        }
    }

    @Test public void theTightestClusterOfWordsWins() {
        SmartVaultText.Match match = SmartVaultText.matchExcerpt(
                "receipt from the shop on monday. " + filler(120)
                        + "the blue otter password is here", "otter password",
                SmartVaultText.terms("password otter"), 60);
        assertTrue(match.exact);
        match = SmartVaultText.matchExcerpt("password reset for the shop. " + filler(120)
                        + "otter lodge wifi password 4417", "otter wifi",
                SmartVaultText.terms("otter wifi password"), 60);
        assertFalse(match.exact);
        assertTrue(match.excerpt, match.excerpt.contains("otter lodge wifi password"));
    }

    // ---- switches ---------------------------------------------------------------------------------

    private static List<OrbitSwitch> switches(View root) {
        List<OrbitSwitch> out = new ArrayList<>();
        collectSwitches(root, out);
        return out;
    }

    private static void collectSwitches(View view, List<OrbitSwitch> out) {
        if (view instanceof OrbitSwitch) out.add((OrbitSwitch) view);
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) collectSwitches(g.getChildAt(i), out);
        }
    }

    private static OrbitSwitch switchLabelled(View root, String label) {
        for (OrbitSwitch s : switches(root)) {
            if (label.contentEquals(s.getContentDescription())) return s;
        }
        return null;
    }

    private static List<String> texts(View view) {
        List<String> out = new ArrayList<>();
        collectTexts(view, out);
        return out;
    }

    private static void collectTexts(View view, List<String> out) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
            CharSequence t = ((TextView) view).getText();
            if (t != null && t.length() > 0) out.add(t.toString());
        }
        if (view instanceof ViewGroup && view.getVisibility() == View.VISIBLE) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) collectTexts(g.getChildAt(i), out);
        }
    }

    private static boolean anyContains(List<String> texts, String needle) {
        for (String t : texts) if (t.contains(needle)) return true;
        return false;
    }

    private static TextView textStartingWith(View view, String prefix) {
        if (view instanceof TextView && ((TextView) view).getText().toString().startsWith(prefix)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = textStartingWith(g.getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void tappingASmartVaultSwitchKeepsItOnScreenSoItCanAnimateAndTick() {
        OrbitVaultStore.saveText(context, "", "One note", "t");
        SmartVault.enable(context, true, false, false, false);
        SmartVaultActivity screen = Robolectric.buildActivity(SmartVaultActivity.class).setup().get();
        View root = screen.getWindow().getDecorView();
        String[][] rows = {
                {SmartVaultActivity.OCR_LABEL, Prefs.SMART_VAULT_OCR},
                {SmartVaultActivity.MEANING_LABEL, Prefs.SMART_VAULT_MEANING},
                {SmartVaultActivity.LINKS_LABEL, Prefs.SMART_VAULT_READ_LINKS},
                {SmartVaultActivity.AI_LABEL, Prefs.SMART_VAULT_AI_NEW},
        };
        for (String[] row : rows) {
            OrbitSwitch control = switchLabelled(root, row[0]);
            assertNotNull(row[0], control);
            boolean before = control.isChecked();
            // The row is the target, exactly as in Settings.
            ((View) control.getParent()).performClick();
            assertEquals(row[0], !before, control.isChecked());
            assertEquals(row[0], !before, Prefs.get(context).getBoolean(row[1], !before));
            assertTrue(row[0] + ": the tapped switch is still attached, not rebuilt away",
                    control.isAttachedToWindow());
            assertSame(row[0], control, switchLabelled(root, row[0]));
        }
    }

    @Test public void beforeTurningOnASwitchOnlyRecordsTheChoice() {
        SmartVaultActivity screen = Robolectric.buildActivity(SmartVaultActivity.class).setup().get();
        OrbitSwitch links = switchLabelled(screen.getWindow().getDecorView(),
                SmartVaultActivity.LINKS_LABEL);
        links.toggle();
        assertTrue(links.isChecked());
        assertFalse(Prefs.smartVaultEnabled(context));
        assertFalse(Prefs.get(context).getBoolean(Prefs.SMART_VAULT_READ_LINKS, false));
        assertTrue(links.isAttachedToWindow());
    }

    @Test public void beta1PreferencesAreShownAsTheyWere() {
        OrbitVaultStore.saveText(context, "", "One note", "t");
        SmartVault.enable(context, false, false, true, true);
        View root = Robolectric.buildActivity(SmartVaultActivity.class).setup().get()
                .getWindow().getDecorView();
        assertFalse(switchLabelled(root, SmartVaultActivity.OCR_LABEL).isChecked());
        assertFalse(switchLabelled(root, SmartVaultActivity.MEANING_LABEL).isChecked());
        assertTrue(switchLabelled(root, SmartVaultActivity.LINKS_LABEL).isChecked());
        assertTrue(switchLabelled(root, SmartVaultActivity.AI_LABEL).isChecked());
    }

    @Test public void switchesSettleInstantlyWithAnimationsOff() throws Exception {
        setDurationScale(0f);
        OrbitVaultStore.saveText(context, "", "One note", "t");
        SmartVault.enable(context, true, false, false, false);
        SmartVaultActivity screen = Robolectric.buildActivity(SmartVaultActivity.class).setup().get();
        OrbitSwitch ocr = switchLabelled(screen.getWindow().getDecorView(),
                SmartVaultActivity.OCR_LABEL);
        ocr.toggle();
        assertFalse(ocr.isChecked());
        assertFalse(Prefs.smartVaultOcr(context));

        OrbitProgressRing ring = new OrbitProgressRing(screen);
        screen.addContentView(ring, new FrameLayout.LayoutParams(40, 40));
        ring.setProgress(60);
        assertEquals("no glide with reduced motion", 60f, ring.drawnProgress(), 0.001f);
    }

    @Test public void theRingGlidesToEachNewValue() {
        SmartVaultActivity screen = Robolectric.buildActivity(SmartVaultActivity.class).setup().get();
        OrbitProgressRing ring = new OrbitProgressRing(screen);
        screen.addContentView(ring, new FrameLayout.LayoutParams(40, 40));
        ShadowLooper.idleMainLooper();
        ring.setProgress(60);
        assertEquals(60, ring.progress());
        assertTrue("starts from where it was", ring.drawnProgress() < 60f);
        ShadowLooper.idleMainLooper(UiKit.MOTION_STANDARD + 50, TimeUnit.MILLISECONDS);
        assertEquals(60f, ring.drawnProgress(), 0.001f);
        ring.setIndeterminate();
        assertTrue(ring.isIndeterminate());
    }

    // ---- model download ---------------------------------------------------------------------------

    @Test public void theDownloadSaysPreparingThenDownloadingThenWaitingWhenItStops() {
        long now = System.currentTimeMillis();
        assertEquals(SmartVaultModel.Phase.MISSING, SmartVaultModel.phase(context, now));
        SmartVaultModel.requestDownload(context);
        now = System.currentTimeMillis();
        assertEquals(SmartVaultModel.Phase.PREPARING, SmartVaultModel.phase(context, now));
        assertEquals("a job that never started is not called preparing forever",
                SmartVaultModel.Phase.WAITING,
                SmartVaultModel.phase(context, now + SmartVaultModel.START_GRACE_MS + 1));

        SmartVaultModel.recordProgress(context, 0L, now);
        assertEquals(SmartVaultModel.Phase.PREPARING, SmartVaultModel.phase(context, now + 10));
        SmartVaultModel.recordProgress(context, SmartVaultModel.TOTAL_BYTES / 4, now);
        assertEquals(SmartVaultModel.Phase.DOWNLOADING, SmartVaultModel.phase(context, now + 10));
        assertTrue(SmartVaultModel.percent(context) >= 24 && SmartVaultModel.percent(context) <= 25);
        assertEquals("a download that stopped reporting is waiting, not downloading",
                SmartVaultModel.Phase.WAITING,
                SmartVaultModel.phase(context, now + SmartVaultModel.STALL_MS + 1));

        SmartVaultModel.markWaiting(context);
        assertEquals(SmartVaultModel.Phase.WAITING, SmartVaultModel.phase(context, now + 10));
        SmartVaultModel.recordProgress(context, SmartVaultModel.TOTAL_BYTES / 2, now + 20);
        assertEquals("resumed", SmartVaultModel.Phase.DOWNLOADING,
                SmartVaultModel.phase(context, now + 30));
    }

    @Test public void theScreenShowsProgressAndUpdatesTheSameRowInPlace() {
        OrbitVaultStore.saveText(context, "", "One note", "t");
        SmartVault.enable(context, false, true, false, false);
        SmartVaultModel.recordProgress(context, SmartVaultModel.TOTAL_BYTES * 42 / 100,
                System.currentTimeMillis());
        SmartVaultActivity screen = Robolectric.buildActivity(SmartVaultActivity.class).setup().get();
        View root = screen.getWindow().getDecorView();
        TextView title = textStartingWith(root, SmartVaultActivity.MODEL_DOWNLOADING);
        assertNotNull(title);
        assertTrue(anyContains(texts(root), "42%"));

        SmartVaultModel.recordProgress(context, SmartVaultModel.TOTAL_BYTES * 80 / 100,
                System.currentTimeMillis());
        screen.refreshLive();
        assertSame("updated in place", title,
                textStartingWith(root, SmartVaultActivity.MODEL_DOWNLOADING));
        assertTrue(anyContains(texts(root), "80%"));
        assertFalse(anyContains(texts(root), "42%"));
    }

    @Test public void aFinishedModelIsRecognisedAndSaysSoQuietly() throws Exception {
        OrbitVaultStore.saveText(context, "", "One note", "t");
        SmartVault.enable(context, false, true, false, false);
        File dir = SmartVaultModel.dir(context);
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (RandomAccessFile vocab = new RandomAccessFile(
                new File(dir, SmartVaultModel.VOCAB.name), "rw");
             RandomAccessFile weights = new RandomAccessFile(
                     new File(dir, SmartVaultModel.WEIGHTS.name), "rw")) {
            vocab.setLength(SmartVaultModel.VOCAB.size);
            weights.setLength(SmartVaultModel.WEIGHTS.size);
        }
        assertTrue(new File(dir, ".verified").createNewFile());
        assertEquals(SmartVaultModel.Phase.READY, SmartVaultModel.phase(context));

        View root = Robolectric.buildActivity(SmartVaultActivity.class).setup().get()
                .getWindow().getDecorView();
        List<String> shown = texts(root);
        assertTrue(anyContains(shown, SmartVaultActivity.MODEL_READY));
        assertFalse(anyContains(shown, SmartVaultActivity.MODEL_DOWNLOADING));
        assertFalse(anyContains(shown, "%"));
    }

    @Test public void aStalledDownloadIsShownAsWaiting() {
        OrbitVaultStore.saveText(context, "", "One note", "t");
        SmartVault.enable(context, false, true, false, false);
        SmartVaultModel.recordProgress(context, SmartVaultModel.TOTAL_BYTES / 2,
                System.currentTimeMillis() - SmartVaultModel.STALL_MS - 5_000);
        View root = Robolectric.buildActivity(SmartVaultActivity.class).setup().get()
                .getWindow().getDecorView();
        assertTrue(anyContains(texts(root), SmartVaultActivity.MODEL_WAITING));
    }

    // ---- the page and the shortcut ----------------------------------------------------------------

    @Test public void thePageIsGroupedAndKeepsWhatLeavesThePhoneVisible() {
        OrbitVaultStore.saveText(context, "", "One note", "t");
        SmartVault.enable(context, true, false, false, false);
        SmartVaultActivity screen = Robolectric.buildActivity(SmartVaultActivity.class).setup().get();
        View root = screen.getWindow().getDecorView();
        List<String> shown = texts(root);
        for (String section : new String[]{SmartVaultActivity.SECTION_SEARCH,
                SmartVaultActivity.SECTION_CAPTURE, SmartVaultActivity.SECTION_AI,
                SmartVaultActivity.SECTION_ITEMS, SmartVaultActivity.SECTION_MANAGE}) {
            assertTrue(section, shown.contains(section));
        }
        assertTrue(anyContains(shown, "Google Play services"));
        assertTrue(anyContains(shown, "Hugging Face"));
        assertTrue(anyContains(shown, "contacts that website"));
        assertTrue(anyContains(shown, "AI allowance"));
        assertTrue(anyContains(shown, "Provider: "));
        assertFalse("secondary detail is folded away", anyContains(shown, "MIT licence"));
        textStartingWith(root, "▸  " + SmartVaultActivity.DETAILS_LABEL).performClick();
        assertTrue(anyContains(texts(root), "MIT licence"));
        assertTrue(anyContains(texts(root), "anonymous statistics"));
    }

    @Test public void theVaultOpensSmartVaultSettingsDirectlyAndBackReturnsToIt() {
        OrbitVaultStore.saveText(context, "", "One note", "t");
        OrbitVaultActivity vault = Robolectric.buildActivity(OrbitVaultActivity.class).setup().get();
        ImageButton shortcut = null;
        boolean sortStillThere = false;
        for (View v : vault.getWindow().getDecorView().getTouchables()) {
            if (!(v instanceof ImageButton)) continue;
            CharSequence d = v.getContentDescription();
            if (d == null) continue;
            if (OrbitVaultActivity.SMART_VAULT_SHORTCUT.contentEquals(d)) shortcut = (ImageButton) v;
            if ("Sort saved items".contentEquals(d)) sortStillThere = true;
        }
        assertNotNull("a Smart Vault shortcut in the Vault header", shortcut);
        assertTrue("the sort control is untouched", sortStillThere);
        shortcut.performClick();
        Intent started = shadowOf(vault).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(SmartVaultActivity.class.getName(), started.getComponent().getClassName());
        assertFalse("the Vault stays underneath", vault.isFinishing());

        SmartVaultActivity screen = Robolectric.buildActivity(SmartVaultActivity.class, started)
                .setup().get();
        View back = null;
        for (View v : screen.getWindow().getDecorView().getTouchables()) {
            if (v.getContentDescription() != null
                    && "Back".contentEquals(v.getContentDescription())) back = v;
        }
        assertNotNull(back);
        back.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("Back closes Smart Vault, revealing the Vault", screen.isFinishing());
    }

    private static String filler(int words) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words; i++) out.append("x ");
        return out.toString();
    }

    private static void setDurationScale(float scale) throws Exception {
        Method setScale = ShadowValueAnimator.class.getDeclaredMethod("setDurationScale",
                float.class);
        setScale.setAccessible(true);
        setScale.invoke(null, scale);
        if (scale == 0f) assertFalse(ValueAnimator.areAnimatorsEnabled());
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** Runtime coverage for the Beta 7 switcher, manager, naming, and IME container behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class DeckBeta7SurfaceTest {
    private Context context;
    private ActivityController<DeckActivity> controller;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DeckLayoutStore.clearForTest(context);
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
    }

    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
    }

    private DeckActivity deck() {
        controller = Robolectric.buildActivity(DeckActivity.class).setup();
        return controller.get();
    }

    @Test public void activeLayoutNameOpensAOneTapSwitcherWhenUseful() {
        DeckLayout work = DeckLayoutStore.createBlankLayout(context, "Work");
        assertNotNull(work);
        assertTrue(DeckLayoutStore.switchLayout(context, DeckLayout.PRIMARY_ID));
        DeckActivity activity = deck();

        assertEquals("My Deck", activity.headerSubtitleForTest().getText().toString());
        assertTrue(activity.headerSubtitleForTest().isClickable());
        assertTrue(String.valueOf(activity.headerSubtitleForTest().getContentDescription())
                .contains("Active layout My Deck"));

        activity.headerSubtitleForTest().performClick();
        assertTrue(activity.sheetOpenForTest());
        View workRow = clickableAncestor(findText(activity.getWindow().getDecorView(), "Work"));
        assertNotNull(workRow);
        workRow.performClick();
        assertEquals(work.id, DeckLayoutStore.activeLayoutId(context));
        assertFalse(activity.sheetOpenForTest());
        assertTrue(activity.gridForTest().orderedChildren().isEmpty());
    }

    @Test public void freeKeepsTheActiveDeckButCalmsAndLocksLayoutManagement() {
        DeckLayout work = DeckLayoutStore.createBlankLayout(context, "Work");
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        DeckActivity activity = deck();

        assertEquals(work.id, DeckLayoutStore.activeLayoutId(context));
        assertEquals("Your shortcuts", activity.headerSubtitleForTest().getText().toString());
        assertFalse(activity.headerSubtitleForTest().hasOnClickListeners());
        activity.openLayoutManagerForTest();
        assertNotNull(findText(activity.getWindow().getDecorView(), "Deck layouts · Orbit Pro"));
        assertNotNull(findContainingText(activity.getWindow().getDecorView(),
                "Your active Deck and all Free organization tools remain fully available"));
    }

    @Test public void managerExposesEveryActionWithoutSwipeGestures() {
        DeckLayoutStore.createBlankLayout(context, "Work");
        DeckActivity activity = deck();
        activity.openLayoutManagerForTest();

        assertNotNull(findText(activity.getWindow().getDecorView(), "New layout"));
        assertNotNull(findText(activity.getWindow().getDecorView(), "Templates"));
        assertNotNull(findText(activity.getWindow().getDecorView(), "My Deck"));
        assertNotNull(findText(activity.getWindow().getDecorView(), "Work"));
        assertNotNull(findDescription(activity.getWindow().getDecorView(), "Options for My Deck"));
        assertNotNull(findDescription(activity.getWindow().getDecorView(), "Options for Work"));
        assertNotNull(findContainingDescription(activity.getWindow().getDecorView(), "Active"));
    }

    @Test public void everySingleLineNamingFlowUsesDoneAndTheSharedScrollableSheet() {
        DeckActivity activity = deck();

        activity.openLayoutNameForTest();
        assertNotNull(activity.sheetScrollForTest());
        assertSingleLineDone(firstEditText(activity));

        DeckLayoutStore.addSection(context, "Section");
        DeckSection section = firstSection();
        activity.refreshForTest();
        activity.openStructuralRenameForTest(section);
        EditText sectionName = firstEditText(activity);
        assertSingleLineDone(sectionName);
        sectionName.setText("Renamed section");
        sectionName.onEditorAction(EditorInfo.IME_ACTION_DONE);
        assertEquals("Renamed section", firstSection().title);

        DeckLayoutStore.addFolder(context, "Folder");
        DeckFolder folder = firstFolder();
        activity.refreshForTest();
        activity.openStructuralRenameForTest(folder);
        EditText folderName = firstEditText(activity);
        assertSingleLineDone(folderName);
        folderName.setText("Renamed folder");
        folderName.onEditorAction(EditorInfo.IME_ACTION_DONE);
        assertEquals("Renamed folder", firstFolder().title);

        DeckTile tile = DeckLayoutStore.deck(context).allTiles().get(0);
        activity.refreshForTest();
        activity.openTileRenameForTest(tile);
        EditText tileName = firstEditText(activity);
        assertSingleLineDone(tileName);
        tileName.setText("Renamed tile");
        tileName.onEditorAction(EditorInfo.IME_ACTION_DONE);
        assertEquals("Renamed tile", DeckLayoutStore.deck(context).allTiles().get(0)
                .config(DeckTile.CONFIG_TITLE));
    }

    @Test public void promptEditorKeepsMultilineMeaningWhileSharingImeSafety() {
        DeckActivity activity = deck();
        activity.openPromptEditorForTest(null);
        List<EditText> fields = editTexts(activity.getWindow().getDecorView());

        assertEquals(2, fields.size());
        assertEquals(EditorInfo.IME_ACTION_NEXT, fields.get(0).getImeOptions()
                & EditorInfo.IME_MASK_ACTION);
        assertFalse(fields.get(1).isSingleLine());
        assertNotNull(activity.sheetScrollForTest());
    }

    @Test public void edgeToEdgeRootTracksImeInsetsAndClearsThemWhenImeCloses() {
        DeckActivity activity = deck();
        int softInput = activity.getWindow().getAttributes().softInputMode;
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                softInput & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);

        dispatchInsets(activity.rootForTest(), 24, 420);
        assertEquals(420, activity.rootForTest().getPaddingBottom());
        dispatchInsets(activity.rootForTest(), 24, 0);
        assertEquals(24, activity.rootForTest().getPaddingBottom());
        dispatchInsets(activity.rootForTest(), 24, 360);
        assertEquals(360, activity.rootForTest().getPaddingBottom());
    }

    @Test @Config(sdk = 35, qualifiers = "sw800dp-w1280dp-h800dp")
    public void tabletLayoutManagerUsesACappedAdaptiveSheet() {
        DeckActivity activity = deck();
        activity.openLayoutManagerForTest();

        assertNotNull(activity.sheetPanelForTest());
        assertTrue(activity.sheetPanelForTest().getLayoutParams().width
                <= UiKit.dp(activity, 640));
        assertTrue(activity.sheetPanelForTest().getLayoutParams().width
                < activity.getResources().getDisplayMetrics().widthPixels);
    }

    private static void dispatchInsets(View root, int barsBottom, int imeBottom) {
        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 0, 0, barsBottom))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, imeBottom))
                .setVisible(WindowInsets.Type.ime(), imeBottom > 0)
                .build();
        root.dispatchApplyWindowInsets(insets);
    }

    private static void assertSingleLineDone(EditText field) {
        assertNotNull(field);
        assertTrue(field.isSingleLine());
        assertEquals(EditorInfo.IME_ACTION_DONE,
                field.getImeOptions() & EditorInfo.IME_MASK_ACTION);
    }

    private EditText firstEditText(DeckActivity activity) {
        List<EditText> fields = editTexts(activity.getWindow().getDecorView());
        return fields.isEmpty() ? null : fields.get(0);
    }

    private DeckSection firstSection() {
        for (DeckItem item : DeckLayoutStore.deck(context).items) {
            if (item instanceof DeckSection) return (DeckSection) item;
        }
        return null;
    }

    private DeckFolder firstFolder() {
        for (DeckItem item : DeckLayoutStore.deck(context).items) {
            if (item instanceof DeckFolder) return (DeckFolder) item;
        }
        return null;
    }

    private static List<EditText> editTexts(View root) {
        List<EditText> out = new ArrayList<>();
        collectEditTexts(root, out);
        return out;
    }

    private static void collectEditTexts(View view, List<EditText> out) {
        if (view instanceof EditText) out.add((EditText) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectEditTexts(group.getChildAt(i), out);
            }
        }
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView findContainingText(View view, String text) {
        if (view instanceof TextView
                && String.valueOf(((TextView) view).getText()).contains(text)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findContainingText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findDescription(View view, String description) {
        if (view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findContainingDescription(View view, String description) {
        if (String.valueOf(view.getContentDescription()).contains(description)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findContainingDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View clickableAncestor(View view) {
        View current = view;
        while (current != null && !current.isClickable()) {
            if (!(current.getParent() instanceof View)) return null;
            current = (View) current.getParent();
        }
        return current;
    }

}

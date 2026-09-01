package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowValueAnimator;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The app-wide contract for Orbit-owned transient windows.
 *
 * <p>This is deliberately a source audit as well as a Robolectric test. An AlertDialog that is
 * merely created cannot be found at runtime without driving the exact product path that owns it,
 * and that is how the populated-branch confirmation once regressed: it was styled but never
 * shown. The audit follows each concrete builder to the same local dialog variable, then requires
 * that variable to be styled and shown before the next creation site. A style call elsewhere in
 * the file therefore cannot hide an unstyled dialog.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class OrbitDialogContractTest {
    private static final Pattern ALERT_BUILDER = Pattern.compile(
            "new\\s+(?:android\\.app\\.)?AlertDialog\\s*\\.\\s*Builder\\s*\\(");
    private static final Pattern DIRECT_DIALOG_PREFIX = Pattern.compile(
            "AlertDialog\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*$");
    private static final Pattern BUILDER_PREFIX = Pattern.compile(
            "AlertDialog\\s*\\.\\s*Builder\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*$");

    private ActivityController<Activity> controller;
    private Activity activity;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        Prefs.get(activity).edit().clear().commit();
        UiKit.syncTheme(activity);
        ShadowDialog.reset();
    }

    @After public void tearDown() {
        ShadowValueAnimator.reset();
        if (controller != null) controller.pause().stop().destroy();
    }

    // ---- the shared runtime contract -----------------------------------------------------------

    @Test public void normalInformationalDialogUsesCanonicalMotionAndLiveAppearance() {
        Prefs.get(activity).edit()
                .putString(Prefs.ACCENT, "mint")
                .putString(Prefs.APP_FONT, "monospace")
                .putBoolean(Prefs.AMOLED_MODE, true)
                .commit();
        UiKit.syncTheme(activity);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Information")
                .setMessage("Orbit keeps the established appearance contract.")
                .setPositiveButton("OK", null)
                .create();
        UiKit.styleOrbitDialog(dialog, activity, false);
        dialog.show();

        View decor = dialog.getWindow().getDecorView();
        assertCanonicalEntranceIsPrepared(dialog, decor);
        WindowGeometry before = WindowGeometry.of(dialog);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue(dialog.isShowing());
        assertCanonicalEntranceFinished(decor);
        assertEquals("Orbit's content animation must not change the dialog window bounds",
                before, WindowGeometry.of(dialog));
        assertFalse(dialog.getWindow().getDecorView().isForceDarkAllowed());
        assertEquals("normal actions follow the live Accent",
                UiKit.accent(activity), dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .getCurrentTextColor());
        TextView message = dialog.findViewById(android.R.id.message);
        assertNotNull(message);
        assertEquals("the selected app font reaches dialog content",
                Typeface.MONOSPACE, message.getTypeface());
        assertEquals("AMOLED keeps true black for the app canvas", Color.BLACK, UiKit.BG);
        assertNotNull("the raised dialog surface remains present in AMOLED",
                dialog.getWindow().getDecorView().getBackground());
    }

    @Test public void destructiveDialogKeepsCanonicalMotionAndRestrainedDangerAction() {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Delete item?")
                .setMessage("This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", null)
                .create();
        UiKit.styleOrbitDialog(dialog, activity, true);
        dialog.show();
        View decor = dialog.getWindow().getDecorView();
        assertCanonicalEntranceIsPrepared(dialog, decor);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue(dialog.isShowing());
        assertCanonicalEntranceFinished(decor);
        assertEquals(UiKit.DANGER,
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor());
        assertEquals(UiKit.accent(activity),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getCurrentTextColor());
    }

    @Test public void reducedMotionStillLeavesTheDialogUsableAndFrameworkControlled()
            throws Exception {
        Method setScale = ShadowValueAnimator.class.getDeclaredMethod("setDurationScale", float.class);
        setScale.setAccessible(true);
        setScale.invoke(null, 0f);
        assertFalse("the shared reduced-motion check must observe the Android setting",
                ValueAnimator.areAnimatorsEnabled());
        assertFalse(UiKit.animationsEnabled());

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Reduced motion")
                .setPositiveButton("OK", null)
                .create();
        UiKit.styleOrbitDialog(dialog, activity, false);
        dialog.show();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue("turning animations off must never prevent a dialog from appearing",
                dialog.isShowing());
        assertEquals("dialogs keep the geometry-stable, fade-only exit style",
                R.style.OrbitDialogWindowAnimation,
                dialog.getWindow().getAttributes().windowAnimations);
        assertCanonicalEntranceFinished(dialog.getWindow().getDecorView());
    }

    // ---- the exact Galaxy warning and its system handoff --------------------------------------

    @Test public void removeOrbitLocalWarningIsOrbitOwnedStyledShownAndHandsOffCleanly() {
        ActivityController<LocalAiActivity> localController =
                Robolectric.buildActivity(LocalAiActivity.class).setup();
        try {
            localController.get().confirmRemoveOrbitLocal();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertEquals("Remove Orbit Local?", Shadows.shadowOf(dialog).getTitle().toString());
            assertTrue("the Orbit warning must actually reach the screen", dialog.isShowing());
            assertCanonicalEntranceIsPrepared(dialog, dialog.getWindow().getDecorView());
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
            assertCanonicalEntranceFinished(dialog.getWindow().getDecorView());
            assertEquals(UiKit.DANGER,
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor());

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals("only the Orbit exit is suppressed before Android owns the next window",
                    0, dialog.getWindow().getAttributes().windowAnimations);
            assertFalse(dialog.isShowing());
            ShadowLooper.idleMainLooper();
        } finally {
            localController.pause().stop().destroy();
        }
    }

    @Test public void componentOnlyWarningUsesTheSameDestructiveHandoffContract() {
        ActivityController<LocalAiActivity> localController =
                Robolectric.buildActivity(LocalAiActivity.class).setup();
        try {
            localController.get().confirmUninstallComponent();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertEquals("Uninstall the component?",
                    Shadows.shadowOf(dialog).getTitle().toString());
            assertTrue(dialog.isShowing());
            assertCanonicalEntranceIsPrepared(dialog, dialog.getWindow().getDecorView());
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
            assertCanonicalEntranceFinished(dialog.getWindow().getDecorView());
            assertEquals(UiKit.DANGER,
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor());

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(0, dialog.getWindow().getAttributes().windowAnimations);
            assertFalse(dialog.isShowing());
            ShadowLooper.idleMainLooper();
        } finally {
            localController.pause().stop().destroy();
        }
    }

    @Test public void deleteModelWarningUsesTheSharedStableWindowEntrance() throws Exception {
        ActivityController<LocalAiActivity> localController =
                Robolectric.buildActivity(LocalAiActivity.class).setup();
        try {
            Method confirm = LocalAiActivity.class
                    .getDeclaredMethod("confirmDeleteModel", boolean.class);
            confirm.setAccessible(true);
            confirm.invoke(localController.get(), false);

            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertEquals("Remove Qwen 2.5 (1.5B)?",
                    Shadows.shadowOf(dialog).getTitle().toString());
            assertTrue(dialog.isShowing());
            assertCanonicalEntranceIsPrepared(dialog, dialog.getWindow().getDecorView());
            WindowGeometry before = WindowGeometry.of(dialog);
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
            assertCanonicalEntranceFinished(dialog.getWindow().getDecorView());
            assertEquals(before, WindowGeometry.of(dialog));
            assertEquals(UiKit.DANGER,
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).getCurrentTextColor());
        } finally {
            localController.pause().stop().destroy();
        }
    }

    @Test public void systemInstallerUninstallerPermissionsAndPickersRemainSystemOwned() {
        String request = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitLocalUninstaller.java");
        String receiver = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitLocalUninstallReceiver.java");
        String updater = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitUpdater.java");
        String componentInstaller = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitLocalInstaller.java");
        String pickerBridge = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/AttachmentPickerActivity.java");
        String calendarBridge = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/CalendarPermissionActivity.java");
        String manifest = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/AndroidManifest.xml");

        assertTrue(request.contains("getPackageInstaller()"));
        assertTrue(request.contains("installer.uninstall(OrbitLocalComponent.PACKAGE"));
        assertTrue(receiver.contains("Intent.EXTRA_INTENT"));
        assertTrue(receiver.contains("context.startActivity(confirm)"));
        assertFalse("Orbit must not clone Android's uninstall confirmation",
                receiver.contains("new AlertDialog.Builder"));
        assertFalse("Orbit must not try to paint Android's package-installer window",
                receiver.contains("styleOrbitDialog"));
        assertTrue(updater.contains("Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES"));
        assertTrue(updater.contains("activity.startActivity(install)"));
        assertTrue(componentInstaller.contains("new Intent(Intent.ACTION_VIEW)"));
        assertTrue(componentInstaller.contains("activity.startActivity(install)"));
        assertTrue(pickerBridge.contains("Intent.ACTION_OPEN_DOCUMENT"));
        assertTrue(pickerBridge.contains("requestPermissions("));
        assertFalse(componentInstaller.contains("new AlertDialog.Builder"));
        assertFalse(pickerBridge.contains("new AlertDialog.Builder"));

        // Calendar permission is Android's window, exactly like the picker's. Orbit's own
        // confirmation happens before this bridge opens and never overlaps it.
        assertTrue(calendarBridge.contains("requestPermissions("));
        assertFalse("Orbit must not draw its own Calendar permission prompt",
                calendarBridge.contains("new AlertDialog.Builder"));
        assertFalse(calendarBridge.contains("styleOrbitDialog"));

        // Picker, calendar permission, widget action, and now the external Share doorway. All four
        // are invisible bridges rather than pages, and all four keep the dedicated non-page theme.
        assertTrue("invisible picker, calendar, widget and share bridges keep their non-page theme",
                count(manifest, "android:theme=\"@style/Theme.Orbit.Bridge\"") == 4);

        // The exported Share doorway draws nothing of its own. It validates, stages, and starts a
        // real conversation; a dialog here would be Orbit painting a window over another app's
        // share sheet.
        String shareBridge = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ShareToOrbitActivity.java");
        assertFalse(shareBridge.contains("new AlertDialog.Builder"));
        assertFalse(shareBridge.contains("styleOrbitDialog"));
    }

    // ---- resource and popup motion --------------------------------------------------------------

    @Test public void popupAndDialogMotionResourcesStaySeparateAndRestrained() {
        String styles = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/res/values/styles.xml");
        String enter = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/res/anim/orbit_popup_enter.xml");
        String exit = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/res/anim/orbit_popup_exit.xml");
        String dialogExit = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/res/anim/orbit_dialog_exit.xml");
        String uiKit = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/UiKit.java");

        assertTrue(styles.contains("<style name=\"OrbitPopupAnimation\">"));
        assertTrue(styles.contains("@anim/orbit_popup_enter"));
        assertTrue(styles.contains("@anim/orbit_popup_exit"));
        assertTrue(enter.contains("android:fromXScale=\"0.985\""));
        assertTrue(enter.contains("android:toXScale=\"1.0\""));
        assertTrue(enter.contains("android:duration=\"115\""));
        assertTrue(exit.contains("android:toXScale=\"0.992\""));
        assertTrue(exit.contains("android:duration=\"85\""));

        String motion = (enter + exit).toLowerCase();
        assertFalse("dialog motion must not bounce", motion.contains("bounce"));
        assertFalse("dialog motion must not overshoot", motion.contains("overshoot"));
        assertFalse("popup windows must not stretch or slide", motion.contains("<translate"));

        assertTrue(styles.contains("<style name=\"OrbitDialogWindowAnimation\">"));
        assertTrue(styles.contains("@anim/orbit_dialog_exit"));
        assertFalse("the AlertDialog window exit must not scale", dialogExit.contains("<scale"));
        assertFalse("the AlertDialog window exit must not move", dialogExit.contains("<translate"));
        assertTrue("the dialog window must not reuse PopupWindow's scale animation",
                uiKit.contains("window.setWindowAnimations(R.style.OrbitDialogWindowAnimation)"));
        assertTrue("the canonical entrance belongs to the dialog decor view",
                uiKit.contains("animateOrbitDialogEntrance(dialog)"));
    }

    @Test public void popupWindowsUseTheSharedMotionAndDialogHandoff() {
        String uiKit = stripNonCode(ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/UiKit.java"));
        Matcher creations = Pattern.compile("new\\s+PopupWindow\\s*\\(").matcher(uiKit);
        List<Integer> starts = new ArrayList<>();
        while (creations.find()) starts.add(creations.start());
        assertEquals("all Orbit PopupWindows live in the three shared UiKit builders", 3,
                starts.size());
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : uiKit.length();
            String region = uiKit.substring(starts.get(i), end);
            assertTrue("PopupWindow at UiKit.java:" + lineAt(uiKit, starts.get(i))
                            + " bypasses OrbitPopupAnimation",
                    Pattern.compile("popup\\s*\\.\\s*setAnimationStyle\\s*\\(\\s*"
                            + "R\\.style\\.OrbitPopupAnimation\\s*\\)").matcher(region).find());
        }
        assertTrue(uiKit.contains("showOrbitMenuWithDialogHandoff"));
        assertTrue(uiKit.contains("popup.setAnimationStyle(0)"));

        String routines = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RoutinesActivity.java");
        assertTrue("the popup action that opens Delete must keep the completion-based handoff",
                routines.contains("UiKit.showOrbitMenuWithDialogHandoff"));
    }

    // ---- repository-wide AlertDialog audit -----------------------------------------------------

    @Test public void everyAppOwnedAlertDialogIsStyledAndShownAtItsOwnCreationSite()
            throws IOException {
        int creations = 0;
        for (Path file : mainJavaFiles()) {
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String code = stripNonCode(source);
            Matcher matcher = ALERT_BUILDER.matcher(code);
            List<Integer> starts = new ArrayList<>();
            while (matcher.find()) starts.add(matcher.start());
            creations += starts.size();

            for (int i = 0; i < starts.size(); i++) {
                int start = starts.get(i);
                int boundary = i + 1 < starts.size() ? starts.get(i + 1) : code.length();
                DialogCreation creation = resolveCreation(file, code, start, boundary);
                String region = code.substring(creation.afterCreate, boundary);
                String variable = Pattern.quote(creation.variable);
                boolean styled = Pattern.compile(
                        "(?:(?:UiKit\\s*\\.\\s*)?styleOrbitDialog|styleDialog)"
                                + "\\s*\\(\\s*" + variable + "\\b")
                        .matcher(region).find();
                boolean shown = Pattern.compile(variable + "\\s*\\.\\s*show\\s*\\(")
                        .matcher(region).find();
                String site = relative(file) + ":" + lineAt(code, start);
                assertTrue(site + " creates " + creation.variable
                        + " without the canonical Orbit dialog styling path", styled);
                assertTrue(site + " styles " + creation.variable
                        + " but never calls " + creation.variable + ".show()", shown);
            }
        }
        assertTrue("the source audit unexpectedly found no app-owned AlertDialogs", creations > 0);
    }

    @Test public void dialogWrappersResolveToUiKitAndPrepareIsNotUsedAsAShortcut()
            throws IOException {
        Pattern wrapper = Pattern.compile(
                "(?m)^\\s*(?:(?:public|protected|private|static|final)\\s+)*void\\s+"
                        + "(styleOrbitDialog|styleDialog)\\s*\\([^)]*\\)\\s*\\{");
        for (Path file : mainJavaFiles()) {
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String code = stripNonCode(source);
            if (!file.getFileName().toString().equals("UiKit.java")) {
                assertFalse(relative(file) + " bypasses the full styling contract with prepareOrbitDialog",
                        code.contains("UiKit.prepareOrbitDialog"));
                Matcher definitions = wrapper.matcher(code);
                while (definitions.find()) {
                    int close = matchingBrace(code, code.indexOf('{', definitions.start()));
                    assertTrue("wrapper " + definitions.group(1) + " at " + relative(file) + ":"
                                    + lineAt(code, definitions.start()) + " does not reach UiKit",
                            code.substring(definitions.start(), close + 1)
                                    .contains("UiKit.styleOrbitDialog"));
                }
            }
        }
    }

    private static DialogCreation resolveCreation(Path file, String code, int start, int boundary) {
        int prefixStart = Math.max(code.lastIndexOf(';', start), code.lastIndexOf('{', start)) + 1;
        String prefix = code.substring(prefixStart, start);
        Matcher direct = DIRECT_DIALOG_PREFIX.matcher(prefix);
        String variable;
        int createStart;
        if (direct.find()) {
            variable = direct.group(1);
            createStart = start;
        } else {
            Matcher builder = BUILDER_PREFIX.matcher(prefix);
            if (!builder.find()) {
                fail(relative(file) + ":" + lineAt(code, start)
                        + " creates an inline AlertDialog that cannot be audited; assign the builder "
                        + "and resulting dialog to local variables");
                return null;
            }
            String builderVariable = Pattern.quote(builder.group(1));
            Matcher created = Pattern.compile("AlertDialog\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
                    + "\\s*=\\s*" + builderVariable + "\\s*\\.\\s*create\\s*\\(\\s*\\)")
                    .matcher(code.substring(start, boundary));
            if (!created.find()) {
                fail(relative(file) + ":" + lineAt(code, start)
                        + " creates an AlertDialog.Builder but no auditable AlertDialog variable");
                return null;
            }
            variable = created.group(1);
            createStart = start + created.start();
        }
        Matcher createCall = Pattern.compile("\\.\\s*create\\s*\\(\\s*\\)")
                .matcher(code.substring(createStart, boundary));
        if (!createCall.find()) {
            fail(relative(file) + ":" + lineAt(code, start)
                    + " does not finish the AlertDialog with create()");
            return null;
        }
        return new DialogCreation(variable, createStart + createCall.end());
    }

    private static int matchingBrace(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            if (code.charAt(i) == '{') depth++;
            else if (code.charAt(i) == '}' && --depth == 0) return i;
        }
        fail("unbalanced Java source around character " + open);
        return code.length() - 1;
    }

    private static List<Path> mainJavaFiles() throws IOException {
        Path root = repositoryRoot();
        List<Path> roots = new ArrayList<>();
        roots.add(root.resolve("app/src/main/java"));
        roots.add(root.resolve("local/src/main/java"));
        List<Path> files = new ArrayList<>();
        for (Path sourceRoot : roots) {
            if (!Files.isDirectory(sourceRoot)) continue;
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                files.addAll(walk.filter(path -> Files.isRegularFile(path)
                                && path.getFileName().toString().endsWith(".java"))
                        .sorted().collect(Collectors.toList()));
            }
        }
        return files;
    }

    private static Path repositoryRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path path = start; path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("settings.gradle"))) return path;
        }
        throw new AssertionError("repository root was not found above " + start);
    }

    private static String relative(Path file) {
        return repositoryRoot().relativize(file).toString().replace('\\', '/');
    }

    private static int lineAt(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) found++;
        return found;
    }

    private static void assertCanonicalEntranceIsPrepared(AlertDialog dialog, View decor) {
        assertEquals("AlertDialog must not use PopupWindow's geometry-changing animation",
                R.style.OrbitDialogWindowAnimation,
                dialog.getWindow().getAttributes().windowAnimations);
        assertEquals("dialog content must be hidden before its first visible frame",
                0f, decor.getAlpha(), 0.0001f);
        assertEquals(UiKit.ORBIT_DIALOG_ENTER_SCALE, decor.getScaleX(), 0.0001f);
        assertEquals(UiKit.ORBIT_DIALOG_ENTER_SCALE, decor.getScaleY(), 0.0001f);
        assertEquals(0f, decor.getTranslationX(), 0.0001f);
        assertEquals(0f, decor.getTranslationY(), 0.0001f);
    }

    private static void assertCanonicalEntranceFinished(View decor) {
        assertEquals(1f, decor.getAlpha(), 0.0001f);
        assertEquals(1f, decor.getScaleX(), 0.0001f);
        assertEquals(1f, decor.getScaleY(), 0.0001f);
        assertEquals(0f, decor.getTranslationX(), 0.0001f);
        assertEquals(0f, decor.getTranslationY(), 0.0001f);
    }

    /** Removes comments and literals without changing offsets or line numbers. */
    private static String stripNonCode(String source) {
        StringBuilder out = new StringBuilder(source.length());
        final int code = 0, line = 1, block = 2, string = 3, character = 4;
        int state = code;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (state == code) {
                if (c == '/' && next == '/') {
                    out.append(' ').append(' ');
                    i++;
                    state = line;
                } else if (c == '/' && next == '*') {
                    out.append(' ').append(' ');
                    i++;
                    state = block;
                } else if (c == '"') {
                    out.append(' ');
                    state = string;
                    escaped = false;
                } else if (c == '\'') {
                    out.append(' ');
                    state = character;
                    escaped = false;
                } else {
                    out.append(c);
                }
            } else if (state == line) {
                if (c == '\n') {
                    out.append('\n');
                    state = code;
                } else out.append(' ');
            } else if (state == block) {
                if (c == '*' && next == '/') {
                    out.append(' ').append(' ');
                    i++;
                    state = code;
                } else out.append(c == '\n' ? '\n' : ' ');
            } else {
                out.append(c == '\n' ? '\n' : ' ');
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if ((state == string && c == '"') || (state == character && c == '\'')) {
                    state = code;
                }
            }
        }
        return out.toString();
    }

    private static final class DialogCreation {
        final String variable;
        final int afterCreate;

        DialogCreation(String variable, int afterCreate) {
            this.variable = variable;
            this.afterCreate = afterCreate;
        }
    }

    private static final class WindowGeometry {
        final int x;
        final int y;
        final int width;
        final int height;
        final int gravity;

        private WindowGeometry(WindowManager.LayoutParams attributes) {
            x = attributes.x;
            y = attributes.y;
            width = attributes.width;
            height = attributes.height;
            gravity = attributes.gravity;
        }

        static WindowGeometry of(AlertDialog dialog) {
            return new WindowGeometry(dialog.getWindow().getAttributes());
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof WindowGeometry)) return false;
            WindowGeometry geometry = (WindowGeometry) other;
            return x == geometry.x && y == geometry.y && width == geometry.width
                    && height == geometry.height && gravity == geometry.gravity;
        }

        @Override public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + width;
            result = 31 * result + height;
            return 31 * result + gravity;
        }

        @Override public String toString() {
            return "WindowGeometry{x=" + x + ", y=" + y + ", width=" + width
                    + ", height=" + height + ", gravity=" + gravity + "}";
        }
    }
}

package com.orbit.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The user's chosen image picker, stored as an exact target rather than a package to re-resolve.
 *
 * <p>Resolving a package into an Activity later gave different answers in different contexts: the
 * Side-button overlay reaches pickers through a bridge Activity in the assistant's own task, where
 * a lookup that succeeds in the companion app could come back empty, and the overlay then opened
 * the system picker instead. The selection now records the exact Activity and the action that
 * Settings already proved works, so every surface launches the same component with no further
 * interpretation.
 */
public final class GalleryAppPreference {
    public static final String SYSTEM_PACKAGE = "";
    private static final String KEY_COMPONENT = "gallery_app_component";
    private static final String KEY_ACTION = "gallery_app_action";

    /** A chosen picker: either Android's system picker, or one exact Activity. */
    public static final class Target {
        public final String packageName;
        public final String className;
        public final String action;

        Target(String packageName, String className, String action) {
            this.packageName = packageName == null ? "" : packageName.trim();
            this.className = className == null ? "" : className.trim();
            this.action = action == null || action.trim().isEmpty()
                    ? Intent.ACTION_PICK : action.trim();
        }

        public static Target system() {
            return new Target("", "", Intent.ACTION_PICK);
        }

        public boolean isSystem() {
            return packageName.isEmpty();
        }

        /** True when an app was chosen but its exact Activity is not known yet. */
        public boolean needsResolution() {
            return !packageName.isEmpty() && className.isEmpty();
        }
    }

    public static final class Option {
        public final String packageName;
        public final String className;
        public final String action;
        public final String label;

        Option(String packageName, String className, String action, String label) {
            this.packageName = packageName == null ? "" : packageName;
            this.className = className == null ? "" : className;
            this.action = action == null ? Intent.ACTION_PICK : action;
            this.label = label == null || label.trim().isEmpty() ? this.packageName : label.trim();
        }

        Target toTarget() {
            return new Target(packageName, className, action);
        }
    }

    private GalleryAppPreference() {}

    public static List<Option> options(Context context) {
        List<Option> result = new ArrayList<>();
        result.add(new Option(SYSTEM_PACKAGE, "", Intent.ACTION_PICK, "System picker"));
        if (context == null) return result;

        PackageManager packageManager = context.getPackageManager();
        Map<String, Option> byPackage = new LinkedHashMap<>();
        collect(packageManager, Intent.ACTION_PICK, context.getPackageName(), byPackage);
        collect(packageManager, Intent.ACTION_GET_CONTENT, context.getPackageName(), byPackage);
        List<Option> installed = new ArrayList<>(byPackage.values());
        installed.sort(Comparator.comparing(option -> option.label.toLowerCase(Locale.US)));
        result.addAll(installed);
        return result;
    }

    /**
     * Writes the whole selection in one commit, so a force stop straight afterwards can never
     * leave a new package beside a previous app's component or action.
     */
    public static void setPreferredOption(Context context, Option option) {
        if (context == null) return;
        SharedPreferences.Editor editor = Prefs.get(context).edit();
        if (option == null || option.packageName.trim().isEmpty()) {
            editor.remove(Prefs.GALLERY_APP_PACKAGE).remove(KEY_COMPONENT).remove(KEY_ACTION);
        } else {
            editor.putString(Prefs.GALLERY_APP_PACKAGE, option.packageName.trim())
                    .putString(KEY_COMPONENT, option.className.trim())
                    .putString(KEY_ACTION, option.action.trim());
        }
        editor.commit();
    }

    /** Kept for callers that only know a package; resolves it to an exact target first. */
    public static void setPreferredPackage(Context context, String packageName) {
        if (context == null) return;
        String clean = packageName == null ? "" : packageName.trim();
        if (clean.isEmpty()) {
            setPreferredOption(context, null);
            return;
        }
        Option discovered = discover(context, clean);
        setPreferredOption(context, discovered != null ? discovered
                : new Option(clean, "", Intent.ACTION_PICK, clean));
    }

    /**
     * The stored selection, migrating an older package-only choice to an exact target the first
     * time its Activity can be discovered. A choice that cannot be resolved right now is returned
     * unresolved rather than being erased or quietly turned into the system picker.
     */
    public static Target storedTarget(Context context) {
        if (context == null) return Target.system();
        SharedPreferences prefs = Prefs.get(context);
        String packageName = prefs.getString(Prefs.GALLERY_APP_PACKAGE, "").trim();
        if (packageName.isEmpty()) return Target.system();

        String className = prefs.getString(KEY_COMPONENT, "").trim();
        String action = prefs.getString(KEY_ACTION, "").trim();
        if (!className.isEmpty()) return new Target(packageName, className, action);

        Option discovered = discover(context, packageName);
        if (discovered == null) return new Target(packageName, "", Intent.ACTION_PICK);
        setPreferredOption(context, discovered);
        return discovered.toTarget();
    }

    /** Package of the stored selection, whether or not its Activity is known. */
    public static String storedPackage(Context context) {
        if (context == null) return SYSTEM_PACKAGE;
        return Prefs.get(context).getString(Prefs.GALLERY_APP_PACKAGE, "").trim();
    }

    /** Intent for an exact target, or null when it names an Activity that cannot be built. */
    public static Intent intentForTarget(Target target) {
        if (target == null || target.isSystem() || target.needsResolution()) return null;
        Intent intent = imagePickIntent(target.action);
        intent.setComponent(new ComponentName(target.packageName, target.className));
        return intent;
    }

    /** Picker Intent for the current selection, used by the full chat. */
    public static Intent createIntent(Context context) {
        Target target = storedTarget(context);
        Intent explicit = intentForTarget(target);
        return explicit == null ? systemPickerIntent() : explicit;
    }

    public static Intent systemPickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    /** Finds the exact picker Activity a package offers, preferring ACTION_PICK. */
    private static Option discover(Context context, String packageName) {
        if (context == null || packageName == null || packageName.trim().isEmpty()) return null;
        Map<String, Option> byPackage = new LinkedHashMap<>();
        collect(context.getPackageManager(), Intent.ACTION_PICK, context.getPackageName(), byPackage);
        Option pick = byPackage.get(packageName);
        if (pick != null) return pick;
        byPackage.clear();
        collect(context.getPackageManager(), Intent.ACTION_GET_CONTENT, context.getPackageName(), byPackage);
        return byPackage.get(packageName);
    }

    private static Intent imagePickIntent(String action) {
        Intent intent = new Intent(action);
        if (Intent.ACTION_PICK.equals(action)) {
            intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        } else {
            intent.setType("image/*");
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private static void collect(PackageManager packageManager, String action, String ownPackage,
                                Map<String, Option> output) {
        List<ResolveInfo> matches;
        try {
            matches = packageManager.queryIntentActivities(
                    imagePickIntent(action), PackageManager.MATCH_DEFAULT_ONLY);
        } catch (Exception ignored) { return; }
        for (ResolveInfo match : matches) {
            if (match == null || match.activityInfo == null) continue;
            String packageName = match.activityInfo.packageName;
            String className = match.activityInfo.name;
            if (packageName == null || className == null) continue;
            if (packageName.equals(ownPackage) || output.containsKey(packageName)) continue;
            CharSequence loaded = match.loadLabel(packageManager);
            output.put(packageName, new Option(packageName, className, action,
                    loaded == null ? packageName : loaded.toString()));
        }
    }
}

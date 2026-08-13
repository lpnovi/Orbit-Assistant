package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared preferred image-picker discovery and fallback for full chat and Side button. */
public final class GalleryAppPreference {
    public static final String SYSTEM_PACKAGE = "";

    public static final class Option {
        public final String packageName;
        public final String label;

        Option(String packageName, String label) {
            this.packageName = packageName == null ? "" : packageName;
            this.label = label == null || label.trim().isEmpty() ? this.packageName : label.trim();
        }
    }

    private GalleryAppPreference() {}

    public static List<Option> options(Context context) {
        List<Option> result = new ArrayList<>();
        result.add(new Option(SYSTEM_PACKAGE, "System picker"));
        if (context == null) return result;

        PackageManager packageManager = context.getPackageManager();
        Map<String, Option> byPackage = new LinkedHashMap<>();
        collect(packageManager, imagePickIntent(Intent.ACTION_PICK), context.getPackageName(), byPackage);
        collect(packageManager, imagePickIntent(Intent.ACTION_GET_CONTENT), context.getPackageName(), byPackage);
        List<Option> installed = new ArrayList<>(byPackage.values());
        installed.sort(Comparator.comparing(option -> option.label.toLowerCase(java.util.Locale.US)));
        result.addAll(installed);
        return result;
    }

    public static String preferredPackage(Context context) {
        String stored = Prefs.get(context).getString(Prefs.GALLERY_APP_PACKAGE, "").trim();
        if (stored.isEmpty()) return SYSTEM_PACKAGE;
        if (preferredIntent(context, stored) != null) return stored;
        clear(context);
        return SYSTEM_PACKAGE;
    }

    public static void setPreferredPackage(Context context, String packageName) {
        String clean = packageName == null ? "" : packageName.trim();
        if (clean.isEmpty()) Prefs.get(context).edit().remove(Prefs.GALLERY_APP_PACKAGE).apply();
        else Prefs.get(context).edit().putString(Prefs.GALLERY_APP_PACKAGE, clean).apply();
    }

    public static void clear(Context context) {
        Prefs.get(context).edit().remove(Prefs.GALLERY_APP_PACKAGE).apply();
    }

    /** Returns the selected installed picker, or Android's safe document picker fallback. */
    public static Intent createIntent(Context context) {
        String packageName = preferredPackage(context);
        Intent preferred = preferredIntent(context, packageName);
        return preferred == null ? systemPickerIntent() : preferred;
    }

    public static Intent systemPickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    private static Intent preferredIntent(Context context, String packageName) {
        if (context == null || packageName == null || packageName.trim().isEmpty()) return null;
        PackageManager packageManager = context.getPackageManager();
        Intent pick = imagePickIntent(Intent.ACTION_PICK);
        pick.setPackage(packageName);
        if (pick.resolveActivity(packageManager) != null) return pick;
        Intent content = imagePickIntent(Intent.ACTION_GET_CONTENT);
        content.setPackage(packageName);
        return content.resolveActivity(packageManager) == null ? null : content;
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

    private static void collect(PackageManager packageManager, Intent intent, String ownPackage,
                                Map<String, Option> output) {
        List<ResolveInfo> matches;
        try { matches = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY); }
        catch (Exception ignored) { return; }
        for (ResolveInfo match : matches) {
            if (match == null || match.activityInfo == null) continue;
            String packageName = match.activityInfo.packageName;
            if (packageName == null || packageName.equals(ownPackage) || output.containsKey(packageName)) continue;
            CharSequence loaded = match.loadLabel(packageManager);
            output.put(packageName, new Option(packageName,
                    loaded == null ? packageName : loaded.toString()));
        }
    }
}

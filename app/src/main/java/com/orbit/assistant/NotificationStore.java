package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NotificationStore {
    private static final String FILE = "orbit_notification_history";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_BLOCKED = "blocked_packages";
    private static final String KEY_KNOWN_APPS = "known_apps";
    private static final int MAX_ITEMS = 700;

    private NotificationStore() {}

    public static final class Item {
        public final String key;
        public final String packageName;
        public final String appLabel;
        public final String title;
        public final String text;
        public final String subText;
        public final String conversationTitle;
        public final long postedAt;
        public final long removedAt;
        public final boolean ongoing;

        Item(String key, String packageName, String appLabel, String title, String text,
             String subText, String conversationTitle, long postedAt, long removedAt,
             boolean ongoing) {
            this.key = safe(key);
            this.packageName = safe(packageName);
            this.appLabel = safe(appLabel);
            this.title = safe(title);
            this.text = safe(text);
            this.subText = safe(subText);
            this.conversationTitle = safe(conversationTitle);
            this.postedAt = postedAt;
            this.removedAt = removedAt;
            this.ongoing = ongoing;
        }

        public String compactBody() {
            String body = !text.isEmpty() ? text : !subText.isEmpty() ? subText : conversationTitle;
            return compact(body, 420);
        }
    }

    public static final class AppSummary {
        public final String packageName;
        public final String appLabel;
        public final int count;
        public final boolean blocked;

        AppSummary(String packageName, String appLabel, int count, boolean blocked) {
            this.packageName = safe(packageName);
            this.appLabel = safe(appLabel);
            this.count = count;
            this.blocked = blocked;
        }
    }

    public static synchronized void rememberKnownApp(Context c, String pkg, String label) {
        if (c == null || safe(pkg).isEmpty() || c.getPackageName().equals(pkg)) return;
        SharedPreferences p = prefs(c);
        JSONObject known = parseObject(p.getString(KEY_KNOWN_APPS, "{}"));
        try {
            known.put(pkg, safe(label).isEmpty() ? pkg : label);
            p.edit().putString(KEY_KNOWN_APPS, known.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized void upsert(Context c, Item item) {
        if (c == null || item == null || item.packageName.isEmpty()) return;
        rememberKnownApp(c, item.packageName, item.appLabel);
        if (c.getPackageName().equals(item.packageName) || isBlocked(c, item.packageName)) return;

        ArrayList<Item> all = new ArrayList<>(read(c));
        int replace = -1;
        for (int i = 0; i < all.size(); i++) {
            if (item.key.equals(all.get(i).key)) {
                replace = i;
                break;
            }
        }
        if (replace >= 0) all.set(replace, item);
        else all.add(item);

        sortNewest(all);
        pruneInMemory(c, all);
        save(c, all);
    }

    public static synchronized void markRemoved(Context c, String key, long removedAt) {
        if (c == null || safe(key).isEmpty()) return;
        ArrayList<Item> all = new ArrayList<>(read(c));
        boolean changed = false;
        for (int i = 0; i < all.size(); i++) {
            Item old = all.get(i);
            if (!key.equals(old.key)) continue;
            all.set(i, new Item(old.key, old.packageName, old.appLabel, old.title,
                    old.text, old.subText, old.conversationTitle, old.postedAt,
                    removedAt, old.ongoing));
            changed = true;
            break;
        }
        if (changed) save(c, all);
    }

    public static synchronized List<Item> all(Context c) {
        ArrayList<Item> all = new ArrayList<>(read(c));
        boolean changed = pruneInMemory(c, all);
        if (changed) save(c, all);
        return all;
    }

    public static synchronized List<Item> between(Context c, long startInclusive, long endExclusive) {
        ArrayList<Item> out = new ArrayList<>();
        for (Item i : all(c)) {
            if (i.postedAt >= startInclusive && i.postedAt < endExclusive &&
                    !isBlocked(c, i.packageName)) out.add(i);
        }
        sortNewest(out);
        return out;
    }

    public static synchronized List<AppSummary> appSummaries(Context c) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> labels = new HashMap<>();

        JSONObject known = parseObject(prefs(c).getString(KEY_KNOWN_APPS, "{}"));
        JSONArray names = known.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String pkg = names.optString(i, "");
                if (pkg.isEmpty()) continue;
                labels.put(pkg, known.optString(pkg, pkg));
                counts.put(pkg, 0);
            }
        }

        for (Item item : all(c)) {
            labels.put(item.packageName, item.appLabel.isEmpty() ? item.packageName : item.appLabel);
            counts.put(item.packageName, counts.getOrDefault(item.packageName, 0) + 1);
        }

        ArrayList<AppSummary> out = new ArrayList<>();
        for (String pkg : labels.keySet()) {
            if (c.getPackageName().equals(pkg)) continue;
            out.add(new AppSummary(pkg, labels.get(pkg), counts.getOrDefault(pkg, 0),
                    isBlocked(c, pkg)));
        }

        Collections.sort(out, (a, b) -> {
            if (a.blocked != b.blocked) return a.blocked ? 1 : -1;
            if (a.count != b.count) return Integer.compare(b.count, a.count);
            return a.appLabel.compareToIgnoreCase(b.appLabel);
        });
        return out;
    }

    public static synchronized boolean isBlocked(Context c, String pkg) {
        return prefs(c).getStringSet(KEY_BLOCKED, Collections.emptySet())
                .contains(safe(pkg));
    }

    public static synchronized void setBlocked(Context c, String pkg, boolean blocked) {
        String pName = safe(pkg);
        if (pName.isEmpty()) return;

        Set<String> next = new HashSet<>(
                prefs(c).getStringSet(KEY_BLOCKED, Collections.emptySet()));
        if (blocked) next.add(pName);
        else next.remove(pName);
        prefs(c).edit().putStringSet(KEY_BLOCKED, next).apply();

        if (blocked) {
            ArrayList<Item> all = new ArrayList<>(read(c));
            all.removeIf(i -> pName.equals(i.packageName));
            save(c, all);
        }
    }

    public static synchronized void clear(Context c) {
        prefs(c).edit().remove(KEY_ITEMS).apply();
    }

    public static synchronized int count(Context c) {
        return all(c).size();
    }

    static synchronized JSONObject backupConfiguration(Context c) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray blocked = new JSONArray();
        for (String pkg : prefs(c).getStringSet(KEY_BLOCKED, Collections.emptySet())) blocked.put(pkg);
        out.put("blockedPackages", blocked);
        out.put("knownApps", parseObject(prefs(c).getString(KEY_KNOWN_APPS, "{}")));
        return out;
    }

    static synchronized boolean restoreBackupConfiguration(Context c, JSONObject values) {
        if (values == null) return false;
        JSONArray blocked = values.optJSONArray("blockedPackages");
        JSONObject known = values.optJSONObject("knownApps");
        if (blocked == null || known == null) return false;
        Set<String> packages = new HashSet<>();
        for (int i = 0; i < blocked.length(); i++) {
            String pkg = blocked.optString(i, "").trim();
            if (pkg.isEmpty()) return false;
            packages.add(pkg);
        }
        return prefs(c).edit().putStringSet(KEY_BLOCKED, packages)
                .putString(KEY_KNOWN_APPS, known.toString()).commit();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static List<Item> read(Context c) {
        ArrayList<Item> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                out.add(new Item(
                        o.optString("key", ""),
                        o.optString("packageName", ""),
                        o.optString("appLabel", ""),
                        o.optString("title", ""),
                        o.optString("text", ""),
                        o.optString("subText", ""),
                        o.optString("conversationTitle", ""),
                        o.optLong("postedAt", 0),
                        o.optLong("removedAt", 0),
                        o.optBoolean("ongoing", false)
                ));
            }
        } catch (Exception ignored) {}
        sortNewest(out);
        return out;
    }

    private static void save(Context c, List<Item> source) {
        JSONArray arr = new JSONArray();
        try {
            int limit = Math.min(MAX_ITEMS, source.size());
            for (int i = 0; i < limit; i++) {
                Item n = source.get(i);
                arr.put(new JSONObject()
                        .put("key", n.key)
                        .put("packageName", n.packageName)
                        .put("appLabel", n.appLabel)
                        .put("title", n.title)
                        .put("text", n.text)
                        .put("subText", n.subText)
                        .put("conversationTitle", n.conversationTitle)
                        .put("postedAt", n.postedAt)
                        .put("removedAt", n.removedAt)
                        .put("ongoing", n.ongoing));
            }
        } catch (Exception ignored) {}
        prefs(c).edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    private static boolean pruneInMemory(Context c, ArrayList<Item> all) {
        long cutoff = System.currentTimeMillis() -
                Prefs.notificationRetentionDays(c) * 24L * 60L * 60L * 1000L;
        int before = all.size();
        all.removeIf(i -> i.postedAt <= 0 || i.postedAt < cutoff ||
                isBlocked(c, i.packageName));
        sortNewest(all);
        while (all.size() > MAX_ITEMS) all.remove(all.size() - 1);
        return before != all.size();
    }

    private static void sortNewest(List<Item> all) {
        all.sort((a, b) -> Long.compare(b.postedAt, a.postedAt));
    }

    private static JSONObject parseObject(String s) {
        try { return new JSONObject(s == null ? "{}" : s); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static String compact(String s, int max) {
        String value = safe(s).replaceAll("\\s+", " ");
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 1)).trim() + "…";
    }
}

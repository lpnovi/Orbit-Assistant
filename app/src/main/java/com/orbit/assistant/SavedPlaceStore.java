package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Durable local store for reusable named locations such as Home, Work, or Gym. */
public final class SavedPlaceStore {
    private static final String PREFS = "orbit_saved_places_v1";
    private static final String KEY = "places";
    private static final int MAX_PLACES = 50;

    public static final class Place {
        public final String id;
        public final String name;
        public final double latitude;
        public final double longitude;
        public final long createdAt;

        public Place(String id, String name, double latitude, double longitude, long createdAt) {
            this.id = id == null ? "" : id.trim();
            this.name = sanitizeName(name);
            this.latitude = latitude;
            this.longitude = longitude;
            this.createdAt = createdAt;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("latitude", latitude)
                    .put("longitude", longitude)
                    .put("createdAt", createdAt);
        }

        static Place fromJson(JSONObject o) {
            if (o == null) return null;
            Place p = new Place(o.optString("id", ""), o.optString("name", ""),
                    o.optDouble("latitude", Double.NaN), o.optDouble("longitude", Double.NaN),
                    o.optLong("createdAt", 0L));
            return valid(p) ? p : null;
        }
    }

    private SavedPlaceStore() {}

    public static Place create(String name, double latitude, double longitude) {
        return new Place(UUID.randomUUID().toString(), name, latitude, longitude, System.currentTimeMillis());
    }

    public static synchronized List<Place> list(Context c) {
        ArrayList<Place> out = new ArrayList<>();
        if (c == null) return out;
        try {
            JSONArray a = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                Place p = Place.fromJson(a.optJSONObject(i));
                if (p != null) out.add(p);
            }
        } catch (Exception ignored) {}
        Collections.sort(out, Comparator.comparing(place -> place.name.toLowerCase(Locale.US)));
        return out;
    }

    public static synchronized Place get(Context c, String id) {
        if (id == null || id.trim().isEmpty()) return null;
        for (Place p : list(c)) if (id.equals(p.id)) return p;
        return null;
    }

    public static synchronized boolean upsert(Context c, Place place) {
        if (c == null || !valid(place)) return false;
        ArrayList<Place> places = new ArrayList<>(list(c));
        for (Place p : places) {
            if (!p.id.equals(place.id) && p.name.equalsIgnoreCase(place.name)) return false;
        }
        boolean replaced = false;
        for (int i = 0; i < places.size(); i++) {
            if (places.get(i).id.equals(place.id)) {
                places.set(i, place);
                replaced = true;
                break;
            }
        }
        if (!replaced) places.add(place);
        while (places.size() > MAX_PLACES) places.remove(places.size() - 1);
        write(c, places);
        return true;
    }

    public static synchronized boolean remove(Context c, String id) {
        if (c == null || id == null || id.trim().isEmpty()) return false;
        ArrayList<Place> places = new ArrayList<>(list(c));
        boolean changed = places.removeIf(place -> id.equals(place.id));
        if (changed) write(c, places);
        return changed;
    }

    public static String sanitizeName(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("\\s+", " ");
        return s.length() > 60 ? s.substring(0, 60).trim() : s;
    }

    public static boolean validCoordinates(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude) && latitude >= -90d && latitude <= 90d &&
                !Double.isNaN(longitude) && !Double.isInfinite(longitude) && longitude >= -180d && longitude <= 180d;
    }

    private static boolean valid(Place p) {
        return p != null && !p.id.isEmpty() && !p.name.isEmpty() && validCoordinates(p.latitude, p.longitude);
    }

    private static void write(Context c, List<Place> places) {
        JSONArray a = new JSONArray();
        if (places != null) for (Place p : places) {
            try { if (valid(p)) a.put(p.toJson()); } catch (Exception ignored) {}
        }
        prefs(c).edit().putString(KEY, a.toString()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

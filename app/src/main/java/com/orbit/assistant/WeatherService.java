package com.orbit.assistant;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic weather helper used before the language model.
 * Weather data comes from Open-Meteo so Orbit can answer weather questions
 * directly in chat without opening a browser or spending model tokens.
 */
public final class WeatherService {
    private static final String GEO = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast";
    private static final Pattern LOCATION_AFTER_IN = Pattern.compile(
            "(?i)(?:weather|forecast|temperature|temp|rain|snow|storm|storms|wind|windy).*?\\b(?:in|for|at|around)\\s+([^?.,]+(?:,\\s*[^?.,]+)?)");
    private static final Pattern LOCATION_BEFORE_WEATHER = Pattern.compile(
            "(?i)^\\s*(?:what(?:'s| is)?|how(?:'s| is)?)?\\s*(?:the\\s+)?(?:weather|forecast|temperature)\\s+(?:like\\s+)?(?:in|for|at)\\s+(.+?)\\s*[?!.]*$");

    private WeatherService() {}

    public static boolean shouldHandle(String prompt, List<AssistantClient.History> history) {
        String p = norm(prompt);
        if (p.isEmpty()) return false;
        if (looksLikeWeatherQuestion(p)) return true;
        if (looksLikeLocationReply(p) && previousTurnAskedForWeatherLocation(history)) return true;
        return false;
    }

    public static AssistantReply handle(Context context, String prompt, List<AssistantClient.History> history) {
        try {
            String p = prompt == null ? "" : prompt.trim();
            String locationQuery = extractLocation(p);
            if (locationQuery.isEmpty() && looksLikeLocationReply(norm(p)) && previousTurnAskedForWeatherLocation(history)) {
                locationQuery = p;
            }

            Place place = null;
            if (!locationQuery.isEmpty()) {
                place = geocode(locationQuery);
                if (place == null) {
                    return text("I couldn't find that location. Try a city and state/country, for example Ann Arbor, Michigan or Dublin, Ireland.");
                }
                Prefs.get(context).edit().putString(Prefs.WEATHER_LOCATION, place.displayName).apply();
            } else if (Prefs.weatherUseDeviceLocation(context) && hasLocationPermission(context)) {
                Location location = currentLocation(context);
                if (location != null) place = new Place(location.getLatitude(), location.getLongitude(), "your current location");
            }

            if (place == null) {
                String saved = Prefs.weatherLocation(context);
                if (!saved.isEmpty()) place = geocode(saved);
            }

            if (place == null) {
                return text("I can give you the weather directly in Orbit now. Tell me a city once, or enable device location for weather in Orbit Settings.");
            }

            Weather w = fetch(place, usesFahrenheit(context));
            if (w == null) return text("I couldn't load the weather right now. Try again in a moment.");
            return text(formatAnswer(place, w, p));
        } catch (Exception e) {
            return text("I couldn't load the weather right now. Try again in a moment.");
        }
    }

    private static AssistantReply text(String s) {
        return new AssistantReply(s == null ? "" : s.replace("—", "-"), java.util.Collections.emptyList());
    }

    private static boolean looksLikeWeatherQuestion(String p) {
        if (p.contains("weather") || p.contains("forecast")) return true;
        if (p.matches(".*\\b(will|is|are|does|do)\\b.*\\b(rain|raining|snow|snowing|storm|storms|windy|hot|cold)\\b.*")) return true;
        if (p.matches(".*\\b(temp|temperature)\\b.*") && containsAny(p, "today", "tonight", "tomorrow", "now", "outside", "current", "this week", "weekend", " in ", " for ")) return true;
        return false;
    }

    private static boolean looksLikeLocationReply(String p) {
        if (p.length() < 2 || p.length() > 80) return false;
        if (p.contains("?") || p.contains("weather") || p.contains("forecast")) return false;
        return p.matches("[a-z0-9 .,'-]+");
    }

    private static boolean previousTurnAskedForWeatherLocation(List<AssistantClient.History> history) {
        if (history == null || history.isEmpty()) return false;
        int start = Math.max(0, history.size() - 3);
        for (int i = history.size() - 1; i >= start; i--) {
            AssistantClient.History h = history.get(i);
            if (h == null || h.content == null || !"assistant".equalsIgnoreCase(h.role)) continue;
            String s = norm(h.content);
            if ((s.contains("weather") && (s.contains("city") || s.contains("location") || s.contains("area"))) ||
                    s.contains("tell me a city")) return true;
        }
        return false;
    }

    private static String extractLocation(String prompt) {
        if (prompt == null) return "";
        Matcher m = LOCATION_AFTER_IN.matcher(prompt);
        if (m.find()) return cleanLocation(m.group(1));
        m = LOCATION_BEFORE_WEATHER.matcher(prompt);
        if (m.find()) return cleanLocation(m.group(1));
        return "";
    }

    private static String cleanLocation(String s) {
        if (s == null) return "";
        String out = s.trim();
        out = out.replaceAll("(?i)\\b(today|tonight|tomorrow|right now|now|this week|this weekend|weekend)\\b.*$", "").trim();
        return out;
    }

    private static Place geocode(String query) throws Exception {
        if (query == null || query.trim().isEmpty()) return null;
        String url = GEO + "?name=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name()) + "&count=1&language=en&format=json";
        JSONObject root = getJson(url);
        JSONArray results = root == null ? null : root.optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        JSONObject r = results.optJSONObject(0);
        if (r == null) return null;
        double lat = r.optDouble("latitude", Double.NaN);
        double lon = r.optDouble("longitude", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        String name = r.optString("name", query.trim());
        String admin = r.optString("admin1", "");
        String country = r.optString("country", "");
        StringBuilder display = new StringBuilder(name);
        if (!admin.isEmpty() && !admin.equalsIgnoreCase(name)) display.append(", ").append(admin);
        if (!country.isEmpty() && !country.equalsIgnoreCase(admin)) display.append(", ").append(country);
        return new Place(lat, lon, display.toString());
    }

    private static Weather fetch(Place place, boolean fahrenheit) throws Exception {
        String tempUnit = fahrenheit ? "fahrenheit" : "celsius";
        String windUnit = fahrenheit ? "mph" : "kmh";
        String url = FORECAST + "?latitude=" + place.lat + "&longitude=" + place.lon +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=7&temperature_unit=" + tempUnit + "&wind_speed_unit=" + windUnit;
        JSONObject root = getJson(url);
        if (root == null) return null;
        JSONObject current = root.optJSONObject("current");
        JSONObject daily = root.optJSONObject("daily");
        if (current == null || daily == null) return null;

        Weather w = new Weather();
        w.fahrenheit = fahrenheit;
        w.currentTemp = current.optDouble("temperature_2m", Double.NaN);
        w.feels = current.optDouble("apparent_temperature", Double.NaN);
        w.currentCode = current.optInt("weather_code", -1);
        w.wind = current.optDouble("wind_speed_10m", Double.NaN);
        w.precip = current.optDouble("precipitation", Double.NaN);
        w.dates = daily.optJSONArray("time");
        w.codes = daily.optJSONArray("weather_code");
        w.highs = daily.optJSONArray("temperature_2m_max");
        w.lows = daily.optJSONArray("temperature_2m_min");
        w.rainChance = daily.optJSONArray("precipitation_probability_max");
        return w;
    }

    private static String formatAnswer(Place place, Weather w, String prompt) {
        String p = norm(prompt);
        String unit = w.fahrenheit ? "°F" : "°C";
        String windUnit = w.fahrenheit ? "mph" : "km/h";
        boolean tomorrowOnly = p.contains("tomorrow") && !p.contains("today");
        boolean week = p.contains("week") || p.contains("next few days") || p.contains("forecast");

        if (tomorrowOnly && w.highs != null && w.highs.length() > 1) {
            return "Tomorrow in " + place.displayName + ": " + condition(codeAt(w.codes, 1)) + ", high " + n(tempAt(w.highs, 1)) + unit +
                    ", low " + n(tempAt(w.lows, 1)) + unit + ", with about a " + percentAt(w.rainChance, 1) + "% chance of precipitation. Weather data: Open-Meteo.";
        }

        StringBuilder out = new StringBuilder();
        out.append(place.displayName).append(": ");
        if (!Double.isNaN(w.currentTemp)) out.append(n(w.currentTemp)).append(unit).append(" and ");
        out.append(condition(w.currentCode).toLowerCase(Locale.US)).append(" right now.");
        if (w.highs != null && w.highs.length() > 0) {
            out.append(" Today: high ").append(n(tempAt(w.highs, 0))).append(unit)
                    .append(", low ").append(n(tempAt(w.lows, 0))).append(unit)
                    .append(", with about a ").append(percentAt(w.rainChance, 0)).append("% chance of precipitation.");
        }
        if (!Double.isNaN(w.feels) && Math.abs(w.feels - w.currentTemp) >= 2) out.append(" Feels like ").append(n(w.feels)).append(unit).append(".");
        if (!Double.isNaN(w.wind) && w.wind >= 1) out.append(" Wind around ").append(n(w.wind)).append(" ").append(windUnit).append(".");

        if (week && w.highs != null) {
            int count = Math.min(5, w.highs.length());
            out.append(" Next few days:");
            for (int i = 1; i < count; i++) {
                out.append(" ").append(dayLabel(w.dates, i)).append(" ")
                        .append(n(tempAt(w.highs, i))).append("/").append(n(tempAt(w.lows, i))).append(unit)
                        .append(", ").append(condition(codeAt(w.codes, i)).toLowerCase(Locale.US))
                        .append(" (").append(percentAt(w.rainChance, i)).append("% precip).");
            }
        } else if (w.highs != null && w.highs.length() > 1) {
            out.append(" Tomorrow: high ").append(n(tempAt(w.highs, 1))).append(unit)
                    .append(", low ").append(n(tempAt(w.lows, 1))).append(unit)
                    .append(", ").append(condition(codeAt(w.codes, 1)).toLowerCase(Locale.US)).append(".");
        }
        out.append(" Weather data: Open-Meteo.");
        return out.toString().replace("—", "-");
    }

    private static String dayLabel(JSONArray dates, int i) {
        try {
            String s = dates == null ? "" : dates.optString(i, "");
            if (s.length() >= 10) {
                java.time.LocalDate d = java.time.LocalDate.parse(s.substring(0, 10));
                return d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault());
            }
        } catch (Exception ignored) {}
        return "Day " + (i + 1);
    }

    private static double tempAt(JSONArray a, int i) { return a == null ? Double.NaN : a.optDouble(i, Double.NaN); }
    private static int codeAt(JSONArray a, int i) { return a == null ? -1 : a.optInt(i, -1); }
    private static int percentAt(JSONArray a, int i) {
        if (a == null) return 0;
        int v = a.optInt(i, 0);
        return Math.max(0, Math.min(100, v));
    }
    private static String n(double v) { return Double.isNaN(v) ? "?" : String.valueOf((int) Math.round(v)); }

    private static String condition(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Foggy";
            case 51: case 53: case 55: case 56: case 57: return "Drizzle";
            case 61: case 63: case 65: case 66: case 67: return "Rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: case 82: return "Rain showers";
            case 85: case 86: return "Snow showers";
            case 95: case 96: case 99: return "Thunderstorms";
            default: return "Mixed conditions";
        }
    }

    private static boolean usesFahrenheit(Context c) {
        String country = Locale.getDefault().getCountry();
        return "US".equalsIgnoreCase(country) || "BS".equalsIgnoreCase(country) || "BZ".equalsIgnoreCase(country) || "KY".equalsIgnoreCase(country) || "PW".equalsIgnoreCase(country);
    }

    private static boolean hasLocationPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static Location currentLocation(Context c) {
        if (!hasLocationPermission(c)) return null;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        try {
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                try {
                    Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
                } catch (SecurityException ignored) {}
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 6L * 60L * 60L * 1000L) return best;

            if (Build.VERSION.SDK_INT >= 30) {
                String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER :
                        (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null);
                if (provider != null) {
                    CountDownLatch latch = new CountDownLatch(1);
                    AtomicReference<Location> ref = new AtomicReference<>();
                    ExecutorService exec = Executors.newSingleThreadExecutor();
                    try {
                        lm.getCurrentLocation(provider, new CancellationSignal(), exec, loc -> { ref.set(loc); latch.countDown(); });
                        latch.await(8, TimeUnit.SECONDS);
                        if (ref.get() != null) return ref.get();
                    } finally {
                        exec.shutdownNow();
                    }
                }
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "OrbitAssistant/0.5.9 Android");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) sb.append(line);
            }
            if (code < 200 || code >= 300) return null;
            return new JSONObject(sb.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }
    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.US); }

    private static final class Place {
        final double lat, lon;
        final String displayName;
        Place(double lat, double lon, String displayName) { this.lat = lat; this.lon = lon; this.displayName = displayName; }
    }
    private static final class Weather {
        boolean fahrenheit;
        double currentTemp, feels, wind, precip;
        int currentCode;
        JSONArray dates, codes, highs, lows, rainChance;
    }
}

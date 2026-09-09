package com.orbit.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What shape the hosted search actually arrived in, recorded without recording anything said.
 *
 * <p><b>Why this exists.</b> Orbit's provenance tests were green on synthetic events for three
 * releases while a real Galaxy S25 Ultra kept receiving zero structured sources. Green tests and a
 * failing device mean the fixtures are not the traffic, and nothing on the device could say what
 * the traffic actually looked like. This records the schema of a searched response - which event
 * types arrived, which keys the search envelope carried, how many source objects were inside it -
 * so the next schema drift is a fact somebody can read instead of a guess somebody ships.
 *
 * <p><b>It records names, never content.</b> Event types, JSON key names, action type names and
 * annotation type names are the provider's vocabulary, not the user's words. Counts are counts. The
 * one thing derived from a URL is the host of a source Orbit successfully recognised, which
 * {@link RichAnswerTrace} already reports. Deliberately absent: the prompt, the answer, the search
 * query, any raw event payload, any header, cookie or token, any path, query string or fragment.
 *
 * <p><b>Bounded everywhere.</b> Every set stops at {@link #MAX_NAMES} entries, every name at
 * {@link #MAX_NAME_CHARS} characters, and a name is stored only if it looks like an identifier -
 * so a provider that one day puts a sentence where a type name belongs cannot smuggle it in here.
 * Exactly one snapshot is kept: the most recent response that used search.
 */
public final class HostedSearchSchemaTrace {

    /** How many distinct names one set will keep. */
    public static final int MAX_NAMES = 24;

    /** The longest name written down. */
    static final int MAX_NAME_CHARS = 48;

    private static final String FILE = "orbit_diagnostics";
    private static final String KEY = "hosted_search_schema";

    /** The one name stored that is not an identifier, for an annotation that declared no type. */
    static final String UNTYPED = "(untyped)";

    /** Identifier shape. Anything else is not a schema name and is not stored. */
    private static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,48}");

    /** One searched response, described by its shape. */
    public static final class Snapshot {
        public long timestamp = System.currentTimeMillis();
        /** True once anything in the stream identified itself as hosted web search. */
        public boolean searchSeen;
        public final Set<String> eventTypes = new LinkedHashSet<>();
        public final Set<String> searchCallKeys = new LinkedHashSet<>();
        public final Set<String> actionKeys = new LinkedHashSet<>();
        public final Set<String> actionTypes = new LinkedHashSet<>();
        public final Set<String> annotationTypes = new LinkedHashSet<>();
        public final Set<String> recognizedHosts = new LinkedHashSet<>();
        /** Objects seen inside a search envelope's results/sources arrays. */
        public int sourceObjects;
        /** Source URLs Orbit actually accepted from this stream. */
        public int recognizedUrls;
    }

    private HostedSearchSchemaTrace() {}

    // ---- observing -------------------------------------------------------------------------------

    /**
     * Notes the shape of one SSE event. Never throws and never inspects free text.
     *
     * <p>Called for every event of a stream, before Orbit decides whether it carries provenance, so
     * a search envelope Orbit failed to understand is recorded exactly as loudly as one it did.
     */
    public static void observe(Snapshot snapshot, JSONObject event) {
        if (snapshot == null || event == null) return;
        try {
            String type = event.optString("type", "");
            add(snapshot.eventTypes, type);
            if (type.toLowerCase(Locale.US).contains("web_search")) snapshot.searchSeen = true;

            if (type.startsWith("response.web_search_call.")) describeSearchCall(snapshot, event);
            describeItem(snapshot, event.optJSONObject("item"));
            JSONObject response = event.optJSONObject("response");
            JSONArray output = response == null ? null : response.optJSONArray("output");
            if (output != null) {
                for (int i = 0; i < output.length(); i++) describeItem(snapshot, output.optJSONObject(i));
            }
            describeAnnotations(snapshot, event.optJSONObject("part"));
            describeAnnotations(snapshot, event);
            describeAnnotation(snapshot, event.optJSONObject("annotation"));
        } catch (Exception ignored) {
            // A diagnostics buffer must never be able to break a response.
        }
    }

    /** Records a source URL Orbit accepted. Only the host is kept. */
    public static void recognized(Snapshot snapshot, String url) {
        if (snapshot == null) return;
        snapshot.recognizedUrls++;
        add(snapshot.recognizedHosts, RichAnswerTrace.hostOf(url));
    }

    private static void describeItem(Snapshot snapshot, JSONObject item) {
        if (item == null) return;
        String type = item.optString("type", "");
        if ("web_search_call".equals(type)) {
            describeSearchCall(snapshot, item);
        } else if ("message".equals(type)) {
            JSONArray content = item.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    describeAnnotations(snapshot, content.optJSONObject(i));
                }
            }
        }
    }

    private static void describeSearchCall(Snapshot snapshot, JSONObject call) {
        if (call == null) return;
        snapshot.searchSeen = true;
        keys(snapshot.searchCallKeys, call);
        JSONObject action = call.optJSONObject("action");
        if (action != null) {
            keys(snapshot.actionKeys, action);
            add(snapshot.actionTypes, action.optString("type", ""));
            snapshot.sourceObjects += count(action.opt("sources")) + count(action.opt("results"));
        }
        snapshot.sourceObjects += count(call.opt("sources")) + count(call.opt("results"))
                + count(call.opt("citations"));
    }

    private static void describeAnnotations(Snapshot snapshot, JSONObject holder) {
        if (holder == null) return;
        JSONArray annotations = holder.optJSONArray("annotations");
        if (annotations == null) return;
        for (int i = 0; i < annotations.length(); i++) {
            describeAnnotation(snapshot, annotations.optJSONObject(i));
        }
    }

    private static void describeAnnotation(Snapshot snapshot, JSONObject annotation) {
        if (annotation == null) return;
        String type = annotation.optString("type", "");
        add(snapshot.annotationTypes, type.isEmpty() ? UNTYPED : type);
    }

    /** How many objects an array of source descriptors holds. Never reads their values. */
    private static int count(Object value) {
        return value instanceof JSONArray ? ((JSONArray) value).length() : 0;
    }

    private static void keys(Set<String> into, JSONObject object) {
        java.util.Iterator<String> it = object.keys();
        while (it.hasNext()) add(into, it.next());
    }

    private static void add(Set<String> into, String name) {
        if (into == null || name == null) return;
        String clean = name.trim();
        if (clean.isEmpty() || into.size() >= MAX_NAMES) return;
        if (clean.length() > MAX_NAME_CHARS) return;
        if (!UNTYPED.equals(clean) && !NAME.matcher(clean).matches()) return;
        into.add(clean);
    }

    // ---- storage ---------------------------------------------------------------------------------

    /** Stores one snapshot, replacing the previous one. Only searched responses are kept. */
    public static void record(Context context, Snapshot snapshot) {
        if (context == null || snapshot == null || !snapshot.searchSeen) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", snapshot.timestamp);
            o.put("ev", array(snapshot.eventTypes));
            o.put("ck", array(snapshot.searchCallKeys));
            o.put("ak", array(snapshot.actionKeys));
            o.put("at", array(snapshot.actionTypes));
            o.put("an", array(snapshot.annotationTypes));
            o.put("hosts", array(snapshot.recognizedHosts));
            o.put("so", snapshot.sourceObjects);
            o.put("ru", snapshot.recognizedUrls);
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putString(KEY, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Empties the buffer. Used by the local-data reset and by tests. */
    public static void clear(Context context) {
        if (context == null) return;
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply();
        } catch (Exception ignored) {}
    }

    /** The stored snapshot, or null when no searched response has been seen. */
    public static Snapshot last(Context context) {
        if (context == null) return null;
        try {
            String stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getString(KEY, "");
            if (stored == null || stored.trim().isEmpty()) return null;
            JSONObject o = new JSONObject(stored);
            Snapshot snapshot = new Snapshot();
            snapshot.timestamp = o.optLong("t", 0L);
            snapshot.searchSeen = true;
            read(snapshot.eventTypes, o.optJSONArray("ev"));
            read(snapshot.searchCallKeys, o.optJSONArray("ck"));
            read(snapshot.actionKeys, o.optJSONArray("ak"));
            read(snapshot.actionTypes, o.optJSONArray("at"));
            read(snapshot.annotationTypes, o.optJSONArray("an"));
            read(snapshot.recognizedHosts, o.optJSONArray("hosts"));
            snapshot.sourceObjects = o.optInt("so", 0);
            snapshot.recognizedUrls = o.optInt("ru", 0);
            return snapshot;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---- reporting -------------------------------------------------------------------------------

    /** The readable block the Diagnostics section shows. */
    public static String body(Context context) {
        Snapshot snapshot = last(context);
        if (snapshot == null) {
            return "No hosted web search has been observed on this device yet.\n\n"
                    + "Ask a question that needs a web lookup, then come back here. Orbit records the "
                    + "shape of the search response only: event names, field names and counts, never "
                    + "the question, the answer or the search query.";
        }
        StringBuilder b = new StringBuilder();
        b.append("Last searched response: ").append(RichAnswerTrace.ago(snapshot.timestamp)).append('\n');
        b.append("Source objects seen: ").append(snapshot.sourceObjects).append('\n');
        b.append("Source URLs recognized: ").append(snapshot.recognizedUrls);
        b.append(list("SSE event types seen", snapshot.eventTypes));
        b.append(list("web_search_call keys", snapshot.searchCallKeys));
        b.append(list("action keys", snapshot.actionKeys));
        b.append(list("action types", snapshot.actionTypes));
        b.append(list("annotation types", snapshot.annotationTypes));
        b.append(list("Recognized source hosts", snapshot.recognizedHosts));
        return b.toString();
    }

    /** The one line the Diagnostics summary carries, or empty when nothing has been seen. */
    public static String summaryLine(Context context) {
        Snapshot snapshot = last(context);
        if (snapshot == null) return "";
        return String.format(Locale.US,
                "Hosted search schema: %d event types, %d source objects, %d URLs recognized",
                snapshot.eventTypes.size(), snapshot.sourceObjects, snapshot.recognizedUrls);
    }

    private static String list(String title, Set<String> names) {
        if (names == null || names.isEmpty()) return "";
        StringBuilder b = new StringBuilder("\n\n").append(title).append(':');
        for (String name : names) b.append("\n- ").append(name);
        return b.toString();
    }

    private static JSONArray array(Set<String> names) {
        JSONArray out = new JSONArray();
        for (String name : names) out.put(name);
        return out;
    }

    private static void read(Set<String> into, JSONArray array) {
        if (array == null) return;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) names.add(array.optString(i, ""));
        for (String name : names) add(into, name);
    }
}

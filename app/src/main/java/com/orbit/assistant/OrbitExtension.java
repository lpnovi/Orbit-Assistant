package com.orbit.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, fully validated representation of an untrusted .orbitext manifest. */
public final class OrbitExtension {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;
    public static final int MAX_ACTIONS = 20;
    public static final int MAX_POST_BODY_BYTES = 16 * 1024;
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;
    public static final int DEFAULT_TIMEOUT_SECONDS = 8;
    public static final int MAX_TIMEOUT_SECONDS = 10;

    public static final String TYPE_OPEN_URL = "open_url";
    public static final String TYPE_HTTPS_REQUEST = "https_request";

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{2,79}");
    private static final Pattern ACTION_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{1,63}");
    private static final Set<String> SENSITIVE_KEYS = set(
            "authorization", "cookie", "credential", "credentials", "password", "passwd",
            "secret", "token", "access_token", "refresh_token", "api_key", "apikey");

    public final int schemaVersion;
    public final String id;
    public final String name;
    public final String version;
    public final String author;
    public final String description;
    public final List<Action> actions;
    private final JSONObject manifest;

    public static final class Action {
        public final String id;
        public final String name;
        public final String description;
        public final String type;
        public final String url;
        public final String method;
        public final JSONObject body;
        public final int timeoutSeconds;

        private Action(String id, String name, String description, String type, String url,
                       String method, JSONObject body, int timeoutSeconds) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.type = type;
            this.url = url;
            this.method = method;
            this.body = copyObject(body);
            this.timeoutSeconds = timeoutSeconds;
        }

        public String capabilityLabel() {
            if (TYPE_OPEN_URL.equals(type)) return "Open URL";
            return "HTTPS " + method;
        }
    }

    private OrbitExtension(int schemaVersion, String id, String name, String version,
                           String author, String description, List<Action> actions,
                           JSONObject manifest) {
        this.schemaVersion = schemaVersion;
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.description = description;
        this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
        this.manifest = copyObject(manifest);
    }

    public static OrbitExtension parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) fail("The extension file is empty.");
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES)
            fail("The extension manifest is too large.");
        try {
            return parse(new JSONObject(raw));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("The extension file is not valid JSON.");
        }
    }

    public static OrbitExtension parse(JSONObject source) {
        if (source == null) fail("The extension manifest is missing.");
        JSONObject manifest = copyObject(source);
        if (manifest.toString().getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES)
            fail("The extension manifest is too large.");
        requireOnlyKeys(manifest, set("schemaVersion", "id", "name", "version", "author",
                "description", "actions"), "extension manifest");

        Object schemaValue = manifest.opt("schemaVersion");
        if (!(schemaValue instanceof Number)) fail("Extension schemaVersion must be a number.");
        int schema = ((Number) schemaValue).intValue();
        if (schema != SCHEMA_VERSION || ((Number) schemaValue).doubleValue() != schema)
            fail("This extension schema version is not supported.");
        String id = requiredText(manifest, "id", 80);
        if (!ID.matcher(id).matches()) fail("The extension ID is invalid.");
        String name = requiredText(manifest, "name", 60);
        String version = requiredText(manifest, "version", 32);
        String author = requiredText(manifest, "author", 60);
        String description = requiredText(manifest, "description", 240);

        JSONArray array = manifest.optJSONArray("actions");
        if (array == null || array.length() == 0 || array.length() > MAX_ACTIONS)
            fail("Extensions must declare between 1 and " + MAX_ACTIONS + " actions.");
        List<Action> actions = new ArrayList<>();
        Set<String> actionIds = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject action = array.optJSONObject(i);
            if (action == null) fail("Every extension action must be a JSON object.");
            String actionId = requiredText(action, "id", 64);
            if (!ACTION_ID.matcher(actionId).matches() || !actionIds.add(actionId))
                fail("Extension action IDs must be valid and unique.");
            String actionName = requiredText(action, "name", 60);
            String actionDescription = optionalText(action, "description", 180);
            String type = requiredText(action, "type", 24).toLowerCase(Locale.US);
            if (TYPE_OPEN_URL.equals(type)) {
                requireOnlyKeys(action, set("id", "name", "description", "type", "url"),
                        "Open URL action");
                String url = requiredText(action, "url", 2048);
                validatePublicUrl(url, true);
                actions.add(new Action(actionId, actionName, actionDescription, type, url,
                        "", null, DEFAULT_TIMEOUT_SECONDS));
            } else if (TYPE_HTTPS_REQUEST.equals(type)) {
                requireOnlyKeys(action, set("id", "name", "description", "type", "endpoint",
                        "method", "body", "timeoutSeconds"), "HTTPS Request action");
                String endpoint = requiredText(action, "endpoint", 2048);
                validatePublicUrl(endpoint, false);
                String method = optionalText(action, "method", 4).toUpperCase(Locale.US);
                if (method.isEmpty()) method = "GET";
                if (!("GET".equals(method) || "POST".equals(method)))
                    fail("HTTPS Request method must be GET or POST.");
                JSONObject body = action.optJSONObject("body");
                if (action.has("body") && body == null) fail("HTTPS Request body must be a JSON object.");
                if ("GET".equals(method) && body != null) fail("GET actions cannot include a request body.");
                if (body != null) {
                    if (body.toString().getBytes(StandardCharsets.UTF_8).length > MAX_POST_BODY_BYTES)
                        fail("HTTPS Request body is too large.");
                    rejectSensitiveKeys(body);
                }
                Object timeoutValue = action.opt("timeoutSeconds");
                if (timeoutValue != null && timeoutValue != JSONObject.NULL &&
                        !(timeoutValue instanceof Number))
                    fail("HTTPS Request timeoutSeconds must be a number.");
                int timeout = timeoutValue instanceof Number
                        ? ((Number) timeoutValue).intValue() : DEFAULT_TIMEOUT_SECONDS;
                if (timeoutValue instanceof Number && ((Number) timeoutValue).doubleValue() != timeout)
                    fail("HTTPS Request timeoutSeconds must be a whole number.");
                if (timeout < 1 || timeout > MAX_TIMEOUT_SECONDS)
                    fail("HTTPS Request timeout must be between 1 and " + MAX_TIMEOUT_SECONDS + " seconds.");
                actions.add(new Action(actionId, actionName, actionDescription, type, endpoint,
                        method, body, timeout));
            } else {
                fail("Unsupported extension action type: " + type);
            }
        }
        return new OrbitExtension(schema, id, name, version, author, description, actions, manifest);
    }

    public Action findAction(String actionId) {
        if (actionId == null) return null;
        for (Action action : actions) if (action.id.equals(actionId)) return action;
        return null;
    }

    public JSONObject toJson() {
        return copyObject(manifest);
    }

    public List<String> contactedHosts() {
        List<String> hosts = new ArrayList<>();
        for (Action action : actions) {
            try {
                String host = new URI(action.url).getHost();
                if (host != null && !hosts.contains(host.toLowerCase(Locale.US)))
                    hosts.add(host.toLowerCase(Locale.US));
            } catch (Exception ignored) {}
        }
        return hosts;
    }

    /** Rechecks resolved addresses immediately before an HTTPS request is opened. */
    static void validateResolvedPublicHost(String url) throws Exception {
        URI uri = new URI(url);
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("Extension endpoint has no host.");
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (!isPublicAddress(address)) {
                throw new IllegalArgumentException("Extension endpoints must use public network addresses.");
            }
        }
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                address.isLinkLocalAddress() || address.isSiteLocalAddress() ||
                address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
            if (a == 100 && b >= 64 && b <= 127) return false;
            if (a == 169 && b == 254) return false;
            if (a == 172 && b >= 16 && b <= 31) return false;
            if (a == 192 && (b == 0 || b == 168)) return false;
            if (a == 198 && (b == 18 || b == 19)) return false;
            // Documentation-only address ranges are not public service endpoints.
            if ((a == 192 && b == 0 && (bytes[2] & 0xff) == 2) ||
                    (a == 198 && b == 51 && (bytes[2] & 0xff) == 100) ||
                    (a == 203 && b == 0 && (bytes[2] & 0xff) == 113)) return false;
        } else if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if ((first & 0xfe) == 0xfc || first == 0xff) return false; // ULA or multicast
            if (first == 0x20 && second == 0x01 && (bytes[2] & 0xff) == 0x0d &&
                    (bytes[3] & 0xff) == 0xb8) return false; // documentation range
        }
        return true;
    }

    private static void validatePublicUrl(String value, boolean allowHttp) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            if (!("https".equals(scheme) || (allowHttp && "http".equals(scheme)))) fail("Extension URL scheme is not allowed.");
            if (uri.getHost() == null || uri.getHost().trim().isEmpty() || uri.getUserInfo() != null ||
                    uri.getFragment() != null) fail("Extension URL is invalid.");
            rejectSensitiveQuery(uri.getRawQuery());
            String host = uri.getHost().toLowerCase(Locale.US);
            if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local"))
                fail("Extension URLs must use a public host.");
            // Literal private/loopback addresses are rejected during install; DNS is checked again at execution.
            if (host.matches("[0-9.]+") || host.contains(":")) validateResolvedPublicHost(value);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            fail("Extension URL is invalid.");
        }
    }

    private static void rejectSensitiveKeys(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (SENSITIVE_KEYS.contains(key.toLowerCase(Locale.US)))
                    fail("Extension request bodies cannot contain credential or secret fields.");
                rejectSensitiveKeys(object.opt(key));
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) rejectSensitiveKeys(array.opt(i));
        }
    }

    private static void rejectSensitiveQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return;
        for (String pair : rawQuery.split("&")) {
            String rawKey = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            try {
                String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
                        .toLowerCase(Locale.US);
                if (SENSITIVE_KEYS.contains(key))
                    fail("Extension URLs cannot contain credential or secret query fields.");
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                fail("Extension URL query is invalid.");
            }
        }
    }

    private static void requireOnlyKeys(JSONObject object, Set<String> allowed, String label) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) fail("Unsupported field in " + label + ": " + key);
        }
    }

    private static String requiredText(JSONObject object, String key, int max) {
        String value = optionalText(object, key, max);
        if (value.isEmpty()) fail("Missing extension field: " + key);
        return value;
    }

    private static String optionalText(JSONObject object, String key, int max) {
        Object raw = object.opt(key);
        if (raw == null || raw == JSONObject.NULL) return "";
        if (!(raw instanceof String)) fail("Extension field " + key + " must be text.");
        String value = ((String) raw).trim().replaceAll("\\s+", " ");
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i)))
                fail("Extension field " + key + " contains unsupported control characters.");
        }
        if (value.length() > max) fail("Extension field " + key + " is too long.");
        return value;
    }

    private static JSONObject copyObject(JSONObject source) {
        try { return source == null ? new JSONObject() : new JSONObject(source.toString()); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static Set<String> set(String... values) {
        Set<String> out = new HashSet<>();
        Collections.addAll(out, values);
        return out;
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}

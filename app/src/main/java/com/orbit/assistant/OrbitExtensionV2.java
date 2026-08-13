package com.orbit.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict schema-v2 parser and structural template renderer. */
final class OrbitExtensionV2 {
    static final int MAX_SETUP_FIELDS = 12;
    static final int MAX_PARAMETERS = 12;
    static final int MAX_HEADERS = 12;
    static final int MAX_TEXT_LENGTH = 4096;
    static final int MAX_CHOICES = 12;

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{1,63}");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{(config|param)\\.([a-z0-9][a-z0-9_-]{1,63})}}" );
    private static final Set<String> BLOCKED_HEADERS = set(
            "host", "content-length", "cookie", "proxy-authorization", "proxy-authenticate",
            "connection", "transfer-encoding", "upgrade", "te", "trailer");

    private OrbitExtensionV2() {}

    static OrbitExtension parse(JSONObject manifest) {
        onlyKeys(manifest, set("schemaVersion", "id", "name", "version", "author",
                "description", "setupFields", "actions"), "extension manifest");
        String id = requiredText(manifest, "id", 80);
        if (!id.matches("[a-z0-9][a-z0-9._-]{2,79}")) fail("The extension ID is invalid.");
        String name = requiredText(manifest, "name", 60);
        String version = requiredText(manifest, "version", 32);
        String author = requiredText(manifest, "author", 60);
        String description = requiredText(manifest, "description", 240);

        if (manifest.has("setupFields") && manifest.optJSONArray("setupFields") == null)
            fail("Extension setupFields must be an array.");
        List<OrbitExtension.SetupField> setup = parseSetup(manifest.optJSONArray("setupFields"));
        Map<String, OrbitExtension.SetupField> setupById = new HashMap<>();
        for (OrbitExtension.SetupField field : setup) setupById.put(field.id, field);

        JSONArray rawActions = manifest.optJSONArray("actions");
        if (rawActions == null || rawActions.length() == 0 ||
                rawActions.length() > OrbitExtension.MAX_ACTIONS)
            fail("Extensions must declare between 1 and " + OrbitExtension.MAX_ACTIONS + " actions.");
        List<OrbitExtension.Action> actions = new ArrayList<>();
        Set<String> actionIds = new HashSet<>();
        for (int i = 0; i < rawActions.length(); i++) {
            JSONObject action = rawActions.optJSONObject(i);
            if (action == null) fail("Every extension action must be a JSON object.");
            String actionId = requiredId(action, "id", actionIds, "Extension action IDs must be valid and unique.");
            String actionName = requiredText(action, "name", 60);
            String actionDescription = optionalText(action, "description", 180);
            String type = requiredText(action, "type", 24).toLowerCase(Locale.US);
            if (OrbitExtension.TYPE_OPEN_URL.equals(type)) {
                onlyKeys(action, set("id", "name", "description", "type", "url"),
                        "Open URL action");
                String url = requiredText(action, "url", 2048);
                OrbitExtension.validatePublicUrl(url, true);
                actions.add(new OrbitExtension.Action(actionId, actionName, actionDescription,
                        type, url, "", null, OrbitExtension.DEFAULT_TIMEOUT_SECONDS,
                        Collections.emptyList(), Collections.emptyList()));
                continue;
            }
            if (!OrbitExtension.TYPE_HTTPS_REQUEST.equals(type))
                fail("Unsupported extension action type: " + type);

            onlyKeys(action, set("id", "name", "description", "type", "endpoint", "method",
                    "body", "timeoutSeconds", "parameters", "headers"), "HTTPS Request action");
            if (action.has("parameters") && action.optJSONArray("parameters") == null)
                fail("HTTPS Request parameters must be an array.");
            List<OrbitExtension.ActionParameter> parameters = parseParameters(
                    action.optJSONArray("parameters"));
            Map<String, OrbitExtension.ActionParameter> paramsById = new HashMap<>();
            for (OrbitExtension.ActionParameter parameter : parameters)
                paramsById.put(parameter.id, parameter);

            String endpoint = requiredText(action, "endpoint", 2048);
            validateEndpointTemplate(endpoint, setupById, paramsById);
            String method = optionalText(action, "method", 4).toUpperCase(Locale.US);
            if (method.isEmpty()) method = "GET";
            if (!("GET".equals(method) || "POST".equals(method)))
                fail("HTTPS Request method must be GET or POST.");
            JSONObject body = action.optJSONObject("body");
            if (action.has("body") && body == null)
                fail("HTTPS Request body must be a JSON object.");
            if ("GET".equals(method) && body != null) fail("GET actions cannot include a request body.");
            if (body != null) {
                if (body.toString().getBytes(StandardCharsets.UTF_8).length > OrbitExtension.MAX_POST_BODY_BYTES)
                    fail("HTTPS Request body is too large.");
                OrbitExtension.rejectSensitiveKeys(body);
                validateBodyTemplates(body, setupById, paramsById);
            }
            int timeout = wholeNumber(action, "timeoutSeconds", OrbitExtension.DEFAULT_TIMEOUT_SECONDS);
            if (timeout < 1 || timeout > OrbitExtension.MAX_TIMEOUT_SECONDS)
                fail("HTTPS Request timeout must be between 1 and " +
                        OrbitExtension.MAX_TIMEOUT_SECONDS + " seconds.");
            if (action.has("headers") && action.optJSONObject("headers") == null)
                fail("HTTPS Request headers must be an object.");
            List<OrbitExtension.RequestHeader> headers = parseHeaders(
                    action.optJSONObject("headers"), setupById, paramsById);
            actions.add(new OrbitExtension.Action(actionId, actionName, actionDescription,
                    type, endpoint, method, body, timeout, parameters, headers));
        }
        return new OrbitExtension(OrbitExtension.SCHEMA_VERSION_V2, id, name, version, author,
                description, setup, actions, manifest);
    }

    private static List<OrbitExtension.SetupField> parseSetup(JSONArray array) {
        if (array == null) return Collections.emptyList();
        if (array.length() > MAX_SETUP_FIELDS) fail("Too many extension setup fields.");
        List<OrbitExtension.SetupField> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject field = array.optJSONObject(i);
            if (field == null) fail("Every setup field must be a JSON object.");
            onlyKeys(field, set("id", "label", "description", "type", "required",
                    "maxLength", "default"), "setup field");
            String id = requiredId(field, "id", ids, "Setup field IDs must be valid and unique.");
            String label = requiredText(field, "label", 60);
            String description = optionalText(field, "description", 180);
            String type = requiredText(field, "type", 16).toLowerCase(Locale.US);
            if (!(OrbitExtension.SETUP_TEXT.equals(type) || OrbitExtension.SETUP_URL.equals(type) ||
                    OrbitExtension.SETUP_SECRET.equals(type) || OrbitExtension.SETUP_SECRET_URL.equals(type)))
                fail("Unsupported setup field type: " + type);
            boolean secret = OrbitExtension.SETUP_SECRET.equals(type) ||
                    OrbitExtension.SETUP_SECRET_URL.equals(type);
            if (!secret && sensitiveSetupIdentity(id + " " + label))
                fail("Credential-like setup fields must use secure storage.");
            boolean required = optionalBoolean(field, "required", false);
            int cap = (OrbitExtension.SETUP_URL.equals(type) ||
                    OrbitExtension.SETUP_SECRET_URL.equals(type)) ? 2048 : MAX_TEXT_LENGTH;
            int maxLength = wholeNumber(field, "maxLength", Math.min(512, cap));
            if (maxLength < 1 || maxLength > cap) fail("Setup field maxLength is out of range.");
            String defaultValue = optionalTextPreserving(field, "default", maxLength);
            if (secret && field.has("default")) fail("Secret setup fields cannot declare defaults.");
            if (OrbitExtension.SETUP_URL.equals(type) && !defaultValue.isEmpty())
                OrbitExtension.validatePublicUrl(defaultValue, false);
            out.add(new OrbitExtension.SetupField(id, label, description, type, required,
                    maxLength, defaultValue));
        }
        return out;
    }

    private static List<OrbitExtension.ActionParameter> parseParameters(JSONArray array) {
        if (array == null) return Collections.emptyList();
        if (array.length() > MAX_PARAMETERS) fail("Too many action parameters.");
        List<OrbitExtension.ActionParameter> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject parameter = array.optJSONObject(i);
            if (parameter == null) fail("Every action parameter must be a JSON object.");
            onlyKeys(parameter, set("id", "label", "description", "type", "required",
                    "maxLength", "default", "choices"), "action parameter");
            String id = requiredId(parameter, "id", ids,
                    "Action parameter IDs must be valid and unique.");
            if (sensitiveParameterId(id))
                fail("Action parameters cannot be used for credentials or secrets.");
            String label = requiredText(parameter, "label", 60);
            String description = optionalText(parameter, "description", 180);
            String type = requiredText(parameter, "type", 16).toLowerCase(Locale.US);
            if (!(OrbitExtension.PARAM_TEXT.equals(type) || OrbitExtension.PARAM_CHOICE.equals(type)))
                fail("Unsupported action parameter type: " + type);
            boolean required = optionalBoolean(parameter, "required", false);
            int maxLength = wholeNumber(parameter, "maxLength",
                    OrbitExtension.PARAM_CHOICE.equals(type) ? 64 : 2000);
            if (maxLength < 1 || maxLength > MAX_TEXT_LENGTH)
                fail("Action parameter maxLength is out of range.");
            List<OrbitExtension.Choice> choices = parseChoices(parameter.optJSONArray("choices"), type);
            String defaultValue = optionalTextPreserving(parameter, "default", maxLength);
            if (OrbitExtension.PARAM_CHOICE.equals(type)) {
                if (choices.isEmpty()) fail("Choice parameters must declare choices.");
                if (!defaultValue.isEmpty() && !containsChoice(choices, defaultValue))
                    fail("Choice parameter default is not allowed.");
            } else if (parameter.has("choices")) {
                fail("Text parameters cannot declare choices.");
            }
            out.add(new OrbitExtension.ActionParameter(id, label, description, type, required,
                    maxLength, defaultValue, choices));
        }
        return out;
    }

    private static List<OrbitExtension.Choice> parseChoices(JSONArray array, String type) {
        if (array == null) return Collections.emptyList();
        if (!OrbitExtension.PARAM_CHOICE.equals(type) || array.length() == 0 ||
                array.length() > MAX_CHOICES) fail("Choice parameter choices are invalid.");
        List<OrbitExtension.Choice> out = new ArrayList<>();
        Set<String> values = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject choice = array.optJSONObject(i);
            if (choice == null) fail("Every parameter choice must be a JSON object.");
            onlyKeys(choice, set("value", "label"), "parameter choice");
            String value = requiredText(choice, "value", 64);
            String label = requiredText(choice, "label", 60);
            if (!values.add(value)) fail("Parameter choice values must be unique.");
            out.add(new OrbitExtension.Choice(value, label));
        }
        return out;
    }

    private static List<OrbitExtension.RequestHeader> parseHeaders(JSONObject headers,
            Map<String, OrbitExtension.SetupField> setup,
            Map<String, OrbitExtension.ActionParameter> params) {
        if (headers == null) return Collections.emptyList();
        if (headers.length() > MAX_HEADERS) fail("Too many request headers.");
        List<OrbitExtension.RequestHeader> out = new ArrayList<>();
        Set<String> normalizedNames = new HashSet<>();
        Iterator<String> names = headers.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (!HEADER_NAME.matcher(name).matches()) fail("A request header name is invalid.");
            String lower = name.toLowerCase(Locale.US);
            if (!normalizedNames.add(lower)) fail("Request header names must be unique.");
            if (BLOCKED_HEADERS.contains(lower) || lower.startsWith("proxy-"))
                fail("Request header is not allowed: " + name);
            Object raw = headers.opt(name);
            if (!(raw instanceof String)) fail("Request header values must be text.");
            String value = (String) raw;
            if (value.length() > 1024 || unsafeHeaderValue(value))
                fail("A request header value is invalid.");
            validateTemplate(value, setup, params, TemplateLocation.HEADER);
            if (value.matches("(?i)^\\s*(Bearer|Basic|Token)\\s+.+$") &&
                    !isCredentialHeader(lower))
                fail("Credential-like header literals are not allowed.");
            if (isCredentialHeader(lower)) validateCredentialHeader(value, setup);
            out.add(new OrbitExtension.RequestHeader(name, value));
        }
        return out;
    }

    private static void validateCredentialHeader(String template,
            Map<String, OrbitExtension.SetupField> setup) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        boolean foundSecret = false;
        while (matcher.find()) {
            if (!"config".equals(matcher.group(1)))
                fail("Credential headers may use only secure setup fields.");
            OrbitExtension.SetupField field = setup.get(matcher.group(2));
            if (field == null || !OrbitExtension.SETUP_SECRET.equals(field.type))
                fail("Credential headers may use only secret setup fields.");
            foundSecret = true;
        }
        if (!foundSecret) fail("Credential header literals are not allowed.");
        String residue = PLACEHOLDER.matcher(template).replaceAll("").trim();
        if (!residue.isEmpty() && !residue.matches("(?i)(Bearer|Basic|Token)"))
            fail("Credential header prefixes are not allowed.");
    }

    private static void validateBodyTemplates(Object value,
            Map<String, OrbitExtension.SetupField> setup,
            Map<String, OrbitExtension.ActionParameter> params) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.contains("{{") || key.contains("}}"))
                    fail("Placeholders are not allowed in JSON object keys.");
                validateBodyTemplates(object.opt(key), setup, params);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++)
                validateBodyTemplates(array.opt(i), setup, params);
        } else if (value instanceof String) {
            validateTemplate((String) value, setup, params, TemplateLocation.BODY);
        }
    }

    static JSONObject validateAndNormalizeConfiguration(OrbitExtension extension, JSONObject source) {
        JSONObject raw = source == null ? new JSONObject() : source;
        JSONObject out = new JSONObject();
        Iterator<String> keys = raw.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            OrbitExtension.SetupField field = extension.findSetupField(id);
            if (field == null || field.isSecret()) fail("Extension configuration contains an unsupported field.");
            Object value = raw.opt(id);
            if (!(value instanceof String)) fail("Extension configuration values must be text.");
            String normalized = normalizeUserValue(field.type, (String) value, field.maxLength);
            if (!normalized.isEmpty()) put(out, id, normalized);
        }
        return out;
    }

    static String validateSetupValue(OrbitExtension.SetupField field, String value) {
        if (field == null) fail("Unknown extension setup field.");
        String normalized = normalizeUserValue(field.type, value, field.maxLength);
        if ((OrbitExtension.SETUP_URL.equals(field.type) ||
                OrbitExtension.SETUP_SECRET_URL.equals(field.type)) && !normalized.isEmpty())
            OrbitExtension.validatePublicUrl(normalized, false);
        return normalized;
    }

    static JSONObject validateAndNormalizeParameters(OrbitExtension.Action action, JSONObject source,
                                                       boolean requireRequired) {
        JSONObject raw = source == null ? new JSONObject() : source;
        JSONObject out = new JSONObject();
        Iterator<String> keys = raw.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            OrbitExtension.ActionParameter parameter = action.findParameter(id);
            if (parameter == null) fail("Routine action contains an unsupported parameter.");
            Object value = raw.opt(id);
            if (!(value instanceof String)) fail("Routine action parameters must be text.");
            String normalized = normalizePlain((String) value, parameter.maxLength);
            if (OrbitExtension.PARAM_CHOICE.equals(parameter.type) && !normalized.isEmpty() &&
                    !containsChoice(parameter.choices, normalized))
                fail("Routine action choice is not allowed.");
            if (!normalized.isEmpty()) put(out, id, normalized);
        }
        for (OrbitExtension.ActionParameter parameter : action.parameters) {
            String value = out.optString(parameter.id, "");
            if (value.isEmpty() && !parameter.defaultValue.isEmpty()) {
                value = parameter.defaultValue;
                put(out, parameter.id, value);
            }
            if (requireRequired && parameter.required && value.isEmpty())
                fail("Enter " + parameter.label + ".");
        }
        if (out.toString().getBytes(StandardCharsets.UTF_8).length > OrbitExtension.MAX_POST_BODY_BYTES)
            fail("Routine action parameters are too large.");
        return out;
    }

    static String renderString(String template, Map<String, String> config,
                               Map<String, String> params) {
        if (template == null) return "";
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Map<String, String> values = "config".equals(matcher.group(1)) ? config : params;
            String replacement = values == null ? "" : values.get(matcher.group(2));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static JSONObject renderBody(JSONObject template, Map<String, String> config,
                                 Map<String, String> params) {
        return (JSONObject) renderJsonValue(template == null ? new JSONObject() : template,
                config, params);
    }

    private static Object renderJsonValue(Object value, Map<String, String> config,
                                          Map<String, String> params) {
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            JSONObject out = new JSONObject();
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                put(out, key, renderJsonValue(source.opt(key), config, params));
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            JSONArray out = new JSONArray();
            for (int i = 0; i < source.length(); i++)
                out.put(renderJsonValue(source.opt(i), config, params));
            return out;
        }
        if (value instanceof String) return renderString((String) value, config, params);
        return value == null ? JSONObject.NULL : value;
    }

    private static void validateTemplate(String template,
            Map<String, OrbitExtension.SetupField> setup,
            Map<String, OrbitExtension.ActionParameter> params,
            TemplateLocation location) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String scope = matcher.group(1);
            String id = matcher.group(2);
            if ("config".equals(scope)) {
                OrbitExtension.SetupField field = setup.get(id);
                if (field == null) fail("Template references an unknown setup field.");
                if (field.isSecret()) {
                    boolean allowed = (location == TemplateLocation.URL &&
                            OrbitExtension.SETUP_SECRET_URL.equals(field.type)) ||
                            (location == TemplateLocation.HEADER &&
                                    OrbitExtension.SETUP_SECRET.equals(field.type));
                    if (!allowed) fail("Secure setup field is used in an unsupported template location.");
                }
            } else if (!params.containsKey(id)) {
                fail("Template references an unknown action parameter.");
            }
        }
        String remainder = PLACEHOLDER.matcher(template).replaceAll("");
        if (remainder.contains("{{") || remainder.contains("}}"))
            fail("Template placeholder syntax is invalid.");
    }

    private static void validateEndpointTemplate(String endpoint,
            Map<String, OrbitExtension.SetupField> setup,
            Map<String, OrbitExtension.ActionParameter> params) {
        validateTemplate(endpoint, setup, params, TemplateLocation.URL);
        if (!endpoint.contains("{{")) {
            OrbitExtension.validatePublicUrl(endpoint, false);
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(endpoint);
        if (matcher.matches() && "config".equals(matcher.group(1))) {
            OrbitExtension.SetupField field = setup.get(matcher.group(2));
            if (field != null && (OrbitExtension.SETUP_URL.equals(field.type) ||
                    OrbitExtension.SETUP_SECRET_URL.equals(field.type))) return;
        }
        int authorityStart = endpoint.toLowerCase(Locale.US).startsWith("https://") ? 8 : -1;
        if (authorityStart < 0) fail("Templated endpoints must keep a fixed HTTPS host or use one URL setup field.");
        int authorityEnd = endpoint.length();
        for (char separator : new char[]{'/', '?', '#'}) {
            int index = endpoint.indexOf(separator, authorityStart);
            if (index >= 0) authorityEnd = Math.min(authorityEnd, index);
        }
        if (endpoint.substring(authorityStart, authorityEnd).contains("{{"))
            fail("Endpoint hosts cannot come from text or Routine parameters.");
        String safeExample = PLACEHOLDER.matcher(endpoint).replaceAll("orbit");
        OrbitExtension.validatePublicUrl(safeExample, false);
        matcher.reset();
        while (matcher.find()) {
            if (!"config".equals(matcher.group(1))) continue;
            OrbitExtension.SetupField field = setup.get(matcher.group(2));
            if (field != null && (OrbitExtension.SETUP_URL.equals(field.type) ||
                    OrbitExtension.SETUP_SECRET_URL.equals(field.type)))
                fail("URL setup fields must supply the complete endpoint.");
        }
    }

    private static String normalizeUserValue(String type, String value, int max) {
        String normalized = normalizePlain(value, max);
        if (OrbitExtension.SETUP_URL.equals(type) || OrbitExtension.SETUP_SECRET_URL.equals(type)) {
            if (!normalized.isEmpty()) OrbitExtension.validatePublicUrl(normalized, false);
        }
        return normalized;
    }

    private static String normalizePlain(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max) fail("A configured value is too long.");
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '\u0000' || c == '\r') fail("A configured value contains unsupported control characters.");
        }
        return normalized;
    }

    private static String requiredId(JSONObject object, String key, Set<String> ids, String error) {
        String id = requiredText(object, key, 64);
        if (!ID.matcher(id).matches() || !ids.add(id)) fail(error);
        return id;
    }

    private static String requiredText(JSONObject object, String key, int max) {
        String value = optionalText(object, key, max);
        if (value.isEmpty()) fail("Missing extension field: " + key);
        return value;
    }

    private static String optionalText(JSONObject object, String key, int max) {
        String value = optionalTextPreserving(object, key, max);
        value = value.trim().replaceAll("\\s+", " ");
        return value;
    }

    private static String optionalTextPreserving(JSONObject object, String key, int max) {
        Object raw = object.opt(key);
        if (raw == null || raw == JSONObject.NULL) return "";
        if (!(raw instanceof String)) fail("Extension field " + key + " must be text.");
        String value = (String) raw;
        if (value.length() > max) fail("Extension field " + key + " is too long.");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u0000' || c == '\r')
                fail("Extension field " + key + " contains unsupported control characters.");
        }
        return value;
    }

    private static boolean optionalBoolean(JSONObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        Object raw = object.opt(key);
        if (!(raw instanceof Boolean)) fail("Extension field " + key + " must be true or false.");
        return (Boolean) raw;
    }

    private static int wholeNumber(JSONObject object, String key, int fallback) {
        if (!object.has(key)) return fallback;
        Object raw = object.opt(key);
        if (!(raw instanceof Number)) fail("Extension field " + key + " must be a number.");
        int value = ((Number) raw).intValue();
        if (((Number) raw).doubleValue() != value)
            fail("Extension field " + key + " must be a whole number.");
        return value;
    }

    private static boolean containsChoice(List<OrbitExtension.Choice> choices, String value) {
        for (OrbitExtension.Choice choice : choices) if (choice.value.equals(value)) return true;
        return false;
    }

    private static boolean containsCrLf(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static boolean unsafeHeaderValue(String value) {
        if (containsCrLf(value)) return true;
        for (int i = 0; i < value.length(); i++)
            if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static boolean isCredentialHeader(String lower) {
        return "authorization".equals(lower) || "x-api-key".equals(lower) ||
                "x-auth-token".equals(lower) || lower.contains("credential") ||
                lower.contains("secret") || lower.endsWith("-token") ||
                lower.endsWith("-key") || lower.contains("api-key");
    }

    private static boolean sensitiveParameterId(String id) {
        String lower = id.toLowerCase(Locale.US);
        return lower.contains("secret") || lower.contains("password") ||
                lower.contains("credential") || lower.contains("token") ||
                lower.contains("api_key") || lower.contains("apikey");
    }

    private static boolean sensitiveSetupIdentity(String value) {
        String lower = value.toLowerCase(Locale.US).replace('-', '_');
        return lower.contains("secret") || lower.contains("password") ||
                lower.contains("credential") || lower.contains("token") ||
                lower.contains("api_key") || lower.contains("apikey") ||
                lower.contains("webhook");
    }

    private static void onlyKeys(JSONObject object, Set<String> allowed, String label) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) fail("Unsupported field in " + label + ": " + key);
        }
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (Exception ignored) { fail("Extension JSON could not be processed safely."); }
    }

    private static Set<String> set(String... values) {
        Set<String> out = new HashSet<>();
        Collections.addAll(out, values);
        return out;
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }

    private enum TemplateLocation { URL, BODY, HEADER }
}

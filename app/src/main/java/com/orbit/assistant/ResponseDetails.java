package com.orbit.assistant;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What produced one answer, for the optional Response details view.
 *
 * <p>Only facts Orbit actually has. The provider, model and strength are the ones the request was
 * sent with, recorded by the provider that sent it. The time is measured on this phone from the
 * moment Orbit handed the request to the provider until the finished answer arrived, and is always
 * called "response time", never processing time: Orbit cannot see how long the backend spent.
 * An answer Orbit produced itself (a calculation, a device command) names no model at all.
 *
 * <p>An answer to an Auto request (0.8.3.0-beta.5+) also records that Auto was what the user
 * selected, the short reason the router gave, and the routing policy version. These are written
 * once, with the answer, so a later change to how Auto routes never rewrites what an old answer
 * says about itself: a Beta 5 answer keeps saying policy 1. Nothing here ever holds a prompt, a
 * credential, or a model's reasoning.
 *
 * <p><b>OpenRouter Auto is not Orbit Auto (0.8.3.0-beta.6+).</b> OpenRouter Auto is an explicit
 * OpenRouter model whose router picks a downstream model inside OpenRouter. Its answers are never
 * marked as Auto-routed; instead {@link #servedBy} keeps the model OpenRouter reported producing
 * the answer, shown as "Answered by". When OpenRouter reports none, nothing is shown or guessed.
 */
public final class ResponseDetails {
    public final String provider;
    public final String model;
    /** Display name captured when the answer was produced, or empty in older stored answers. */
    public final String modelName;
    /** Backend strength id, or "" when none was sent. */
    public final String strength;
    /** Locally measured response time, or -1 when unknown. */
    public final long elapsedMs;
    /** "auto" when Orbit Auto chose the model, "" for an explicit selection. */
    public final String requested;
    /** Auto's short routing reason, e.g. "Large context + complex reasoning", or "". */
    public final String routeReason;
    /** The routing policy that chose this answer's model, or 0 for none. */
    public final int routerPolicy;
    /** For OpenRouter Auto only: the downstream model OpenRouter reported, or "". */
    public final String servedBy;

    ResponseDetails(String provider, String model, String strength, long elapsedMs) {
        this(provider, model, OrbitModelCatalog.displayName(model), strength, elapsedMs);
    }

    ResponseDetails(String provider, String model, String modelName, String strength, long elapsedMs) {
        this(provider, model, modelName, strength, elapsedMs, "", "", 0);
    }

    ResponseDetails(String provider, String model, String modelName, String strength, long elapsedMs,
                    String requested, String routeReason, int routerPolicy) {
        this(provider, model, modelName, strength, elapsedMs, requested, routeReason, routerPolicy, "");
    }

    ResponseDetails(String provider, String model, String modelName, String strength, long elapsedMs,
                    String requested, String routeReason, int routerPolicy, String servedBy) {
        this.provider = provider == null ? "" : provider.trim();
        this.model = model == null ? "" : model.trim();
        this.modelName = modelName == null ? "" : modelName.trim();
        this.strength = strength == null ? "" : strength.trim();
        this.elapsedMs = elapsedMs < 0 ? -1 : elapsedMs;
        this.requested = AiSelection.AUTO_ID.equals(requested) ? AiSelection.AUTO_ID : "";
        this.routeReason = this.requested.isEmpty() || routeReason == null ? "" : routeReason.trim();
        this.routerPolicy = this.requested.isEmpty() ? 0 : Math.max(0, routerPolicy);
        String served = servedBy == null ? "" : servedBy.trim();
        this.servedBy = openRouterAuto(this.provider, this.model) && served.length() <= 120
                && !served.equals(this.model) ? served : "";
    }

    private static boolean openRouterAuto(String provider, String model) {
        return Prefs.PROVIDER_OPENROUTER.equals(provider)
                && OrbitModelCatalog.OPENROUTER_AUTO.equals(model);
    }

    /** The selection a request was actually sent with. */
    public static ResponseDetails sentWith(AiSelection selection) {
        if (selection == null) return null;
        return new ResponseDetails(selection.provider, selection.model, selection.modelName(),
                selection.effortId(), -1);
    }

    public ResponseDetails withElapsed(long ms) {
        return new ResponseDetails(provider, model, modelName, strength, ms, requested,
                routeReason, routerPolicy, servedBy);
    }

    /** The same answer, marked as chosen by Orbit Auto for the reason the router gave. */
    public ResponseDetails withRoute(SmartRouter.Route route) {
        if (route == null) return this;
        return new ResponseDetails(provider, model, modelName, strength, elapsedMs,
                AiSelection.AUTO_ID, route.reason, route.policy, servedBy);
    }

    /** The downstream model OpenRouter Auto reported. Ignored for every other model. */
    public ResponseDetails withServedBy(String reported) {
        return new ResponseDetails(provider, model, modelName, strength, elapsedMs, requested,
                routeReason, routerPolicy, reported);
    }

    /** True when Orbit Auto chose the model that produced this answer. */
    public boolean autoRouted() { return !requested.isEmpty(); }

    /** True when no AI model produced this answer: Orbit answered it on the phone itself. */
    public boolean answeredByOrbit() { return provider.isEmpty() && model.isEmpty(); }

    /** "2.4 s", or "" when unknown. */
    public String elapsedLabel() {
        if (elapsedMs < 0) return "";
        if (elapsedMs < 10_000) return String.format(Locale.US, "%.1f s", elapsedMs / 1000.0);
        return Math.round(elapsedMs / 1000.0) + " s";
    }

    /** Label and value pairs, only for what is known, in display order. */
    public List<String[]> rows() {
        List<String[]> rows = new ArrayList<>();
        if (answeredByOrbit()) {
            rows.add(new String[]{"Answered by", "Orbit, on this phone"});
        } else {
            if (autoRouted()) {
                rows.add(new String[]{"Selection", AiSelection.AUTO_LABEL});
            } else if (openRouterAuto(provider, model)) {
                rows.add(new String[]{"Selection", "OpenRouter Auto"});
            }
            if (!provider.isEmpty()) rows.add(new String[]{"Provider", AiProviders.byId(provider).displayName()});
            String name = modelName.isEmpty() ? OrbitModelCatalog.displayName(model) : modelName;
            if (!model.isEmpty()) rows.add(new String[]{"Model", name.isEmpty() ? model : name});
            if (!servedBy.isEmpty()) {
                String served = OrbitModelCatalog.displayName(servedBy);
                rows.add(new String[]{"Answered by", served.isEmpty() ? servedBy : served});
            }
            AiStrength s = AiStrength.fromId(strength);
            if (s != null) rows.add(new String[]{"Strength", s.label});
            if (autoRouted() && !routeReason.isEmpty()) rows.add(new String[]{"Why", routeReason});
            if (autoRouted() && routerPolicy > 0) {
                rows.add(new String[]{"Router policy", String.valueOf(routerPolicy)});
            }
        }
        String time = elapsedLabel();
        if (!time.isEmpty()) rows.add(new String[]{"Response time", time});
        return rows;
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        if (!provider.isEmpty()) o.put("provider", provider);
        if (!model.isEmpty()) o.put("model", model);
        if (!modelName.isEmpty()) o.put("modelName", modelName);
        if (!strength.isEmpty()) o.put("strength", strength);
        if (elapsedMs >= 0) o.put("elapsedMs", elapsedMs);
        if (!servedBy.isEmpty()) o.put("servedBy", servedBy);
        // Only for an Auto answer, so an explicit answer's record is exactly what it was.
        if (autoRouted()) {
            o.put("requested", requested);
            if (!routeReason.isEmpty()) o.put("routeReason", routeReason);
            if (routerPolicy > 0) o.put("routerPolicy", routerPolicy);
        }
        return o;
    }

    static ResponseDetails fromJson(JSONObject o) {
        if (o == null) return null;
        return new ResponseDetails(o.optString("provider", ""), o.optString("model", ""),
                o.optString("modelName", ""), o.optString("strength", ""),
                o.optLong("elapsedMs", -1), o.optString("requested", ""),
                o.optString("routeReason", ""), o.optInt("routerPolicy", 0),
                o.optString("servedBy", ""));
    }
}

package com.orbit.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Smart Routing (0.8.3.0-beta.5+): the one place Auto turns a request into an exact selection.
 *
 * <p><b>Explicit model = exact model; Auto = permission to route.</b> Nothing calls this for an
 * explicit selection. For Auto it runs once, when a request is queued, and its answer (provider,
 * model, strength, a short reason, and {@link #POLICY_VERSION}) is frozen with that request. The
 * worker sends exactly that; it never routes, and it refuses an unrouted Auto outright.
 *
 * <p><b>Local and deterministic.</b> Routing reads counts and a few plain signals Orbit already has
 * on the phone: how long the message is, whether it carries images or document text, roughly how
 * large the assembled request is (measured by the same builder as the context meter), whether it
 * reads like a phone instruction or a question about current events, and the Model Library's
 * structured capabilities. It never sends the message, an attachment or anything else to any
 * provider to decide, and there is no randomness: the same request under the same state always
 * routes the same way.
 *
 * <p><b>Two phases.</b> Eligibility removes what cannot or may not answer: providers the user has
 * not enabled for Auto, providers that are not ready, models outside the curated candidate set,
 * unavailable or deprecated models, models without a known context window, requests that do not
 * fit, and images a model cannot read. Selection then orders what is left by how well each model
 * suits the request's demand, prefers a route that can run phone actions or search when the request
 * needs one, prefers non-metered providers, uses a Favorite only to break a remaining tie, and
 * finally falls back to a fixed order.
 *
 * <p><b>One model, one attempt.</b> Auto picks a single route. A failure is reported and Retry asks
 * again; nothing here cascades to another provider.
 */
public final class SmartRouter {

    /** Stored with every routed request. Bump when the policy below changes meaning. */
    public static final int POLICY_VERSION = 1;

    /** Room left for the answer when checking that a request fits a model's window. */
    static final int OUTPUT_RESERVE_TOKENS = 8_192;
    /** Share of a model's published window Auto is willing to fill, since every count is estimated. */
    static final double WINDOW_USE = 0.9;
    /** From here a request is described as large-context. */
    static final int LARGE_CONTEXT_TOKENS = 120_000;
    /**
     * Most content (everything but Orbit's own instructions) Orbit Local is offered: comfortably
     * inside its 4,096-token window after its instructions and answer headroom, so nothing the
     * request needs is trimmed away to make it fit.
     */
    static final int LOCAL_MAX_CONTENT_TOKENS = 1_600;

    /** How much a request asks of a model. */
    enum Demand { SIMPLE, NORMAL, COMPLEX, VERY_COMPLEX }

    // ---- the curated candidate set ---------------------------------------------------------------

    /**
     * One model Auto may use, and how well it suits each {@link Demand}: 0 is what it is for, higher
     * is a further stretch. -1 means never for that demand.
     */
    static final class Candidate {
        final String provider;
        final String model;
        final int[] fit;

        Candidate(String provider, String model, int simple, int normal, int complex, int veryComplex) {
            this.provider = provider;
            this.model = model;
            this.fit = new int[]{simple, normal, complex, veryComplex};
        }

        int fitFor(Demand demand) { return fit[demand.ordinal()]; }
    }

    /**
     * The only models Auto routes to, in fixed tie-break order. A model a provider's live catalog
     * reports is never a candidate merely by appearing there: it has to be named here, still be in
     * the catalog, be active, and have a known context window.
     *
     * <p>Left out on purpose: GPT-6 Astra (access varies by account), the GPT-5.6 family (the GPT-6
     * models above cover the same ground), Claude Fable 5.1 (reserved for explicit choice), and the
     * relay (it spends an operator's metered key).
     */
    static final List<Candidate> CANDIDATES = Collections.unmodifiableList(Arrays.asList(
            new Candidate(Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL, 0, -1, -1, -1),
            new Candidate(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, 0, 0, 2, 3),
            new Candidate(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, 3, 2, 0, 0),
            new Candidate(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_HAIKU_4_5, 0, 1, 3, 3),
            new Candidate(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_SONNET_5_5, 2, 0, 1, 2),
            new Candidate(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_OPUS_5_5, 3, 2, 0, 0),
            new Candidate(Prefs.PROVIDER_XAI, OrbitModelCatalog.GROK_4_7, 1, 0, 0, 1)));

    /** True when this exact model is in Auto's curated set. */
    public static boolean isCandidate(String provider, String model) {
        for (Candidate c : CANDIDATES) if (c.provider.equals(provider) && c.model.equals(model)) return true;
        return false;
    }

    // ---- what the router knows about one candidate right now ------------------------------------

    /** One candidate with everything about the phone's current state that routing reads. */
    static final class Option {
        final Candidate candidate;
        /** The live catalog entry, or null when the provider's catalog no longer offers it. */
        final AiModelSpec spec;
        final boolean permitted;
        final boolean ready;
        final boolean knownUnavailable;
        final boolean favorite;
        final boolean providerImages;
        final boolean providerDeviceActions;
        final boolean providerWebSearch;

        Option(Candidate candidate, AiModelSpec spec, boolean permitted, boolean ready,
               boolean knownUnavailable, boolean favorite, boolean providerImages,
               boolean providerDeviceActions, boolean providerWebSearch) {
            this.candidate = candidate;
            this.spec = spec;
            this.permitted = permitted;
            this.ready = ready;
            this.knownUnavailable = knownUnavailable;
            this.favorite = favorite;
            this.providerImages = providerImages;
            this.providerDeviceActions = providerDeviceActions;
            this.providerWebSearch = providerWebSearch;
        }

        boolean local() { return Prefs.PROVIDER_LOCAL.equals(candidate.provider); }
        boolean metered() { return AutoPermissions.metered(candidate.provider); }
        boolean vision() { return spec != null && spec.vision && providerImages; }
        boolean webSearch() { return spec != null && spec.webSearch && providerWebSearch; }
    }

    /** Every curated candidate as the phone sees it now. Reads state; changes nothing. */
    static List<Option> options(Context c) {
        ProviderCatalogRepository.loadCached(c);
        List<Option> out = new ArrayList<>();
        for (Candidate candidate : CANDIDATES) {
            AiProvider provider = AiProviders.byId(candidate.provider);
            AiCapabilities caps = provider.capabilities();
            boolean ready;
            try {
                ready = provider.id().equals(candidate.provider)
                        && provider.status(c) == AiProvider.Status.READY;
            } catch (RuntimeException e) {
                ready = false;
            }
            out.add(new Option(candidate,
                    OrbitModelCatalog.spec(candidate.provider, candidate.model),
                    AutoPermissions.allows(c, candidate.provider), ready,
                    ModelAvailability.knownUnavailable(c, candidate.provider, candidate.model),
                    ModelLibraryStore.isFavorite(c, candidate.provider, candidate.model),
                    caps.images, caps.deviceActions, caps.hostedWebSearch));
        }
        return out;
    }

    // ---- the request -----------------------------------------------------------------------------

    /**
     * What routing knows about one request: a few counts and signals, all computed on the phone.
     * The message text is read here and nowhere else in routing, and it is never stored.
     */
    static final class Request {
        final int imageCount;
        final int attachmentChars;
        /** Estimated size of the whole assembled request, including Orbit's instructions. */
        final int estimatedTokens;
        /** The same, without Orbit's own instructions. */
        final int contentTokens;
        final Demand demand;
        final boolean deviceAction;
        final boolean currentInfo;

        Request(String prompt, int imageCount, int attachmentChars, int estimatedTokens,
                int contentTokens) {
            String text = prompt == null ? "" : prompt.trim();
            this.imageCount = Math.max(0, imageCount);
            this.attachmentChars = Math.max(0, attachmentChars);
            this.estimatedTokens = Math.max(0, estimatedTokens);
            this.contentTokens = Math.max(0, Math.min(this.estimatedTokens, contentTokens));
            this.demand = demand(text, this.imageCount, this.attachmentChars, this.estimatedTokens);
            this.deviceAction = OrbitLocalActionRouter.looksActionable(text);
            this.currentInfo = CURRENT_INFO.matcher(text.toLowerCase(Locale.US)).find();
        }

        boolean largeContext() { return estimatedTokens >= LARGE_CONTEXT_TOKENS; }
    }

    /** Words that ask for real reasoning work rather than recall or chat. Kept deliberately short. */
    private static final Pattern REASONING = Pattern.compile(
            "\\b(prove|proof|derive|debug|optimi[sz]e|algorithm|theorem|step[- ]by[- ]step|"
                    + "trade-?offs?|architecture|refactor|analy[sz]e|analysis|calculate|solve|"
                    + "evaluate|in depth|rigorous)\\b");
    /** An explanation is ordinary conversation, never a "simple" request for the smallest model. */
    private static final Pattern EXPLANATORY = Pattern.compile(
            "\\b(explain|describe|compare|summari[sz]e|why|how (does|do|can|would|should|is|are))\\b");
    /** Time-sensitive questions, which only a route with Orbit's web search can answer well. */
    private static final Pattern CURRENT_INFO = Pattern.compile(
            "\\b(today|tonight|yesterday|this week|right now|latest|news|currently|"
                    + "current (price|events?|status)|stock price|who won|breaking)\\b");
    private static final Pattern CODE = Pattern.compile(
            "```|\\b(def|class|function|public|private|return|import|const|var)\\b[^\\n]*[({;=]");

    /** How much a request asks for. Counts and a few plain signals; no phrase-by-phrase rules. */
    static Demand demand(String prompt, int images, int attachmentChars, int estimatedTokens) {
        String text = prompt == null ? "" : prompt.trim();
        String lower = text.toLowerCase(Locale.US);
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        int score = 0;
        if (words > 40) score++;
        if (words > 150) score++;
        if (words > 400) score++;
        if (CODE.matcher(text).find()) score += 2;
        int cues = 0;
        java.util.regex.Matcher m = REASONING.matcher(lower);
        List<String> seen = new ArrayList<>();
        while (m.find() && cues < 2) {
            if (!seen.contains(m.group(1))) {
                seen.add(m.group(1));
                cues++;
            }
        }
        score += cues * 2;
        if (countQuestions(text) >= 3) score++;
        if (attachmentChars > 4_000) score++;
        if (attachmentChars > 40_000) score++;
        if (estimatedTokens >= LARGE_CONTEXT_TOKENS) score++;
        boolean explanatory = EXPLANATORY.matcher(lower).find();
        boolean small = words <= 12 && images == 0 && attachmentChars == 0 && !explanatory;
        if (score == 0 && small) return Demand.SIMPLE;
        if (score <= 1) return Demand.NORMAL;
        if (score <= 4) return Demand.COMPLEX;
        return Demand.VERY_COMPLEX;
    }

    private static int countQuestions(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '?') n++;
        return n;
    }

    /**
     * Builds the routing view of a request about to be queued. {@code history} is the conversation
     * as the request will be built from it, ending with the turn being answered.
     */
    static Request describe(Context c, List<AssistantClient.History> history, String prompt,
                            String attachmentText, List<android.graphics.Bitmap> images,
                            boolean explicitAttachment, KeptContext.Prepared kept) {
        int imageCount = 0;
        if (images != null) for (android.graphics.Bitmap image : images) if (image != null) imageCount++;
        int[] size = ContextEstimate.assembledSize(c, history, prompt, attachmentText, images,
                explicitAttachment, kept);
        return new Request(prompt, imageCount, attachmentText == null ? 0 : attachmentText.length(),
                size[0], size[1]);
    }

    // ---- the route -------------------------------------------------------------------------------

    /**
     * What Auto decided for one request: an exact selection with its reason, or an error explaining
     * why no permitted model could take it. Stored with the request; never recomputed.
     */
    public static final class Route {
        /** The exact selection the request is sent with, or null when routing failed. */
        public final AiSelection selection;
        /** A short factual category, e.g. "Large context + complex reasoning". Never scoring. */
        public final String reason;
        public final int policy;
        /** What the user is told when nothing could be chosen; empty on success. */
        public final String error;

        Route(AiSelection selection, String reason, int policy, String error) {
            this.selection = selection;
            this.reason = reason == null ? "" : reason;
            this.policy = policy;
            this.error = error == null ? "" : error;
        }

        public boolean ok() { return selection != null && !selection.isAuto() && error.isEmpty(); }

        static Route failed(String error) { return new Route(null, "", POLICY_VERSION, error); }

        /** The routing record stored beside the request's exact selection. */
        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject().put("requested", AiSelection.AUTO_ID)
                    .put("policy", policy);
            if (!reason.isEmpty()) o.put("reason", reason);
            if (!error.isEmpty()) o.put("error", error);
            return o;
        }

        /** Reads a stored routing record back; {@code exact} is the selection stored beside it. */
        static Route fromJson(JSONObject o, AiSelection exact) {
            if (o == null || !AiSelection.AUTO_ID.equals(o.optString("requested", ""))) return null;
            String error = o.optString("error", "");
            return new Route(error.isEmpty() && exact != null && !exact.isAuto() ? exact : null,
                    o.optString("reason", ""), o.optInt("policy", POLICY_VERSION), error);
        }
    }

    /** Routes one request with the phone's current state. */
    static Route route(Context c, Request request) {
        return route(request, options(c));
    }

    /** The routing decision itself. Pure: the same inputs always give the same route. */
    static Route route(Request request, List<Option> options) {
        List<Option> usable = new ArrayList<>();
        for (Option o : options) if (o.permitted && o.ready) usable.add(o);
        if (usable.isEmpty()) return Route.failed(failureMessage(request, options, usable));
        List<Option> eligible = new ArrayList<>();
        boolean contextExcluded = false;
        for (Option o : usable) {
            Exclusion why = exclusion(o, request);
            if (why == null) eligible.add(o);
            else if (why == Exclusion.CONTEXT) contextExcluded = true;
        }
        if (eligible.isEmpty()) return Route.failed(failureMessage(request, options, usable));

        final Demand demand = request.demand;
        List<Option> ranked = new ArrayList<>(eligible);
        Collections.sort(ranked, Comparator
                // A route that can carry out the phone action, or search, the request needs.
                .comparingInt((Option o) -> request.deviceAction && !o.providerDeviceActions ? 1 : 0)
                .thenComparingInt(o -> request.currentInfo && !o.webSearch() ? 1 : 0)
                // How well the model suits what the request asks for.
                .thenComparingInt(o -> o.candidate.fitFor(demand))
                // The user's account or the phone before a separately billed API.
                .thenComparingInt(o -> o.metered() ? 1 : 0)
                // A Favorite only breaks a tie that is otherwise exact.
                .thenComparingInt(o -> o.favorite ? 0 : 1)
                .thenComparingInt(o -> CANDIDATES.indexOf(o.candidate)));
        Option chosen = ranked.get(0);
        AiSelection selection = AiSelection.of(chosen.candidate.provider, chosen.candidate.model,
                strengthFor(chosen.spec, demand));
        return new Route(selection, reason(request, chosen, contextExcluded), POLICY_VERSION, "");
    }

    private enum Exclusion { UNAVAILABLE, METADATA, CONTEXT, VISION, DEMAND, LOCAL_LIMITS }

    /** Why this option cannot take this request, or null when it can. Permission is checked apart. */
    private static Exclusion exclusion(Option o, Request r) {
        AiModelSpec spec = o.spec;
        if (spec == null || !"active".equals(spec.availability) || !spec.selectable()
                || o.knownUnavailable) return Exclusion.UNAVAILABLE;
        if (o.candidate.fitFor(r.demand) < 0) {
            return o.local() ? Exclusion.LOCAL_LIMITS : Exclusion.DEMAND;
        }
        if (r.imageCount > 0 && !o.vision()) return Exclusion.VISION;
        if (o.local()) {
            // Orbit Local fits each request to its own small window; Auto offers it only what fits
            // there whole, and nothing that needs search it does not have.
            if (r.contentTokens > LOCAL_MAX_CONTENT_TOKENS || r.currentInfo) return Exclusion.LOCAL_LIMITS;
            return null;
        }
        if (spec.contextWindowTokens <= 0) return Exclusion.METADATA;
        if (!fits(r.estimatedTokens, spec.contextWindowTokens)) return Exclusion.CONTEXT;
        return null;
    }

    static boolean fits(int tokens, int window) {
        return window > 0 && tokens + OUTPUT_RESERVE_TOKENS <= window * WINDOW_USE;
    }

    /**
     * The strength Auto asks for: Low, Medium, High or Extra High by demand, moved to the nearest
     * one this model accepts. Auto never asks for Max; that stays an explicit choice.
     */
    static AiStrength strengthFor(AiModelSpec spec, Demand demand) {
        if (spec == null || !spec.hasStrengths()) return null;
        AiStrength wanted;
        switch (demand) {
            case SIMPLE: wanted = AiStrength.LOW; break;
            case COMPLEX: wanted = AiStrength.HIGH; break;
            case VERY_COMPLEX: wanted = AiStrength.XHIGH; break;
            default: wanted = AiStrength.MEDIUM; break;
        }
        AiStrength resolved = spec.resolveStrength(wanted);
        if (resolved == AiStrength.MAX) {
            for (int i = spec.strengths.size() - 1; i >= 0; i--) {
                if (spec.strengths.get(i) != AiStrength.MAX) return spec.strengths.get(i);
            }
        }
        return resolved;
    }

    // ---- what Auto says ----------------------------------------------------------------------------

    static final String SIMPLE = "Simple request";
    static final String NORMAL = "Normal conversation";
    static final String COMPLEX = "Complex reasoning";
    static final String IMAGE = "Image input required";
    static final String LARGE_CONTEXT = "Large context";
    static final String LOCAL = "Local model sufficient";
    static final String DEVICE_ACTION = "Device action";
    static final String CURRENT = "Current information";
    static final String PREFERRED = "Preferred eligible model";

    /** At most two short categories, joined with "+". */
    private static String reason(Request r, Option chosen, boolean contextExcluded) {
        if (chosen.local()) return LOCAL;
        List<String> parts = new ArrayList<>();
        if (r.imageCount > 0) parts.add(IMAGE);
        else if (contextExcluded || r.largeContext()) parts.add(LARGE_CONTEXT);
        else if (r.deviceAction && chosen.providerDeviceActions) parts.add(DEVICE_ACTION);
        else if (r.currentInfo && chosen.webSearch()) parts.add(CURRENT);
        switch (r.demand) {
            case SIMPLE: parts.add(SIMPLE); break;
            case NORMAL: parts.add(NORMAL); break;
            default: parts.add(COMPLEX); break;
        }
        if (parts.size() < 2 && chosen.candidate.fitFor(r.demand) > 0) parts.add(PREFERRED);
        StringBuilder out = new StringBuilder(parts.get(0));
        for (int i = 1; i < parts.size() && i < 2; i++) {
            String next = parts.get(i);
            out.append(" + ").append(Character.toLowerCase(next.charAt(0))).append(next.substring(1));
        }
        return out.toString();
    }

    static final String HINT = " Choose a model from the AI menu, or change which providers Auto "
            + "can use in Settings > Intelligence > Auto.";

    /**
     * Why nothing could be chosen. When a connected provider the user has not enabled for Auto could
     * have taken the request, Orbit says so by name, and still does not use it.
     */
    private static String failureMessage(Request r, List<Option> all, List<Option> usable) {
        for (Option o : all) {
            if (o.permitted || !o.ready) continue;
            if (exclusion(o, r) == null) {
                String name = AutoPermissions.shortName(o.candidate.provider);
                return name + " supports this request, but " + name + " is not enabled for Auto."
                        + HINT;
            }
        }
        if (usable.isEmpty()) return "No Auto-enabled provider is currently available." + HINT;
        if (r.imageCount > 0) {
            boolean anyVision = false;
            for (Option o : usable) if (o.vision()) anyVision = true;
            if (!anyVision) return "No Auto-enabled model can handle this image." + HINT;
        }
        boolean anyFits = false;
        for (Option o : usable) {
            if (o.local() ? r.contentTokens <= LOCAL_MAX_CONTENT_TOKENS
                    : o.spec != null && fits(r.estimatedTokens, o.spec.contextWindowTokens)) {
                anyFits = true;
            }
        }
        if (!anyFits) return "No Auto-enabled model can fit this much context." + HINT;
        return "No Auto-enabled model can handle this request." + HINT;
    }
}

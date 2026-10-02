package com.orbit.assistant;

import android.content.Context;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Which providers Auto may route to (0.8.3.0-beta.5+).
 *
 * <p><b>Connected is not permitted.</b> A saved Anthropic, xAI or OpenRouter credential lets the
 * user pick those models explicitly; it never lets Auto spend through them. All three are billed
 * per request (a developer API, or OpenRouter credits), so their Auto permission is off until the
 * user turns it on here, a migration never turns it on, a backup never restores it, signing in
 * never turns it on, and removing the credential turns it back off so a later one starts off too.
 *
 * <p>ChatGPT and Orbit Local are on by default: ChatGPT uses the account the user signed in with,
 * and Orbit Local runs on the phone. Either is still only a candidate while it is actually ready.
 *
 * <p>Only {@link SmartRouter} reads this and only the user's own switches write it. Nothing in the
 * routing path can change a permission.
 */
public final class AutoPermissions {

    /** The providers Auto can ever consider, in the order the settings sheet lists them. */
    public static final List<String> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
            Prefs.PROVIDER_CHATGPT, Prefs.PROVIDER_LOCAL,
            Prefs.PROVIDER_ANTHROPIC, Prefs.PROVIDER_XAI, Prefs.PROVIDER_OPENROUTER));

    private AutoPermissions() {}

    /** True for a provider billed per request: an API key of the user's own, or OpenRouter credits. */
    public static boolean metered(String provider) {
        return Prefs.PROVIDER_ANTHROPIC.equals(provider) || Prefs.PROVIDER_XAI.equals(provider)
                || Prefs.PROVIDER_OPENROUTER.equals(provider);
    }

    /** The preference behind one provider's switch, or null for a provider Auto never uses. */
    static String key(String provider) {
        if (Prefs.PROVIDER_CHATGPT.equals(provider)) return Prefs.AUTO_USE_CHATGPT;
        if (Prefs.PROVIDER_LOCAL.equals(provider)) return Prefs.AUTO_USE_LOCAL;
        if (Prefs.PROVIDER_ANTHROPIC.equals(provider)) return Prefs.AUTO_USE_ANTHROPIC;
        if (Prefs.PROVIDER_XAI.equals(provider)) return Prefs.AUTO_USE_XAI;
        if (Prefs.PROVIDER_OPENROUTER.equals(provider)) return Prefs.AUTO_USE_OPENROUTER;
        return null;
    }

    /** The value a provider has before the user touches its switch. Metered providers: off. */
    static boolean defaultFor(String provider) {
        return key(provider) != null && !metered(provider);
    }

    /**
     * Whether the user lets Auto route to this provider. The relay is never an Auto provider: it
     * spends an operator's metered key.
     */
    public static boolean allows(Context c, String provider) {
        String key = key(provider);
        if (c == null || key == null) return false;
        return Prefs.get(c).getBoolean(key, defaultFor(provider));
    }

    /** The user's own switch. The only writer of a permission. */
    public static void set(Context c, String provider, boolean allowed) {
        String key = key(provider);
        if (c == null || key == null) return;
        Prefs.get(c).edit().putBoolean(key, allowed).apply();
    }

    /**
     * A metered provider's credential was removed: its Auto permission goes with it, so saving a
     * credential again later never brings Auto spending back on its own.
     */
    static void revokeOnCredentialRemoval(Context c, String provider) {
        if (c == null || !metered(provider)) return;
        String key = key(provider);
        if (key != null) Prefs.get(c).edit().putBoolean(key, false).apply();
    }

    /** "ChatGPT, Orbit Local": what Auto may use right now, for one quiet summary line. */
    public static String summary(Context c) {
        StringBuilder out = new StringBuilder();
        for (String provider : PROVIDERS) {
            if (!allows(c, provider)) continue;
            if (out.length() > 0) out.append(", ");
            out.append(shortName(provider));
        }
        return out.length() == 0 ? "No providers" : out.toString();
    }

    /** The name the Auto sheet uses: the company for API providers, the product otherwise. */
    static String shortName(String provider) {
        if (Prefs.PROVIDER_CHATGPT.equals(provider)) return "ChatGPT";
        if (Prefs.PROVIDER_LOCAL.equals(provider)) return "Orbit Local";
        if (Prefs.PROVIDER_ANTHROPIC.equals(provider)) return "Anthropic";
        if (Prefs.PROVIDER_XAI.equals(provider)) return "xAI";
        if (Prefs.PROVIDER_OPENROUTER.equals(provider)) return "OpenRouter";
        return AiProviders.byId(provider).displayName();
    }
}

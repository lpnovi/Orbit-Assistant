package com.orbit.assistant;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * The one decision about whether Orbit may contact a web address, and which one.
 *
 * <p>Rich Answers gave Orbit a reason to fetch things nobody typed. A model names a page, Orbit
 * reads that page's declared preview image, and then fetches the image - three network calls from
 * addresses the user never saw. That is a request-forgery surface, and the answer to it is not care
 * at each call site: it is one policy that every fetch has to pass, applied again at every redirect
 * hop, so a redirect chain cannot walk a validated public address round to something on this
 * device's own network.
 *
 * <p><b>What is refused, and why.</b> Anything but {@code https} for a fetch, because a rich image
 * is decoration on an answer and is not worth a cleartext request. {@code file:}, {@code content:},
 * {@code javascript:} and {@code intent:} outright, because those are not web addresses at all and
 * one of them reaches this app's own private storage. A URL carrying credentials, because Orbit has
 * no business replaying somebody's user info. And every address that resolves onto this device or
 * the network it is sitting on - loopback, link-local, RFC1918, carrier-grade NAT, unique local
 * IPv6 - because a phone's local network is exactly what an attacker cannot otherwise reach.
 *
 * <p>Two questions, deliberately separate. {@link #isFetchableImageUrl} and
 * {@link #isFetchablePageUrl} decide what Orbit will contact <em>itself</em>, and are strict.
 * {@link #isOpenableWebUrl} decides what Orbit will hand to Android when the user taps Open source,
 * and allows ordinary {@code http} because that is a normal link the browser is entitled to handle
 * - Orbit is not fetching it, the user asked for it, and it opens somewhere the user can see.
 *
 * <p>Syntax is checked without a network. The address check needs DNS and therefore blocks, so it
 * is only ever asked on a background thread, immediately before the connection it guards.
 */
public final class RichAnswerUrlPolicy {

    /** How many redirects one fetch may follow before Orbit gives up on it. */
    public static final int MAX_REDIRECTS = 4;

    private RichAnswerUrlPolicy() {}

    /**
     * Whether this is syntactically an address Orbit may fetch: https, a real host, no credentials.
     *
     * <p>Everything here is decided from the text of the URL, so it is cheap, deterministic, and
     * safe to call on any thread. It is never sufficient on its own - {@link #resolvesToPublicHost}
     * is what actually keeps a fetch off this device's network.
     */
    public static boolean hasSafeFetchSyntax(String value) {
        URI uri = parse(value);
        if (uri == null) return false;
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        if (uri.getUserInfo() != null) return false;
        // "https://user:pass@host" does not always populate getUserInfo once a URI has been
        // resolved from a relative reference, so the authority is checked directly as well.
        String authority = uri.getRawAuthority();
        if (authority != null && authority.indexOf('@') >= 0) return false;
        String host = asciiHost(uri.getHost());
        return !host.isEmpty() && !isBlockedHostname(host);
    }

    /** The same question for a page Orbit will read metadata out of. */
    public static boolean isFetchablePageUrl(String value) {
        return hasSafeFetchSyntax(value);
    }

    /** The same question for a picture Orbit will download and decode. */
    public static boolean isFetchableImageUrl(String value) {
        return hasSafeFetchSyntax(value);
    }

    /**
     * Whether Orbit may hand this address to Android because the user tapped it.
     *
     * <p>Wider than a fetch and still closed: ordinary {@code http} and {@code https} only, a real
     * host, and no credentials. {@code intent:} in particular is refused here rather than only at
     * the fetch, because that is the scheme that turns a link into an Activity launch, and the
     * whole point of Open source is that it opens a page in a browser.
     */
    public static boolean isOpenableWebUrl(String value) {
        URI uri = parse(value);
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        if (uri.getUserInfo() != null) return false;
        String authority = uri.getRawAuthority();
        if (authority != null && authority.indexOf('@') >= 0) return false;
        String host = asciiHost(uri.getHost());
        // A hostname with no dot is either a bare label on the local network or a typo; neither is
        // a page a citation legitimately points at.
        return !host.isEmpty() && host.indexOf('.') > 0 && !isBlockedHostname(host);
    }

    /**
     * Where a redirect actually leads, or empty when it leads somewhere Orbit will not follow.
     *
     * <p>Resolved against the address that issued it, so a relative {@code Location} works exactly
     * as a browser would resolve it, and then put through the full policy again. Revalidating every
     * hop is the point: a first hop that passes proves nothing at all about the second.
     */
    public static String redirectTarget(String from, String location) {
        if (location == null || location.trim().isEmpty()) return "";
        try {
            // Both sides are normalised first. A {@code Location} header is written by somebody
            // else and routinely contains the same unencoded characters a filename does, so
            // resolving it raw threw before it could ever be judged - which read as a redirect
            // being blocked when nothing unsafe had happened at all.
            String base = normalizedForRequest(from);
            String target = normalizedForRequest(location);
            if (base.isEmpty() || target.isEmpty()) return "";
            String resolved = URI.create(base).resolve(target).toString();
            return hasSafeFetchSyntax(resolved) ? resolved : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Whether every address this host resolves to is on the public internet.
     *
     * <p>Every address, not the first one: a name that answers with one public address and one
     * private address is a name Orbit refuses outright, because which of them a connection lands on
     * is not something this check controls.
     *
     * <p>Performs DNS. Background threads only.
     */
    public static boolean resolvesToPublicHost(String value) {
        if (!hasSafeFetchSyntax(value)) return false;
        try {
            URI uri = parse(value);
            if (uri == null) return false;
            InetAddress[] addresses = InetAddress.getAllByName(asciiHost(uri.getHost()));
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) if (!isPublicAddress(address)) return false;
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Hostnames that never leave this device or its network, refused before DNS is consulted.
     *
     * <p>Cheap, and it closes the case where a resolver hands back something plausible for a name
     * that plainly is not a public web host.
     */
    static boolean isBlockedHostname(String host) {
        if (host == null || host.isEmpty()) return true;
        String lower = host.toLowerCase(Locale.US);
        if (lower.equals("localhost") || lower.endsWith(".localhost")) return true;
        if (lower.endsWith(".local") || lower.endsWith(".internal") || lower.endsWith(".home.arpa")) return true;
        // An address written as a literal needs no resolver, so it is decided here rather than
        // waiting for the DNS check. That matters beyond tidiness: a literal reaches places a
        // hostname never gets to - a stored record read back, a candidate scored, a URL judged on
        // a thread that must not block - and every one of those would otherwise see 192.168.1.1
        // as an ordinary public address.
        return isPrivateLiteralAddress(lower);
    }

    /**
     * Whether a hostname is an IP literal that points somewhere private.
     *
     * <p>Only literals. A name is left to {@link #resolvesToPublicHost}, because what a name means
     * is a question for a resolver and not for a parser. {@code InetAddress.getByName} performs no
     * lookup for a literal, so this stays a pure syntax check.
     */
    static boolean isPrivateLiteralAddress(String host) {
        if (host == null || host.isEmpty()) return false;
        String value = host;
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        boolean looksNumeric = value.indexOf(':') >= 0;
        if (!looksNumeric) {
            looksNumeric = true;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (!Character.isDigit(c) && c != '.') { looksNumeric = false; break; }
            }
        }
        if (!looksNumeric) return false;
        try {
            return !isPublicAddress(InetAddress.getByName(value));
        } catch (Exception ignored) {
            // Something shaped like an address that will not parse as one is not an address Orbit
            // is willing to guess about.
            return true;
        }
    }

    /**
     * Whether one resolved address is genuinely on the public internet.
     *
     * <p>The JDK's own predicates cover most of it; the explicit ranges below cover what they do
     * not - carrier-grade NAT, the documentation and benchmarking blocks, and IPv6 unique local
     * addresses - so a device behind a mobile network cannot be talked into probing its carrier's
     * private space.
     */
    static boolean isPublicAddress(InetAddress address) {
        if (address == null) return false;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                address.isLinkLocalAddress() || address.isSiteLocalAddress() ||
                address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
            if (a == 100 && b >= 64 && b <= 127) return false;      // carrier-grade NAT
            if (a == 169 && b == 254) return false;                 // link-local
            if (a == 172 && b >= 16 && b <= 31) return false;       // RFC1918
            if (a == 192 && b == 168) return false;                 // RFC1918
            if (a == 192 && b == 0 && (c == 0 || c == 2)) return false;
            if (a == 198 && (b == 18 || b == 19)) return false;     // benchmarking
            if (a == 198 && b == 51 && c == 100) return false;      // documentation
            if (a == 203 && b == 0 && c == 113) return false;       // documentation
            return true;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return false;               // unique local
            if (first == 0xff) return false;                        // multicast
        }
        return true;
    }

    /** The host as ASCII and lower case, or empty when there is not one. */
    static String asciiHost(String host) {
        if (host == null) return "";
        String trimmed = host.trim();
        if (trimmed.isEmpty()) return "";
        try {
            return IDN.toASCII(trimmed).toLowerCase(Locale.US);
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * The address Orbit will actually put on the wire, percent-encoded where it has to be.
     *
     * <p>This exists because of a real failure on a real device. Java sends a URL's path bytes
     * more or less as it finds them, so an ordinary Wikimedia filename containing an accent or a
     * non-Latin character produced a raw high byte in the request line and the CDN answered
     * <b>HTTP 400</b> - a legitimate public picture, refused for a reason that had nothing to do
     * with safety. Browsers encode those characters before sending; Orbit now does too.
     *
     * <p><b>It is an encoder, not a relaxation.</b> The characters that make request-splitting
     * possible - carriage return, line feed, tab, every other control character - are still refused
     * outright rather than encoded, because a URL containing one is not a URL somebody meant to
     * write. What gets encoded is the ordinary stuff a person types into a filename: a space, an
     * accent, a non-Latin script. An escape that is already there is left exactly as it is, so a
     * correctly written {@code %28} never becomes {@code %2528}.
     *
     * @return the encoded address, or empty when this is not something Orbit will fetch.
     */
    public static String normalizedForRequest(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > RichAnswerImage.MAX_URL_CHARS) return "";
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Control characters, including CR, LF and tab. Never encoded, never sent: a request
            // line cannot be split by something that was refused before it got here.
            if (c < 0x20 || c == 0x7f) return "";
            if (c == ' ') { out.append("%20"); continue; }
            if (c == '%') {
                // An escape that is already well formed is preserved untouched; a stray percent
                // sign is encoded so it cannot be read as the start of one.
                if (i + 2 < text.length() && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2))) {
                    out.append(text, i, i + 3);
                    i += 2;
                } else {
                    out.append("%25");
                }
                continue;
            }
            if (c < 0x80) { out.append(c); continue; }
            // Everything above ASCII is written as its UTF-8 bytes, which is what a browser sends
            // and what a CDN expects to receive.
            out.append(percentEncodeUtf8(text.substring(i, i + charCountAt(text, i))));
            i += charCountAt(text, i) - 1;
        }
        return out.toString();
    }

    private static int charCountAt(String text, int index) {
        return Character.charCount(text.codePointAt(index));
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static String percentEncodeUtf8(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            out.append('%').append(String.format(Locale.US, "%02X", b & 0xff));
        }
        return out.toString();
    }

    /**
     * Parses an address for judging, after normalising what a browser would normalise.
     *
     * <p>The normalisation happens first so that the decision Orbit makes about an address is the
     * decision about the address it is going to send. Judging the raw text and then sending
     * something slightly different is how a check and a request come to disagree.
     */
    private static URI parse(String value) {
        String text = normalizedForRequest(value);
        if (text.isEmpty()) return null;
        try {
            URI uri = new URI(text);
            return uri.isAbsolute() ? uri : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}

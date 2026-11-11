package dev.ledgerforge.webhook;

import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * SSRF defence for outbound webhooks. A URL is accepted only if:
 *
 * <ul>
 *   <li>it is absolute {@code https} ({@code http} only with an explicit development flag);
 *   <li>it has no user-info, no fragment, and is at most 2048 characters;
 *   <li>every address its host resolves to is publicly routable: loopback, private (RFC 1918),
 *       link-local (incl. cloud metadata 169.254.169.254), CGNAT, unique-local IPv6, multicast,
 *       unspecified and reserved ranges are rejected unless private targets are explicitly allowed
 *       for local development.
 * </ul>
 *
 * The check runs at registration and again immediately before every delivery (mitigating DNS
 * rebinding). Redirects are never followed by the delivery client.
 */
@Component
public class WebhookUrlValidator {

    private final boolean allowPrivateTargets;
    private final boolean allowPlainHttp;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookUrlValidator(AppProperties props) {
        this(props.webhooks().allowPrivateTargets(), props.webhooks().allowPlainHttp());
    }

    public WebhookUrlValidator(boolean allowPrivateTargets, boolean allowPlainHttp) {
        this.allowPrivateTargets = allowPrivateTargets;
        this.allowPlainHttp = allowPlainHttp;
    }

    public URI validate(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 2048) {
            throw reject("URL is required and must be at most 2048 characters");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw reject("URL is not valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowPlainHttp && scheme.equals("http"))) {
            throw reject(allowPlainHttp ? "URL must use http or https" : "URL must use https");
        }
        if (uri.getRawUserInfo() != null) {
            throw reject("URL must not contain credentials");
        }
        if (uri.getRawFragment() != null) {
            throw reject("URL must not contain a fragment");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw reject("URL must include a host");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw reject("URL port is invalid");
        }
        if (!allowPrivateTargets) {
            String lower = host.toLowerCase(Locale.ROOT);
            if (lower.equals("localhost")
                    || lower.endsWith(".localhost")
                    || lower.endsWith(".local")
                    || lower.endsWith(".internal")
                    || lower.endsWith(".lan")
                    || lower.endsWith(".home.arpa")) {
                throw reject("URL host is not publicly routable");
            }
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw reject("URL host could not be resolved");
            }
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw reject("URL resolves to a private or reserved network address");
                }
            }
        }
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int a0 = b[0] & 0xff;
            int a1 = b[1] & 0xff;
            if (a0 == 0) return false; // 0.0.0.0/8 "this network"
            if (a0 == 100 && a1 >= 64 && a1 <= 127) return false; // 100.64.0.0/10 CGNAT
            if (a0 == 192 && a1 == 0 && (b[2] & 0xff) == 0) return false; // 192.0.0.0/24 IETF
            if (a0 == 192 && a1 == 0 && (b[2] & 0xff) == 2) return false; // TEST-NET-1
            if (a0 == 198 && (a1 == 18 || a1 == 19)) return false; // benchmarking
            if (a0 == 198 && a1 == 51 && (b[2] & 0xff) == 100) return false; // TEST-NET-2
            if (a0 == 203 && a1 == 0 && (b[2] & 0xff) == 113) return false; // TEST-NET-3
            if (a0 >= 240) return false; // reserved + broadcast
            return true;
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return false; // fc00::/7 unique local
            if (first == 0xfe && (b[1] & 0xc0) == 0xc0) return false; // fec0::/10 site local (deprecated)
            if (first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return false; // 2001:db8::/32 documentation
            }
            // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible addresses: judge the embedded IPv4.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && ((b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff || (b[10] == 0 && b[11] == 0))) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static ApiException reject(String detail) {
        return new ApiException(ErrorCode.WEBHOOK_URL_REJECTED, detail);
    }
}

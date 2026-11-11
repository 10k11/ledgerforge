package dev.ledgerforge.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Client metadata helpers. The remote address is taken from the servlet container; when deployed
 * behind a trusted reverse proxy, enable {@code server.forward-headers-strategy=native} so that
 * Tomcat (not application code) resolves X-Forwarded-For safely.
 */
public final class ClientInfo {

    private ClientInfo() {}

    public static String ipAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua == null) {
            return null;
        }
        return ua.length() > 255 ? ua.substring(0, 255) : ua;
    }

    public static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    public static String currentIp() {
        HttpServletRequest req = currentRequest();
        return req == null ? null : ipAddress(req);
    }
}

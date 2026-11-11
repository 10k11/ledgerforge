package dev.ledgerforge.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal browser-like API client for integration tests: keeps cookies (refresh + XSRF), sends the
 * bearer token, and exposes JSON responses.
 */
public class ApiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;
    private String accessToken;
    private UUID userId;

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    public record Response(int status, Map<String, String> headers, String body) {

        public Response expectStatus(int expected) {
            assertThat(status).as("HTTP status; body=%s", body).isEqualTo(expected);
            return this;
        }

        public JsonNode json() {
            return body == null || body.isBlank() ? JSON.nullNode() : JSON.readTree(body);
        }

        public String code() {
            return json().path("code").asString();
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    // ------------------------------------------------------------------ auth helpers

    public Response register(
            String email, String name, String password, String type, String currency, String business) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", name);
        body.put("email", email);
        body.put("password", password);
        body.put("accountType", type);
        body.put("currency", currency);
        if (business != null) {
            body.put("businessName", business);
        }
        body.put("acceptTerms", true);
        Response r = post("/api/v1/auth/register", body, null).expectStatus(201);
        adopt(r);
        return r;
    }

    public Response login(String email, String password) {
        Response r = post("/api/v1/auth/login", Map.of("email", email, "password", password), null);
        if (r.status() == 200) {
            adopt(r);
        }
        return r;
    }

    /** Refresh exactly like the SPA: fetch CSRF cookie, then POST with the X-XSRF-TOKEN header. */
    public Response refresh() {
        get("/api/v1/auth/csrf");
        Response r = send("POST", "/api/v1/auth/refresh", null, Map.of("X-XSRF-TOKEN", csrfCookie()), false);
        if (r.status() == 200) {
            adopt(r);
        }
        return r;
    }

    public Response logout() {
        get("/api/v1/auth/csrf");
        return send("POST", "/api/v1/auth/logout", null, Map.of("X-XSRF-TOKEN", csrfCookie()), false);
    }

    private void adopt(Response r) {
        JsonNode json = r.json();
        accessToken = json.get("accessToken").asString();
        userId = UUID.fromString(json.get("user").get("id").asString());
    }

    public String csrfCookie() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse("");
    }

    public String refreshCookie() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("lf_refresh"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    public void setRefreshCookie(String value) {
        cookies.getCookieStore().removeAll();
        HttpCookie cookie = new HttpCookie("lf_refresh", value);
        cookie.setPath("/api/v1/auth");
        cookie.setVersion(0);
        cookies.getCookieStore().add(URI.create(baseUrl + "/api/v1/auth"), cookie);
    }

    public UUID userId() {
        return userId;
    }

    public String accessToken() {
        return accessToken;
    }

    public void setAccessToken(String token) {
        this.accessToken = token;
    }

    // ------------------------------------------------------------------ HTTP

    public Response get(String path) {
        return send("GET", path, null, Map.of(), true);
    }

    public Response delete(String path) {
        return send("DELETE", path, null, Map.of(), true);
    }

    public Response patch(String path, Object body) {
        return send("PATCH", path, body, Map.of(), true);
    }

    public Response post(String path, Object body, String idempotencyKey) {
        Map<String, String> headers = idempotencyKey == null ? Map.of() : Map.of("Idempotency-Key", idempotencyKey);
        return send("POST", path, body, headers, true);
    }

    public Response send(String method, String path, Object body, Map<String, String> headers, boolean bearer) {
        try {
            String json = body == null ? null : (body instanceof String s ? s : JSON.writeValueAsString(body));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .method(
                            method,
                            json == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(json));
            if (json != null) {
                b.header("Content-Type", "application/json");
            }
            if (bearer && accessToken != null) {
                b.header("Authorization", "Bearer " + accessToken);
            }
            headers.forEach(b::header);
            HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> h = new LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> h.put(k.toLowerCase(), String.join(",", v)));
            return new Response(response.statusCode(), h, response.body());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

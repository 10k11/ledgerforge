package dev.ledgerforge.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 webhook signatures.
 *
 * <p>Header: {@code LedgerForge-Signature: t=<unix-seconds>,v1=<hex(HMAC_SHA256(secret, t + "." +
 * body))>}. Binding the timestamp into the MAC lets receivers reject replays older than a tolerance.
 *
 * <p>{@link #verify} is the reference receiver-side implementation: it parses the header, enforces
 * the timestamp tolerance and compares MACs in constant time.
 */
public final class WebhookSignature {

    public static final String SIGNATURE_HEADER = "LedgerForge-Signature";
    public static final String EVENT_ID_HEADER = "LedgerForge-Event-Id";
    public static final String EVENT_TYPE_HEADER = "LedgerForge-Event-Type";
    public static final String TIMESTAMP_HEADER = "LedgerForge-Timestamp";

    private WebhookSignature() {}

    public static String sign(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + hmacHex(secret, timestampSeconds + "." + body);
    }

    public enum Result {
        VALID,
        MALFORMED_HEADER,
        TIMESTAMP_OUT_OF_TOLERANCE,
        SIGNATURE_MISMATCH
    }

    public static Result verify(String secret, String header, String body, Instant now, Duration tolerance) {
        if (header == null) {
            return Result.MALFORMED_HEADER;
        }
        Long timestamp = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return Result.MALFORMED_HEADER;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (timestamp == null || v1 == null) {
            return Result.MALFORMED_HEADER;
        }
        if (Math.abs(now.getEpochSecond() - timestamp) > tolerance.toSeconds()) {
            return Result.TIMESTAMP_OUT_OF_TOLERANCE;
        }
        byte[] expected = hmacHex(secret, timestamp + "." + body).getBytes(StandardCharsets.US_ASCII);
        byte[] provided = v1.getBytes(StandardCharsets.US_ASCII);
        // Constant-time comparison: run time does not depend on where the first mismatch is.
        return MessageDigest.isEqual(expected, provided) ? Result.VALID : Result.SIGNATURE_MISMATCH;
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}

package dev.ledgerforge.webhook;

import dev.ledgerforge.common.config.AppProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts webhook signing secrets at rest with AES-256-GCM. Signing secrets must be recoverable
 * (HMAC needs the raw secret), so they are encrypted rather than hashed. The key comes from
 * {@code WEBHOOK_ENCRYPTION_KEY} (base64, 32 bytes; mandatory in production); in development it is
 * derived from the JWT secret.
 */
@Component
public class SecretCipher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;

    public SecretCipher(AppProperties props) {
        String configured = props.webhooks().encryptionKey();
        byte[] keyBytes;
        if (configured != null && !configured.isBlank()) {
            keyBytes = Base64.getDecoder().decode(configured.trim());
            if (keyBytes.length != 32) {
                throw new IllegalStateException("WEBHOOK_ENCRYPTION_KEY must be 32 bytes, base64-encoded");
            }
        } else {
            keyBytes = sha256(
                    ("ledgerforge-webhook-secrets:" + props.security().jwt().secret())
                            .getBytes(StandardCharsets.UTF_8));
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(ByteBuffer.allocate(iv.length + ct.length)
                            .put(iv)
                            .put(ct)
                            .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret decryption failed", e);
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

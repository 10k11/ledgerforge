package dev.ledgerforge.common.util;

import java.security.SecureRandom;

/**
 * Generates public, human-friendly references such as {@code TRF_7K2M9QX4ZB1HPA}. Uses Crockford
 * base32 (no I, L, O, U) so references are unambiguous when read aloud. 14 random symbols = 70
 * bits of entropy; uniqueness is still guaranteed by database unique constraints.
 */
public final class ReferenceGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ReferenceGenerator() {}

    public static String next(String prefix) {
        return prefix + "_" + randomSymbols(14);
    }

    public static String randomSymbols(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    public static String randomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}

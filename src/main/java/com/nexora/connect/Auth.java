package com.nexora.connect;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Every request carries a time, a one-time nonce and an HMAC-SHA256 signature made with the link key, so the key itself is never sent.
 * Request signature: HMAC(key, time \n nonce \n METHOD \n path?query \n sha256(body)).
 * Answers are signed too (HMAC(key, "resp" \n time \n nonce \n sha256(answer))), so the bot knows the answer really came from this plugin.
 */
final class Auth {
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9]{8,64}");
    private final byte[] key;
    private final long skewSeconds;
    private final Map<String, Long> seen = new LinkedHashMap<>();   // nonce -> when it may be forgotten (in insertion order)

    Auth(String key, long skewSeconds) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.skewSeconds = skewSeconds;
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** null when the request is fine, otherwise "signature", "clock" or "replay". The reason is only told to someone who has the key. */
    synchronized String check(String time, String nonce, String method, String target, String signature, byte[] body) {
        if (time == null || nonce == null || signature == null || !NONCE.matcher(nonce).matches()) return "signature";
        String expected = hmac(time + "\n" + nonce + "\n" + method + "\n" + target + "\n" + sha256Hex(body));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.toLowerCase().getBytes(StandardCharsets.UTF_8))) return "signature";
        long sent;
        try {
            sent = Long.parseLong(time);
        } catch (NumberFormatException e) {
            return "signature";
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - sent) > skewSeconds) return "clock";
        for (Iterator<Long> it = seen.values().iterator(); it.hasNext() && it.next() < now; ) it.remove();
        if (seen.containsKey(nonce)) return "replay";
        seen.put(nonce, now + skewSeconds * 2);
        return null;
    }

    String signResponse(String time, String nonce, byte[] body) {
        return hmac("resp\n" + time + "\n" + nonce + "\n" + sha256Hex(body));
    }
}

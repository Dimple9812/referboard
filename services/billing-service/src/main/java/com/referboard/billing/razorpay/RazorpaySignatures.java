package com.referboard.billing.razorpay;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Razorpay's two HMAC-SHA256 signatures, compared in constant time:
 * <ul>
 *   <li>checkout: {@code hmac(order_id + "|" + payment_id, key_secret)}, returned to the browser after payment;</li>
 *   <li>webhooks: {@code hmac(raw request body, webhook_secret)} in the {@code X-Razorpay-Signature} header.</li>
 * </ul>
 */
public final class RazorpaySignatures {

    private RazorpaySignatures() {
    }

    public static boolean validPayment(String orderId, String paymentId, String signature, String keySecret) {
        if (orderId == null || paymentId == null || signature == null || keySecret == null || keySecret.isBlank()) {
            return false;
        }
        return matches(hmac(orderId + "|" + paymentId, keySecret), signature);
    }

    public static boolean validWebhook(byte[] body, String signature, String webhookSecret) {
        if (body == null || signature == null || webhookSecret == null || webhookSecret.isBlank()) {
            return false;
        }
        return matches(hmac(body, webhookSecret), signature);
    }

    public static String hmac(String data, String secret) {
        return hmac(data.getBytes(StandardCharsets.UTF_8), secret);
    }

    public static String hmac(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean matches(String expectedHex, String actual) {
        return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.US_ASCII),
                actual.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}

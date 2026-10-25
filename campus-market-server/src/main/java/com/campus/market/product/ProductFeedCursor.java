package com.campus.market.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/** Public browsing position, not an authentication token or a signed snapshot. */
public record ProductFeedCursor(long epochSecond, long id) {
    private static final String ERROR = "浏览位置无效，请刷新列表后重试";
    private static final long MAX_ID = 9_007_199_254_740_991L;

    public String encode(String keyword, String category) {
        validPosition(epochSecond, id);
        String payload = "1|" + epochSecond + "|" + id + "|" + fingerprint(keyword, category);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
    }
    public static ProductFeedCursor decode(String token, String keyword, String category) {
        if (token == null || token.isEmpty()) return null;
        try {
            if (token.length() > 256 || !token.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) throw new IllegalArgumentException();
            String[] fields = new String(bytes, StandardCharsets.US_ASCII).split("\\|", -1);
            if (fields.length != 4 || !fields[0].equals("1") || !fields[1].matches("[1-9][0-9]{0,9}")
                    || !fields[2].matches("[1-9][0-9]{0,15}") || !fields[3].equals(fingerprint(keyword, category))) throw new IllegalArgumentException();
            long seconds = Long.parseLong(fields[1]), id = Long.parseLong(fields[2]);
            validPosition(seconds, id);
            return new ProductFeedCursor(seconds, id);
        } catch (IllegalArgumentException exception) {
            throw new InvalidProductException(ERROR);
        }
    }
    private static void validPosition(long seconds, long id) {
        // The project's created_at column is TIMESTAMP(0); never silently truncate fractional timestamps.
        if (seconds < 1 || seconds > 2_147_483_647L || id < 1 || id > MAX_ID) throw new InvalidProductException(ERROR);
    }
    private static String fingerprint(String keyword, String category) {
        try {
            String context = "NEWEST|" + keyword.length() + ":" + keyword + "|" + category;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(context.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}

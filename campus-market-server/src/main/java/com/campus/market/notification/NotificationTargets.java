package com.campus.market.notification;

/** Public destination kinds, derived only from immutable server-side notification sources. */
final class NotificationTargets {
    private NotificationTargets() {}
    static String target(String category,long resource,long recipient,String source) {
        return switch(category) {
            case "REPORT" -> "REPORT_DETAIL";
            case "MODERATION" -> "PRODUCT_MODERATION";
            case "ORDER" -> order(resource,recipient,source);
            default -> throw new IllegalStateException("通知分类无效");
        };
    }
    private static String order(long resource,long recipient,String source) {
        if(("ORDER_ISSUE:"+resource+":"+recipient+":RESOLVED").equals(source))return "OWN_ORDER_ISSUE";
        String prefix="ORDER_REVIEW:"+resource+":";
        if(source!=null&&source.startsWith(prefix)) {
            String author=source.substring(prefix.length());
            if(author.matches("[1-9][0-9]{0,15}")) {
                long reviewer=Long.parseLong(author);
                if(reviewer<=9007199254740991L&&reviewer!=recipient)return "ORDER_REVIEWS";
            }
        }
        // Existing/unknown ORDER sources retain their original authenticated order-detail entry.
        return "ORDER_DETAIL";
    }
}

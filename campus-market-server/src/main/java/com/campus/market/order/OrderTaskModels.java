package com.campus.market.order;

import io.swagger.v3.oas.annotations.media.Schema;

public final class OrderTaskModels {
    private OrderTaskModels() {}
    @Schema(name="BuyerTradeTasks")
    public record Buyer(long pendingPayment,long payable,long overduePendingPayment,long toConfirm,long toReview) {}
    @Schema(name="SellerTradeTasks")
    public record Seller(long toDeliver,long toReview) {}
    @Schema(name="OwnTradeTaskOverview")
    public record Overview(long userId,String sampledAt,Buyer buyer,Seller seller) {}
}

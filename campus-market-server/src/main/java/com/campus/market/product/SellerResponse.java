package com.campus.market.product;

// 公开商品接口只返回展示所需的卖家资料。
public record SellerResponse(long id, String nickname) {
}

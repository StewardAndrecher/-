package com.campus.market.product;

/** 每次读取的数据库快照：可见性、版本及不能随正文缓存的动态字段。 */
public record ProductDetailState(long id, ProductStatus status, long version,
                                 SellerResponse seller, long favoriteCount) {
}

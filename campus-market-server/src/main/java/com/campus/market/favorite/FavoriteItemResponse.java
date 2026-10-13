package com.campus.market.favorite;

import com.campus.market.product.ProductResponse;

// product为null表示商品不可用，不泄露已下架商品的标题、描述或价格。
public record FavoriteItemResponse(long productId, ProductResponse product) {
}

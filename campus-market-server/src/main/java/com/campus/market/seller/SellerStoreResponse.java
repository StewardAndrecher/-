package com.campus.market.seller;

import com.campus.market.product.SellerResponse;
import com.campus.market.product.ProductPageResponse;

public record SellerStoreResponse(SellerResponse seller, ProductPageResponse products) {}

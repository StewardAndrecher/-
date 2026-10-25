package com.campus.market.product;

public record ProductResponse(
        long id,
        String title,
        String description,
        long priceInCents,
        SellerResponse seller,
        ProductStatus status,
        String coverImageId,
        String coverImageUrl,
        long version, CategoryResponse category, long favoriteCount, ProductDetails details
) {
 public ProductResponse(long id,String title,String description,long priceInCents,SellerResponse seller,ProductStatus status,String coverImageId,String coverImageUrl,long version,CategoryResponse category,long favoriteCount) {
  this(id,title,description,priceInCents,seller,status,coverImageId,coverImageUrl,version,category,favoriteCount,ProductDetails.legacy(coverImageId));
 }
}

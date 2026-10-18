package com.campus.market.product;

/** 只保存曾经公开的商品正文；不保存账号、会话、卖家昵称或收藏数。 */
public record CachedProductDetail(long id, String title, String description,
                                  long priceInCents, String coverImageId,
                                  long version, ProductCategory category) {
    public CachedProductDetail {
        if (id <= 0 || version < 0 || version > 9_007_199_254_740_991L
                || title == null || title.length() < 2 || title.length() > 50
                || description == null || description.length() > 500
                || priceInCents <= 0 || priceInCents > 99_999_999L || category == null) {
            throw new IllegalArgumentException("商品缓存数据不完整");
        }
        if (coverImageId != null && !coverImageId.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("商品缓存图片标识无效");
        }
    }

    public static CachedProductDetail from(ProductResponse product) {
        if (product.status() != ProductStatus.ON_SALE) {
            throw new IllegalArgumentException("只允许缓存公开在售商品");
        }
        return new CachedProductDetail(product.id(), product.title(), product.description(),
                product.priceInCents(), product.coverImageId(), product.version(),
                ProductCategory.valueOf(product.category().code()));
    }

    public ProductResponse response(ProductDetailState state) {
        if (state.id() != id || state.version() != version || state.status() != ProductStatus.ON_SALE) {
            throw new IllegalArgumentException("缓存版本与数据库不一致");
        }
        String url = coverImageId == null ? null : "/api/images/" + coverImageId;
        return new ProductResponse(id, title, description, priceInCents, state.seller(),
                state.status(), coverImageId, url, version, category.response(), state.favoriteCount());
    }
}

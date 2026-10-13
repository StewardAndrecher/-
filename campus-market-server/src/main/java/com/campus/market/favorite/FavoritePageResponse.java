package com.campus.market.favorite;

import java.util.List;

public record FavoritePageResponse(
        List<FavoriteItemResponse> records, long total, int page, int size
) {
}

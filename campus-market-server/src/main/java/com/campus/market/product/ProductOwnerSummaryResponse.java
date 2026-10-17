package com.campus.market.product;

/** 金额以分为单位，仅表示已售出商品的标价合计。 */
public record ProductOwnerSummaryResponse(
        long total,
        long onSale,
        long offShelf,
        long sold,
        long soldPriceInCents
) {}

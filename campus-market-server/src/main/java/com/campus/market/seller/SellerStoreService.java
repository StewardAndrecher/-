package com.campus.market.seller;

import com.campus.market.product.ProductRepository;
import com.campus.market.product.ProductPageResponse;
import com.campus.market.product.ProductSort;
import com.campus.market.product.ProductCategory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SellerStoreService {
    private final SellerRepository sellers;
    private final ProductRepository products;
    public SellerStoreService(SellerRepository sellers, ProductRepository products) {
        this.sellers = sellers;
        this.products = products;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SellerStoreResponse getStore(long sellerId, int page, int size, String sortCode) {
        return getStore(sellerId, page, size, sortCode, "");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SellerStoreResponse getStore(long sellerId, int page, int size, String sortCode, String categoryCode) {
        if (sellerId <= 0 || sellerId > 9_007_199_254_740_991L)
            throw new InvalidSellerException("卖家ID必须是有效的正整数");
        if (page < 1 || size < 1 || size > 50)
            throw new InvalidSellerException("页码必须大于等于1，每页数量需要1～50");
        ProductSort sort = ProductSort.parse(sortCode);
        ProductCategory category = "".equals(categoryCode) ? null : ProductCategory.parse(categoryCode);
        var seller = sellers.findPublicSeller(sellerId).orElseThrow(SellerNotFoundException::new);
        long total = products.countOnSaleBySeller(sellerId, category);
        var records = products.findPublicPageBySeller(sellerId, sort, size, ((long) page - 1) * size, category);
        return new SellerStoreResponse(seller, new ProductPageResponse(records, total, page, size));
    }
}

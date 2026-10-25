package com.campus.market.product;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductLookupService {
    private final ProductRepository products;
    public ProductLookupService(ProductRepository products) { this.products = products; }

    @Transactional(readOnly = true)
    public ProductLookupModels.Response lookup(ProductLookupModels.Request request) {
        var ids = request == null ? null : request.productIds();
        if (ids == null || ids.isEmpty() || ids.size() > 20 ||
                ids.stream().anyMatch(id -> id == null || id < 1 || id > 9_007_199_254_740_991L) ||
                new HashSet<>(ids).size() != ids.size()) {
            throw new InvalidProductException("一次只能查询1至20个不重复的有效商品编号");
        }
        var selected = List.copyOf(ids);
        Map<Long, ProductResponse> visible = new HashMap<>();
        for (var product : products.findPublicByIds(selected)) visible.put(product.id(), product);
        return new ProductLookupModels.Response(selected.stream()
                .map(id -> new ProductLookupModels.Entry(id, visible.get(id))).toList());
    }
}

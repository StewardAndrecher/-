package com.campus.market.favorite;

import com.campus.market.auth.SessionService;
import com.campus.market.product.InvalidProductException;
import com.campus.market.product.ProductNotFoundException;
import com.campus.market.product.ProductRepository;
import com.campus.market.product.ProductStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class FavoriteService {
    private final FavoriteRepository favorites;
    private final ProductRepository products;
    private final SessionService sessions;

    public FavoriteService(FavoriteRepository favorites, ProductRepository products, SessionService sessions) {
        this.favorites = favorites;
        this.products = products;
        this.sessions = sessions;
    }

    @Transactional
    public FavoriteStateResponse add(long productId, String authorization) {
        long userId = sessions.currentUser(authorization).id();
        validateId(productId);
        var product = products.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException("商品不存在或已下架"));
        if (product.status() != ProductStatus.ON_SALE) {
            throw new ProductNotFoundException("商品不存在或已下架");
        }
        favorites.add(userId, productId);
        return new FavoriteStateResponse(productId, true);
    }

    @Transactional
    public FavoriteStateResponse remove(long productId, String authorization) {
        long userId = sessions.currentUser(authorization).id();
        validateId(productId);
        // 取消收藏允许商品已下架或记录已不存在；只删除当前账号自己的关系。
        favorites.remove(userId, productId);
        return new FavoriteStateResponse(productId, false);
    }

    @Transactional(readOnly = true)
    public List<Long> selectedIds(List<Long> ids, String authorization) {
        long userId = sessions.currentUser(authorization).id();
        if (ids == null) return List.of();
        if (ids.size() > 50) throw new InvalidProductException("一次最多查询50件商品的收藏状态");
        ids.forEach(this::validateId);
        return favorites.findSelectedIds(userId, ids.stream().distinct().toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FavoritePageResponse page(int page, int size, String authorization) {
        long userId = sessions.currentUser(authorization).id();
        if (page < 1 || size < 1 || size > 50) {
            throw new InvalidProductException("页码须大于等于1，每页数量须在1～50之间");
        }
        long total = favorites.count(userId);
        return new FavoritePageResponse(favorites.findPage(userId, size, ((long) page - 1) * size),
                total, page, size);
    }

    private void validateId(Long id) {
        if (id == null || id <= 0 || id > 9_007_199_254_740_991L) {
            throw new InvalidProductException("商品ID必须是有效的正整数");
        }
    }
}

package com.campus.market.product;

import com.campus.market.auth.SessionService;
import com.campus.market.media.ImageService;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final SessionService sessionService;
    private final ImageService imageService;

    public ProductService(ProductRepository productRepository, SessionService sessionService, ImageService imageService) {
        this.productRepository = productRepository;
        this.sessionService = sessionService;
        this.imageService = imageService;
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public ProductPageResponse listProducts(
            int page,
            int size,
            String keyword
    ) {
        validatePagination(page, size);

        String searchText = keyword == null
                ? ""
                : keyword.strip();

        if (searchText.length() > 50) {
            throw new InvalidProductException(
                    "搜索关键词不能超过50个字符"
            );
        }

        long offset = ((long) page - 1) * size;

        long total =
                productRepository.countByKeyword(searchText);

        List<ProductResponse> records =
                productRepository.findPage(
                        searchText,
                        size,
                        offset
                );

        return new ProductPageResponse(
                records,
                total,
                page,
                size
        );
    }

    @Transactional
    public ProductResponse createProduct(
            CreateProductRequest request,
            String authorization
    ) {
        // 卖家由后端核验会话后确定，不能接受前端指定的用户ID。
        var seller = sessionService.currentUser(authorization);
        if (request == null) {
            throw new InvalidProductException("商品信息不能为空");
        }
        ProductFields fields = validateProduct(request.title(), request.description(), request.priceInCents());
        String coverId = imageService.requireOwned(request.coverImageId(), seller.id());
        long id = productRepository.insert(fields.title(), fields.description(), fields.priceInCents(), seller.id(), coverId);
        return productRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("未能读取刚发布的商品"));
    }

    @Transactional
    public ProductResponse updateProduct(long id, UpdateProductRequest request, String authorization) {
        var user = sessionService.currentUser(authorization);
        validateId(id);
        var product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException("商品不存在"));
        requireOwner(product, user.id());
        if (request == null) throw new InvalidProductException("商品信息不能为空");
        ProductFields fields = validateProduct(request.title(), request.description(), request.priceInCents());
        requireVersion(product, request.version());
        String coverId = request.coverImageId() == null ? product.coverImageId()
                : imageService.requireOwned(request.coverImageId(), user.id());
        // 内容未变时也视为成功，避免依赖数据库驱动的更新行数配置。
        if (product.title().equals(fields.title()) && product.description().equals(fields.description())
                && product.priceInCents() == fields.priceInCents()
                && Objects.equals(product.coverImageId(), coverId)) return product;
        int updated = productRepository.updateOwnedProduct(id, user.id(), fields.title(),
                fields.description(), fields.priceInCents(), coverId, request.version());
        if (updated != 1) throw new ProductConflictException("商品已被更新，请重新加载后再保存");
        return productRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("未能读取刚修改的商品"));
    }

    private void requireVersion(ProductResponse product, Long version) {
        if (version == null || version < 0 || version > 9_007_199_254_740_991L) {
            throw new InvalidProductException("请提供有效的商品版本号，重新加载商品后重试");
        }
        if (version != product.version()) {
            throw new ProductConflictException("商品已被更新，当前草稿未保存，请重新加载后再操作");
        }
    }

    private ProductFields validateProduct(String rawTitle, String rawDescription, Long priceInCents) {
        String title = rawTitle == null ? "" : rawTitle.strip();
        String description = rawDescription == null ? "" : rawDescription.strip();
        if (title.length() < 2 || title.length() > 50) {
            throw new InvalidProductException("商品标题需要2～50个字符");
        }
        if (description.length() > 500) {
            throw new InvalidProductException("商品描述不能超过500个字符");
        }
        if (priceInCents == null || priceInCents <= 0 || priceInCents > 99_999_999L) {
            throw new InvalidProductException("价格必须在0.01～999999.99元之间");
        }
        return new ProductFields(title, description, priceInCents);
    }

    private record ProductFields(String title, String description, long priceInCents) {}

    private void validateId(long id) {
        if (id <= 0) throw new InvalidProductException("商品ID必须是正整数");
    }

    private void validatePagination(int page, int size) {
        if (page < 1) {
            throw new InvalidProductException(
                    "页码必须大于等于1"
            );
        }

        if (size < 1 || size > 50) {
            throw new InvalidProductException(
                    "每页数量必须在1～50之间"
            );
        }

    }

    private void requireOwner(ProductResponse product, long userId) {
        if (product.seller() == null) {
            throw new ProductForbiddenException("历史商品未登记卖家，暂不支持管理");
        }
        if (product.seller().id() != userId) {
            throw new ProductForbiddenException("只能管理自己发布的商品");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductPageResponse listMyProducts(int page, int size, String authorization) {
        var user = sessionService.currentUser(authorization);
        validatePagination(page, size);
        long total = productRepository.countBySeller(user.id());
        var records = productRepository.findPageBySeller(user.id(), size, ((long) page - 1) * size);
        return new ProductPageResponse(records, total, page, size);
    }

    @Transactional
    public ProductResponse updateStatus(long id, UpdateProductStatusRequest request, String authorization) {
        var user = sessionService.currentUser(authorization);
        validateId(id);
        var product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException("商品不存在"));
        requireOwner(product, user.id());
        if (request == null || request.status() == null) {
            throw new InvalidProductException("请指定商品上架或下架状态");
        }
        requireVersion(product, request.version());
        // 当前版本的无变化请求不递增版本；旧版本即使状态相同也拒绝。
        if (product.status() == request.status()) return product;
        if (productRepository.updateOwnedStatus(id, user.id(), request.status(), request.version()) != 1) {
            throw new ProductConflictException("商品已被更新，请刷新后确认状态再操作");
        }
        return productRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("未能读取商品状态"));
    }

    public ProductResponse getProduct(long id, String authorization) {
        validateId(id);
        var product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("商品不存在"));
        if (product.status() == ProductStatus.ON_SALE) return product;
        if (authorization == null || authorization.isBlank()) {
            throw new ProductNotFoundException("商品不存在或已下架");
        }
        var user = sessionService.currentUser(authorization);
        if (product.seller() == null || product.seller().id() != user.id()) {
            throw new ProductNotFoundException("商品不存在或已下架");
        }
        return product;
    }
}

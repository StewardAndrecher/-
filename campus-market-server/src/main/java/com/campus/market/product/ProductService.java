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

    private final com.campus.market.governance.WriteGovernance governance;
    private final com.campus.market.governance.ContentRules rules;
    private final ProductRepository productRepository;
    private final SessionService sessionService;
    private final ImageService imageService;
    private final ProductDetailCache detailCache;
    private final com.campus.market.order.OrderRepository orders;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductService(ProductRepository productRepository, SessionService sessionService, ImageService imageService,
                          ProductDetailCache detailCache, com.campus.market.order.OrderRepository orders, com.campus.market.governance.WriteGovernance governance, com.campus.market.governance.ContentRules rules) {
        this.productRepository = productRepository;
        this.sessionService = sessionService;
        this.imageService = imageService;
        this.detailCache = detailCache;
        this.orders = orders;
        this.governance=governance; this.rules=rules;
    }

    public ProductService(ProductRepository products,SessionService sessions,ImageService images,ProductDetailCache cache,com.campus.market.order.OrderRepository orders){this(products,sessions,images,cache,orders,null,null);}

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public ProductPageResponse listProducts(
            int page,
            int size,
            String keyword,
            String category,
            String sort
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

        String categoryCode = category == null || category.isEmpty() ? "" : ProductCategory.parse(category).name();
        ProductSort order = ProductSort.parse(sort);
        long offset = ((long) page - 1) * size;

        long total =
                productRepository.countByKeyword(searchText, categoryCode);

        List<ProductResponse> records =
                productRepository.findPage(
                        searchText,
                        categoryCode,
                        order,
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

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ProductPageResponse listProducts(int page,int size,String keyword,String category,String sort,String campus) {
        if(campus==null||campus.isEmpty())return listProducts(page,size,keyword,category,sort);
        validatePagination(page,size);
        if(!ProductDetails.CAMPUSES.contains(campus))throw new InvalidProductException("校区筛选无效");
        String search=keyword==null?"":keyword.strip();if(search.length()>50)throw new InvalidProductException("搜索关键词不能超过50个字符");
        String code=category==null||category.isEmpty()?"":ProductCategory.parse(category).name();
        return productRepository.campusPage(search,code,campus,ProductSort.parse(sort),page,size);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductPageResponse listProducts(int page, int size, String keyword) {
        return listProducts(page, size, keyword, "", "NEWEST");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductPageResponse listProducts(int page, int size, String keyword, String category) {
        return listProducts(page, size, keyword, category, "NEWEST");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductFeedResponse listProductFeed(int size, String keyword, String category, String sort, String token) {
        if (size < 1 || size > 20) throw new InvalidProductException("每次加载数量必须在1～20之间");
        String searchText = keyword == null ? "" : keyword.strip();
        if (searchText.length() > 50) throw new InvalidProductException("搜索关键词不能超过50个字符");
        String categoryCode = category == null || category.isEmpty() ? "" : ProductCategory.parse(category).name();
        if (ProductSort.parse(sort) != ProductSort.NEWEST) throw new InvalidProductException("连续浏览暂只支持最新发布排序");
        ProductFeedCursor cursor = ProductFeedCursor.decode(token, searchText, categoryCode);
        List<ProductRepository.FeedRow> rows = productRepository.findFeed(searchText, categoryCode, cursor, size + 1);
        boolean hasMore = rows.size() > size;
        List<ProductRepository.FeedRow> visible = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (hasMore) {
            // Anchor the last visible row, never the extra lookahead row, or one product would be skipped.
            var last = visible.get(visible.size() - 1);
            next = new ProductFeedCursor(last.epochSecond(), last.product().id()).encode(searchText, categoryCode);
        }
        return new ProductFeedResponse(visible.stream().map(ProductRepository.FeedRow::product).toList(), hasMore, next, size);
    }

    @Transactional
    public ProductResponse createProduct(
            CreateProductRequest request,
            String authorization
    ) {
        // 卖家由后端核验会话后确定，不能接受前端指定的用户ID。
        var seller = sessionService.currentUser(authorization);
        governance.requireWritable(seller.id(),"PRODUCT");
        var normalized = normalizeCreateProduct(request, seller.id());
        rules.requireAllowed(normalized.title(),normalized.description(),normalized.details().handoverLocation());
        long id = productRepository.insert(normalized.title(), normalized.description(), normalized.priceInCents(),
                seller.id(), normalized.coverImageId(), normalized.categoryCode());
        productRepository.saveDetails(id,normalized.details());
        return productRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("未能读取刚发布的商品"));
    }

    public CreateProductRequest normalizeCreateProduct(CreateProductRequest request, long sellerId) {
        if (request == null) throw new InvalidProductException("商品信息不能为空");
        ProductFields fields = validateProduct(request.title(), request.description(), request.priceInCents());
        String category = request.categoryCode() == null ? ProductCategory.UNCLASSIFIED.name()
                : ProductCategory.parse(request.categoryCode()).name();
        String coverId = imageService.requireOwned(request.coverImageId(), sellerId);
        ProductDetails details=ProductDetails.validate(request.details(),coverId);
        for(String image:details.imageIds())imageService.requireOwned(image,sellerId);
        return new CreateProductRequest(fields.title(), fields.description(), fields.priceInCents(), coverId, category,details);
    }

    @Transactional
    public ProductResponse updateProduct(long id, UpdateProductRequest request, String authorization) {
        var user = sessionService.currentUser(authorization);
        governance.requireWritable(user.id(),"PRODUCT");
        validateId(id);
        var product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException("商品不存在"));
        requireOwner(product, user.id());
        if (product.status() == ProductStatus.SOLD) throw new ProductConflictException("商品已售出，不能再编辑");
        if (request == null) throw new InvalidProductException("商品信息不能为空");
        ProductFields fields = validateProduct(request.title(), request.description(), request.priceInCents());
        rules.requireAllowed(fields.title(),fields.description(),request.details()==null?"":request.details().handoverLocation());
        requireVersion(product, request.version());
        String coverId = request.coverImageId() == null ? product.coverImageId()
                : imageService.requireOwned(request.coverImageId(), user.id());
        String category = request.categoryCode() == null ? product.category().code()
                : ProductCategory.parse(request.categoryCode()).name();
        ProductDetails details=ProductDetails.validate(request.details()==null&&Objects.equals(coverId,product.coverImageId())?product.details():request.details(),coverId);
        for(String image:details.imageIds())imageService.requireOwned(image,user.id());
        // 内容未变时也视为成功，避免依赖数据库驱动的更新行数配置。
        if (product.title().equals(fields.title()) && product.description().equals(fields.description())
                && product.priceInCents() == fields.priceInCents()
                && Objects.equals(product.coverImageId(), coverId)
                && product.category().code().equals(category) && product.details().equals(details)) return product;
        orders.requireUnreserved(id);
        int updated = productRepository.updateOwnedProduct(id, user.id(), fields.title(),
                fields.description(), fields.priceInCents(), coverId, request.version(), category);
        if (updated != 1) throw new ProductConflictException("商品已被更新，请重新加载后再保存");
        productRepository.saveDetails(id,details);
        detailCache.evictAfterCommit(id, product.version());
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
        return listMyProducts(page, size, "", authorization);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductPageResponse listMyProducts(int page, int size, String status, String authorization) {
        return listMyProducts(page, size, status, "", authorization);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductPageResponse listMyProducts(int page, int size, String status, String keyword, String authorization) {
        return listMyProducts(page, size, status, keyword, "NEWEST", authorization);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductPageResponse listMyProducts(int page, int size, String status, String keyword, String sort, String authorization) {
        var user = sessionService.currentUser(authorization);
        validatePagination(page, size);
        ProductStatus filter = null;
        if (status != null && !status.isEmpty()) {
            try { filter = ProductStatus.valueOf(status); }
            catch (IllegalArgumentException error) {
                throw new InvalidProductException("商品状态筛选不正确");
            }
        }
        String searchText = keyword == null ? "" : keyword.strip();
        if (searchText.length() > 50) throw new InvalidProductException("搜索关键词不能超过50个字符");
        ProductOwnerSort order = ProductOwnerSort.parse(sort);
        long total = productRepository.countBySeller(user.id(), filter, searchText);
        var records = productRepository.findPageBySeller(user.id(), filter, searchText, order, size, ((long) page - 1) * size);
        return new ProductPageResponse(records, total, page, size);
    }

    @Transactional(readOnly = true)
    public ProductOwnerSummaryResponse mySummary(String authorization) {
        var user = sessionService.currentUser(authorization);
        return productRepository.summarizeBySeller(user.id());
    }

    @Transactional
    public ProductResponse updateStatus(long id, UpdateProductStatusRequest request, String authorization) {
        var user = sessionService.currentUser(authorization);
        governance.lockUser(user.id());
        if(request!=null && request.status()==ProductStatus.ON_SALE)governance.requireWritable(user.id(),"PRODUCT");
        validateId(id);
        var product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException("商品不存在"));
        requireOwner(product, user.id());
        if (request == null || request.status() == null) {
            throw new InvalidProductException("请指定商品状态");
        }
        requireVersion(product, request.version());
        if(request.status()==ProductStatus.ON_SALE)rules.requireAllowed(product.title(),product.description(),product.details().handoverLocation());
        if (request.status() != ProductStatus.OFF_SHELF) productRepository.requireNotModerated(id);
        // 当前版本的无变化请求不递增版本；旧版本即使状态相同也拒绝。
        if (product.status() == request.status()) return product;
        if (product.status() == ProductStatus.SOLD) throw new ProductConflictException("商品已售出，不能重新上架或下架");
        orders.requireUnreserved(id);
        if (productRepository.updateOwnedStatus(id, user.id(), request.status(), request.version()) != 1) {
            throw new ProductConflictException("商品已被更新，请刷新后确认状态再操作");
        }
        detailCache.evictAfterCommit(id, product.version());
        return productRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("未能读取商品状态"));
    }

    public ProductResponse getProduct(long id, String authorization) {
        validateId(id);
        if (detailCache.canRead()) {
            // 缓存命中仍查可见性和当前版本，旧读请求晚回填也无法公开下架商品。
            var state = productRepository.findDetailState(id)
                    .orElseThrow(() -> new ProductNotFoundException("商品不存在"));
            if (state.status() == ProductStatus.ON_SALE) {
                var cached = detailCache.read(id, state.version());
                if (cached.isPresent()) return cached.get().response(state);
            }
        }
        var product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("商品不存在"));
        if (product.status() == ProductStatus.ON_SALE) {
            detailCache.putPublic(product);
            return product;
        }
        if (authorization == null || authorization.isBlank()) {
            throw new ProductNotFoundException("商品不存在或不在售");
        }
        var user = sessionService.currentUser(authorization);
        if (product.seller() == null || product.seller().id() != user.id()) {
            throw new ProductNotFoundException("商品不存在或不在售");
        }
        return product;
    }
}

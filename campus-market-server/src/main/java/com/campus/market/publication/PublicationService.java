package com.campus.market.publication;

import com.campus.market.auth.LoginRequiredException;
import com.campus.market.auth.SessionService;
import com.campus.market.product.ProductService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationService {
    private final PublicationRepository publications;
    private final ProductService products;
    private final SessionService sessions;
    public PublicationService(PublicationRepository publications, ProductService products, SessionService sessions) {
        this.publications=publications; this.products=products; this.sessions=sessions;
    }

    @Transactional
    public PublicationResponse create(CreatePublicationRequest request, String authorization) {
        var user=sessions.currentUser(authorization);
        if (request==null || request.requestId()==null || !request.requestId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new InvalidPublicationException("请提供小写标准UUID格式的requestId");
        }
        // 同一账号的发布排队；当前读能看到前一次已提交的回执。
        if (!publications.lockPublisher(user.id())) throw new LoginRequiredException();
        var normalized=products.normalizeCreateProduct(request.product(),user.id());
        var previous=publications.findForUpdate(user.id(),request.requestId());
        if (previous.isPresent()) {
            var saved=previous.get();
            if (!saved.product().equals(normalized)) {
                throw new PublicationConflictException("提交标识已用于另一份商品内容，请使用原内容确认");
            }
            // 对比首次提交快照，而非之后可能已编辑或下架的商品。
            return new PublicationResponse(saved.productId(),request.requestId(),true);
        }
        var product=products.createProduct(normalized,authorization);
        publications.insert(user.id(),request.requestId(),product.id(),normalized);
        return new PublicationResponse(product.id(),request.requestId(),false);
    }
}

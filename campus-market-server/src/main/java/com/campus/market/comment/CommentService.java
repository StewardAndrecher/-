package com.campus.market.comment;

import com.campus.market.auth.SessionService;
import com.campus.market.product.ProductService;
import com.campus.market.product.ProductStatus;
import com.campus.market.product.ProductNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentService {
    private final com.campus.market.governance.WriteGovernance governance;
    private final com.campus.market.governance.ContentRules rules;
    private final CommentRepository comments;
    private final ProductService productService;
    private final SessionService sessions;

    public CommentService(CommentRepository comments, ProductService productService, SessionService sessions,com.campus.market.governance.WriteGovernance governance,com.campus.market.governance.ContentRules rules) {this.governance=governance;this.rules=rules;
        this.comments = comments;
        this.productService = productService;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CommentPageResponse page(long productId, int page, int size, String authorization) {
        validateId(productId);
        if (page < 1 || size < 1 || size > 50) throw new InvalidCommentException("页码须大于等于1，每页数量须在1～50之间");
        // 在售公开；下架后只有卖家能读取，复用商品详情的访问规则。
        productService.getProduct(productId, authorization);
        long total = comments.count(productId);
        return new CommentPageResponse(comments.findPage(productId, size, ((long) page - 1) * size), total, page, size);
    }

    @Transactional
    public CommentSubmissionResponse create(long productId, CreateCommentRequest request, String authorization) {
        var user = sessions.currentUser(authorization);
        governance.lockUser(user.id());
        validateId(productId);
        String content = request == null || request.content() == null ? "" : request.content().strip();
        int length = content.codePointCount(0, content.length());
        if (length < 1 || length > 500) throw new InvalidCommentException("留言需为1～500个字符，不能全为空白");
        String requestId = request.requestId();
        if (requestId == null || !requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new InvalidCommentException("请提供小写标准UUID格式的requestId");
        }
        // 同一商品的提交在此排队，再用当前读检查原提交结果。
        var status = comments.lockProduct(productId)
                .orElseThrow(() -> new ProductNotFoundException("商品不存在或已下架"));
        var existing = comments.findSubmissionForUpdate(productId, user.id(), requestId);
        if (existing.isPresent()) {
            var submission = existing.get();
            if (!submission.content().equals(content)) {
                throw new CommentRequestConflictException("这次提交标识已用于另一段内容，请勿更换内容后复用");
            }
            return receipt(productId, submission.id(), requestId, true, submission.withdrawn());
        }
        // 原提交可确认；新的提交仍要求商品在售。
        if (status != ProductStatus.ON_SALE) throw new ProductNotFoundException("商品不在售，暂不支持新留言");
        rules.requireAllowed(content);governance.requireWritable(user.id(),"COMMENT");
        long id = comments.insert(productId, user.id(), content, requestId);
        return receipt(productId, id, requestId, false, false);
    }

    private CommentSubmissionResponse receipt(long productId, long id, String requestId,
                                              boolean replayed, boolean withdrawn) {
        var comment = comments.findSubmissionComment(productId, id)
                .orElseThrow(() -> new IllegalStateException("未能读取留言提交结果"));
        return new CommentSubmissionResponse(comment, requestId, replayed, withdrawn);
    }

    @Transactional
    public void withdraw(long productId, long commentId, String authorization) {
        long userId = sessions.currentUser(authorization).id();
        validateId(productId);
        validateId(commentId);
        long authorId = comments.findAuthor(productId, commentId)
                .orElseThrow(() -> new CommentNotFoundException("留言不存在"));
        if (authorId != userId) throw new CommentForbiddenException("只能撤回自己发表的留言");
        // 作者身份不可变；重复撤回成功，商品下架后作者也能撤回自己的内容。
        comments.withdraw(productId, commentId, userId);
    }

    private void validateId(long id) {
        if (id <= 0 || id > 9_007_199_254_740_991L) throw new InvalidCommentException("ID必须是有效的正整数");
    }
}

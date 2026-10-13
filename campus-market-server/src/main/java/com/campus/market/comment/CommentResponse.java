package com.campus.market.comment;

public record CommentResponse(
        long id, long productId, String content,
        CommentAuthorResponse author, boolean seller, String createdAt
) {
}

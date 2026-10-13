package com.campus.market.comment;

public record CommentSubmissionResponse(
        CommentResponse comment, String requestId,
        boolean replayed, boolean withdrawn
) {
}

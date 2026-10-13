package com.campus.market.comment;

import java.util.List;

public record CommentPageResponse(
        List<CommentResponse> records, long total, int page, int size
) {
}

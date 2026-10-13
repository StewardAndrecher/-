package com.campus.market.comment;

public class CommentForbiddenException extends RuntimeException {
    public CommentForbiddenException(String message) {
        super(message);
    }
}

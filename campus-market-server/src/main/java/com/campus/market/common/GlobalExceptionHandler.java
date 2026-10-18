package com.campus.market.common;

import com.campus.market.product.InvalidProductException;
import com.campus.market.publication.InvalidPublicationException;
import com.campus.market.publication.PublicationConflictException;
import com.campus.market.seller.InvalidSellerException;
import com.campus.market.seller.SellerNotFoundException;
import com.campus.market.comment.InvalidCommentException;
import com.campus.market.comment.CommentRequestConflictException;
import com.campus.market.comment.CommentForbiddenException;
import com.campus.market.comment.CommentNotFoundException;
import com.campus.market.product.ProductConflictException;
import com.campus.market.product.ProductNotFoundException;
import com.campus.market.product.ProductForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import com.campus.market.user.InvalidUserException;
import com.campus.market.user.UserProfileConflictException;
import com.campus.market.user.UsernameAlreadyExistsException;
import com.campus.market.user.InvalidCredentialsException;
import com.campus.market.auth.LoginRequiredException;
import com.campus.market.media.ImageValidationException;
import com.campus.market.media.ImageNotFoundException;


@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidPublicationException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidPublication(InvalidPublicationException exception) {
        return ResponseEntity.badRequest().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse("INVALID_PUBLICATION",exception.getMessage()));
    }
    @ExceptionHandler(PublicationConflictException.class)
    public ResponseEntity<ApiErrorResponse> handlePublicationConflict(PublicationConflictException exception) {
        return ResponseEntity.status(409).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse("PUBLICATION_CONFLICT",exception.getMessage()));
    }

    @ExceptionHandler(InvalidSellerException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidSeller(InvalidSellerException exception) {
        return ResponseEntity.badRequest().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse("INVALID_SELLER", exception.getMessage()));
    }

    @ExceptionHandler(SellerNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleSellerNotFound(SellerNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse("SELLER_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(ImageValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleImageValidation(ImageValidationException exception) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse("INVALID_IMAGE", exception.getMessage()));
    }
    @ExceptionHandler(ImageNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleImageNotFound(ImageNotFoundException exception) {
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse("IMAGE_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(com.campus.market.intent.PurchaseIntentException.class)
    public ResponseEntity<ApiErrorResponse> handlePurchaseIntent(com.campus.market.intent.PurchaseIntentException exception) {
        return ResponseEntity.status(exception.status()).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(exception.code(),exception.getMessage()));
    }

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);



    @ExceptionHandler(LoginRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleLoginRequired(
            LoginRequiredException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .header("WWW-Authenticate", "Bearer")
                .body(new ApiErrorResponse("LOGIN_REQUIRED", exception.getMessage()));
    }
    @ExceptionHandler(InvalidProductException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidProduct(
            InvalidProductException exception
    ) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse("INVALID_PRODUCT", exception.getMessage())
        );
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleProductNotFound(
            ProductNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiErrorResponse("PRODUCT_NOT_FOUND", exception.getMessage())
        );
    }

    @ExceptionHandler(ProductForbiddenException.class)
    public ResponseEntity<ApiErrorResponse> handleProductForbidden(
            ProductForbiddenException exception
    ) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                new ApiErrorResponse("PRODUCT_FORBIDDEN", exception.getMessage())
        );
    }

    @ExceptionHandler(ProductConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleProductConflict(ProductConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse("PRODUCT_CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler(InvalidCommentException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidComment(InvalidCommentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse("INVALID_COMMENT", exception.getMessage()));
    }

    @ExceptionHandler(CommentForbiddenException.class)
    public ResponseEntity<ApiErrorResponse> handleCommentForbidden(CommentForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse("COMMENT_FORBIDDEN", exception.getMessage()));
    }

    @ExceptionHandler(CommentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCommentNotFound(CommentNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse("COMMENT_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(CommentRequestConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleCommentRequestConflict(CommentRequestConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse("COMMENT_REQUEST_CONFLICT", exception.getMessage()));
    }

    // Spring 已经判断了这些框架异常的状态码；这里统一响应内容。
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        String message = switch (status.value()) {
            case 400 -> "请求参数或JSON格式不正确";
            case 404 -> "请求的资源不存在";
            case 405 -> "请求方法不支持";
            case 406 -> "无法提供请求的响应格式";
            case 415 -> "请求内容类型不支持，请使用application/json";
            case 413 -> "图片不能超过5MB";
            default -> "请求失败";
        };
        String code = "HTTP_" + status.value();

        if (status.is5xxServerError()) {
            log.error("处理请求失败", exception);
            code = "SERVER_ERROR";
            message = "服务器暂时无法处理请求，请稍后重试";
        }

        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(code, message));
    }

    @ExceptionHandler(UserProfileConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleUserProfileConflict(UserProfileConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ApiErrorResponse("USER_PROFILE_CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler(InvalidUserException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidUser(
            InvalidUserException exception
    ) {
        return ResponseEntity.badRequest().body(
                new ApiErrorResponse("INVALID_USER", exception.getMessage())
        );
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleUsernameAlreadyExists(
            UsernameAlreadyExistsException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiErrorResponse("USERNAME_ALREADY_EXISTS", exception.getMessage())
        );
    }
    @ExceptionHandler(com.campus.market.draft.DraftBackupException.class)
    public ResponseEntity<ApiErrorResponse> handleDraftBackup(com.campus.market.draft.DraftBackupException ex) {
        return ResponseEntity.status(ex.status()).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(com.campus.market.order.OrderException.class)
    public ResponseEntity<ApiErrorResponse> handleOrder(com.campus.market.order.OrderException exception) {
        return ResponseEntity.status(exception.status()).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(exception.code(),exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception
    ) {
        // 详细原因保留在后端日志中，前端收到适合用户阅读的信息。
        log.error("处理请求失败", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiErrorResponse(
                        "SERVER_ERROR",
                        "服务器暂时无法处理请求，请稍后重试"
                )
        );
    }
    @ExceptionHandler(com.campus.market.auth.LoginRateLimitedException.class)
    public ResponseEntity<ApiErrorResponse> handleLoginRateLimited(
            com.campus.market.auth.LoginRateLimitedException exception) {
        return ResponseEntity.status(429).cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header("Retry-After", String.valueOf(exception.retryAfterSeconds()))
                .body(new ApiErrorResponse("LOGIN_RATE_LIMITED", exception.getMessage()));
    }

    @ExceptionHandler(com.campus.market.auth.LoginProtectionUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleLoginProtectionUnavailable(
            com.campus.market.auth.LoginProtectionUnavailableException exception) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache").header("Retry-After", "5")
                .body(new ApiErrorResponse("LOGIN_PROTECTION_UNAVAILABLE", exception.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).body(
                new ApiErrorResponse("INVALID_CREDENTIALS", exception.getMessage())
        );
    }
}

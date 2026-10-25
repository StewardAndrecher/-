package com.campus.market.auth;

import io.swagger.v3.oas.annotations.media.Schema;

public record PasswordChangeRequest(
        @Schema(accessMode=Schema.AccessMode.WRITE_ONLY) String currentPassword,
        @Schema(accessMode=Schema.AccessMode.WRITE_ONLY, minLength=8, maxLength=64) String newPassword) {
    @Override public String toString() { return "PasswordChangeRequest[REDACTED]"; }
}

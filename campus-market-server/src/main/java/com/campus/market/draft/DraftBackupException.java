package com.campus.market.draft;

public class DraftBackupException extends RuntimeException {
 private final int status;
 private final String code;
 public DraftBackupException(int status,String code,String message) { super(message);this.status=status;this.code=code; }
 public int status() { return status; }
 public String code() { return code; }
 public static DraftBackupException invalid() { return new DraftBackupException(400,"INVALID_DRAFT","草稿内容或版本格式不正确"); }
 public static DraftBackupException conflict() { return new DraftBackupException(409,"DRAFT_CONFLICT","云端草稿已有不同或更新内容，请刷新检查，或在本机另存新草稿"); }
}

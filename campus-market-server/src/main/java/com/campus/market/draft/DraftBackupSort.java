package com.campus.market.draft;

/** 仅允许服务端定义的排序片段，绝不拼接用户传来的SQL。 */
public enum DraftBackupSort {
 UPDATED_DESC("updated_at DESC,draft_id ASC"),
 UPDATED_ASC("updated_at ASC,draft_id ASC"),
 TITLE_ASC("title COLLATE utf8mb4_bin ASC,draft_id ASC");
 private final String orderBy;
 DraftBackupSort(String orderBy) { this.orderBy=orderBy; }
 public String orderBy() { return orderBy; }
 public static DraftBackupSort parse(String code) {
  if(code==null||code.isEmpty()) return UPDATED_DESC;
  try { return valueOf(code); }
  catch(IllegalArgumentException error) { throw new DraftBackupException(400,"INVALID_DRAFT_QUERY","草稿排序方式无效，请从排序列表中选择"); }
 }
}

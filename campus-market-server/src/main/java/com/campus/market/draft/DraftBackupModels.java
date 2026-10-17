package com.campus.market.draft;

public final class DraftBackupModels {
 private DraftBackupModels() {}
 public record Content(String title,String description,String priceText,String coverImageId,String categoryCode) {}
 public record SaveRequest(Long sourceRevision,Content content) {}
 public record Backup(String draftId,long sourceRevision,long version,String updatedAt,Content content) {}
 public record Page(java.util.List<Backup> items,long total,int page,int size,int totalPages,String keyword,String category,String sort) {}
 public record Saved(Backup backup,boolean created) {}
}

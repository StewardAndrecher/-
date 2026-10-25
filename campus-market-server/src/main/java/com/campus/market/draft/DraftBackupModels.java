package com.campus.market.draft;

public final class DraftBackupModels {
 private DraftBackupModels() {}
 public record Content(String title,String description,String priceText,String coverImageId,String categoryCode,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.campus.market.product.ProductDetails details) {
  public Content(String title,String description,String priceText,String coverImageId,String categoryCode){this(title,description,priceText,coverImageId,categoryCode,null);}
 }
 public record SaveRequest(Long sourceRevision,Content content) {}
 public record Backup(String draftId,long sourceRevision,long version,String updatedAt,Content content) {}
 public record Page(java.util.List<Backup> items,long total,int page,int size,int totalPages,String keyword,String category,String sort) {}
 public record Saved(Backup backup,boolean created) {}
}

package com.campus.market.product;

import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class ProductModerationModels {
    private ProductModerationModels() {}
    public record Action(String action, String reason, @JsonDeserialize(using=ProductVersionDeserializer.class) Long version, String requestId) {}
    public record Appeal(String reason, @JsonDeserialize(using=ProductVersionDeserializer.class) Long version, String requestId) {}
    public record Review(String state, String reason, String appealReason, long lastEventId, String updatedAt) {}
    public record Item(ProductResponse product, Review moderation) {}
    public record Evidence(String title, String description, long priceInCents, String categoryCode, String coverImageId) {}
    public record Event(long id, String action, String actorRole, String reason, String fromState, String toState,
                        long fromVersion, long toVersion, String createdAt, Evidence snapshot) {}
    public record Detail(Item item, List<Event> events) {public Detail {events=List.copyOf(events);}}
    public record Receipt(Detail detail, boolean replayed) {}
    public record Page(List<Item> records, long total, int page, int size) {public Page {records=List.copyOf(records);}}
    record Existing(long productId, String action, String reason, long version) {}
}

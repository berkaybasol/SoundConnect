package com.berkayb.soundconnect.modules.marketplace.dto;

import com.berkayb.soundconnect.modules.marketplace.MarketplaceTypes.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MarketplaceResponses {
    private MarketplaceResponses() {}
    public record Category(UUID id, String code, String name, List<Category> children) {}
    public record ListingCategory(UUID id, String code, String name, UUID rootId, String rootCode, String rootName) {}
    public record Location(UUID id, String name) {}
    public record Photo(UUID assetId) {}
    public record Seller(UUID userId, String username, String profileType, UUID profileId, String displayName, String avatarUrl) {}
    public record Listing(UUID id, long version, Status status, String title, String description,
                          ListingCategory category, String brand, String model, Condition condition,
                          Long priceMinor, String currency, boolean negotiable, Delivery deliveryMethod,
                          Location city, Location district, List<Photo> photos, Seller seller,
                          @JsonProperty("isOwner") boolean isOwner, boolean saved,
                          Instant createdAt, Instant publishedAt) {}
    public record ReportReceipt(UUID id, ReportStatus status) {}
    public record AdminReport(UUID id, long version, UUID listingId, UUID reporterUserId,
                              ReportReason reason, String description, ReportStatus status,
                              JsonNode evidence, Instant reportedAt, UUID reviewedByUserId,
                              Instant reviewedAt, ReportDecision decision, String resolutionNote) {}
}

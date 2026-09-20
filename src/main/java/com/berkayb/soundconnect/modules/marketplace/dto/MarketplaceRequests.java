package com.berkayb.soundconnect.modules.marketplace.dto;

import com.berkayb.soundconnect.modules.marketplace.MarketplaceTypes.*;
import com.berkayb.soundconnect.modules.marketplace.support.MarketplaceIntegerDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class MarketplaceRequests {
    private MarketplaceRequests() {}
    public record Draft(@NotNull UUID clientRequestId) {}
    public record Version(@NotNull @PositiveOrZero @JsonDeserialize(using=MarketplaceIntegerDeserializer.class) Long expectedVersion) {}
    public record Update(
            @NotNull @PositiveOrZero @JsonDeserialize(using=MarketplaceIntegerDeserializer.class) Long expectedVersion,
            @Size(max=120) String title, @Size(max=4000) String description,
            UUID categoryId, @Size(max=80) String brand, @Size(max=100) String model,
            Condition condition, @Positive @Max(100_000_000_000L) @JsonDeserialize(using=MarketplaceIntegerDeserializer.class) Long priceMinor,
            UUID districtId, @NotNull @Size(max=8) List<@NotNull UUID> photoIds,
            @NotNull Boolean negotiable, Delivery deliveryMethod) {}
    public record Filter(@Size(max=100) String q, UUID categoryId, UUID cityId, UUID districtId,
                         Condition condition, @PositiveOrZero Long minPriceMinor,
                         @PositiveOrZero Long maxPriceMinor, SortOrder sort) {}
    public record Report(@NotNull ReportReason reason, @Size(max=1000) String description,
                         @NotNull UUID clientRequestId) {}
    public record Review(@NotNull @PositiveOrZero @JsonDeserialize(using=MarketplaceIntegerDeserializer.class) Long expectedVersion,
                         @NotNull ReportDecision decision,
                         @NotBlank @Size(min=5,max=1000) String resolutionNote) {}
}

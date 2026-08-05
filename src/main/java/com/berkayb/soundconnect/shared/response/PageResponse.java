package com.berkayb.soundconnect.shared.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable API pagination contract independent of Spring Data's internal JSON
 * representation. Controllers should expose this DTO instead of {@link Page}.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> PageResponse<T> from(Page<T> source) {
        return new PageResponse<>(
                List.copyOf(source.getContent()),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.isFirst(),
                source.isLast()
        );
    }

    /**
     * Rolling-deployment compatibility for mobile clients that consumed
     * Spring Data's historical {@code number} field.
     */
    @JsonProperty("number")
    public int legacyNumber() {
        return page;
    }
}

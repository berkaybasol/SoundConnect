package com.berkayb.soundconnect.modules.studio.room.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

public record StudioPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> StudioPageResponse<T> from(Page<T> source) {
        return new StudioPageResponse<>(
                List.copyOf(source.getContent()),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.isFirst(),
                source.isLast()
        );
    }
}

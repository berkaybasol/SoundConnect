package com.berkayb.soundconnect.modules.event.discovery;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import java.util.List;

public record EventDiscoveryPage(List<EventResponseDto> content, int number, int size,
        long totalElements, int totalPages, boolean last) {
    public EventDiscoveryPage {
        content = List.copyOf(content);
    }
}

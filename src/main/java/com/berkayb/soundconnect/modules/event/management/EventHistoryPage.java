package com.berkayb.soundconnect.modules.event.management;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import java.util.List;

public record EventHistoryPage(List<EventResponseDto> items, String nextCursor, boolean hasNext) {}

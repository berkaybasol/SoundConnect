package com.berkayb.soundconnect.modules.message.dm.dto.response;

import java.util.List;

/** A keyset page; the opaque cursor belongs to the authenticated reader. */
public record DMConversationPageResponseDto(
        List<DMConversationPreviewResponseDto> content, boolean hasNext, String nextCursor) {
    public DMConversationPageResponseDto {
        content = List.copyOf(content);
    }
}

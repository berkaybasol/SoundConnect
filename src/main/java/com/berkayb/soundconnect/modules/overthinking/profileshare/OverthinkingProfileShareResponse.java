package com.berkayb.soundconnect.modules.overthinking.profileshare;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import java.time.Instant;
import java.util.UUID;

public final class OverthinkingProfileShareResponse {
    private OverthinkingProfileShareResponse() { }
    public record State(UUID postId, UUID shareId, boolean publishedOnProfile, String note, Instant publishedAt, boolean canPublish) { }
    public record Post(UUID shareId, String note, Instant publishedAt, OverthinkingPostResponseDto post) { }
}

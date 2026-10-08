package com.berkayb.soundconnect.modules.feed.musician.delivery;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MusicianFeedDeliveryTokenCodecTest {
    @Test
    void tokenIsInvalidAtTheExactLedgerExpiryBoundary() {
        MusicianFeedProperties properties = new MusicianFeedProperties();
        properties.setDeliverySecret("delivery-token-test-secret-at-least-32-bytes");
        MusicianFeedDeliveryTokenCodec codec = new MusicianFeedDeliveryTokenCodec(
                new ObjectMapper().findAndRegisterModules(), properties);
        UUID viewer = UUID.randomUUID();
        Instant deliveredAt = Instant.parse("2026-09-11T12:00:00Z");
        Instant expiresAt = deliveredAt.plusSeconds(60);
        MusicianFeedDeliveredItem delivery = new MusicianFeedDeliveredItem(
                UUID.randomUUID(), viewer, UUID.randomUUID(), "TRACK:one",
                MusicianFeedItemType.TRACK, "MEDIA", UUID.randomUUID(), "MUSICIAN",
                UUID.randomUUID(), "FOLLOWING_PUBLICATION", Set.of(), 1, "musician-v1.0.0",
                0, null, "{}", deliveredAt, expiresAt, expiresAt.plusSeconds(60),
                MusicianFeedLane.FOLLOWING);
        String token = codec.encode(delivery);

        assertThat(codec.decode(token, viewer, expiresAt.minusMillis(1)).itemId())
                .isEqualTo("TRACK:one");
        assertThatThrownBy(() -> codec.decode(token, viewer, expiresAt))
                .isInstanceOfSatisfying(SoundConnectException.class, failure ->
                        assertThat(failure.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
    }
}

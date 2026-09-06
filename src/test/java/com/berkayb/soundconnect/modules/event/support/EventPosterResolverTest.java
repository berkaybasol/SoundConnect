package com.berkayb.soundconnect.modules.event.support;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EventPosterResolverTest {
    private final MediaAssetService media = mock(MediaAssetService.class);

    @Test void missingPosterDoesNotPerformMediaLookup() {
        assertThat(EventPosterResolver.resolve(null, media)).isNull();
        assertThat(EventPosterResolver.resolve("  ", media)).isNull();
        verifyNoInteractions(media);
    }

    @Test void legacyUrlRemainsCompatible() {
        assertThat(EventPosterResolver.resolve("https://cdn.test/poster.jpg", media))
                .isEqualTo("https://cdn.test/poster.jpg");
        verifyNoInteractions(media);
    }

    @Test void mediaIdResolvesToDisplayUrl() {
        UUID id = UUID.randomUUID();
        when(media.getDisplayUrl(id)).thenReturn("https://cdn.test/processed.jpg");
        assertThat(EventPosterResolver.resolve(id.toString(), media)).isEqualTo("https://cdn.test/processed.jpg");
    }

    @Test void mediaFailureFallsBackWithoutExposingId() {
        UUID id = UUID.randomUUID();
        when(media.getDisplayUrl(id)).thenThrow(new IllegalStateException("Unavailable"));
        assertThat(EventPosterResolver.resolve(id.toString(), media)).isNull();
    }
}

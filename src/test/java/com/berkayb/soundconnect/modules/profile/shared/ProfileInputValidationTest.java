package com.berkayb.soundconnect.modules.profile.shared;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ProfileInputValidationTest {
    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "data:text/html,<script>alert(1)</script>",
            "file:///tmp/profile", "//example.com/path", "https://user:password@example.com",
            "https://example.com:65536", "https://example.com/a\nb"})
    void rejectsNonWebOrAmbiguousLinks(String value) {
        assertThatThrownBy(() -> ProfileInputValidation.webUrl(value, "websiteUrl"))
                .isInstanceOfSatisfying(SoundConnectException.class, exception ->
                        assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
    }

    @Test void preservesOmissionClearingAndAlreadyEncodedPaths() {
        assertThat(ProfileInputValidation.webUrl(null, "link")).isNull();
        assertThat(ProfileInputValidation.webUrl("   ", "link")).isEmpty();
        assertThat(ProfileInputValidation.webUrl(" instagram.com/artist ", "link"))
                .isEqualTo("https://instagram.com/artist");
        assertThat(ProfileInputValidation.webUrl("https://example.com/a%20b?name=a%26b", "link"))
                .isEqualTo("https://example.com/a%20b?name=a%26b");
    }

    @Test void boundsTheCanonicalUrlAndStoredIdentifiers() {
        assertThatThrownBy(() -> ProfileInputValidation.webUrl("example.com/" + "a".repeat(240), "link"))
                .isInstanceOf(SoundConnectException.class);
        assertThat(ProfileInputValidation.optionalIdentifier("   ")).isNull();
        assertThatThrownBy(() -> ProfileInputValidation.trackIds(Arrays.asList("track", null)))
                .isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> ProfileInputValidation.trackIds(List.of(" ")))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test void nestedSpotifyMetadataHasAnHttpValidationBoundary() {
        var track = new SpotifyTrackItemDto("track", "x".repeat(256), -1, false,
                null, null, null, null, List.of());
        var dto = new MusicianProfileSaveRequestDto(null, null, null, null, null, null,
                null, null, null, null, List.of(track));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(dto)).extracting(violation -> violation.getPropertyPath().toString())
                    .contains("spotifyTracks[0].name", "spotifyTracks[0].durationMs");
        }
    }
}

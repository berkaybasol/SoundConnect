package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandMemberTitleUpdateDto;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class BandMemberTitleTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "　  "})
    void blankClearsTitle(String raw) { assertThat(BandMemberTitle.normalize(raw)).isNull(); }

    @Test void unicodeTrimsAndNormalizesNfc() {
        assertThat(BandMemberTitle.normalize("   Vokal / Bag\u0306lama　")).isEqualTo("Vokal / Bağlama");
    }

    @ParameterizedTest @ValueSource(strings = {
            "Elektro gitar", "Vokal & Gitar", "🎸", "👨‍👩‍👧‍👦", "🇹🇷", "हिन्दी", "Lead vocal", "42", "Kurucu"
    })
    void freeFormTextIsOnlyADisplayTitle(String raw) {
        assertThat(BandMemberTitle.normalize(raw)).isNotBlank();
    }

    @Test void countsGraphemesInsteadOfUtf16UnitsOrCodePoints() {
        assertThat(BandMemberTitle.normalize("👩‍🎤".repeat(20))).isEqualTo("👩‍🎤".repeat(20));
        assertThat(BandMemberTitle.normalize("a\u0301".repeat(20))).isEqualTo("á".repeat(20));
        assertThatThrownBy(() -> BandMemberTitle.normalize("👩‍🎤".repeat(21))).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> BandMemberTitle.normalize("a".repeat(21))).isInstanceOf(SoundConnectException.class);
    }

    @ParameterizedTest @ValueSource(strings = {
            "a\nb", "a\rb", "a\tb", "a\u0000b", "a\u0085b", "a\u2028b", "a\u2029b",
            "a\u202Eb", "a\u2066b", "a\u200Bb", "a\uFEFFb", "a\u00ADb", "\u200D", "\u0301", "\uFE0F", "\uD800"
    })
    void controlsBidiAndInvisibleOnlyInputAreRejected(String raw) {
        assertThatThrownBy(() -> BandMemberTitle.normalize(raw)).isInstanceOf(SoundConnectException.class);
    }

    @Test void rawCodePointBudgetBoundsCombiningInputBeforeNfcAndSegmentation() {
        assertThatThrownBy(() -> BandMemberTitle.normalize("a" + "\u0301".repeat(256))).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> BandMemberTitle.normalize(" ".repeat(257))).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> BandMemberTitle.normalize("a" + "\u0344".repeat(255))).isInstanceOf(SoundConnectException.class);
        assertThat(BandMemberTitle.normalize("a" + "\u0301".repeat(255))).isNotBlank();
    }

    @Test void dtoValidationRejectsBadVersionAndTextBeforeService() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new BandMemberTitleUpdateDto("Vokal", null))).isNotEmpty();
            assertThat(validator.validate(new BandMemberTitleUpdateDto("Vokal", -1L))).isNotEmpty();
            assertThat(validator.validate(new BandMemberTitleUpdateDto("a".repeat(21), 0L))).isNotEmpty();
            assertThat(validator.validate(new BandMemberTitleUpdateDto("a\nb", 0L))).isNotEmpty();
            assertThat(validator.validate(new BandMemberTitleUpdateDto("👩‍🎤".repeat(20), 0L))).isEmpty();
            assertThat(validator.validate(new BandMemberTitleUpdateDto(null, 0L))).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"expectedTitleVersion\":0}",
            "{\"memberTitle\":42,\"expectedTitleVersion\":0}",
            "{\"memberTitle\":true,\"expectedTitleVersion\":0}",
            "{\"memberTitle\":\"Vokal\",\"expectedTitleVersion\":\"0\"}",
            "{\"memberTitle\":\"Vokal\",\"expectedTitleVersion\":0.5}",
            "{\"memberTitle\":\"Vokal\",\"expectedTitleVersion\":9223372036854775808}"
    })
    void jsonTypesAreStrict(String json) {
        assertThatThrownBy(() -> new ObjectMapper().readValue(json, BandMemberTitleUpdateDto.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}

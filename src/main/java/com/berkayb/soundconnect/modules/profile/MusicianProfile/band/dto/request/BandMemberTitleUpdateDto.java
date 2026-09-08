package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandMemberTitle;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

public record BandMemberTitleUpdateDto(
        // A grapheme can span multiple UTF-16 characters. Final normalization
        // and the 20-grapheme limit are enforced by BandMemberTitle.
        @JsonProperty(value = "memberTitle", required = true)
        @Size(max = 512) @JsonDeserialize(using = StrictText.class) String memberTitle,
        @NotNull @PositiveOrZero @JsonDeserialize(using = StrictVersion.class) Long expectedTitleVersion
) {
    @AssertTrue(message = "Üye başlığı tek satır ve en fazla 20 karakter olmalı.")
    @JsonIgnore
    public boolean isMemberTitleValid() {
        try { BandMemberTitle.normalize(memberTitle); return true; }
        catch (SoundConnectException invalid) { return false; }
    }

    public static final class StrictText extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(String.class, "memberTitle must be text or null");
            }
            return parser.getText();
        }
    }

    public static final class StrictVersion extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Long.class, "expectedTitleVersion must be an integer");
            }
            return parser.getLongValue();
        }
    }
}

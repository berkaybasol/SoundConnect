package com.berkayb.soundconnect.modules.event.performer.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

/** A missing choice never opts a new participation consent into publication. */
public record EventPerformerAcceptRequestDto(
        @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean showOnProfile
) {
    /** Privacy consent accepts JSON booleans, never coerced strings or numbers. */
    public static final class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_TRUE)) return true;
            if (parser.hasToken(JsonToken.VALUE_FALSE)) return false;
            return context.reportInputMismatch(Boolean.class, "showOnProfile must be a JSON boolean.");
        }
    }
}

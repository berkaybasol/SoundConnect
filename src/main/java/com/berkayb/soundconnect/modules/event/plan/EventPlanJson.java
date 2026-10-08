package com.berkayb.soundconnect.modules.event.plan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Revisions and recurrence days are exact integers, never truncated floats or coerced strings. */
public final class EventPlanJson {
    private EventPlanJson() { }
    public static final class Revision extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Long.class,"expectedVersion must be a JSON integer.");
            }
            return parser.getLongValue();
        }
    }
    public static final class Weekday extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class,"weekdays must contain JSON integers.");
            }
            return parser.getIntValue();
        }
    }
}

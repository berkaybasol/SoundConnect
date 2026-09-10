package com.berkayb.soundconnect.modules.overthinking.profileshare;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

@JsonDeserialize(using = OverthinkingProfileShareUpdate.Deserializer.class)
public record OverthinkingProfileShareUpdate(String note) {
    public static final class Deserializer extends StdDeserializer<OverthinkingProfileShareUpdate> {
        public Deserializer() { super(OverthinkingProfileShareUpdate.class); }
        @Override public OverthinkingProfileShareUpdate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = parser.getCodec().readTree(parser);
            if (!root.isObject() || root.size() > 1 || (root.size() == 1 && !root.has("note"))
                    || (root.has("note") && !root.get("note").isNull() && !root.get("note").isTextual())) {
                return context.reportInputMismatch(OverthinkingProfileShareUpdate.class, "Only an optional text note is accepted");
            }
            return new OverthinkingProfileShareUpdate(root.path("note").isTextual() ? root.get("note").textValue() : null);
        }
    }
}

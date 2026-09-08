package com.berkayb.soundconnect.modules.event.audience;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Set;

@JsonDeserialize(using = EventIntentUpdate.Deserializer.class)
public record EventIntentUpdate(EventIntent intent, Boolean publishedOnProfile, String note, Long expectedVersion) {
    /** Exact full-state command: no actor IDs, unknown fields, duplicate keys or scalar coercion. */
    public static final class Deserializer extends StdDeserializer<EventIntentUpdate> {
        public Deserializer() { super(EventIntentUpdate.class); }
        @Override public EventIntentUpdate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = parser.getCodec().readTree(parser);
            Set<String> names = Set.of("intent", "publishedOnProfile", "note", "expectedVersion");
            if (!root.isObject() || root.size() != 4) return invalid(context);
            var fields = root.fieldNames();
            while (fields.hasNext()) if (!names.contains(fields.next())) return invalid(context);
            if (!root.path("intent").isTextual() || !root.path("publishedOnProfile").isBoolean()
                    || !(root.path("note").isNull() || root.path("note").isTextual())
                    || !root.path("expectedVersion").isIntegralNumber() || !root.path("expectedVersion").canConvertToLong()) return invalid(context);
            EventIntent intent;
            try { intent = EventIntent.valueOf(root.get("intent").textValue()); }
            catch (IllegalArgumentException invalid) { return invalid(context); }
            return new EventIntentUpdate(intent, root.get("publishedOnProfile").booleanValue(),
                    root.get("note").isNull() ? null : root.get("note").textValue(), root.get("expectedVersion").longValue());
        }
        private EventIntentUpdate invalid(DeserializationContext context) throws IOException {
            return context.reportInputMismatch(EventIntentUpdate.class, "Invalid event intent command");
        }
    }
}

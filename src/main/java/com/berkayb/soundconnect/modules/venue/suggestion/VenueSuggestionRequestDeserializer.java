package com.berkayb.soundconnect.modules.venue.suggestion;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/** Narrow endpoint-only parsing: no enum ordinals, scalar coercion or silently ignored contact/recipient fields. */
public class VenueSuggestionRequestDeserializer extends StdDeserializer<VenueSuggestionRequest> {
    private static final Set<String> FIELDS = Set.of("requestId", "venueName", "cityId", "districtId", "liveMusic");
    public VenueSuggestionRequestDeserializer() { super(VenueSuggestionRequest.class); }
    @Override public VenueSuggestionRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        if (!node.isObject() || node.size() != FIELDS.size()) throw invalid(parser);
        for (String field : FIELDS) if (!node.has(field) || !node.get(field).isTextual()) throw invalid(parser);
        try {
            return new VenueSuggestionRequest(uuid(node.get("requestId").textValue()), node.get("venueName").textValue(),
                    uuid(node.get("cityId").textValue()), uuid(node.get("districtId").textValue()),
                    VenueSuggestionRequest.LiveMusic.valueOf(node.get("liveMusic").textValue()));
        } catch (IllegalArgumentException malformed) { throw invalid(parser); }
    }
    private static UUID uuid(String text) {
        UUID value = UUID.fromString(text);
        if (!value.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException("Noncanonical UUID");
        return value;
    }
    private static JsonMappingException invalid(JsonParser parser) { return JsonMappingException.from(parser, "Invalid venue suggestion fields"); }
}

package com.berkayb.soundconnect.modules.analytics;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Scoped strict parsing: no scalar/enum coercion, undeclared identity fields or silently ignored fields. */
public class AnalyticsRequestDeserializer extends StdDeserializer<AnalyticsRequest> {
    private static final Set<String> FIELDS = Set.of("id", "type", "eventId", "venueId", "sourceEventId", "observedAt");
    public AnalyticsRequestDeserializer() { super(AnalyticsRequest.class); }
    @Override public AnalyticsRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode node = parser.getCodec().readTree(parser);
        try {
            if (!node.isObject() || node.size() != 2 || !node.has("clientId") || !node.has("observations")) throw invalid();
            UUID clientId = uuid(node.get("clientId"), false);
            var array = node.get("observations");
            if (!array.isArray() || array.isEmpty() || array.size() > 20) throw invalid();
            var observations = new ArrayList<AnalyticsRequest.Observation>();
            var ids = new HashSet<UUID>();
            for (JsonNode row : array) {
                if (!row.isObject()) throw invalid();
                var names = row.fieldNames();
                while (names.hasNext()) if (!FIELDS.contains(names.next())) throw invalid();
                UUID id = uuid(row.get("id"), false);
                if (!ids.add(id)) throw invalid();
                AnalyticsRequest.Type type = AnalyticsRequest.Type.valueOf(text(row.get("type")));
                UUID event = uuid(row.get("eventId"), true), venue = uuid(row.get("venueId"), true);
                UUID source = uuid(row.get("sourceEventId"), true);
                String timestamp = text(row.get("observedAt"));
                if (!timestamp.endsWith("Z")) throw invalid();
                Instant observed = Instant.parse(timestamp);
                if (type == AnalyticsRequest.Type.VENUE_PROFILE_VIEW) {
                    if (venue == null || event != null) throw invalid();
                } else if (event == null || venue != null || source != null) throw invalid();
                observations.add(new AnalyticsRequest.Observation(id, type, event, venue, source, observed));
            }
            return new AnalyticsRequest(clientId, List.copyOf(observations));
        } catch (RuntimeException malformed) {
            throw JsonMappingException.from(parser, "Invalid analytics observation fields");
        }
    }
    private static UUID uuid(JsonNode value, boolean optional) {
        if (value == null || value.isNull()) { if (optional) return null; throw invalid(); }
        String text = text(value);
        UUID id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text) || id.equals(AnalyticsIdentity.NONE)) throw invalid();
        return id;
    }
    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid observation"); }
}

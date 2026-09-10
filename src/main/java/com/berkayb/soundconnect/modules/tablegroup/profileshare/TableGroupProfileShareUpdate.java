package com.berkayb.soundconnect.modules.tablegroup.profileshare;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

@JsonDeserialize(using = TableGroupProfileShareUpdate.Deserializer.class)
public record TableGroupProfileShareUpdate(String note) {
    public static final class Deserializer extends StdDeserializer<TableGroupProfileShareUpdate> {
        public Deserializer() { super(TableGroupProfileShareUpdate.class); }

        @Override
        public TableGroupProfileShareUpdate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = parser.getCodec().readTree(parser);
            if (!root.isObject() || root.size() > 1 || (root.size() == 1 && !root.has("note"))
                    || (root.has("note") && !root.get("note").isNull() && !root.get("note").isTextual())) {
                return context.reportInputMismatch(TableGroupProfileShareUpdate.class, "Only an optional text note is accepted");
            }
            return new TableGroupProfileShareUpdate(root.path("note").isTextual() ? root.get("note").textValue() : null);
        }
    }
}

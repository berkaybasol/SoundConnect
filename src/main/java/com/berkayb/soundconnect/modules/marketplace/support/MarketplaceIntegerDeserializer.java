package com.berkayb.soundconnect.modules.marketplace.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Prices and versions must never be silently rounded by Jackson's default float coercion. */
public class MarketplaceIntegerDeserializer extends JsonDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Long.class,"Expected a JSON integer");
        }
        return parser.getLongValue();
    }
}

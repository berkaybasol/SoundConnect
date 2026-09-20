package com.berkayb.soundconnect.modules.marketplace;

import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceRequests.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class MarketplaceRequestContractTest {
    private final ObjectMapper json=new ObjectMapper();
    @ParameterizedTest @ValueSource(strings={"1.99","\"199\"","true","9223372036854775808"})
    void moneyCannotBeRoundedCoercedOrOverflowed(String price) {
        assertThatThrownBy(()->json.readValue("{\"priceMinor\":"+price+"}",Update.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @Test void nullableDraftPriceAndIntegerPublicationPriceRemainValid() throws Exception {
        assertThat(json.readValue("{\"priceMinor\":null}",Update.class).priceMinor()).isNull();
        assertThat(json.readValue("{\"priceMinor\":12345}",Update.class).priceMinor()).isEqualTo(12345L);
    }
    @Test void fractionalVersionCannotPassOptimisticConcurrency() {
        assertThatThrownBy(()->json.readValue("{\"expectedVersion\":1.5}",Version.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}

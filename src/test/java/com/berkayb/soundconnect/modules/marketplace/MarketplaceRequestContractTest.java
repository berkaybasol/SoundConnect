package com.berkayb.soundconnect.modules.marketplace;

import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceRequests.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    @ParameterizedTest @CsvSource({
            "Update,title,120", "Update,description,4000", "Update,brand,80", "Update,model,100",
            "Filter,q,100", "Report,description,1000", "Review,resolutionNote,1000"
    })
    void requestTextLimitsCountCodePointsInsteadOfUtf16Units(String request,String field,int limit) {
        Class<?> type=switch(request) {
            case "Update" -> Update.class;
            case "Filter" -> Filter.class;
            case "Report" -> Report.class;
            case "Review" -> Review.class;
            default -> throw new IllegalArgumentException(request);
        };
        String emoji="\uD83C\uDFB8";
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertThat(validator.validateValue(type,field,emoji.repeat(limit))).isEmpty();
            assertThat(validator.validateValue(type,field,emoji.repeat(limit+1))).hasSize(1);
        }
    }
    @Test void reviewMinimumCountsCodePointsAndStillRejectsBlankNotes() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertThat(validator.validateValue(Review.class,"resolutionNote","\uD83C\uDFB8".repeat(3))).hasSize(1);
            assertThat(validator.validateValue(Review.class,"resolutionNote","\uD83C\uDFB8".repeat(5))).isEmpty();
            assertThat(validator.validateValue(Review.class,"resolutionNote","     ")).hasSize(1);
        }
    }
}

package com.berkayb.soundconnect.shared.response;

import com.berkayb.soundconnect.modules.studio.room.dto.response.StudioPageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaginationResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sharedPageContractPublishesStableAndLegacyPageNumbers() throws Exception {
        PageResponse<String> response = PageResponse.from(
                new PageImpl<>(List.of("item"), PageRequest.of(2, 10), 31)
        );

        assertRollingPaginationContract(objectMapper.valueToTree(response));
    }

    @Test
    void studioPageContractPublishesStableAndLegacyPageNumbers() throws Exception {
        StudioPageResponse<String> response = StudioPageResponse.from(
                new PageImpl<>(List.of("item"), PageRequest.of(2, 10), 31)
        );

        assertRollingPaginationContract(objectMapper.valueToTree(response));
    }

    private static void assertRollingPaginationContract(JsonNode json) {
        assertThat(json.get("page").asInt()).isEqualTo(2);
        assertThat(json.get("number").asInt()).isEqualTo(2);
        assertThat(json.get("size").asInt()).isEqualTo(10);
        assertThat(json.get("totalElements").asLong()).isEqualTo(31);
        assertThat(json.get("totalPages").asInt()).isEqualTo(4);
        assertThat(json.get("first").asBoolean()).isFalse();
        assertThat(json.get("last").asBoolean()).isFalse();
        assertThat(json.get("content").get(0).asText()).isEqualTo("item");
    }
}

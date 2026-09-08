package com.berkayb.soundconnect.modules.profile.VenueProfile.controller.user;

import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueActiveArtistDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.enums.VenueActiveArtistType;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueActiveArtistService;
import com.berkayb.soundconnect.shared.response.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VenueActiveArtistControllerTest {
    VenueActiveArtistService service;
    MockMvc mvc;
    UUID venueId = UUID.randomUUID();
    String path = "/api/v1/public/venue-profiles/" + venueId + "/active-artists";

    @BeforeEach
    void setUp() {
        service = mock(VenueActiveArtistService.class);
        mvc = MockMvcBuilders.standaloneSetup(new VenueActiveArtistController(service)).build();
    }

    @Test
    void defaultPublicRequestHasStablePageAndContainsNoPrivateInvitationFields() throws Exception {
        UUID artistId = UUID.randomUUID();
        when(service.list(venueId, VenueActiveArtistType.MUSICIAN, null, 0, 20)).thenReturn(
                new PageResponse<>(List.of(new VenueActiveArtistDto(artistId, "bugrasahin",
                        VenueActiveArtistType.MUSICIAN, null)), 0, 20, 1, 1, true, true));
        mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(artistId.toString()))
                .andExpect(jsonPath("$.data.content[0].type").value("MUSICIAN"))
                .andExpect(jsonPath("$.data.content[0].name").value("bugrasahin"))
                .andExpect(jsonPath("$.data.content[0].message").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].requestId").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].userId").doesNotExist());
    }

    @Test
    void forwardsGroupSearchAndPagination() throws Exception {
        when(service.list(venueId, VenueActiveArtistType.BAND, "Şah", 2, 10)).thenReturn(
                new PageResponse<>(List.of(), 2, 10, 0, 0, false, true));
        mvc.perform(get(path).param("type", "BAND").param("q", "Şah")
                        .param("page", "2").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
        verify(service).list(venueId, VenueActiveArtistType.BAND, "Şah", 2, 10);
    }

    @ParameterizedTest
    @CsvSource({"type,MANUAL", "type,OTHER", "page,-1", "page,10001", "size,0", "size,51", "page,no"})
    void invalidQueryParametersAreRejectedBeforeService(String key, String value) throws Exception {
        mvc.perform(get(path).param(key, value)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void oversizedSearchAndMalformedVenueAreRejectedBeforeService() throws Exception {
        mvc.perform(get(path).param("q", "x".repeat(101))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/venue-profiles/not-a-uuid/active-artists"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

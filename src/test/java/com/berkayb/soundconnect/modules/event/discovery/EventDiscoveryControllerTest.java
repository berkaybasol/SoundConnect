package com.berkayb.soundconnect.modules.event.discovery;

import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EventDiscoveryControllerTest {
    EventDiscoveryService service;
    MockMvc mvc;
    UUID city = UUID.randomUUID();
    String path = "/api/v1/events/discovery";

    @BeforeEach
    void setUp() {
        service = mock(EventDiscoveryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new EventDiscoveryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void defaultsAndPageEnvelopeRemainStable() throws Exception {
        when(service.discover(null, city, null, null, 0, 20))
                .thenReturn(new EventDiscoveryPage(List.of(), 0, 20, 0, 0, true));
        mvc.perform(get(path).param("cityId", city.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.last").value(true));
        verify(service).discover(null, city, null, null, 0, 20);
    }

    @Test
    void allFiltersReachServiceWithoutChangingSelectedCalendarDay() throws Exception {
        UUID district = UUID.randomUUID(), neighborhood = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 9, 9);
        when(service.discover(date, city, district, neighborhood, 2, 10))
                .thenReturn(new EventDiscoveryPage(List.of(), 2, 10, 0, 0, true));
        mvc.perform(get(path).param("cityId", city.toString()).param("date", date.toString())
                        .param("districtId", district.toString()).param("neighborhoodId", neighborhood.toString())
                        .param("page", "2").param("size", "10"))
                .andExpect(status().isOk());
        verify(service).discover(date, city, district, neighborhood, 2, 10);
    }

    @Test
    void cityIsRequired() throws Exception {
        mvc.perform(get(path)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,1001", "page,no", "size,0", "size,51", "size,999999999999999",
            "cityId,invalid", "districtId,invalid", "neighborhoodId,invalid", "date,2026-02-30", "date,2026-09-08T00:00:00"})
    void malformedAndOutOfRangeParametersNeverReachService(String key, String value) throws Exception {
        var request = get(path).param("cityId", city.toString());
        if (key.equals("cityId")) request = get(path);
        mvc.perform(request.param(key, value)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}

package com.berkayb.soundconnect.modules.location.controller;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
class NeighborhoodControllerIT {
	
	@Autowired MockMvc mockMvc;
	@Autowired CityRepository cityRepository;
	@Autowired DistrictRepository districtRepository;
	@Autowired NeighborhoodRepository neighborhoodRepository;
	
	@MockitoBean
	org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
	
	private District district;
	
	private static final String BASE = EndPoints.Neighborhood.BASE; // "/api/v1/neighborhoods"
	private static final String SAVE = BASE + EndPoints.Neighborhood.SAVE;
	private static final String GET_ALL = BASE + EndPoints.Neighborhood.GET_ALL;
	private static final String GET_BY_ID = BASE + EndPoints.Neighborhood.GET_BY_ID;               // "/get-by-id/{id}"
	private static final String GET_BY_DISTRICT = BASE + EndPoints.Neighborhood.GET_BY_DISTRICT;   // "/get-by-district/{districtId}"
	private static final String DELETE = BASE + EndPoints.Neighborhood.DELETE;                     // "/delete/{id}"
	
	@BeforeEach
	void setup() {
		// FK sırası: önce neighborhood → district → city
		neighborhoodRepository.deleteAll();
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		
		City city = cityRepository.save(City.builder().name("TestCity2").build());
		district = districtRepository.save(
				District.builder().name("Merkez-2").city(city).build()
		);
	}
	
	@Test
	void save_and_getAll_and_getById_and_getByDistrict_and_delete_flow() throws Exception {
		// --- SAVE ---
		String payload = """
            {"name":"Cumhuriyet","districtId":"%s"}
        """.formatted(district.getId());
		
		mockMvc.perform(post(SAVE)
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(payload))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(201))
		       .andExpect(jsonPath("$.data.id", not(blankOrNullString())))
		       .andExpect(jsonPath("$.data.name").value("Cumhuriyet"))
		       .andExpect(jsonPath("$.data.districtId").value(district.getId().toString()));
		
		// --- GET ALL ---
		mockMvc.perform(get(GET_ALL))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)));
		
		String nId = neighborhoodRepository.findAll().getFirst().getId().toString();
		
		// --- GET BY ID ---
		mockMvc.perform(get(GET_BY_ID.replace("{id}", nId)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.id").value(nId))
		       .andExpect(jsonPath("$.data.districtId").value(district.getId().toString()));
		
		// --- GET BY DISTRICT ---
		mockMvc.perform(get(GET_BY_DISTRICT.replace("{districtId}", district.getId().toString())))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)));
		
		// --- DELETE ---
		mockMvc.perform(delete(DELETE.replace("{id}", nId)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200));
		
		// After delete
		mockMvc.perform(get(GET_BY_DISTRICT.replace("{districtId}", district.getId().toString())))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data", hasSize(0)));
	}
}
package com.berkayb.soundconnect.modules.location.controller;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
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

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("web")
class DistrictControllerIT {
	
	@Autowired MockMvc mockMvc;
	@Autowired CityRepository cityRepository;
	@Autowired DistrictRepository districtRepository;
	
	@MockitoBean
	org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
	
	private City city;
	
	private static final String BASE = EndPoints.District.BASE; // "/api/v1/districts"
	private static final String SAVE = BASE + EndPoints.District.SAVE;
	private static final String GET_ALL = BASE + EndPoints.District.GET_ALL;
	private static final String GET_BY_ID = BASE + EndPoints.District.GET_BY_ID;             // "/get-by-id/{id}"
	private static final String GET_BY_CITY = BASE + EndPoints.District.GET_BY_CITY;         // "/get-by-city/{cityId}"
	private static final String DELETE = BASE + EndPoints.District.DELETE;                   // "/delete-district/{id}"
	
	@BeforeEach
	void setup() {
		// FK sırası: önce district’leri sil, sonra city’leri
		districtRepository.deleteAll();
		cityRepository.deleteAll();
		
		city = cityRepository.save(City.builder().name("TestCity").build());
	}
	
	@Test
	void save_and_getAll_and_getById_and_getByCity_and_delete_flow() throws Exception {
		// --- SAVE ---
		String payload = """
            {"name":"Merkez","cityId":"%s"}
        """.formatted(city.getId());
		
		String locationJson = mockMvc.perform(post(SAVE)
				                                      .contentType(MediaType.APPLICATION_JSON)
				                                      .content(payload))
		                             .andExpect(status().isOk())
		                             .andExpect(jsonPath("$.success").value(true))
		                             .andExpect(jsonPath("$.code").value(201))
		                             .andExpect(jsonPath("$.data.id", not(blankOrNullString())))
		                             .andExpect(jsonPath("$.data.name").value("Merkez"))
		                             .andExpect(jsonPath("$.data.cityId").value(city.getId().toString()))
		                             .andReturn().getResponse().getContentAsString();
		
		// Extract created id (basitçe regex’siz: JSON path asserttan sonra GET ile alacağız)
		// --- GET ALL ---
		mockMvc.perform(get(GET_ALL).accept(MediaType.APPLICATION_JSON))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)))
		       .andExpect(jsonPath("$.data[0].name", not(blankOrNullString())));
		
		// District id’yi GET ALL’dan çekelim
		String firstId = districtRepository.findAll().getFirst().getId().toString();
		
		// --- GET BY ID ---
		mockMvc.perform(get(GET_BY_ID.replace("{id}", firstId)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.id").value(firstId))
		       .andExpect(jsonPath("$.data.cityId").value(city.getId().toString()));
		
		// --- GET BY CITY ---
		mockMvc.perform(get(GET_BY_CITY.replace("{cityId}", city.getId().toString())))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)));
		
		// --- DELETE ---
		mockMvc.perform(delete(DELETE.replace("{id}", firstId)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200));
		
		// After delete: list empty
		mockMvc.perform(get(GET_BY_CITY.replace("{cityId}", city.getId().toString())))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data", hasSize(0)));
	}
}
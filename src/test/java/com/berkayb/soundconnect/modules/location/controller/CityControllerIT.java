// CityControllerIT.java
package com.berkayb.soundconnect.modules.location.controller;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.mail.MailProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
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
class CityControllerIT {
	
	@Autowired
	MockMvc mockMvc;
	@Autowired
	CityRepository cityRepository;
	
	
	@MockitoBean private MailProducer mailProducer;
	@MockitoBean(name = "rabbitListenerContainerFactory")
	private RabbitListenerContainerFactory<?> rabbitFactory;
	
	private static final String BASE = EndPoints.City.BASE;
	
	@BeforeEach
	void setup() {
		cityRepository.deleteAll();
	}
	
	@Test
	void save_and_getAll_and_getById_and_delete_flow() throws Exception {
		// create
		mockMvc.perform(post(BASE + EndPoints.City.SAVE)
				                .contentType(MediaType.APPLICATION_JSON)
				                .content("{\"name\":\"Ankara\"}"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.code").value(201))
		       .andExpect(jsonPath("$.data.id", notNullValue()))
		       .andExpect(jsonPath("$.data.name").value("Ankara"));
		
		// list
		mockMvc.perform(get(BASE + EndPoints.City.GET_ALL))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)));
		
		// get by id
		City city = cityRepository.findAll().get(0);
		mockMvc.perform(get(BASE + EndPoints.City.GET_CITY, city.getId()))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.id").value(city.getId().toString()))
		       .andExpect(jsonPath("$.data.name").value("Ankara"));
		
		// delete
		mockMvc.perform(delete(BASE + EndPoints.City.DELETE, city.getId()))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.code").value(200));
		
		// list after delete
		mockMvc.perform(get(BASE + EndPoints.City.GET_ALL))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.data.length()", is(0)));
	}
}
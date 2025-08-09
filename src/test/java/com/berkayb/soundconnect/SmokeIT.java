package com.berkayb.soundconnect;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SmokeIT = "uygulama test ortamında ayağa kalkıyor mu" testi
 * Full Spring Context + Testcontainers PostgreSQL
 */
@SpringBootTest
@ActiveProfiles("test") // src/test/resources/application-test.yml profilini kullan
@AutoConfigureMockMvc
@Testcontainers
public class SmokeIT {
	
	// Test boyunca çalışacak izole PostgreSQL container
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
			.withDatabaseName("sc_test")
			.withUsername("sc")
			.withPassword("sc");
	
	// Spring datasource ayarlarını container bilgileriyle override ediyoruz
	@DynamicPropertySource
	static void registerDatasourceProps(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
	}
	
	@Autowired
	private MockMvc mockMvc;
	
	@Test
	void contextLoadsAndPingEndpointReturnsOk() throws Exception {
		// /api/ping endpointine istek atıp 200 OK bekliyoruz
		mockMvc.perform(get("/api/ping"))
		       .andExpect(status().isOk());
	}
}
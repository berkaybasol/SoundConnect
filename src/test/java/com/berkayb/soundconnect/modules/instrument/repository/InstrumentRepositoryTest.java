package com.berkayb.soundconnect.modules.instrument.repository;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InstrumentRepositoryTest {
	
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");
	
	@DynamicPropertySource
	static void overrideProps(DynamicPropertyRegistry r) {
		r.add("spring.datasource.url", postgres::getJdbcUrl);
		r.add("spring.datasource.username", postgres::getUsername);
		r.add("spring.datasource.password", postgres::getPassword);
		// Dialect belirtmene gerek yok, Postgres'i görür.
		r.add("spring.jpa.hibernate.ddl-auto", () -> "update"); // veya create-drop
	}
	
	@Autowired
	private InstrumentRepository repository;
	
	@Test
	@DisplayName("existsByName true dönmeli")
	void existsByName_returnsTrueWhenNameExists() {
		repository.save(Instrument.builder().name("Violin").build());
		
		boolean exists = repository.existsByName("Violin");
		
		assertThat(exists).isTrue();
	}
	
	@Test
	@DisplayName("existsByName false dönmeli")
	void existsByName_returnsFalseWhenNameNotExists() {
		boolean exists = repository.existsByName("Theremin");
		assertThat(exists).isFalse();
	}
}
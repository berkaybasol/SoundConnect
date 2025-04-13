package com.berkayb.soundconnect.shared.config;


import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger konfigürasyon sınıfıdır.
 * JWT token ile güvenli endpoint'lerin test edilebilmesini sağlar.
 */

@Configuration
@SecurityScheme(
		name = "bearerAuth", // Bu ismi .addSecurityItem icinde kullanicaz
		type = SecuritySchemeType.HTTP, // HTTP authentication
		scheme = "bearer", // Bearer Token
		bearerFormat = "JWT" // Kullandigimiz token formati
)
public class SwaggerConfig {
	/**
	 * OpenAPI tanımını özelleştirir.
	 * Uygulama bilgilerini ve global security yapılandırmasını içerir.
	 */
	@Bean
	public OpenAPI customOpenAPI() {
		return new OpenAPI()
				.info(new Info().title("JWT Auth API").version("1.0"))
				.addSecurityItem(new SecurityRequirement().addList("bearerAuth")) // Tüm endpoint'lere güvenlik şeması uygula
				.components(new Components()
						            .addSecuritySchemes("bearerAuth", new io.swagger.v3.oas.models.security.SecurityScheme()
								            .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP)
								            .scheme("bearer")
								            .bearerFormat("JWT")));
	}
	
	/**
	 * Swagger UI'da görünen API grubunu ayarlar.
	 * Tüm path'ler dahil edilir.
	 */
	@Bean
	public GroupedOpenApi publicApi() {
		return GroupedOpenApi.builder()
		                     .group("v1") // UI'da görünür grup ismi
		                     .pathsToMatch("/**") // Tüm endpointler gösterilsin
		                     .build();
	}
	
}
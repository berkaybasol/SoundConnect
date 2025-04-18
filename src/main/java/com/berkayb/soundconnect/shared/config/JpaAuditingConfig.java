package com.berkayb.soundconnect.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing // → @CreatedDate ve @LastModifiedDate anotasyonlarını aktif eder.
public class JpaAuditingConfig {
}
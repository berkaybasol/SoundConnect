package com.berkayb.soundconnect.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/*
Bu sinif entitylerin otomatik olusturma guncellenme tarihlerini yonetmek icin acildi.
Bu sinif olmadan @CreatedDate, @LastModifiedDate gibi anatasyonlar calismaz.
 */
@Configuration
@EnableJpaAuditing // → @CreatedDate ve @LastModifiedDate anotasyonlarını aktif eder.
public class JpaAuditingConfig {
}
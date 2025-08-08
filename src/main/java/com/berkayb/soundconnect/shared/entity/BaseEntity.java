package com.berkayb.soundconnect.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;
/*
Projedeki tum entitylere ortak kimlik (UUID id),
oluşturulma tarihi (createdAt) ve güncellenme tarihi (updatedAt)
gibi temel alanları ve davranışları tek bir yerden vermek icin actigimiz sinif. DRY prensibi uyguluyoruz.
 */


@SuperBuilder
// Bu sınıf “saf entity” değil, diğer entity’lere üst sınıf olacak demek.
// BaseEntity’nin alanları (id, createdAt, updatedAt) tüm child entity’lere direkt eklenir
@MappedSuperclass

@EntityListeners(AuditingEntityListener.class) // JPA Auditing mekanizmasını aktif eder (yani createdAt/updatedAt otomatik dolacak).
@Data
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseEntity implements Serializable {
	@Id
	@GeneratedValue(generator = "UUID")
	@GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
	@Column(name = "id", updatable = false, nullable = false)
	private UUID id;
	
	@CreatedDate
	@Column(updatable = false)
	protected LocalDateTime createdAt;
	
	@LastModifiedDate
	protected LocalDateTime updatedAt;
}
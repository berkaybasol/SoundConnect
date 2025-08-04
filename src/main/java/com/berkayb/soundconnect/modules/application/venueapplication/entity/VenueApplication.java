package com.berkayb.soundconnect.modules.application.venueapplication.entity;

import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * kullanicinin mekan sahibi olmak icin yaptigi basvurulari tutan entity
 * her basvuru pending, approved veya rejected durumda olabilir
 * admin onaylayana kadar hicbir sekilde kullaniciya venue rolu atanmaz.
 */

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_venue_applications")
public class VenueApplication extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User applicant; // basvuru sahibi
	
	@Column(nullable = false, length = 100)
	private String venueName;
	
	@Column(nullable = false, length = 255)
	private String venueAddress;
	
	@Column(length = 15)
	private String phone;
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ApplicationStatus status;
	
	@Column(nullable = false)
	private LocalDateTime applicationDate;
	
	@Column
	private LocalDateTime decisionDate; // karar tarihi.
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "city_id", nullable = false)
	private City city;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "district_id", nullable = false)
	private District district;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "neighborhood_id")
	private Neighborhood neighborhood;
	
}
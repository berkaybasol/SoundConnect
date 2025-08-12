package com.berkayb.soundconnect.modules.venue.entity;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_venues")
public class Venue extends BaseEntity {
	
	@Column(nullable = false, length = 50)
	private String name;
	
	@Column(nullable = false, length = 255)
	private String address;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "city_id", nullable = false)
	private City city;
	
	@Column(length = 15)
	private String phone;
	
	@Column(length = 255)
	private String website;
	
	@Column(length = 500)
	private String description;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "district_id", nullable = false)
	private District district;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "neighborhood_id", nullable = false)
	private Neighborhood neighborhood;
	
	@Column(name = "music_start_time")
	private String musicStartTime;
	
	
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private VenueStatus status;
	
	
	@Builder.Default
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	@ManyToMany(mappedBy = "activeVenues", fetch = FetchType.LAZY)
	private Set<MusicianProfile> activeMusicians = new HashSet<>();
	
	
	/**
	 * Lombok'un @ToString anotasyonu, tüm alanları otomatik olarak toString metoduna dahil eder.
	 * Bu durum @ManyToOne gibi ilişki alanlarında sonsuz döngü (StackOverflowError) yaratabilir.
	 * Örneğin: Venue -> User -> Venue -> User ... şeklinde birbirini referanslayan yapılar oluşabilir.
	 *
	 * Bu riski önlemek için ilgili ilişki alanlarına @ToString.Exclude eklenir.
	 * Böylece toString çıktısında sadece ID veya null yazılır, ilişkili nesneye erişilmez.
	 */
	@ToString.Exclude
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "owner_id")
	private User owner;
}
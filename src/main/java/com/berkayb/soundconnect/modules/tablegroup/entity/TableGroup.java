package com.berkayb.soundconnect.modules.tablegroup.entity;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_table_group", indexes = {
		@Index(name = "idx_tablegroup_venueid", columnList = "venue_id"),
		@Index(name = "idx_tablegroup_venue_name", columnList = "venue_name"),
		@Index(name = "idx_tablegroup_expires_at", columnList = "expires_at"),
		@Index(name = "idx_tablegroup_status", columnList = "status"),
		@Index(name = "idx_group_city_status_exp", columnList = "city_id,status,expires_at"),
		@Index(name = "idx_group_city_district_status_exp", columnList = "city_id,district_id,status,expires_at"),
		@Index(name = "idx_group_city_district_neighborhood_status_exp", columnList = "city_id,district_id,neighborhood_id,status,expires_at"),
})
public class TableGroup extends BaseEntity {
	
	@Column(name = "owner_id", nullable = false, columnDefinition = "uuid")
	private UUID ownerId;
	
	// Serbest/manuel girilen mekan adi
	@Column(name = "venue_name", length = 128)
	private String venueName;
	
	// Soundconnect'e kayitli venue girmek isterse
	@Column(name = "venue_id", columnDefinition = "uuid")
	private UUID venueId;
	
	@Column(name = "max_person_count", nullable = false)
	private int maxPersonCount;
	
	@Column(name = "gender_prefs")
	private List<String> genderPrefs;
	
	@Column (name = "age_min", nullable = false)
	private int ageMin;
	
	@Column(name = "age_max", nullable = false)
	private int ageMax;
	
	@Column(name = "start_at")
	private LocalDateTime startAt;
	
	@Column(name = "expires_at",nullable = false)
	private LocalDateTime expiresAt;
	
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private TableGroupStatus status;
	
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(
			name = "tbl_table_group_participants",
			joinColumns = @JoinColumn(name = "table_group_id")
	)
	@Builder.Default
	private Set<TableGroupParticipant> participants = new HashSet<>();
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "city_id", nullable = false)
	private City city;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "district_id")
	private District district;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "neighborhood_id")
	private Neighborhood neighborhood;
	
	
}
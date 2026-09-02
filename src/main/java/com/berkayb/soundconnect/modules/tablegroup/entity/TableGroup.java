package com.berkayb.soundconnect.modules.tablegroup.entity;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
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
@AttributeOverride(
		name = "createdAt",
		column = @Column(name = "created_at", nullable = false, updatable = false)
)
@Table(
		name = "tbl_table_group",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_table_group_owner_create_request",
				columnNames = {"owner_id", "create_request_key"}
		),
		indexes = {
		@Index(name = "idx_tablegroup_venueid", columnList = "venue_id"),
		@Index(name = "idx_tablegroup_venue_name", columnList = "venue_name"),
		@Index(name = "idx_tablegroup_expires_at", columnList = "expires_at"),
		@Index(name = "idx_tablegroup_status", columnList = "status"),
		@Index(name = "idx_tablegroup_owner_status_exp_id", columnList = "owner_id,status,expires_at,id"),
		@Index(name = "idx_group_city_status_exp", columnList = "city_id,status,expires_at"),
		@Index(name = "idx_group_city_district_status_exp", columnList = "city_id,district_id,status,expires_at"),
		@Index(name = "idx_group_city_district_neighborhood_status_exp", columnList = "city_id,district_id,neighborhood_id,status,expires_at"),
})
public class TableGroup extends BaseEntity {

	@Version
	@Builder.Default
	@Column(nullable = false)
	private long version = 0;
	
	@Column(name = "owner_id", nullable = false, columnDefinition = "uuid")
	private UUID ownerId;

	@Column(name = "create_request_key", nullable = false, columnDefinition = "uuid")
	private UUID createRequestKey;
	
	// Optional custom name or immutable display snapshot for a registered venue.
	@Column(name = "venue_name", length = 128)
	private String venueName;
	
	// Optional link to a registered venue. Both venue fields may be null.
	@Column(name = "venue_id", columnDefinition = "uuid")
	private UUID venueId;

	/**
	 * Required for every new aggregate. The physical column remains nullable
	 * only so immutable terminal rows from the prelaunch database can be retained;
	 * a NOT VALID database check rejects every new null write.
	 */
	@Column(name = "description", length = 280)
	private String description;
	
	@Column(name = "max_person_count", nullable = false)
	private int maxPersonCount;
	
	
	@ElementCollection(fetch = FetchType.LAZY) //eklendi
	@BatchSize(size = 50)
	@CollectionTable( //eklendi
			name = "tbl_table_group_gender_prefs", //eklendi
			joinColumns = @JoinColumn(name = "table_group_id") //eklendi
	) //eklendi
	@Column(name = "gender_pref", length = 16, nullable = false) //eklendi
	private List<String> genderPrefs; //degisti
	
	
	@Column (name = "age_min", nullable = false)
	private int ageMin;
	
	@Column(name = "age_max", nullable = false)
	private int ageMax;
	
	@Column(name = "start_at", nullable = false)
	private Instant startAt;

	/**
	 * User-selected gathering time. This is display/business metadata and must
	 * never be used as the aggregate lifecycle cutoff.
	 */
	@Column(name = "meeting_at", nullable = false)
	private Instant meetingAt;
	
	@Column(name = "expires_at",nullable = false)
	private Instant expiresAt;
	
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private TableGroupStatus status;
	
	@ElementCollection(fetch = FetchType.LAZY)
	@BatchSize(size = 50)
	@CollectionTable(
			name = "tbl_table_group_participants",
			joinColumns = @JoinColumn(name = "table_group_id"),
			uniqueConstraints = @UniqueConstraint(
					name = "uk_table_group_participant_user",
					columnNames = {"table_group_id", "user_id"}
			)
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

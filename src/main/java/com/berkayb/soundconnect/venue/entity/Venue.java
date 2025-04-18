package com.berkayb.soundconnect.venue.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import com.berkayb.soundconnect.user.entity.User;
import com.berkayb.soundconnect.user.enums.City;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_venues")
public class Venue extends BaseEntity {
	
	@Id
	@GeneratedValue(generator = "UUID")
	@GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
	@Column(name = "id", updatable = false, nullable = false)
	private UUID id;
	
	@Column(nullable = false, length = 50)
	private String name;
	
	@Column(nullable = false, length = 255)
	private String address;
	
	@Enumerated(EnumType.STRING)
	private City city;
	
	@Column(length = 15)
	private String phone;
	
	@Column(length = 255)
	private String website;
	
	@Column(length = 500)
	private String description;
	
	@Column(nullable = false)
	private boolean isApproved = false;
	
	@ToString.Exclude
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "owner_id")
	private User owner;
}
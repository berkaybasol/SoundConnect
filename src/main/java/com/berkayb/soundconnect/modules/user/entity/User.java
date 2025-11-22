package com.berkayb.soundconnect.modules.user.entity;

import com.berkayb.soundconnect.modules.social.follow.entity.Follow;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity.OrganizerProfile;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.entity.ProducerProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import com.berkayb.soundconnect.modules.user.enums.Gender;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;


@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Entity
@Table(name = "tbl_user")
public class User extends BaseEntity {
	
	@Column(name = "user_name", unique = true, nullable = false)
	private String username;
	
	
	@Column(nullable = false)
	private String password;
	
	@Column(unique = true, nullable = false)
	private String email;
	
	private String phone;
	
	private String description;
	
	@Enumerated(EnumType.STRING)
	private Gender gender;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "city_id")
	private City city;
	
	@Enumerated(EnumType.STRING)
	private UserStatus status; // ACTIVE, INACTIVE. PENDING
	
	private String emailVerificationToken;
	
	private LocalDateTime emailVerificationExpiry;
	
	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false)
	@Builder.Default
	private AuthProvider provider = AuthProvider.LOCAL; // kullanicini kayit tipi local veya google default local baslatiyoruz
	
	@Builder.Default
	@Column(name = "email_verified", nullable = false)
	private Boolean emailVerified = false;
	
	@Builder.Default // NullPointer yemeyek diye bos deger atiyo hashsete
	@ManyToMany(fetch = FetchType.EAGER)
	@JoinTable(
			name = "user_roles",
			joinColumns = @JoinColumn(name = "user_id"),
			inverseJoinColumns = @JoinColumn(name = "role_id")
	)
	private Set<Role> roles = new HashSet<>();
	
	@Builder.Default
	@ManyToMany(fetch = FetchType.EAGER)
	@JoinTable(
			name = "user_permissions",
			joinColumns = @JoinColumn(name = "user_id"),
			inverseJoinColumns = @JoinColumn(name = "permission_id")
	)
	private Set<Permission> permissions = new HashSet<>();
	
	@OneToMany(mappedBy = "following", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
	@JsonIgnore
	private Set<Follow> followers = new HashSet<>();
	
	// Takip ettiklerim (benim takip ettiklerim)
	@OneToMany(mappedBy = "follower", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
	@JsonIgnore
	private Set<Follow> following = new HashSet<>();
	
	@OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
	@JsonIgnore
	private MusicianProfile musicianProfile;
	
	@OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
	@JsonIgnore
	private OrganizerProfile organizerProfile;
	
	@OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
	@JsonIgnore
	private ProducerProfile producerProfile;
	
	@OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
	@JsonIgnore
	private StudioProfile studioProfile;
	
	private String profilePicture;
	
	// BASE ENTITYDEN ALIYOR ZATEN
	// private LocalDateTime createdAt;
	
	// private LocalDateTime updatedAt;
}
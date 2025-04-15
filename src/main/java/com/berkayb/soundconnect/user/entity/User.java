package com.berkayb.soundconnect.user.entity;

import com.berkayb.soundconnect.follow.entity.Follow;
import com.berkayb.soundconnect.instrument.entity.Instrument;
import com.berkayb.soundconnect.role.entity.Permission;
import com.berkayb.soundconnect.role.entity.Role;
import com.berkayb.soundconnect.user.enums.City;
import com.berkayb.soundconnect.user.enums.Gender;
import com.berkayb.soundconnect.user.enums.UserStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_user")
public class User {
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	@Column(name = "user_name", unique = true, nullable = false)
	private String username;
	
	
	@Column(nullable = false)
	private String password;
	
	//@Column(unique = true, nullable = false) testte aci cekmek istemiyom :D
	private String email;
	
	// Telefon numarası benzersiz ve zorunlu.
	// @Column(unique = true, nullable = false) testte aci cekmek istemiyom :D
	private String phone;
	
	private String description;
	
	@Enumerated(EnumType.STRING)
	private Gender gender;
	
	@Enumerated(EnumType.STRING)
	private City city;
	
	@Enumerated(EnumType.STRING)
	private UserStatus status;
	
	
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
	
	@OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
	private List<Instrument> instruments;
	
	
	@OneToMany(mappedBy = "following", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
	@JsonIgnore
	private Set<Follow> followers = new HashSet<>();
	
	// Takip ettiklerim (benim takip ettiklerim)
	@OneToMany(mappedBy = "follower", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
	@JsonIgnore
	private Set<Follow> following = new HashSet<>();
	
	private String profilePicture;
	
	private LocalDateTime createdAt;
	
	private LocalDateTime updatedAt;
}
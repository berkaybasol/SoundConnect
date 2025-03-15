package com.berkayb.soundconnect.entity;

import com.berkayb.soundconnect.enums.City;
import com.berkayb.soundconnect.enums.Gender;
import com.berkayb.soundconnect.enums.Role;
import com.berkayb.soundconnect.enums.UserStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
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
	@Column(unique = true, nullable = false)
	private String userName;
	//@Column(nullable = false)  BU SONRA GELCEK SIMDI UGRASTIRMASIN TESTLER
	private String password;
	@Column(unique = true, nullable = false)
	private String email;
	@Column(unique = true, nullable = false)
	private String phone;
	private String description;
	@Enumerated(EnumType.STRING)
	private Gender gender;
	@Enumerated(EnumType.STRING)
	private City city;
	@Enumerated(EnumType.STRING)
	private UserStatus status;
	@Enumerated(EnumType.STRING)
	private Role role;
	@OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
	private List<Instrument> instruments;
	private Integer followers; // BURALAR ENTITY OLACAK
	private Integer following; // BURALAR ENTITY OLACAK
	private String profilePicture;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;
	
	
}
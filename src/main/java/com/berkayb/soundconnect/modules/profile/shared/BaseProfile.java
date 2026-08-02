package com.berkayb.soundconnect.modules.profile.shared;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@MappedSuperclass
public abstract class BaseProfile extends BaseEntity {
@OneToOne
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;
	
	@Column(nullable = true)
	private String name;
	
	@Column(length = 1024)
	private String description;
	
	private UUID profilePictureMediaId;
	
	private String address;
	
	private String phone;
	
	@Column(length = 255)
	private String website;
	
	@Column(length = 255)
	private String youtubeUrl;
	
	@Column(length = 255)
	private String instagramUrl;
}

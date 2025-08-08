package com.berkayb.soundconnect.modules.profile.shared;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

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
	
	private String profilePicture; // url veya dosya yolu
	
	private String address;
	
	private String phone;
	
	private String website;
	
	private String youtubeUrl;
	
	private String instagramUrl;
	
	// TODO ileride, fotograf, ses ve video icin mediya entity ile manytomany iliski kurulcak
}
package com.berkayb.soundconnect.modules.profile.ProducerProfile.entity;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_producer_profile")
public class ProducerProfile extends BaseEntity {
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
	
	private String instagramUrl;
	
	private String youtubeUrl;
	
	// TODO ileride media, comment, notification..
}
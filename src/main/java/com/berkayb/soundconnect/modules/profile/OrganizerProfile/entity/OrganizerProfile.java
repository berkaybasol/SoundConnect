package com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity;

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
@Table(name = "tbl_organizer_profile")
public class OrganizerProfile extends BaseEntity {
	/*
	her profil bir kullaniciya baglidir
	user entity ile birebir iliski,
	bir kullanici yalnizca bir OrganizerProfile sahibi olabilir.
	 */
	@OneToOne
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;
	
	@Column(nullable = true)
	private String name;
	
	@Column(length = 1024)
	private String description;
	
	private String profilePicture;
	
	private String address;
	
	private String phone;
	
	private String instagramUrl;
	
	private String youtubeUrl;
	
	//TODO ileride fotograf ses ve video icin media entity ile ManyToMany iliski kurulcak.
	
}
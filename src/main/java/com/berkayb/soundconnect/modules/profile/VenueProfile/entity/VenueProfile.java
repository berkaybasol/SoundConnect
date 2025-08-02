package com.berkayb.soundconnect.modules.profile.VenueProfile.entity;

import com.berkayb.soundconnect.modules.venue.entity.Venue;
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
@Table(name = "tbl_venue_profile")
public class VenueProfile extends BaseEntity {
	
	// her profil mutlaka bir mekana bagli olur
	// bir mekanin bir tane profil sayfasi olabilir.
	@OneToOne
	@JoinColumn(name = "venue_id", nullable = false, unique = true)
	private Venue venue;
	
	@Column(length = 1024)
	private String bio;
	
	
	private String profilePicture; // dosya yolu verdicez
	
	private String instagramUrl;
	
	private String youtubeUrl;
	
	private String websiteUrl;
	
	/** TODO bu profile ileride comment sistemi eklenecek.
	 *  yorumlar RabbitMQ uzerinden notification event'leri ile birlikte entegre edilecek.
	 *  notification & comment modulleri mvp'den sonra, profil yapisi oturdugunda gelistirilecek.
 	 */
	
	
	
	
}
package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.HashSet;
import java.util.Set;

/**
 * Band Entity.
 * Uygulamada muzisyenlerin olusturdugu ve euye olabildigi gruplari temsil eder
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EqualsAndHashCode(callSuper = true, exclude = {"members"})
@Table(name = "tbl_band", uniqueConstraints = {
		@UniqueConstraint(name = "uk_band_name", columnNames = "name")
}
)
public class Band extends BaseEntity {
	
	@Column(nullable = false, unique = true, length = 100)
	private String name;
	
	@Column(length = 1024)
	private String description;
	
	private String profilePicture;
	
	private String instagramUrl;
	private String youtubeUrl;
	private String soundCloudUrl;
	
	// yonetim tamamen BandMember tarafindan yapilir
	@OneToMany(mappedBy = "band", cascade = CascadeType.ALL, orphanRemoval = true)
	@Builder.Default
	private Set<BandMember> members = new HashSet<>();
	
	//TODO media entegre
}
package com.berkayb.soundconnect.modules.setlistcreator.entity;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Sahne icin olusturulan ana setlist varligi
 * Bu entity sadece ust bilgileri tutar (MusicianProfile veya Band)
 * - setlerin sirasi
 * - performans adi
 * SetlistSet ve SetlistItem domainin ic yapisini olusturur
 */

@Entity
@Table(name = "tbl_setlist")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Setlist extends BaseEntity {
	
	@Column(nullable = false)
	private String name; // Setlist adi. orn 8.12.25 Nil Rock Bar
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "musician_profile_id")
	private MusicianProfile musicianProfile;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "band_id")
	private Band band;
	
	@OneToMany(mappedBy = "setlist", cascade = CascadeType.ALL, orphanRemoval = true)
	@Builder.Default
	private List<SetlistSet> sets = new ArrayList<>(); // setlist icindeki setler. 1.yari 2.yari vs.
	
	// helper methods
	
	// set ekleme
	public void addSet(SetlistSet set) {
		sets.add(set);
		set.setSetlist(this);
	}
	
	// set cikarma
	public void removeSet(SetlistSet set) {
		sets.remove(set);
		set.setSetlist(null);
	}
}
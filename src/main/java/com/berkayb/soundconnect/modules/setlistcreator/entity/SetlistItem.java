package com.berkayb.soundconnect.modules.setlistcreator.entity;

import com.berkayb.soundconnect.modules.setlistcreator.enums.Key;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * bir setlist icindeki tek bir sarkiyi tem sil eder.
 */

@Entity
@Table(name = "tbl_setlist_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SetlistItem extends BaseEntity {

	@Column(nullable = false)
	private String artistName; // sanatci adi
	
	@Column(nullable = false)
	private String songName; // sarki adi
	
	@Column(nullable = false)
	 private Integer orderNumber;
	
	@Enumerated(EnumType.STRING)
	private Key key; // sarki tonu
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "set_id", nullable = false)
	private SetlistSet set;
}
package com.berkayb.soundconnect.modules.setlistcreator.entity;


import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Bir setlist icindeki yarilari temsil eder bolum/set/yari artik adina ne derseler
 * sarkilar SetlistItem ile bu setin icine eklenir
 */

@Entity
@Table( name = "tbl_setlist_set")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SetlistSet extends BaseEntity {
	
	// Set adi
	@Column(nullable = false)
	private String title;
	
	private String duration; // setin suresi ornegin ilk yari 45 dk gibi
	
	@Column(nullable = false)
	private Integer orderNumber; // UI'da setlerin sirasini korumak icin zorunlu alan 1.yari 2. yari gibi
	
	// setin bagli oldugu ana setlist
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "setlist_id", nullable = false)
	private Setlist setlist;
	
	// setin icindeki sarki listesi (siralama drag-drop veya manuel duzenleme ile yapilabilir)
	@OneToMany(mappedBy = "set", cascade = CascadeType.ALL, orphanRemoval = true)
	@Builder.Default
	private List<SetlistItem> items = new ArrayList<>();
	
	// helper methods
	
	// sarki ekle
	public void addItem(SetlistItem item) {
		items.add(item);
		item.setSet(this);
	}
	
	// sarki kaldir
	public void removeItem(SetlistItem item) {
		items.remove(item);
		item.setSet(null);
	}
	
	
}
package com.berkayb.soundconnect.modules.instrument.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_instrument")
public class Instrument extends BaseEntity {
	
	@Column(nullable = false, unique = true)
	private String name;
	
	
}
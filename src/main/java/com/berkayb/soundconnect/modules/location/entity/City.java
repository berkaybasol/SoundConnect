package com.berkayb.soundconnect.modules.location.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_city")
public class City extends BaseEntity {
	
	@Column(nullable = false, unique = true)
	private String name;
	
	@OneToMany(mappedBy = "city", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<District> districts = new ArrayList<>();
	
}
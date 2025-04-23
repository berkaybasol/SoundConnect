package com.berkayb.soundconnect.modules.location.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Data
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_district")
public class District extends BaseEntity {
	
	@Column(nullable = false)
	private String name;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "city_id", nullable = false)
	private City city;
	
	@OneToMany(mappedBy = "district", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Neighborhood> neighborhoods = new ArrayList<>();
	
}
package com.berkayb.soundconnect.modules.role.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@ToString
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_permissions")
public class Permission extends BaseEntity {
	
	@Column(nullable = false, unique = true)
	private String name;
}
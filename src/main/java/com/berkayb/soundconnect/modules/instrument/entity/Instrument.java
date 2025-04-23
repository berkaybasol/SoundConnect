package com.berkayb.soundconnect.modules.instrument.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import com.berkayb.soundconnect.modules.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_instrument")
public class Instrument extends BaseEntity {
	
	@Column(unique = true, nullable = false)
	private String name;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;
	
}
package com.berkayb.soundconnect.modules.social.like.entity;

import com.berkayb.soundconnect.modules.social.comment.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Table(
		name = "tbl_like",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_like_user_target",
						columnNames = {"user_id", "target_type", "target_id"}
				)
		}
)
public class Like extends BaseEntity {
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;
	
	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false)
	EngagementTargetType targetType;
	
	@Column(name = "target_id", nullable = false)
	private UUID targetId;
	
	
}
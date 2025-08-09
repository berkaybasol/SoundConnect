package com.berkayb.soundconnect.modules.follow.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import com.berkayb.soundconnect.modules.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Entity
@Table(
		name = "tbl_follow",
		uniqueConstraints = @UniqueConstraint(columnNames = {"follower_id", "following_id"})
)
public class Follow extends BaseEntity {
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "follower_id")
	private User follower;
	
	@ManyToOne (fetch = FetchType.LAZY)
	@JoinColumn(name = "following_id")
	private User following;
	
	private LocalDateTime followedAt;
	
	//TODO notficiation ve comment modulu hazir olunca..
	
	
	
}
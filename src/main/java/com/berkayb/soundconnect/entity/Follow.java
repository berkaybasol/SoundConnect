package com.berkayb.soundconnect.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
		name = "tbl_follow",
		uniqueConstraints = @UniqueConstraint(columnNames = {"follower_id", "following_id"})
)
public class Follow {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "follower_id")
	private User follower;
	
	@ManyToOne (fetch = FetchType.LAZY)
	@JoinColumn(name = "following_id")
	private User following;
	
	private LocalDateTime followedAt;
	
}
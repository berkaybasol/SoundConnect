package com.berkayb.soundconnect.modules.tablegroup.game.entity;

import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGamePlayerStatus;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_table_group_game_player", uniqueConstraints = {
		@UniqueConstraint(name = "uk_tg_game_player_user", columnNames = {"game_id", "user_id"})
}, indexes = {
		@Index(name = "idx_tg_game_player_status", columnList = "game_id,status")
})
public class TableGroupGamePlayer extends BaseEntity {

	@Column(name = "game_id", nullable = false, updatable = false, columnDefinition = "uuid")
	private UUID gameId;

	@Column(name = "user_id", nullable = false, updatable = false, columnDefinition = "uuid")
	private UUID userId;

	@Column(name = "username", nullable = false, updatable = false, length = 30)
	private String username;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 24)
	private TableGroupGamePlayerStatus status;

	@Column(name = "joined_at", nullable = false, updatable = false)
	private Instant joinedAt;
}

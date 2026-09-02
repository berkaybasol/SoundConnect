package com.berkayb.soundconnect.modules.tablegroup.game.entity;

import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGameActionType;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGamePhase;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_table_group_game_action", uniqueConstraints = {
		@UniqueConstraint(
				name = "uk_tg_game_round_actor",
				columnNames = {"game_id", "round_number", "actor_user_id"}
		),
		@UniqueConstraint(
				name = "uk_tg_game_action_request",
				columnNames = {"game_id", "actor_user_id", "request_id"}
		)
}, indexes = {
		@Index(name = "idx_tg_game_action_round", columnList = "game_id,round_number"),
		@Index(name = "idx_tg_game_action_revealed", columnList = "game_id,revealed,round_number")
})
public class TableGroupGameAction extends BaseEntity {

	@Column(name = "game_id", nullable = false, updatable = false, columnDefinition = "uuid")
	private UUID gameId;

	@Column(name = "request_id", nullable = false, updatable = false, columnDefinition = "uuid")
	private UUID requestId;

	@Column(name = "round_number", nullable = false, updatable = false)
	private int roundNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "phase", nullable = false, updatable = false, length = 24)
	private TableGroupGamePhase phase;

	@Column(name = "actor_user_id", nullable = false, updatable = false, columnDefinition = "uuid")
	private UUID actorUserId;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false, updatable = false, length = 24)
	private TableGroupGameActionType action;

	@Column(name = "target_user_id", updatable = false, columnDefinition = "uuid")
	private UUID targetUserId;

	@Column(name = "value", updatable = false)
	private Integer value;

	@Column(name = "revealed", nullable = false)
	private boolean revealed;
}

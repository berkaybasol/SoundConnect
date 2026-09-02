package com.berkayb.soundconnect.modules.tablegroup.game.entity;

import com.berkayb.soundconnect.modules.tablegroup.game.enums.*;
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
@Table(name = "tbl_table_group_game", uniqueConstraints = {
		@UniqueConstraint(
				name = "uk_tg_game_create_request",
				columnNames = {"table_group_id", "created_by", "create_request_id"}
		)
}, indexes = {
		@Index(name = "idx_tg_game_table_status", columnList = "table_group_id,status"),
		@Index(
				name = "idx_tg_game_due",
				columnList = "status,join_deadline_at,action_deadline_at,id"
		)
})
public class TableGroupGame extends BaseEntity {

	@Version
	@Builder.Default
	@Column(name = "version", nullable = false)
	private long version = 0;

	@Builder.Default
	@Column(name = "revision", nullable = false)
	private long revision = 1;

	@Column(name = "table_group_id", nullable = false, updatable = false, columnDefinition = "uuid")
	private UUID tableGroupId;

	@Column(name = "created_by", nullable = false, updatable = false, columnDefinition = "uuid")
	private UUID createdBy;

	@Column(name = "created_by_username", nullable = false, updatable = false, length = 30)
	private String createdByUsername;

	@Column(name = "create_request_id", nullable = false, updatable = false, columnDefinition = "uuid")
	private UUID createRequestId;

	@Enumerated(EnumType.STRING)
	@Column(name = "topic", nullable = false, updatable = false, length = 32)
	private TableGroupGameTopic topic;

	@Enumerated(EnumType.STRING)
	@Column(name = "mode", nullable = false, updatable = false, length = 32)
	private TableGroupGameMode mode;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 24)
	private TableGroupGameStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "phase", nullable = false, length = 24)
	private TableGroupGamePhase phase;

	@Column(name = "round_number", nullable = false)
	private int roundNumber;

	@Column(name = "join_deadline_at")
	private Instant joinDeadlineAt;

	@Column(name = "action_deadline_at")
	private Instant actionDeadlineAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "selected_user_id", columnDefinition = "uuid")
	private UUID selectedUserId;

	@Column(name = "selected_username", length = 30)
	private String selectedUsername;

	@Enumerated(EnumType.STRING)
	@Column(name = "outcome", length = 24)
	private TableGroupGameOutcome outcome;

	@Column(name = "result_message", length = 500)
	private String resultMessage;

	@Column(name = "cancellation_reason", length = 64)
	private String cancellationReason;
}

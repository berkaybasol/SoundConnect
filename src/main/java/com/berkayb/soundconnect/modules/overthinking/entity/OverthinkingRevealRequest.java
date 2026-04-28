package com.berkayb.soundconnect.modules.overthinking.entity;

import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingRevealRequestStatus;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Table(
		name = "tbl_overthinking_reveal_request",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_overthinking_reveal_post_requester",
						columnNames = {"post_id", "requester_id"}
				)
		},
		indexes = {
				@Index(name = "idx_overthinking_reveal_author_status", columnList = "author_id, status"),
				@Index(name = "idx_overthinking_reveal_requester_status", columnList = "requester_id, status"),
				@Index(name = "idx_overthinking_reveal_post_status", columnList = "post_id, status")
		}
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class OverthinkingRevealRequest extends BaseEntity {
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false)
	private OverthinkingPost post;
	// yazain hangi postu icin gorunurluk istegi atildigini tutar
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "requester_id", nullable = false)
	private User requester;
	// yazari gormek isteyen kullanicidir.
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private User author;
	// postun gercek sahibidir. sorgu ve bildirim islemlerini kolaylastirmak icin ayrica tutulur.
	
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	@Builder.Default
	private OverthinkingRevealRequestStatus status = OverthinkingRevealRequestStatus.PENDING;
	// istegin beklemede, kabul veya reddedilmis Oldugunu belirtir
	
	public boolean isPending() {
		return status == OverthinkingRevealRequestStatus.PENDING;
	}
	
	public boolean isApproved() {
		return status == OverthinkingRevealRequestStatus.APPROVED;
	}
	
	public boolean isRejected() {
		return status == OverthinkingRevealRequestStatus.REJECTED;
	}
	
	public void approve() {
		this.status = OverthinkingRevealRequestStatus.APPROVED;
	}
	
	public void reject() {
		this.status = OverthinkingRevealRequestStatus.REJECTED;
	}
}
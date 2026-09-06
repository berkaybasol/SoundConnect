package com.berkayb.soundconnect.modules.event.performer.entity;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestStatus;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestPurpose;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
		name = "event_performer_requests",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_event_performer_request_event",
				columnNames = "event_id"
		),
		indexes = {
				@Index(name = "idx_event_performer_request_musician_status", columnList = "musician_profile_id,status,created_at"),
				@Index(name = "idx_event_performer_request_band_status", columnList = "band_id,status,created_at")
		}
)
@Check(name = "ck_event_performer_request_contract", constraints = """
		((musician_profile_id is not null and band_id is null)
		 or (musician_profile_id is null and band_id is not null))
		and trim(performer_name_snapshot) <> ''
		and ((status = 'PENDING' and decided_by_user_id is null and decided_at is null)
		     or (status in ('ACCEPTED', 'REJECTED') and decided_by_user_id is not null and decided_at is not null))
		and version >= 0
		and request_purpose in ('PERFORMER_CONSENT', 'PROFILE_VISIBILITY')
		""")
public class EventPerformerRequest extends BaseEntity {
	public static final int PERFORMER_NAME_SNAPSHOT_MAX_LENGTH = 100;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_id", nullable = false, updatable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Event event;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "musician_profile_id", updatable = false)
	private MusicianProfile musicianProfile;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "band_id", updatable = false)
	private Band band;

	@Column(
			name = "performer_name_snapshot",
			nullable = false,
			length = PERFORMER_NAME_SNAPSHOT_MAX_LENGTH,
			updatable = false
	)
	private String performerNameSnapshot;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private EventPerformerRequestStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "request_purpose", nullable = false, length = 30, updatable = false)
	@Builder.Default
	private EventPerformerRequestPurpose requestPurpose = EventPerformerRequestPurpose.PERFORMER_CONSENT;

	@Column(name = "requested_by_user_id", nullable = false, updatable = false)
	private UUID requestedByUserId;

	@Column(name = "decided_by_user_id")
	private UUID decidedByUserId;

	@Column(name = "decided_at")
	private LocalDateTime decidedAt;

	/** Immutable decision snapshot. Later profile show/hide must not change retry semantics. */
	@Column(name = "accepted_profile_publication")
	private Boolean acceptedProfilePublication;

	@Version
	@Column(nullable = false)
	private long version;

	public boolean targetsBand() {
		return band != null;
	}
}

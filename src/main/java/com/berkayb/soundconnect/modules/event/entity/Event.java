package com.berkayb.soundconnect.modules.event.entity;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.event.enums.EventVenueApprovalStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_event")
@Check(name = "ck_event_profile_publication_version", constraints = "profile_publication_version >= 0")
@Check(name = "ck_event_origin_contract", constraints = """
        (event_origin = 'VENUE' and venue_id is not null and venue_approval_status = 'APPROVED' and venue_calendar_approved)
        or (event_origin = 'MUSICIAN' and musician_profile_id is not null and band_id is null
            and performer_approval_status = 'APPROVED' and profile_calendar_approved)
        """)
@Check(name = "ck_event_venue_consent", constraints = """
        (not venue_calendar_approved or (venue_approval_status = 'APPROVED' and venue_id is not null))
        and ((venue_approval_status = 'APPROVED' and venue_id is not null)
          or (venue_approval_status = 'NOT_REQUIRED' and venue_id is null and venue_name_snapshot is null)
          or (venue_approval_status in ('PENDING', 'REJECTED') and venue_id is null
              and venue_name_snapshot is not null and trim(venue_name_snapshot) <> ''))
        """)
@Check(name = "ck_event_profile_calendar_consent", constraints = "not profile_calendar_approved or performer_approval_status = 'APPROVED'")
@Check(name = "ck_event_performer_consent_link", constraints = """
		(performer_approval_status = 'APPROVED'
		 and ((musician_profile_id is not null and band_id is null)
		      or (musician_profile_id is null and band_id is not null))
		 and manual_performer_name is null)
		or (performer_approval_status = 'NOT_REQUIRED'
		    and musician_profile_id is null and band_id is null)
		or (performer_approval_status in ('PENDING', 'REJECTED')
		    and musician_profile_id is null and band_id is null
		    and manual_performer_name is not null
		    and trim(manual_performer_name) <> '')
		""")
public class Event extends BaseEntity {

	@Column(nullable = false)
	private String title;

	@Column(length = 500)
	private String description;

	@Column(nullable = false)
	private LocalDate eventDate;

	@Column(nullable = false)
	private LocalTime startTime;

	private LocalTime endTime;

	private String posterImage; // MediaAsset UUID

	/**
	 * Etkinliğin gerçekleştiği mekan.
	 * Venue zaten city/district/neighborhood ilişkilerini içeriyor.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "venue_id")
	private Venue venue;

	@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
	@Column(name = "event_origin", nullable = false, length = 20, updatable = false)
	@Builder.Default
	private EventOrigin eventOrigin = EventOrigin.VENUE;

	/** Immutable authority: accepting a counterpart request never transfers ownership. */
	@Column(name = "organizer_user_id", nullable = false, updatable = false)
	private UUID organizerUserId;

	@Column(name = "venue_name_snapshot", length = 255)
	private String venueNameSnapshot;

	@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
	@Column(name = "venue_approval_status", nullable = false, length = 20)
	@Builder.Default
	private EventVenueApprovalStatus venueApprovalStatus = EventVenueApprovalStatus.APPROVED;

	@Column(name = "venue_calendar_approved", nullable = false)
	@Builder.Default
	private boolean venueCalendarApproved = true;

	@jakarta.persistence.PrePersist
	void initializeLegacyOrganizer() {
		if (organizerUserId == null && eventOrigin == EventOrigin.VENUE && venue != null && venue.getOwner() != null) {
			organizerUserId = venue.getOwner().getId();
		}
	}

	/**
	 * Public performer link. Exactly one target is present only after consent is
	 * established (an active venue connection at creation time or an accepted
	 * event-scoped request). Pending/rejected/manual performers deliberately keep
	 * both relations null and expose only {@link #manualPerformerName}.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "musician_profile_id")
	private MusicianProfile musicianProfile;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "band_id")
	private Band band;

	@Column(length = 120)
	private String manualPerformerName;

	@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
	@Column(name = "performer_approval_status", nullable = false, length = 20)
	@Builder.Default
	private EventPerformerApprovalStatus performerApprovalStatus = EventPerformerApprovalStatus.NOT_REQUIRED;

	/** Explicit per-event permission; an active venue connection never grants this. */
	@Column(name = "profile_calendar_approved", nullable = false)
	@Builder.Default
	private boolean profileCalendarApproved = false;

	/** Version of the mutable direct/group profile publication, not participation consent. */
	@Column(name = "profile_publication_version", nullable = false)
	@Builder.Default
	private long profilePublicationVersion = 0;
}

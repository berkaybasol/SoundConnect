package com.berkayb.soundconnect.modules.feed.musician.preference.entity;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

/**
 * Private discovery preferences for the musician feed.
 *
 * <p>The opportunity city is deliberately not stored on {@link MusicianProfile}
 * or the Location aggregate: it means "where I want to see opportunities", not
 * residence, public address, or a venue location. The parent profile row is the
 * concurrency fence for this one-to-one aggregate.</p>
 */
@Entity
@Table(name = "tbl_musician_feed_preferences")
@Check(name = "ck_musician_feed_preferences_version",
		constraints = "version >= 0 and version < 9223372036854775807")
@Getter
@Setter
@NoArgsConstructor
public class MusicianFeedPreferences {

	@Id
	@Column(name = "musician_profile_id", nullable = false, updatable = false)
	private UUID musicianProfileId;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "musician_profile_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_musician_feed_preferences_profile"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private MusicianProfile profile;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "opportunity_city_id",
			foreignKey = @ForeignKey(name = "fk_musician_feed_preferences_city"))
	private City opportunityCity;

	/** Client-visible revision, advanced only when the opportunity city changes. */
	@Column(nullable = false)
	private long version;

	public MusicianFeedPreferences(UUID musicianProfileId) {
		this.musicianProfileId = musicianProfileId;
	}
}

package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

/** Isolated from general profile edits, which must never overwrite this preference. */
@Entity
@Table(name = "tbl_musician_calendar_settings")
@Check(name = "ck_musician_calendar_version", constraints = "version >= 0")
@Getter
@Setter
@NoArgsConstructor
public class MusicianCalendarSettings {

	@Id
	@Column(name = "musician_profile_id", nullable = false, updatable = false)
	private UUID musicianProfileId;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "musician_profile_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_musician_calendar_profile"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private MusicianProfile profile;

	@Column(nullable = false)
	private boolean visible;

	/** Incremented under the parent musician row lock, independently of other profile edits. */
	@Column(nullable = false)
	private long version;

	public MusicianCalendarSettings(UUID musicianProfileId) {
		this.musicianProfileId = musicianProfileId;
	}
}

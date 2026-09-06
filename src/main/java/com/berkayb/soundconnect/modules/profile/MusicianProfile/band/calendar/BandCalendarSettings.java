package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

@Entity
@Table(name = "tbl_band_calendar_settings")
@Check(name = "ck_band_calendar_version", constraints = "version >= 0")
@Getter @Setter @NoArgsConstructor
public class BandCalendarSettings {
	@Id
	@Column(name = "band_id", nullable = false, updatable = false)
	private UUID bandId;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "band_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_band_calendar_band"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Band band;

	@Column(nullable = false)
	private boolean visible;

	/** Advanced under the band parent lock, never by general band edits. */
	@Column(nullable = false)
	private long version;

	public BandCalendarSettings(UUID bandId) { this.bandId = bandId; }
}

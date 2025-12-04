package com.berkayb.soundconnect.modules.overthinking.entity;

import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingArtistType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Overthinking modulundeki tekil bir postu temsil eder.
 * kullanici bir baslik ve metin yazar opsiyonel olarak bir muzik ile eslestirir.
 * attachedArtist: Secilen muzigin sahibi soundconnect'te musicianprofile ise otomatik eslestirilip buraya setlenir
 */

@Entity
@Table(name = "tbl_overthinking_post")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class OverthinkingPost extends BaseEntity {
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private User author; // post sahibi
	
	@Column(name = "title", length = 64, nullable = false)
	private String title;
	
	@Column(name = "content", length = 10240, nullable = false)
	private String content;
	
	@Column(name = "spotify_track_url", length = 1024)
	private String spotifyTrackUrl; // spotify'dan eslestirilecek muzigin track urlsi
	
	@Column(name = "spotify_artist_id", length = 255)
	private String spotifyArtistId; // MusicianProfile veya Band entitylerindeki spotifyArtistId ile eslestirme icin kullanilcak
	
	@Column(name = "musician_track_id")
	private UUID musicianTrackId; // spotiden degil de uygulama icinden secerse
	
	@Column(name = "band_track_id")
	private UUID bandTrackId; // sarki bande aitse
	
	@Column(name = "artist_id")
	private UUID artistId; // post ile eslestirilen sanatci is si band veya musicianprofile
	
	@Enumerated(EnumType.STRING)
	@Column(name = "artist_type", length = 32)
	private OverthinkingArtistType artistType;
	
	// helpers
	// posta herhangi bir muzik baglanmis mi?
	public boolean hasMusic() {
		return spotifyTrackUrl != null
				|| musicianTrackId != null
				|| bandTrackId != null;
	}
	
	// post sanatciyla eslesmis mi?
	public boolean hasAttachedArtist() {
		return artistId != null && artistType != null;
	}
	
	
	
}
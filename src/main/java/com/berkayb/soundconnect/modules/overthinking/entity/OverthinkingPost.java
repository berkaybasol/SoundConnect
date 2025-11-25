package com.berkayb.soundconnect.modules.overthinking.entity;

import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingMusicType;
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
	
	@Column(name = "content", length = 4000, nullable = false)
	private String content;
	
	@Enumerated(EnumType.STRING)
	@Column(name = "music_type", nullable = false, length = 32)
	private OverthinkingMusicType musicType; // postta eslestirilecek muzik uygulamadan mi spotiden mi
	
	@Column(name = "spotify_track_id", length = 128)
	private String spotifyTrackId; // spotify'dan eslestirilecek muzigin track idsi
	
	@Column(name = "internal_audio_id")
	private UUID internalAudioId; // soundconnect'dan eslestirilecek muzigin idsi
	
	/**
	 * Secilen muzigin sahibi SoundConnect'te MusicianProfile ise otomatik eslestirilip buraya setlenir.
	 * ORN: - Spotify track -> artistIds -> MusicianProfile.spotifyArtistId
	 *      - Internal auidio -> audio.owner (MusicianProfile)
	 *      - eslesme yoksa null.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "attached_musician_profile_id")
	private MusicianProfile attachedArtist;
	BURDASIN
	@Column(name = "deleted", nullable = false)
	private boolean deleted; // soft delete flag
	
	
}
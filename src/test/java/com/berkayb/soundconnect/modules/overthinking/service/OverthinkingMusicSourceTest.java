package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.comment.repository.CommentTargetAccessRepository;
import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.*;
import com.berkayb.soundconnect.modules.overthinking.support.OverthinkingSpotifyReference;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.spotify.client.SpotifyApiClient;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import com.berkayb.soundconnect.shared.exception.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OverthinkingMusicSourceTest {
    private static final String TRACK = "4uLU6hMCjMI75M1A2tKUQC";
    private static final String ARTIST = "0TnOYISbd1XYRBk9myaseg";
    private static final String URL = "https://open.spotify.com/track/" + TRACK;
    @Mock MusicianProfileRepository musicians;
    @Mock BandRepository bands;
    @Mock TrackRepository tracks;
    @Mock CommentTargetAccessRepository media;
    @Mock SpotifyApiClient spotify;
    @InjectMocks OverthinkingArtistResolverServiceImpl resolver;

    @ParameterizedTest
    @ValueSource(strings = {"http://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",
            "https://attacker.invalid/track/4uLU6hMCjMI75M1A2tKUQC",
            "https://open.spotify.com.attacker.invalid/track/4uLU6hMCjMI75M1A2tKUQC",
            "https://user@open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",
            "https://open.spotify.com:444/track/4uLU6hMCjMI75M1A2tKUQC",
            "https://open.spotify.com/playlist/4uLU6hMCjMI75M1A2tKUQC",
            "https://open.spotify.com/track/short", "4uLU6hMCjMI75M1A2tKUQC",
            "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
            "https://open.spotify.com/track/%34uLU6hMCjMI75M1A2tKUQC",
            "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC/extra"})
    void rejectsUnsupportedOrAmbiguousTrackSources(String url) {
        assertInvalid(() -> resolver.resolveAndSetArtist(new OverthinkingPost(), dto(url, null, null, null, null)));
        assertThat(OverthinkingSpotifyReference.parseOrNull(url)).isNull();
        verifyNoInteractions(spotify, musicians, bands, tracks, media);
    }

    @Test void canonicalizesSharedTrackLinksAndClearsBlankMusicState() {
        var post = new OverthinkingPost();
        resolver.resolveAndSetArtist(post, dto(" HTTPS://OPEN.SPOTIFY.COM:443/intl-tr/track/" + TRACK + "/?si=share#fragment ",
                " ", " Song ", " Artist ", " https://i.scdn.co/image/art "));
        assertThat(post.getSpotifyTrackUrl()).isEqualTo(URL);
        assertThat(post.getSpotifyTrackName()).isEqualTo("Song");
        assertThat(post.getSpotifyAlbumImageUrl()).isEqualTo("https://i.scdn.co/image/art");
        assertThat(post.getSpotifyArtistId()).isNull();
        resolver.resolveAndSetArtist(post, dto(" ", " ", " ", " ", " "));
        assertThat(post.hasMusic()).isFalse();
        assertThat(post.getSpotifyTrackName()).isNull();
        assertThat(post.getSpotifyArtistName()).isNull();
        assertThat(post.getSpotifyAlbumImageUrl()).isNull();
        assertThat(post.hasAttachedArtist()).isFalse();
        verifyNoInteractions(spotify, musicians, bands);
    }

    @Test void metadataRequiresASpotifySourceAndExclusiveSourceChoice() {
        for (var request : List.of(dto(null, ARTIST, null, null, null), dto(null, null, "Song", null, null),
                dto(null, null, null, "Artist", null), dto(null, null, null, null, "https://i.scdn.co/image/art"))) {
            assertInvalid(() -> resolver.resolveAndSetArtist(new OverthinkingPost(), request));
        }
        var multiple = new OverthinkingPostSaveRequestDto("Title", "Body", OverthinkingVisibilityType.VISIBLE,
                URL, null, null, null, null, UUID.randomUUID(), null);
        assertThatThrownBy(() -> resolver.resolveAndSetArtist(new OverthinkingPost(), multiple))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.OVERTHINKING_MULTIPLE_MUSIC_SOURCE));
        assertInvalid(() -> resolver.resolveAndSetArtist(new OverthinkingPost(), dto(URL, "artist-invalid", null, null, null)));
        assertInvalid(() -> resolver.resolveAndSetArtist(new OverthinkingPost(), dto(URL, null, null, null, "javascript:alert(1)")));
        assertInvalid(() -> resolver.resolveAndSetArtist(new OverthinkingPost(), dto(URL, null, "x".repeat(513), null, null)));
    }

    @Test void onlyAuthoritativeMatchingTrackAndArtistCanBindAMusicianProfile() {
        UUID profileId = UUID.randomUUID();
        when(musicians.findBySpotifyArtistId(ARTIST)).thenReturn(Optional.of(MusicianProfile.builder().id(profileId).build()));
        var post = new OverthinkingPost();
        resolver.resolveAndSetArtist(post, dto(URL, ARTIST, "Hint", null, null), snapshot(TRACK, List.of(ARTIST)));
        assertThat(post.getArtistId()).isEqualTo(profileId);
        assertThat(post.getArtistType()).isEqualTo(OverthinkingArtistType.MUSICIAN_PROFILE);
        verifyNoInteractions(bands);
    }

    @Test void verifiedSpotifyArtistCanBindABand() {
        UUID bandId = UUID.randomUUID();
        when(musicians.findBySpotifyArtistId(ARTIST)).thenReturn(Optional.empty());
        when(bands.findBySpotifyArtistId(ARTIST)).thenReturn(Optional.of(Band.builder().id(bandId).build()));
        var post = new OverthinkingPost();
        resolver.resolveAndSetArtist(post, dto(URL, ARTIST, null, null, null), snapshot(TRACK, List.of(ARTIST)));
        assertThat(post.getArtistId()).isEqualTo(bandId);
        assertThat(post.getArtistType()).isEqualTo(OverthinkingArtistType.BAND);
    }

    @Test void clientArtistCannotSpoofAttributionEvenWithARealButDifferentTrackSnapshot() {
        for (var snapshot : List.of(snapshot(TRACK, List.of("1dfeR4HaWDbWqFHLkxsg1d")),
                snapshot("1dfeR4HaWDbWqFHLkxsg1d", List.of(ARTIST)), snapshot(TRACK, List.of()))) {
            var post = OverthinkingPost.builder().artistId(UUID.randomUUID()).artistType(OverthinkingArtistType.BAND).build();
            resolver.resolveAndSetArtist(post, dto(URL, ARTIST, null, null, null), snapshot);
            assertThat(post.getArtistId()).isNull();
            assertThat(post.getArtistType()).isNull();
        }
        verifyNoInteractions(musicians, bands);
    }

    @Test void providerOutageKeepsLinkAndDisplayHintsButNeverTrustsClientAttribution() {
        var post = new OverthinkingPost();
        resolver.resolveAndSetArtist(post, dto(URL, ARTIST, "Offline title", "Offline artist", null));
        assertThat(post.getSpotifyTrackUrl()).isEqualTo(URL);
        assertThat(post.getSpotifyTrackName()).isEqualTo("Offline title");
        assertThat(post.getSpotifyArtistId()).isEqualTo(ARTIST);
        assertThat(post.getArtistId()).isNull();
        assertThat(post.getArtistType()).isNull();
        verifyNoInteractions(musicians, bands);
    }

    @Test void dtoRejectsEverySpotifyFieldBeforeItExceedsStorageCapacity() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var invalid = dto("u".repeat(1025), "i".repeat(256), "n".repeat(513), "a".repeat(513), "i".repeat(1025));
            assertThat(factory.getValidator().validate(invalid)).extracting(v -> v.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("spotifyTrackUrl", "spotifyArtistId", "spotifyTrackName", "spotifyArtistName", "spotifyAlbumImageUrl");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://tracker.example/pixel", "https://i.scdn.co.attacker.test/image/abc",
            "https://attacker@i.scdn.co/image/abc", "http://i.scdn.co/image/abc", "https://i.scdn.co:444/image/abc",
            "https://i.scdn.co/image/abc?redirect=https://attacker.test", "https://i.scdn.co/image/abc#fragment",
            "https://i.scdn.co/image/%61bc", "https://i.scdn.co/image/", "https://127.0.0.1/image/abc"})
    void rejectsUntrustedArtworkOnWritesAndSanitizesItForLegacyReads(String artwork) {
        assertInvalid(() -> resolver.resolveAndSetArtist(new OverthinkingPost(), dto(URL,null,"Song","Artist",artwork)));
        assertThat(OverthinkingSpotifyReference.imageUrlOrNull(artwork)).isNull();
        verifyNoInteractions(spotify,musicians,bands);
    }

    private OverthinkingPostSaveRequestDto dto(String url, String artistId, String name, String artistName, String image) {
        return new OverthinkingPostSaveRequestDto("Title", "Body", OverthinkingVisibilityType.VISIBLE,
                url, artistId, name, artistName, image, null, null);
    }
    private SpotifyTrackItemDto snapshot(String trackId, List<String> artistIds) {
        return new SpotifyTrackItemDto(trackId, "Canonical song", 100, false, null, URL, null, null,
                List.of("Canonical artist"), artistIds);
    }
    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.OVERTHINKING_SPOTIFY_SOURCE_INVALID));
    }
}

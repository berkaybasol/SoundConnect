package com.berkayb.soundconnect.modules.overthinking.mapper;

import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class OverthinkingPostMapperMusicTest {
    private final OverthinkingPostMapper mapper = Mappers.getMapper(OverthinkingPostMapper.class);

    @Test void canonicalMetadataTakesPrecedenceOverClientHintsWhenAvailable() {
        var post = post();
        var canonical = new SpotifyTrackItemDto("4uLU6hMCjMI75M1A2tKUQC", "Real song", 100, false, null,
                post.getSpotifyTrackUrl(), null, "https://i.scdn.co/image/canonical", List.of("Real artist"));
        var result = mapper.toDto(post, false, 0, 0, false, canonical);
        assertThat(result.spotifyTrackName()).isEqualTo("Real song");
        assertThat(result.spotifyArtistName()).isEqualTo("Real artist");
        assertThat(result.spotifyAlbumImageUrl()).isEqualTo("https://i.scdn.co/image/canonical");
    }

    @Test void metadataHintsRemainReadableWhenProviderIsUnavailable() {
        var result = mapper.toDto(post(), false, 0, 0, false, null);
        assertThat(result.spotifyTrackName()).isEqualTo("Hint song");
        assertThat(result.spotifyArtistName()).isEqualTo("Hint artist");
        assertThat(result.spotifyAlbumImageUrl()).isEqualTo("https://i.scdn.co/image/hint");
    }

    private OverthinkingPost post() {
        return OverthinkingPost.builder().title("Title").content("Body")
                .spotifyTrackUrl("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")
                .spotifyTrackName("Hint song").spotifyArtistName("Hint artist")
                .spotifyAlbumImageUrl("https://i.scdn.co/image/hint").build();
    }
}

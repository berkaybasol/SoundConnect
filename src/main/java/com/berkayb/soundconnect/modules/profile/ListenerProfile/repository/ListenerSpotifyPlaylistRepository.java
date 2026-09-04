package com.berkayb.soundconnect.modules.profile.ListenerProfile.repository;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerSpotifyPlaylist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ListenerSpotifyPlaylistRepository extends JpaRepository<ListenerSpotifyPlaylist, UUID> {

	List<ListenerSpotifyPlaylist> findAllByListenerProfileIdOrderByPositionAsc(UUID listenerProfileId);

	@Query("""
			select playlist.spotifyPlaylistId
			from ListenerSpotifyPlaylist playlist
			where playlist.listenerProfile.id = :listenerProfileId
			order by playlist.position
			""")
	List<String> findSpotifyPlaylistIdsByListenerProfileId(
			@Param("listenerProfileId") UUID listenerProfileId
	);

	@Modifying
	@Query("""
			delete from ListenerSpotifyPlaylist playlist
			where playlist.listenerProfile.id = :listenerProfileId
			""")
	int deleteAllByListenerProfileId(@Param("listenerProfileId") UUID listenerProfileId);
}

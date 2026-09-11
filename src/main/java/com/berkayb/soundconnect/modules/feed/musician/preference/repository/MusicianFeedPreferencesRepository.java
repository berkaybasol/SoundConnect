package com.berkayb.soundconnect.modules.feed.musician.preference.repository;

import com.berkayb.soundconnect.modules.feed.musician.preference.entity.MusicianFeedPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MusicianFeedPreferencesRepository extends JpaRepository<MusicianFeedPreferences, UUID> {
	@Query("""
			select preferences from MusicianFeedPreferences preferences
			left join fetch preferences.opportunityCity
			where preferences.musicianProfileId = :profileId
			""")
	Optional<MusicianFeedPreferences> findWithOpportunityCity(@Param("profileId") UUID profileId);
}

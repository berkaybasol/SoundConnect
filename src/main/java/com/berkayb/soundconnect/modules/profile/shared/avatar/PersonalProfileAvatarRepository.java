package com.berkayb.soundconnect.modules.profile.shared.avatar;

import com.berkayb.soundconnect.modules.user.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Fetches all supported personal-profile avatar candidates in one query.
 * Deliberately excludes band, venue, and studio relationships.
 */
public interface PersonalProfileAvatarRepository extends Repository<User, UUID> {

	@Query("""
			select new com.berkayb.soundconnect.modules.profile.shared.avatar.PersonalProfileAvatarCandidate(
				user.id,
				musician.profilePictureMediaId,
				listener.profilePictureMediaId,
				organizer.profilePictureMediaId,
				producer.profilePictureMediaId,
				user.username,
				listener.visibilityMode
			)
			from User user
			left join user.musicianProfile musician
			left join ListenerProfile listener on listener.user = user
			left join user.organizerProfile organizer
			left join user.producerProfile producer
			where user.id in :userIds
			order by user.id
			""")
	List<PersonalProfileAvatarCandidate> findCandidatesByUserIdIn(
			@Param("userIds") Collection<UUID> userIds
	);
}

package com.berkayb.soundconnect.tools.simulation.seed.content.social;

import com.berkayb.soundconnect.modules.event.audience.EventAudienceService;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.overthinking.profileshare.OverthinkingProfileShareRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.profileshare.TableGroupProfileShareRepository;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Verification seam used before accepting persisted social identities.
 *
 * <p>Some production read services deliberately acquire PostgreSQL shared locks
 * so listener visibility cannot change while a public projection is rendered.
 * PostgreSQL rejects {@code FOR SHARE} in a read-only transaction, therefore
 * every verification entry point keeps a regular transaction boundary.</p>
 */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@Transactional(timeout = 10)
public class SimulationSocialContentVerifier {

	private static final Duration TABLE_LIFETIME = Duration.ofHours(24);

	private final OverthinkingPostRepository overthinkingPosts;
	private final OverthinkingProfileShareRepository overthinkingShares;
	private final TableGroupService tableGroups;
	private final TableGroupProfileShareRepository tableGroupShares;
	private final EventAudienceService eventAudience;

	public SimulationSocialContentVerifier(
			OverthinkingPostRepository overthinkingPosts,
			OverthinkingProfileShareRepository overthinkingShares,
			TableGroupService tableGroups,
			TableGroupProfileShareRepository tableGroupShares,
			EventAudienceService eventAudience
	) {
		this.overthinkingPosts = Objects.requireNonNull(overthinkingPosts, "overthinkingPosts");
		this.overthinkingShares = Objects.requireNonNull(overthinkingShares, "overthinkingShares");
		this.tableGroups = Objects.requireNonNull(tableGroups, "tableGroups");
		this.tableGroupShares = Objects.requireNonNull(tableGroupShares, "tableGroupShares");
		this.eventAudience = Objects.requireNonNull(eventAudience, "eventAudience");
	}

	public void verifyOverthinking(
			SimulationSocialContentCheckpoint.OverthinkingPublication item,
			String expectedTitle,
			String expectedContent,
			String expectedNote
	) {
		var post = overthinkingPosts.findById(item.sourcePostId())
				.orElseThrow(() -> conflict("Overthinking source is missing", item.logicalKey()));
		if (post.getAuthor() == null
				|| !item.ownerUserId().equals(post.getAuthor().getId())
				|| !expectedTitle.equals(post.getTitle())
				|| !expectedContent.equals(post.getContent())
				|| post.getVisibilityType() != OverthinkingVisibilityType.VISIBLE
				|| post.getSpotifyTrackUrl() != null
				|| post.getSpotifyArtistId() != null
				|| post.getSpotifyTrackName() != null
				|| post.getSpotifyArtistName() != null
				|| post.getSpotifyAlbumImageUrl() != null
				|| post.getMusicianTrackId() != null
				|| post.getBandTrackId() != null
				|| post.getArtistId() != null
				|| post.getArtistType() != null) {
			throw conflict("Overthinking source differs from the checkpoint story", item.logicalKey());
		}

		var share = overthinkingShares.findById(item.profileShareId())
				.orElseThrow(() -> conflict("Overthinking profile publication is missing", item.logicalKey()));
		if (!item.ownerUserId().equals(share.getOwnerUserId())
				|| !item.sourcePostId().equals(share.getSourcePostId())
				|| !Objects.equals(expectedNote, share.getNote())
				|| share.getListenerProfileId() == null
				|| !item.ownerUserId().equals(overthinkingShares.listenerOwner(
						share.getListenerProfileId()).orElse(null))
				|| share.getPublishedAt() == null) {
			throw conflict("Overthinking profile publication differs from the checkpoint story", item.logicalKey());
		}
	}

	public void verifyTableGroup(
			SimulationSocialContentCheckpoint.TableGroupAggregate item,
			TableGroupCreateRequestDto expected,
			Map<UUID, String> expectedParticipantNotes
	) {
		TableGroupResponseDto table = tableGroups.getTableGroupDetail(item.ownerUserId(), item.tableGroupId());
		if (table == null
				|| !item.tableGroupId().equals(table.id())
				|| !item.ownerUserId().equals(table.ownerId())
				|| table.venueId() != null
				|| !Objects.equals(expected.venueName(), table.venueName())
				|| !expected.description().equals(table.description())
				|| expected.maxPersonCount() != table.maxPersonCount()
				|| !sameValues(expected.genderPrefs(), table.genderPrefs())
				|| expected.ageMin() != table.ageMin()
				|| expected.ageMax() != table.ageMax()
				|| !item.meetingAt().equals(table.meetingAt())
				|| table.startAt() == null
				|| table.expiresAt() == null
				|| !table.startAt().plus(TABLE_LIFETIME).equals(table.expiresAt())
				|| (table.status() != TableGroupStatus.ACTIVE && table.status() != TableGroupStatus.INACTIVE)
				|| !Objects.equals(expected.cityId(), locationId(table.city()))
				|| !Objects.equals(expected.districtId(), locationId(table.district()))
				|| !Objects.equals(expected.neighborhoodId(), locationId(table.neighborhood()))) {
			throw conflict("TableGroup differs from the checkpoint story", item.logicalKey());
		}

		Map<UUID, String> actual = new LinkedHashMap<>();
		Set<UUID> participantIds = new HashSet<>();
		if (table.participants() == null) {
			throw conflict("TableGroup participant state is absent", item.logicalKey());
		}
		for (var participant : table.participants()) {
			if (participant == null || participant.userId() == null
					|| participant.joinedAt() == null
					|| participant.status() != ParticipantStatus.ACCEPTED
					|| !participantIds.add(participant.userId())) {
				throw conflict("TableGroup participant state differs from the story", item.logicalKey());
			}
			actual.put(participant.userId(), participant.joinNote());
		}
		if (!actual.equals(expectedParticipantNotes)) {
			throw conflict("TableGroup membership differs from the checkpoint story", item.logicalKey());
		}
	}

	public void verifyTableGroupPublication(
			SimulationSocialContentCheckpoint.TableGroupPublication item,
			String expectedNote
	) {
		var share = tableGroupShares.findById(item.profileShareId())
				.orElseThrow(() -> conflict("TableGroup profile publication is missing", item.logicalKey()));
		if (!item.publisherUserId().equals(share.getOwnerUserId())
				|| !item.tableGroupId().equals(share.getTableGroupId())
				|| !Objects.equals(expectedNote, share.getNote())
				|| share.getListenerProfileId() == null
				|| !item.publisherUserId().equals(tableGroupShares.listenerOwner(
						share.getListenerProfileId()).orElse(null))
				|| (share.isFinalSourceFrozen() && share.getFinalSource() == null)
				|| share.getPublishedAt() == null) {
			throw conflict("TableGroup profile publication differs from the checkpoint story", item.logicalKey());
		}
	}

	public void verifyEventPublication(SimulationSocialContentCheckpoint.EventPublication item) {
		var state = eventAudience.get(item.listenerUserId(), item.eventId());
		if (state == null
				|| !item.eventId().equals(state.eventId())
				|| !state.eventAvailable()
				|| state.intent() != item.intent()
				|| !state.publishedOnProfile()
				|| !state.publicationVisible()
				|| !Objects.equals(item.note(), state.note())
				|| state.version() != item.version()
				|| !item.profilePostId().equals(state.postId())) {
			throw conflict("Event profile publication differs from the checkpoint story", item.logicalKey());
		}
	}

	private static UUID locationId(TableGroupResponseDto.LocationDto value) {
		return value == null ? null : value.id();
	}

	private static boolean sameValues(List<String> expected, List<String> actual) {
		if (expected == null || actual == null || expected.size() != actual.size()
				|| expected.stream().anyMatch(Objects::isNull) || actual.stream().anyMatch(Objects::isNull)) return false;
		Map<String, Long> expectedCounts = expected.stream().collect(java.util.stream.Collectors.groupingBy(
				java.util.function.Function.identity(), java.util.stream.Collectors.counting()));
		Map<String, Long> actualCounts = actual.stream().collect(java.util.stream.Collectors.groupingBy(
				java.util.function.Function.identity(), java.util.stream.Collectors.counting()));
		return expectedCounts.equals(actualCounts);
	}

	private static IllegalStateException conflict(String message, String logicalKey) {
		return new IllegalStateException(message + ": " + logicalKey);
	}
}

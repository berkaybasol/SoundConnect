package com.berkayb.soundconnect.modules.profile.shared.ownership;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
// The public profile resolver may take a shared listener-visibility row lock.
// PostgreSQL rejects SELECT ... FOR SHARE inside a read-only transaction, so
// callers must keep a normal transaction open through ownership projection.
@Transactional
public class ProfileOwnershipResolverImpl implements ProfileOwnershipResolver {

	private final PublicProfileResolverService publicProfileResolverService;
	private final ProfileOwnershipPolicy profileOwnershipPolicy;

	@Override
	public List<OwnedProfileTarget> resolveOwnedProfiles(UUID userId, Set<ProfileType> allowedTypes) {
		if (userId == null || allowedTypes == null || allowedTypes.isEmpty()) return List.of();

		Set<ProfileType> effectiveTypes = allowedTypes.stream()
		                                              .filter(Objects::nonNull)
		                                              .filter(profileOwnershipPolicy.supportedTypes()::contains)
		                                              .collect(Collectors.toUnmodifiableSet());
		if (effectiveTypes.isEmpty()) return List.of();

		return publicProfileResolverService.resolveByUserId(userId)
		                                   .profiles()
		                                   .stream()
		                                   .map(this::toOwnedTarget)
		                                   .flatMap(Optional::stream)
		                                   .filter(target -> effectiveTypes.contains(target.type()))
		                                   .filter(target -> profileOwnershipPolicy.owns(
				                                   userId,
				                                   target.type(),
				                                   target.sourceId()
		                                   ))
		                                   .toList();
	}

	@Override
	public OwnedProfileTarget requireOwnership(UUID userId, ProfileType type, UUID sourceId) {
		profileOwnershipPolicy.requireOwnership(userId, type, sourceId);

		return resolveOwnedProfiles(userId, Set.of(type)).stream()
		                                                    .filter(target -> sourceId.equals(target.sourceId()))
		                                                    .findFirst()
		                                                    .orElseThrow(() -> new SoundConnectException(
				                                                    ErrorType.FORBIDDEN_ACCESS
		                                                    ));
	}

	private Optional<OwnedProfileTarget> toOwnedTarget(UserProfileTargetDto target) {
		if (target == null || target.type() == null || target.profileId() == null) {
			return Optional.empty();
		}

		try {
			ProfileType type = ProfileType.valueOf(target.type());
			return Optional.of(new OwnedProfileTarget(
					type,
					target.profileId(),
					target.displayName(),
					target.profilePictureUrl()
			));
		} catch (IllegalArgumentException ignored) {
			return Optional.empty();
		}
	}
}

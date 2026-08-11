package com.berkayb.soundconnect.modules.profile.shared.resolver.service;

import com.berkayb.soundconnect.modules.profile.shared.resolver.contributor.PublicProfileContributor;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PublicProfileResolverServiceImpl implements PublicProfileResolverService {
	
	private final List<PublicProfileContributor> contributors;
	
	@Override
	public UserProfilesResolveResponseDto resolveByUserId(UUID userId) {
		if (userId == null) {
			return new UserProfilesResolveResponseDto(null, List.of());
		}
		
		List<UserProfileTargetDto> raw = new ArrayList<>();
		for (PublicProfileContributor contributor : contributors) {
			List<UserProfileTargetDto> items = contributor.resolve(userId);
			if (items != null && !items.isEmpty()) {
				raw.addAll(items);
			}
		}
		
		Map<String, UserProfileTargetDto> unique = new LinkedHashMap<>();
		for (UserProfileTargetDto item : raw) {
			String key = (safe(item.type()) + ":" + String.valueOf(item.profileId())).toLowerCase(Locale.ROOT);
			unique.putIfAbsent(key, item);
		}
		
		List<UserProfileTargetDto> sorted = unique.values().stream()
		                                          .sorted(Comparator
				                                                  .comparing((UserProfileTargetDto t) -> typeRank(t.type()))
				                                                  .thenComparing(t -> safe(t.displayName())))
		                                          .collect(Collectors.toList());
		
		return new UserProfilesResolveResponseDto(userId, sorted);
	}
	
	private int typeRank(String type) {
		if (type == null) return 99;
		return switch (type) {
			case "MUSICIAN" -> 0;
			case "BAND" -> 1;
			case "VENUE" -> 2;
			case "STUDIO" -> 3;
			case "LISTENER" -> 4;
			default -> 50;
		};
	}
	
	private String safe(String s) {
		return s == null ? "" : s;
	}
}

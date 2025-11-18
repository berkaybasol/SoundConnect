package com.berkayb.soundconnect.modules.collab.spec;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabFilterRequestDto;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public class CollabSpecifications {
	
	public static Specification<Collab> filter(CollabFilterRequestDto f) {
		return Specification.where(byCity(f.cityId()))
				.and(byCategory(f.category()))
				.and(byOwnerRole(f.ownerRole()))
				.and(byTargetRoles(f.targetRoles()))
				.and(byRequiredInstrument(f.requiredInstrumentId()))
				.and(byDaily(f.daily()))
				.and(byCreatedDateRange(f.createdAfter(), f.createdBefore()))
				.and(byOpenSlots(f.hasOpenSlots()));
	}
	
	
	// filter methods
	private static Specification<Collab> byCity(UUID cityId) {
		return (root, query, cb) -> {
			if (cityId == null) return null;
			return cb.equal(root.get("city").get("id"), cityId);
		};
	}
	
	private static Specification<Collab> byCategory(Enum<?> category) {
		return (root, query, cb) -> {
			if (category == null) return null;
			return cb.equal(root.get("category"), category);
		};
	}
	
	private static Specification<Collab> byOwnerRole(Enum<?> ownerRole){
		return (root, query, cb) -> {
			if (ownerRole == null) return null;
			return cb.equal(root.get("ownerRole"), ownerRole);
		};
	}
	
	private static Specification<Collab> byTargetRoles(Set<?> targetRoles) {
		return (root, query, cb) -> {
			if (targetRoles == null || targetRoles.isEmpty()) return null;
			Join<Object, Object> join = root.join("targetRoles", JoinType.INNER);
			return join.in(targetRoles);
		};
	}
	
	private static Specification<Collab> byRequiredInstrument(UUID instrumentId) {
		return (root, query, cb) -> {
			if (instrumentId == null) return null;
			Join<Collab, Instrument> join = root.join("requiredInstruments", JoinType.INNER);
			return cb.equal(join.get("id"), instrumentId);
		};
	}
	
	private static Specification<Collab> byDaily(Boolean daily) {
		return (root, query, cb) -> {
			if (daily == null) return null;
			return cb.equal(root.get("daily"), daily);
		};
	}
	
	private static Specification<Collab> byCreatedDateRange(LocalDateTime after, LocalDateTime before) {
		return (root, query, cb) -> {
			if (after == null && before == null) return null;
			
			if (after != null && before != null)
				return cb.between(root.get("createdAt"), after, before);
			
			if (after != null)
				return cb.greaterThanOrEqualTo(root.get("createdAt"), after);
			
			return cb.lessThanOrEqualTo(root.get("createdAt"), before);
		};
	}
	
	/**
	 * hasOpenSlots = true -> required > filled
	 * hasOpenSlots = false -> required == filled
	 */
	
	private static Specification<Collab> byOpenSlots(Boolean hasOpenSlots) {
		return (root, query, cb) -> {
			if (hasOpenSlots == null) return null;
			
			// SQL aggregate kullanabilmek icin group by gerekiyor
			// Bu yuzden JPA'nin count distinct + groupBy yapisina geciyoruz.
			
			query.groupBy(root.get("id"));
			
			var requiredCount = cb.countDistinct(root.join("requiredInstruments", JoinType.LEFT));
			var filledCount = cb.countDistinct(root.join("filledInstruments", JoinType.LEFT));
			
			if (hasOpenSlots) {
				return cb.lessThan(filledCount, requiredCount);
			} else {
				return cb.equal(requiredCount, filledCount);
			}
		};
	}
}
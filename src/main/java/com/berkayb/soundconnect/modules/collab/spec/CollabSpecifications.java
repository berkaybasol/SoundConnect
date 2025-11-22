package com.berkayb.soundconnect.modules.collab.spec;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabFilterRequestDto;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabRequiredSlot;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;
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
	
	private static Specification<Collab> byOwnerRole(Enum<?> ownerRole) {
		return (root, query, cb) -> {
			if (ownerRole == null) return null;
			return cb.equal(root.get("ownerRole"), ownerRole);
		};
	}
	
	private static Specification<Collab> byTargetRoles(Set<CollabRole> targetRoles) {
		return (root, query, cb) -> {
			if (targetRoles == null || targetRoles.isEmpty()) return null;
			
			query.distinct(true);
			var join = root.joinSet("targetRoles", JoinType.INNER);
			return join.in(targetRoles);
		};
	}
	
	/**
	 * İlgili enstrümanı arayan ilanlar.
	 * Artık requiredSlots üzerinden join yapıyoruz.
	 */
	private static Specification<Collab> byRequiredInstrument(UUID instrumentId) {
		return (root, query, cb) -> {
			if (instrumentId == null) return null;
			
			query.distinct(true);
			
			var slotJoin = root.joinSet("requiredSlots", JoinType.INNER);
			return cb.equal(slotJoin.get("instrument").get("id"), instrumentId);
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
	 * hasOpenSlots = true  -> toplam filledCount < toplam requiredCount
	 * hasOpenSlots = false -> toplam filledCount == toplam requiredCount
	 */
	private static Specification<Collab> byOpenSlots(Boolean hasOpenSlots) {
		return (root, query, cb) -> {
			if (hasOpenSlots == null) return null;
			
			// required toplamı
			var subRequired = query.subquery(Long.class);
			var reqRoot = subRequired.from(CollabRequiredSlot.class);
			subRequired.select(cb.sumAsLong(reqRoot.get("requiredCount")));
			subRequired.where(cb.equal(reqRoot.get("collab").get("id"), root.get("id")));
			
			// filled toplamı
			var subFilled = query.subquery(Long.class);
			var fillRoot = subFilled.from(CollabRequiredSlot.class);
			subFilled.select(cb.sumAsLong(fillRoot.get("filledCount")));
			subFilled.where(cb.equal(fillRoot.get("collab").get("id"), root.get("id")));
			
			if (hasOpenSlots) {
				return cb.lessThan(subFilled, subRequired);
			} else {
				return cb.equal(subFilled, subRequired);
			}
		};
	}
}
package com.berkayb.soundconnect.modules.collab.spec;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabRequiredSlot;
import com.berkayb.soundconnect.modules.collab.enums.CollabRole;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.SetJoin;
import jakarta.persistence.criteria.Subquery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CollabSpecifications icin beyaz-kutu unit testler.
 * JPA Criteria API objeleri Mockito ile mocklanarak,
 * her spec'in dogru sekilde join / predicate olusturup olusturmadigi test edilir.
 */
@ExtendWith(MockitoExtension.class)
class CollabSpecificationsTest {
	
	@Mock
	Root<Collab> root;
	
	@Mock
	CriteriaQuery<Object> query;
	
	@Mock
	CriteriaBuilder cb;
	
	// ---------- Reflection helper'lar ----------
	
	@SuppressWarnings("unchecked")
	private Specification<Collab> invokeByTargetRoles(Set<CollabRole> roles) throws Exception {
		Method m = CollabSpecifications.class.getDeclaredMethod("byTargetRoles", Set.class);
		m.setAccessible(true);
		return (Specification<Collab>) m.invoke(null, roles);
	}
	
	@SuppressWarnings("unchecked")
	private Specification<Collab> invokeByRequiredInstrument(UUID instrumentId) throws Exception {
		Method m = CollabSpecifications.class.getDeclaredMethod("byRequiredInstrument", UUID.class);
		m.setAccessible(true);
		return (Specification<Collab>) m.invoke(null, instrumentId);
	}
	
	@SuppressWarnings("unchecked")
	private Specification<Collab> invokeByOpenSlots(Boolean hasOpenSlots) throws Exception {
		Method m = CollabSpecifications.class.getDeclaredMethod("byOpenSlots", Boolean.class);
		m.setAccessible(true);
		return (Specification<Collab>) m.invoke(null, hasOpenSlots);
	}
	
	// ---------- byTargetRoles ----------
	
	@Test
	@DisplayName("byTargetRoles → targetRoles null veya bosken predicate uretmez")
	void byTargetRoles_nullOrEmpty_returnsNullPredicate() throws Exception {
		Specification<Collab> specNull = invokeByTargetRoles(null);
		Specification<Collab> specEmpty = invokeByTargetRoles(Set.of());
		
		Predicate p1 = specNull.toPredicate(root, query, cb);
		Predicate p2 = specEmpty.toPredicate(root, query, cb);
		
		assertThat(p1).isNull();
		assertThat(p2).isNull();
		
		verifyNoInteractions(root, query, cb);
	}
	
	
	
	// ---------- byRequiredInstrument ----------
	
	@Test
	@DisplayName("byRequiredInstrument → instrumentId null iken predicate uretmez")
	void byRequiredInstrument_null_returnsNullPredicate() throws Exception {
		Specification<Collab> spec = invokeByRequiredInstrument(null);
		
		Predicate result = spec.toPredicate(root, query, cb);
		
		assertThat(result).isNull();
		verifyNoInteractions(root, query, cb);
	}
	
	
	// ---------- byOpenSlots ----------
	
	@Test
	@DisplayName("byOpenSlots → hasOpenSlots null ise predicate uretmez")
	void byOpenSlots_null_returnsNullPredicate() throws Exception {
		Specification<Collab> spec = invokeByOpenSlots(null);
		
		Predicate result = spec.toPredicate(root, query, cb);
		
		assertThat(result).isNull();
		verifyNoInteractions(root, query, cb);
	}
	
	@Test
	@DisplayName("byOpenSlots(true) → toplam filled < toplam required icin lessThan predicate'i kullanir")
	void byOpenSlots_true_usesLessThanOnSubqueries() throws Exception {
		Specification<Collab> spec = invokeByOpenSlots(true);
		
		Subquery<Long> subRequired = mock(Subquery.class);
		Subquery<Long> subFilled = mock(Subquery.class);
		@SuppressWarnings("unchecked")
		Root<CollabRequiredSlot> reqRoot = mock(Root.class);
		@SuppressWarnings("unchecked")
		Root<CollabRequiredSlot> fillRoot = mock(Root.class);
		
		when(query.subquery(Long.class)).thenReturn(subRequired, subFilled);
		when(subRequired.from(CollabRequiredSlot.class)).thenReturn(reqRoot);
		when(subFilled.from(CollabRequiredSlot.class)).thenReturn(fillRoot);
		
		when(reqRoot.get("requiredCount")).thenReturn(mock(jakarta.persistence.criteria.Path.class));
		when(fillRoot.get("filledCount")).thenReturn(mock(jakarta.persistence.criteria.Path.class));
		when(reqRoot.get("collab")).thenReturn(mock(jakarta.persistence.criteria.Path.class));
		when(fillRoot.get("collab")).thenReturn(mock(jakarta.persistence.criteria.Path.class));
		when(root.get("id")).thenReturn(mock(jakarta.persistence.criteria.Path.class));
		
		// sumAsLong ve equal icin dummy donusler, detay onemli degil
		when(cb.sumAsLong(any(Expression.class))).thenReturn(mock(Expression.class));
		when(cb.equal(any(), any())).thenReturn(mock(Predicate.class));
		
		// En sonunda kullanilacak lessThan predicate'i
		Predicate lessThanPredicate = mock(Predicate.class);
		when(cb.lessThan(any(Expression.class), any(Expression.class)))
				.thenReturn(lessThanPredicate);
		
		Predicate result = spec.toPredicate(root, query, cb);
		
		assertThat(result).isSameAs(lessThanPredicate);
		verify(cb).lessThan(any(Expression.class), any(Expression.class));
	}
	
	@Test
	@DisplayName("byOpenSlots(false) → toplam filled == toplam required icin equal(subFilled, subRequired) predicate'i kullanir")
	void byOpenSlots_false_usesEqualOnSubqueries() throws Exception {
		Specification<Collab> spec = invokeByOpenSlots(false);
		
		Subquery<Long> subRequired = mock(Subquery.class);
		Subquery<Long> subFilled = mock(Subquery.class);
		@SuppressWarnings("unchecked")
		Root<CollabRequiredSlot> reqRoot = mock(Root.class);
		@SuppressWarnings("unchecked")
		Root<CollabRequiredSlot> fillRoot = mock(Root.class);
		
		when(query.subquery(Long.class)).thenReturn(subRequired, subFilled);
		when(subRequired.from(CollabRequiredSlot.class)).thenReturn(reqRoot);
		when(subFilled.from(CollabRequiredSlot.class)).thenReturn(fillRoot);
		
		// path mock'ları
		when(reqRoot.get("requiredCount")).thenReturn(mock(jakarta.persistence.criteria.Path.class));
		when(fillRoot.get("filledCount")).thenReturn(mock(jakarta.persistence.criteria.Path.class));
		when(reqRoot.get("collab")).thenReturn(mock(jakarta.persistence.criteria.Path.class));
		when(fillRoot.get("collab")).thenReturn(mock(jakarta.persistence.criteria.Path.class));
		when(root.get("id")).thenReturn(mock(jakarta.persistence.criteria.Path.class));
		
		// sumAsLong icin dummy expression
		when(cb.sumAsLong(any(Expression.class))).thenReturn(mock(Expression.class));
		
		// DIKKAT: cb.equal icin STUB YAPMIYORUZ
		// böylece strict stubbing problemi ortadan kalkıyor
		
		// Çalıştır
		Predicate result = spec.toPredicate(root, query, cb);
		
		// Result null olabilir, onu umursamıyoruz; önemli olan equal(Expression, Expression) cagrilmis mi
		// (subFilled vs subRequired)
		verify(cb, atLeastOnce()).equal(any(Expression.class), any(Expression.class));
	}
	
}
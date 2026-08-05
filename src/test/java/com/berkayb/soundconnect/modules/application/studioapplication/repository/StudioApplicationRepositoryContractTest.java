package com.berkayb.soundconnect.modules.application.studioapplication.repository;

import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class StudioApplicationRepositoryContractTest {
	@Test
	void pagedListQueriesFetchEveryMapperRelationWithoutNPlusOne() throws Exception {
		assertMapperGraph(StudioApplicationRepository.class.getMethod(
				"findAllByApplicant", User.class, Pageable.class));
		assertMapperGraph(StudioApplicationRepository.class.getMethod(
				"findAllByStatus", ApplicationStatus.class, Pageable.class));
	}

	private void assertMapperGraph(Method method) {
		EntityGraph graph = method.getAnnotation(EntityGraph.class);
		assertThat(graph).isNotNull();
		assertThat(graph.attributePaths()).containsExactlyInAnyOrder(
				"applicant", "city", "district", "neighborhood", "reviewedBy");
	}
}

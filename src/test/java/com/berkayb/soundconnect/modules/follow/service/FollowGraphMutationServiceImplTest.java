package com.berkayb.soundconnect.modules.follow.service;

import com.berkayb.soundconnect.modules.follow.repository.FollowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowGraphMutationServiceImplTest {

	@Mock
	private FollowRepository followRepository;

	@Test
	void removes_every_incoming_follow_and_returns_affected_count() {
		UUID userId = UUID.randomUUID();
		when(followRepository.deleteAllIncomingByUserId(userId)).thenReturn(4);
		FollowGraphMutationServiceImpl service = new FollowGraphMutationServiceImpl(followRepository);

		assertThat(service.removeAllIncomingFollowers(userId)).isEqualTo(4);

		verify(followRepository).deleteAllIncomingByUserId(userId);
	}

	@Test
	void rejects_null_user_id_without_touching_repository() {
		FollowGraphMutationServiceImpl service = new FollowGraphMutationServiceImpl(followRepository);

		assertThatNullPointerException()
				.isThrownBy(() -> service.removeAllIncomingFollowers(null))
				.withMessage("userId must not be null");

		verifyNoInteractions(followRepository);
	}

	@Test
	void requires_callers_transaction_so_visibility_change_and_purge_are_atomic() throws Exception {
		Method method = FollowGraphMutationServiceImpl.class
				.getMethod("removeAllIncomingFollowers", UUID.class);

		Transactional transactional = method.getAnnotation(Transactional.class);
		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
	}
}

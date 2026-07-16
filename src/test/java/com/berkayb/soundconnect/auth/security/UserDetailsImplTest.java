package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserDetailsImplTest {

	@Test
	void onlyVerifiedActiveAccountIsEnabled() {
		User active = user(UserStatus.ACTIVE, true);
		User inactive = user(UserStatus.INACTIVE, true);
		User unverified = user(UserStatus.ACTIVE, false);

		assertThat(new UserDetailsImpl(active).isEnabled()).isTrue();
		assertThat(new UserDetailsImpl(inactive).isEnabled()).isFalse();
		assertThat(new UserDetailsImpl(unverified).isEnabled()).isFalse();
	}

	@Test
	void pendingVenueAccountIsLocked() {
		User pending = user(UserStatus.PENDING_VENUE_REQUEST, true);

		UserDetailsImpl details = new UserDetailsImpl(pending);

		assertThat(details.isAccountNonLocked()).isFalse();
		assertThat(details.isEnabled()).isFalse();
	}

	private User user(UserStatus status, boolean emailVerified) {
		return User.builder()
				.username("user")
				.email("user@example.com")
				.password("encoded")
				.status(status)
				.emailVerified(emailVerified)
				.build();
	}
}

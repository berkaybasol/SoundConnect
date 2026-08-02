package com.berkayb.soundconnect.modules.user.support;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class UserIdentityConflictMapperTest {
	@Test
	void mapsCanonicalUsernameUniqueViolation() {
		DataIntegrityViolationException exception = violation(
				"duplicate key value violates unique constraint \"ux_tbl_user_username_canonical\" "
						+ "Detail: Key (user_name) already exists",
				"23505"
		);

		assertThat(UserIdentityConflictMapper.map(exception))
				.isInstanceOfSatisfying(SoundConnectException.class,
						mapped -> assertThat(mapped.getErrorType()).isEqualTo(ErrorType.USER_ALREADY_EXISTS));
	}

	@Test
	void mapsEmailUniqueViolationWithoutExposingDatabaseDetails() {
		DataIntegrityViolationException exception = violation(
				"duplicate key value violates unique constraint Detail: Key (email) already exists",
				"23505"
		);

		assertThat(UserIdentityConflictMapper.map(exception))
				.isInstanceOfSatisfying(SoundConnectException.class,
						mapped -> assertThat(mapped.getErrorType()).isEqualTo(ErrorType.EMAIL_ALREADY_EXISTS));
	}

	@Test
	void leavesUnknownOrNonUniqueIntegrityFailuresForTheGlobalHandler() {
		DataIntegrityViolationException exception = violation(
				"violates check constraint ck_tbl_user_username_canonical",
				"23514"
		);

		assertThat(UserIdentityConflictMapper.map(exception)).isSameAs(exception);
	}

	private DataIntegrityViolationException violation(String message, String sqlState) {
		return new DataIntegrityViolationException(message, new SQLException(message, sqlState));
	}
}

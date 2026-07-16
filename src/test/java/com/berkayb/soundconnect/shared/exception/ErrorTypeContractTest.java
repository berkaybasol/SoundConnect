package com.berkayb.soundconnect.shared.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorTypeContractTest {

	@Test
	void everyPublicErrorCodeIsUnique() {
		Map<Integer, List<ErrorType>> byCode = Arrays.stream(ErrorType.values())
				.collect(Collectors.groupingBy(ErrorType::getCode));

		Map<Integer, List<ErrorType>> duplicates = byCode.entrySet().stream()
				.filter(entry -> entry.getValue().size() > 1)
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		assertThat(duplicates).as("Duplicate API error codes").isEmpty();
	}

	@Test
	void securityAndRequestErrorsUseHttpSemantics() {
		assertThat(ErrorType.INVALID_CREDENTIALS.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(ErrorType.UNAUTHORIZED.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(ErrorType.FORBIDDEN_ACCESS.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(ErrorType.TRACK_OWNER_INVALID.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(ErrorType.INVALID_PARAMETER.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(ErrorType.VENUE_APPLICATION_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(ErrorType.BAND_INVITE_UNAUTHORIZED.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(ErrorType.BAND_REMOVE_UNAUTHORIZED.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(ErrorType.COLLAB_NOT_OWNER.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(ErrorType.VENUE_SEARCH_QUERY_REQUIRED.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(ErrorType.SPOTIFY_AUTH_FAILED.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	@Test
	void duplicateAndInvalidStateErrorsUseConflict() {
		assertThat(List.of(
				ErrorType.BAND_ALREADY_FOLLOWED,
				ErrorType.PROFILE_ALREADY_EXISTS,
				ErrorType.INSTRUMENT_ALREADY_EXISTS,
				ErrorType.REQUEST_ALREADY_ACCEPTED,
				ErrorType.REQUEST_ALREADY_REJECTED,
				ErrorType.REQUEST_CANCEL_NOT_ALLOWED,
				ErrorType.CONNECTION_NOT_ACTIVE,
				ErrorType.REQUEST_DISCONNECT_NOT_ALLOWED,
				ErrorType.VENUE_APPLICATION_ALREADY_EXISTS,
				ErrorType.INVALID_APPLICATION_STATUS,
				ErrorType.MEDIA_ASSET_NOT_READY,
				ErrorType.ROLE_ALREADY_EXISTS,
				ErrorType.PERMISSION_ALREADY_EXISTS,
				ErrorType.CITY_ALREADY_EXISTS,
				ErrorType.DISTRICT_ALREADY_EXISTS,
				ErrorType.NEIGHBORHOOD_ALREADY_EXISTS,
				ErrorType.MAX_PARTICIPANT_LIMIT,
				ErrorType.ALREADY_PARTICIPANT,
				ErrorType.BAND_ALREADY_EXISTS,
				ErrorType.BAND_MEMBER_NOT_ACTIVE,
				ErrorType.BAND_CREATE_LIMIT_EXCEEDED,
				ErrorType.COLLAB_SLOT_NOT_FILLED
		)).allMatch(error -> error.getHttpStatus() == HttpStatus.CONFLICT);
	}

	@Test
	void mvcTransportErrorsKeepTheirNativeStatuses() {
		assertThat(ErrorType.ENDPOINT_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(ErrorType.METHOD_NOT_ALLOWED.getHttpStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(ErrorType.UNSUPPORTED_MEDIA_TYPE.getHttpStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
	}

	@Test
	void everyErrorTypeRepresentsAnErrorStatus() {
		assertThat(Arrays.asList(ErrorType.values()))
				.allMatch(error -> error.getHttpStatus().is4xxClientError()
						|| error.getHttpStatus().is5xxServerError());
	}
}

package com.berkayb.soundconnect.modules.tablegroup.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableGroupCreateRequestDtoJsonTest {
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@Test
	void offsetMeetingAtPreservesItsInstant() throws Exception {
		TableGroupCreateRequestDto request = objectMapper.readValue(
				json("\"meetingAt\":\"2026-08-18T10:15:30+03:00\""),
				TableGroupCreateRequestDto.class
		);

		assertThat(request.meetingAt()).isEqualTo(Instant.parse("2026-08-18T07:15:30Z"));
		assertThat(request.description()).isEqualTo("Yeni insanlarla tanışmak istiyorum");
	}

	@Test
	void utcMeetingAtIsAccepted() throws Exception {
		TableGroupCreateRequestDto request = objectMapper.readValue(
				json("\"meetingAt\":\"2026-08-18T07:15:30Z\""),
				TableGroupCreateRequestDto.class
		);

		assertThat(request.meetingAt()).isEqualTo(Instant.parse("2026-08-18T07:15:30Z"));
	}

	@Test
	void zoneLessMeetingAtIsRejectedInsteadOfAssumingIstanbul() {
		assertThatThrownBy(() -> objectMapper.readValue(
				json("\"meetingAt\":\"2026-08-18T10:15:30\""),
				TableGroupCreateRequestDto.class
		)).hasMessageContaining("offset-aware RFC 3339");
	}

	@Test
	void numericMeetingAtIsRejectedByTheStringOnlyContract() {
		assertThatThrownBy(() -> objectMapper.readValue(
				json("\"meetingAt\":1787046930"),
				TableGroupCreateRequestDto.class
		)).hasMessageContaining("String");
	}

	@Test
	void offsetWithoutRfc3339MinutesIsRejected() {
		assertThatThrownBy(() -> objectMapper.readValue(
				json("\"meetingAt\":\"2026-08-18T10:15:30+03\""),
				TableGroupCreateRequestDto.class
		)).hasMessageContaining("offset-aware RFC 3339");
	}

	@Test
	void retiredExpiresAtAliasIsRejectedEvenWhenMeetingAtIsAlsoPresent() {
		assertThatThrownBy(() -> objectMapper.readValue(
				json("""
						"meetingAt":"2026-08-18T07:15:30Z",
						"expiresAt":"2026-08-18T07:15:30Z"
						"""),
				TableGroupCreateRequestDto.class
		)).hasMessageContaining("Unknown TableGroup create field: expiresAt");
	}

	@Test
	void malformedMeetingAtIsRejected() {
		assertThatThrownBy(() -> objectMapper.readValue(
				json("\"meetingAt\":\"not-a-date\""),
				TableGroupCreateRequestDto.class
		)).hasMessageContaining("offset-aware RFC 3339");
	}

	private String json(String timeFields) {
		return """
				{
				  "venueName":"Venue", "description":"Yeni insanlarla tanışmak istiyorum",
				  "maxPersonCount":2,
				  "genderPrefs":["MALE","FEMALE"], "ageMin":20, "ageMax":30,
				  %s, "cityId":"00000000-0000-0000-0000-000000000001"
				}
				""".formatted(timeFields);
	}
}

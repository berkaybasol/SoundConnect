package com.berkayb.soundconnect.modules.tablegroup.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * Accepts only an offset-aware RFC 3339 string and normalizes it to an instant.
 * Numeric epochs and zone-less local timestamps are deliberately outside the
 * public API contract.
 */
public final class OffsetAwareInstantDeserializer extends JsonDeserializer<Instant> {
	private static final DateTimeFormatter RFC_3339 = new DateTimeFormatterBuilder()
			.parseCaseSensitive()
			.append(DateTimeFormatter.ISO_LOCAL_DATE)
			.appendLiteral('T')
			.appendValue(ChronoField.HOUR_OF_DAY, 2)
			.appendLiteral(':')
			.appendValue(ChronoField.MINUTE_OF_HOUR, 2)
			.appendLiteral(':')
			.appendValue(ChronoField.SECOND_OF_MINUTE, 2)
			.optionalStart()
			.appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
			.optionalEnd()
			.appendOffset("+HH:MM", "Z")
			.toFormatter(Locale.ROOT)
			.withResolverStyle(ResolverStyle.STRICT);

	@Override
	public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		if (!parser.hasToken(JsonToken.VALUE_STRING)) {
			context.reportInputMismatch(
					Instant.class,
					"meetingAt must be a String containing an offset-aware RFC 3339 timestamp"
			);
		}

		String value = parser.getText();
		try {
			return OffsetDateTime.parse(value, RFC_3339).toInstant();
		} catch (DateTimeParseException exception) {
			throw InvalidFormatException.from(
					parser,
					"meetingAt must be an offset-aware RFC 3339 timestamp",
					value,
					Instant.class
			);
		}
	}
}

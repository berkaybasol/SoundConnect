package com.berkayb.soundconnect.modules.feed.musician.preference.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/** Full replacement of the private opportunity-city preference; null clears it. */
@JsonDeserialize(using = MusicianFeedPreferencesUpdate.Deserializer.class)
public record MusicianFeedPreferencesUpdate(
		UUID opportunityCityId,
		@NotNull @PositiveOrZero @Max(Long.MAX_VALUE - 1) Long expectedVersion
) {
	/** Reject duplicate/unknown fields and require an explicit city value, even for clear. */
	public static final class Deserializer extends StdDeserializer<MusicianFeedPreferencesUpdate> {
		private static final Set<String> FIELDS = Set.of("opportunityCityId", "expectedVersion");

		public Deserializer() {
			super(MusicianFeedPreferencesUpdate.class);
		}

		@Override
		public MusicianFeedPreferencesUpdate deserialize(JsonParser parser, DeserializationContext context)
				throws IOException {
			parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
			JsonNode root = parser.getCodec().readTree(parser);
			if (!root.isObject() || root.size() != FIELDS.size()) return invalid(context);
			var names = root.fieldNames();
			while (names.hasNext()) if (!FIELDS.contains(names.next())) return invalid(context);

			JsonNode cityNode = root.get("opportunityCityId");
			JsonNode versionNode = root.get("expectedVersion");
			if (cityNode == null || versionNode == null
					|| !(cityNode.isNull() || cityNode.isTextual())
					|| !versionNode.isIntegralNumber() || !versionNode.canConvertToLong()) {
				return invalid(context);
			}

			UUID cityId = null;
			if (cityNode.isTextual()) {
				try {
					cityId = UUID.fromString(cityNode.textValue());
					if (!cityId.toString().equalsIgnoreCase(cityNode.textValue())) return invalid(context);
				} catch (IllegalArgumentException exception) {
					return invalid(context);
				}
			}
			return new MusicianFeedPreferencesUpdate(cityId, versionNode.longValue());
		}

		private MusicianFeedPreferencesUpdate invalid(DeserializationContext context) throws IOException {
			return context.reportInputMismatch(
					MusicianFeedPreferencesUpdate.class,
					"Expected exactly opportunityCityId (UUID or null) and integral expectedVersion"
			);
		}
	}
}

package com.berkayb.soundconnect.tools.simulation.world;

import com.berkayb.soundconnect.shared.util.UsernameUtils;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Band;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.BandMemberRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ContentTargets;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ListenerVisibilityState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Location;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.MusicianProfilePlan;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ObserverProfile;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Scene;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ScenePlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the whole deterministic world before a runner is allowed to materialize it. */
public final class SimulationWorldManifestValidator {
	static final String LOCATION_CATALOG_RESOURCE = "location-seed.json";
	static final String INSTRUMENT_CATALOG_RESOURCE = "instrument-catalog-seed.json";

	private static final int EXPECTED_SCHEMA_VERSION = 1;
	private static final String EXPECTED_WORLD_ID = "soundconnect-local-world-v1";
	private static final String TEST_EMAIL_DOMAIN = "soundconnect.invalid";
	private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{2,63}");
	private static final Pattern EMAIL_LOCAL_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
	private static final Pattern PHONE_PATTERN = Pattern.compile("0[0-9]{10}");

	private static final Map<AccountRole, Integer> EXPECTED_ROLE_COUNTS = Map.of(
			AccountRole.MUSICIAN, 24,
			AccountRole.LISTENER, 10,
			AccountRole.VENUE, 8,
			AccountRole.STUDIO, 8
	);

	private static final Map<Scene, SceneExpectation> EXPECTED_SCENES = Map.of(
			Scene.ISTANBUL_ALTERNATIVE_ROCK,
			new SceneExpectation(List.of("İstanbul"), 10, 3, 3, 3, 2),
			Scene.ANKARA_POP_ELECTRONIC,
			new SceneExpectation(List.of("Ankara"), 5, 2, 1, 2, 1),
			Scene.IZMIR_JAZZ_FUNK,
			new SceneExpectation(List.of("İzmir"), 4, 2, 2, 1, 1),
			Scene.BURSA_METAL_ACOUSTIC,
			new SceneExpectation(List.of("Bursa"), 3, 1, 1, 1, 0),
			Scene.ANTALYA_ESKISEHIR_BRIDGES,
			new SceneExpectation(List.of("Antalya", "Eskişehir"), 2, 2, 1, 1, 0)
	);

	private static final ContentTargets EXPECTED_CONTENT_TARGETS = new ContentTargets(
			36, 30, 34, 60, 18, 14, 10, 240, 300, 100
	);

	private final Set<CatalogLocation> catalogLocations;
	private final Set<String> catalogInstruments;

	public SimulationWorldManifestValidator(ObjectMapper objectMapper) {
		this(
				objectMapper,
				new ClassPathResource(LOCATION_CATALOG_RESOURCE),
				new ClassPathResource(INSTRUMENT_CATALOG_RESOURCE)
		);
	}

	SimulationWorldManifestValidator(
			ObjectMapper objectMapper,
			Resource locationCatalogResource,
			Resource instrumentCatalogResource
	) {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(locationCatalogResource, "locationCatalogResource");
		Objects.requireNonNull(instrumentCatalogResource, "instrumentCatalogResource");
		this.catalogLocations = loadLocationCatalog(objectMapper, locationCatalogResource);
		this.catalogInstruments = loadInstrumentCatalog(objectMapper, instrumentCatalogResource);
	}

	public SimulationWorldManifest validate(SimulationWorldManifest manifest) {
		List<String> violations = new ArrayList<>();
		if (manifest == null) {
			throw new SimulationWorldManifestException("Invalid simulation world manifest: manifest is null");
		}

		if (manifest.schemaVersion() != EXPECTED_SCHEMA_VERSION) {
			violations.add("schemaVersion must be 1");
		}
		if (!EXPECTED_WORLD_ID.equals(manifest.worldId())) {
			violations.add("worldId must be " + EXPECTED_WORLD_ID);
		}
		if (manifest.seed() <= 0) {
			violations.add("seed must be positive");
		}

		validateScenes(manifest.scenes(), violations);
		if (!EXPECTED_CONTENT_TARGETS.equals(manifest.contentTargets())) {
			violations.add("contentTargets must match the local-simulation-v1 baseline");
		}
		Map<String, Account> accountsByKey = validateAccounts(manifest.accounts(), violations);
		validateBands(manifest.bands(), accountsByKey, violations);
		validateActualSceneDistribution(manifest, violations);

		if (!violations.isEmpty()) {
			throw new SimulationWorldManifestException(
					"Invalid simulation world manifest: " + String.join("; ", violations));
		}
		return manifest;
	}

	private void validateScenes(List<ScenePlan> scenes, List<String> violations) {
		if (scenes.size() != EXPECTED_SCENES.size()) {
			violations.add("scenes must contain exactly five entries");
		}
		Map<Scene, ScenePlan> byScene = new EnumMap<>(Scene.class);
		for (ScenePlan plan : scenes) {
			if (plan == null || plan.scene() == null) {
				violations.add("scene entry and scene type must be present");
				continue;
			}
			if (byScene.putIfAbsent(plan.scene(), plan) != null) {
				violations.add("duplicate scene plan: " + plan.scene());
				continue;
			}
			SceneExpectation expected = EXPECTED_SCENES.get(plan.scene());
			if (expected == null || !expected.matches(plan)) {
				violations.add("scene distribution does not match specification: " + plan.scene());
			}
		}
		for (Scene scene : Scene.values()) {
			if (!byScene.containsKey(scene)) {
				violations.add("missing scene plan: " + scene);
			}
		}
	}

	private Map<String, Account> validateAccounts(List<Account> accounts, List<String> violations) {
		if (accounts.size() != 50) {
			violations.add("accounts must contain exactly 50 entries");
		}

		Map<String, Account> accountsByKey = new LinkedHashMap<>();
		Set<String> usernames = new HashSet<>();
		Set<String> emails = new HashSet<>();
		Set<String> phones = new HashSet<>();
		Map<AccountRole, Integer> roleCounts = new EnumMap<>(AccountRole.class);
		Map<ObserverProfile, Integer> observerCounts = new EnumMap<>(ObserverProfile.class);
		Map<EmailVerificationState, Integer> verificationCounts = new EnumMap<>(EmailVerificationState.class);
		Map<ListenerVisibilityState, Integer> listenerCounts = new EnumMap<>(ListenerVisibilityState.class);
		Map<AccountRole, Map<InstitutionState, Integer>> institutionCounts = new EnumMap<>(AccountRole.class);

		for (Account account : accounts) {
			if (account == null) {
				violations.add("account entry must not be null");
				continue;
			}
			String key = canonicalKey(account.key());
			if (key == null || !KEY_PATTERN.matcher(key).matches() || !key.equals(account.key())) {
				violations.add("account key must be canonical lower-case ASCII: " + account.key());
			} else if (accountsByKey.putIfAbsent(key, account) != null) {
				violations.add("duplicate account key: " + key);
			}

			validateIdentity(account, usernames, emails, violations);
			if (account.emailVerification() == null) {
				violations.add("email verification state is required: " + account.key());
			} else {
				verificationCounts.merge(account.emailVerification(), 1, Integer::sum);
				if (account.emailVerification() == EmailVerificationState.UNVERIFIED
						&& (account.role() != AccountRole.MUSICIAN || account.observer() != ObserverProfile.NONE)) {
					violations.add("only a non-observer musician may be UNVERIFIED: " + account.key());
				}
			}
			validateText("givenName", account.givenName(), 2, 50, account.key(), violations);
			validateText("familyName", account.familyName(), 2, 50, account.key(), violations);
			validateText("displayName", account.displayName(), 2, 100, account.key(), violations);
			validateText("bio", account.bio(), 20, 500, account.key(), violations);
			validateText("archetype", account.archetype(), 10, 180, account.key(), violations);
			validateLocation(account, violations);

			if (account.role() == null) {
				violations.add("account role is required: " + account.key());
				continue;
			}
			roleCounts.merge(account.role(), 1, Integer::sum);
			if (account.scene() == null) {
				violations.add("account scene is required: " + account.key());
			}
			if (account.observer() == null) {
				violations.add("observer state is required: " + account.key());
			} else {
				observerCounts.merge(account.observer(), 1, Integer::sum);
			}

			validateRoleState(account, listenerCounts, institutionCounts, phones, violations);
		}

		for (AccountRole role : AccountRole.values()) {
			int expected = EXPECTED_ROLE_COUNTS.get(role);
			if (roleCounts.getOrDefault(role, 0) != expected) {
				violations.add(role + " account count must be " + expected);
			}
		}
		assertCount(observerCounts, ObserverProfile.COMPLETE, 1, "COMPLETE observer", violations);
		assertCount(observerCounts, ObserverProfile.INCOMPLETE, 1, "INCOMPLETE observer", violations);
		assertCount(observerCounts, ObserverProfile.COLD_START, 1, "COLD_START observer", violations);
		assertCount(verificationCounts, EmailVerificationState.UNVERIFIED, 1, "UNVERIFIED account", violations);
		assertCount(verificationCounts, EmailVerificationState.VERIFIED, 49, "VERIFIED account", violations);
		assertCount(listenerCounts, ListenerVisibilityState.STANDARD, 7, "STANDARD listener", violations);
		assertCount(listenerCounts, ListenerVisibilityState.GHOST, 2, "GHOST listener", violations);
		assertCount(listenerCounts, ListenerVisibilityState.VISIBILITY_PENDING, 1,
				"VISIBILITY_PENDING listener", violations);
		assertInstitutionCounts(institutionCounts, violations);
		return accountsByKey;
	}

	private void validateIdentity(
			Account account,
			Set<String> usernames,
			Set<String> emails,
			List<String> violations
	) {
		String username = UsernameUtils.normalize(account.username());
		if (username == null || !UsernameUtils.hasValidCanonicalLength(username)
				|| !username.equals(account.username()) || containsControl(username)) {
			violations.add("username must be canonical and 3-30 characters: " + account.key());
		} else if (!usernames.add(username)) {
			violations.add("duplicate normalized username: " + username);
		}

		String email = canonicalEmail(account.email());
		int separator = email == null ? -1 : email.lastIndexOf('@');
		String local = separator > 0 ? email.substring(0, separator) : "";
		String domain = separator > 0 ? email.substring(separator + 1) : "";
		if (email == null || !email.equals(account.email()) || email.length() > 254
				|| !EMAIL_LOCAL_PATTERN.matcher(local).matches() || !TEST_EMAIL_DOMAIN.equals(domain)) {
			violations.add("email must be canonical and use @soundconnect.invalid: " + account.key());
		} else if (!emails.add(email)) {
			violations.add("duplicate normalized email: " + email);
		}
	}

	private void validateRoleState(
			Account account,
			Map<ListenerVisibilityState, Integer> listenerCounts,
			Map<AccountRole, Map<InstitutionState, Integer>> institutionCounts,
			Set<String> phones,
			List<String> violations
	) {
		boolean observer = account.observer() != null && account.observer() != ObserverProfile.NONE;
		if (observer && account.role() != AccountRole.MUSICIAN) {
			violations.add("only musicians may be reserved observers: " + account.key());
		}

		switch (account.role()) {
			case MUSICIAN -> {
				if (account.listenerVisibility() != null || account.institutionState() != null) {
					violations.add("musician cannot carry listener/institution state: " + account.key());
				}
				validateMusicianProfilePlan(account, violations);
				validatePersonalContactFields(account, violations);
			}
			case LISTENER -> {
				if (account.emailVerification() != EmailVerificationState.VERIFIED) {
					violations.add("listener must be email verified: " + account.key());
				}
				if (account.listenerVisibility() == null) {
					violations.add("listener visibility state is required: " + account.key());
				} else {
					listenerCounts.merge(account.listenerVisibility(), 1, Integer::sum);
				}
				if (account.institutionState() != null) {
					violations.add("listener cannot carry institution state: " + account.key());
				}
				validateNonMusicianProfileFields(account, violations);
				validatePersonalContactFields(account, violations);
			}
			case VENUE, STUDIO -> {
				if (account.emailVerification() != EmailVerificationState.VERIFIED) {
					violations.add("institution must be email verified: " + account.key());
				}
				if (account.observer() != ObserverProfile.NONE) {
					violations.add("institution cannot be an observer: " + account.key());
				}
				if (account.listenerVisibility() != null || account.institutionState() == null) {
					violations.add("institution state is incompatible with role: " + account.key());
				} else {
					institutionCounts.computeIfAbsent(account.role(), ignored -> new EnumMap<>(InstitutionState.class))
							.merge(account.institutionState(), 1, Integer::sum);
				}
				if (account.role() == AccountRole.VENUE && account.institutionState() == InstitutionState.REJECTED) {
					violations.add("venue state supports only APPROVED or PENDING: " + account.key());
				}
				validateNonMusicianProfileFields(account, violations);
				validateInstitutionContactFields(account, phones, violations);
			}
		}
	}

	private void validatePersonalContactFields(Account account, List<String> violations) {
		if (account.contactPhone() != null) {
			violations.add("personal account cannot carry an institution phone: " + account.key());
		}
	}

	private void validateMusicianProfilePlan(Account account, List<String> violations) {
		MusicianProfilePlan plan = account.musicianProfile();
		if (plan == null) {
			violations.add("musician profile plan is required: " + account.key());
			return;
		}
		if (account.instrumentNames().size() > 4) {
			violations.add("musician may define at most four instruments: " + account.key());
		}
		Set<String> instruments = new HashSet<>();
		for (String instrument : account.instrumentNames()) {
			String canonical = canonicalText(instrument);
			if (canonical == null || !catalogInstruments.contains(canonical)) {
				violations.add("musician instrument is not catalog-backed: " + account.key() + "/" + instrument);
			} else if (!instruments.add(canonical)) {
				violations.add("duplicate musician instrument: " + account.key() + "/" + instrument);
			}
		}
		if (plan.populateInstruments() && account.instrumentNames().isEmpty()) {
			violations.add("populated musician instruments cannot be empty: " + account.key());
		}
		if (!plan.populateInstruments() && !account.instrumentNames().isEmpty()) {
			violations.add("omitted musician instruments must be empty: " + account.key());
		}
		if (account.observer() == ObserverProfile.INCOMPLETE) {
			if (plan.isComplete() || plan.populateInstruments() || plan.populateProfilePhoto()
					|| plan.populatePortfolio() || plan.populateSocialLinks()) {
				violations.add("INCOMPLETE observer must omit instrument, photo, portfolio and social fields: "
						+ account.key());
			}
		} else if (!plan.isComplete()) {
			violations.add("only the INCOMPLETE observer may have an incomplete musician profile: " + account.key());
		}
	}

	private void validateNonMusicianProfileFields(Account account, List<String> violations) {
		if (account.musicianProfile() != null || !account.instrumentNames().isEmpty()) {
			violations.add("non-musician cannot carry musician profile fields: " + account.key());
		}
	}

	private void validateLocation(Account account, List<String> violations) {
		Location location = account.location();
		if (location == null) {
			violations.add("catalog location is required for every account: " + account.key());
			return;
		}
		CatalogLocation catalogLocation = CatalogLocation.of(
				location.city(), location.district(), location.neighborhood());
		if (catalogLocation == null || !catalogLocations.contains(catalogLocation)) {
			violations.add("account location is not catalog-backed: " + account.key());
		}
		SceneExpectation scene = EXPECTED_SCENES.get(account.scene());
		if (scene != null && scene.cityFocus().stream().map(SimulationWorldManifestValidator::canonicalText)
				.noneMatch(city -> city.equals(canonicalText(location.city())))) {
			violations.add("account city is outside its scene: " + account.key());
		}
		if (account.role() == AccountRole.VENUE || account.role() == AccountRole.STUDIO) {
			validateText("addressLine", location.addressLine(), 10, 255, account.key(), violations);
		} else if (location.addressLine() != null) {
			violations.add("personal account location must not contain a street address: " + account.key());
		}
	}

	private void validateInstitutionContactFields(
			Account account,
			Set<String> phones,
			List<String> violations
	) {
		String phone = account.contactPhone();
		String normalizedPhone = phone == null ? null : phone.strip();
		if (normalizedPhone == null || !normalizedPhone.equals(phone)
				|| !PHONE_PATTERN.matcher(normalizedPhone).matches()) {
			violations.add("institution contact phone is invalid: " + account.key());
		} else if (!phones.add(normalizedPhone)) {
			violations.add("duplicate institution contact phone: " + normalizedPhone);
		}
	}

	private void assertInstitutionCounts(
			Map<AccountRole, Map<InstitutionState, Integer>> counts,
			List<String> violations
	) {
		Map<InstitutionState, Integer> venues = counts.getOrDefault(AccountRole.VENUE, Map.of());
		assertCount(venues, InstitutionState.APPROVED, 6, "APPROVED venue", violations);
		assertCount(venues, InstitutionState.PENDING, 2, "PENDING venue", violations);
		assertCount(venues, InstitutionState.REJECTED, 0, "REJECTED venue", violations);

		Map<InstitutionState, Integer> studios = counts.getOrDefault(AccountRole.STUDIO, Map.of());
		assertCount(studios, InstitutionState.APPROVED, 6, "APPROVED studio", violations);
		assertCount(studios, InstitutionState.PENDING, 1, "PENDING studio", violations);
		assertCount(studios, InstitutionState.REJECTED, 1, "REJECTED studio", violations);
	}

	private void validateBands(
			List<Band> bands,
			Map<String, Account> accountsByKey,
			List<String> violations
	) {
		if (bands.size() != 4) {
			violations.add("bands must contain exactly four entries");
		}
		Set<String> keys = new HashSet<>();
		Set<String> names = new HashSet<>();
		for (Band band : bands) {
			if (band == null) {
				violations.add("band entry must not be null");
				continue;
			}
			String key = canonicalKey(band.key());
			if (key == null || !KEY_PATTERN.matcher(key).matches() || !key.equals(band.key())) {
				violations.add("band key must be canonical lower-case ASCII: " + band.key());
			} else if (!keys.add(key)) {
				violations.add("duplicate band key: " + key);
			}
			validateText("band name", band.name(), 2, 100, band.key(), violations);
			String normalizedName = canonicalText(band.name());
			if (normalizedName != null && !names.add(normalizedName)) {
				violations.add("duplicate normalized band name: " + band.name());
			}
			validateText("band bio", band.bio(), 20, 500, band.key(), violations);
			validateText("band archetype", band.archetype(), 10, 180, band.key(), violations);
			if (band.scene() == null) {
				violations.add("band scene is required: " + band.key());
			}
			if (band.members().size() < 3 || band.members().size() > 8) {
				violations.add("band must contain 3-8 members: " + band.key());
			}

			Set<String> memberKeys = new HashSet<>();
			int founders = 0;
			int managers = 0;
			boolean ownerIsFounder = false;
			for (SimulationWorldManifest.BandMember member : band.members()) {
				if (member == null || member.accountKey() == null || member.role() == null) {
					violations.add("band member reference and role are required: " + band.key());
					continue;
				}
				if (!memberKeys.add(member.accountKey())) {
					violations.add("duplicate member in band " + band.key() + ": " + member.accountKey());
				}
				Account account = accountsByKey.get(member.accountKey());
				if (account == null || account.role() != AccountRole.MUSICIAN) {
					violations.add("band member must reference a musician: " + member.accountKey());
				} else {
					if (account.scene() != band.scene()) {
						violations.add("band member must belong to the same scene: " + member.accountKey());
					}
					if (account.emailVerification() != EmailVerificationState.VERIFIED) {
						violations.add("band member must be email verified: " + member.accountKey());
					}
					if (account.observer() != ObserverProfile.NONE) {
						violations.add("reserved observer cannot be a band member: " + member.accountKey());
					}
				}
				if (member.role() == BandMemberRole.FOUNDER) {
					founders++;
					ownerIsFounder = Objects.equals(band.ownerAccountKey(), member.accountKey());
				} else if (member.role() == BandMemberRole.MANAGER) {
					managers++;
				}
			}
			if (founders != 1 || !ownerIsFounder) {
				violations.add("band owner must be its only FOUNDER: " + band.key());
			}
			if (managers < 1) {
				violations.add("band must have at least one MANAGER: " + band.key());
			}
		}
	}

	private void validateActualSceneDistribution(
			SimulationWorldManifest manifest,
			List<String> violations
	) {
		Map<Scene, Map<AccountRole, Integer>> accountCounts = new EnumMap<>(Scene.class);
		for (Account account : manifest.accounts()) {
			if (account == null || account.scene() == null || account.role() == null) continue;
			accountCounts.computeIfAbsent(account.scene(), ignored -> new EnumMap<>(AccountRole.class))
					.merge(account.role(), 1, Integer::sum);
		}
		Map<Scene, Integer> bandCounts = new EnumMap<>(Scene.class);
		for (Band band : manifest.bands()) {
			if (band != null && band.scene() != null) bandCounts.merge(band.scene(), 1, Integer::sum);
		}
		for (Map.Entry<Scene, SceneExpectation> entry : EXPECTED_SCENES.entrySet()) {
			Map<AccountRole, Integer> actual = accountCounts.getOrDefault(entry.getKey(), Map.of());
			SceneExpectation expected = entry.getValue();
			if (actual.getOrDefault(AccountRole.MUSICIAN, 0) != expected.musicians()
					|| actual.getOrDefault(AccountRole.LISTENER, 0) != expected.listeners()
					|| actual.getOrDefault(AccountRole.VENUE, 0) != expected.venues()
					|| actual.getOrDefault(AccountRole.STUDIO, 0) != expected.studios()
					|| bandCounts.getOrDefault(entry.getKey(), 0) != expected.bands()) {
				violations.add("actual account/band distribution is invalid for scene: " + entry.getKey());
			}
		}
	}

	private static void validateText(
			String field,
			String value,
			int min,
			int max,
			String owner,
			List<String> violations
	) {
		String stripped = value == null ? null : value.strip();
		int length = stripped == null ? 0 : stripped.codePointCount(0, stripped.length());
		if (stripped == null || !stripped.equals(value) || length < min || length > max || containsControl(value)) {
			violations.add(field + " is invalid: " + owner);
		}
	}

	private static <E extends Enum<E>> void assertCount(
			Map<E, Integer> counts,
			E value,
			int expected,
			String label,
			List<String> violations
	) {
		if (counts.getOrDefault(value, 0) != expected) {
			violations.add(label + " count must be " + expected);
		}
	}

	private static boolean containsControl(String value) {
		return value != null && value.codePoints().anyMatch(Character::isISOControl);
	}

	private static String canonicalKey(String value) {
		return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
	}

	private static String canonicalEmail(String value) {
		return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
	}

	private static String canonicalText(String value) {
		return value == null ? null : Normalizer.normalize(value.strip(), Normalizer.Form.NFC)
				.toLowerCase(Locale.ROOT);
	}

	private static Set<CatalogLocation> loadLocationCatalog(ObjectMapper objectMapper, Resource resource) {
		Set<CatalogLocation> locations = new HashSet<>();
		try (InputStream input = resource.getInputStream()) {
			JsonNode cities = objectMapper.readTree(input);
			if (cities == null || !cities.isArray()) {
				throw new SimulationWorldManifestException("Location catalog root must be an array");
			}
			for (JsonNode city : cities) {
				String cityName = text(city, "name");
				JsonNode districts = city.get("districts");
				if (cityName == null || districts == null || !districts.isArray()) continue;
				for (JsonNode district : districts) {
					String districtName = text(district, "name");
					JsonNode neighborhoods = district.get("neighborhoods");
					if (districtName == null || neighborhoods == null || !neighborhoods.isArray()) continue;
					for (JsonNode neighborhood : neighborhoods) {
						if (neighborhood.isTextual()) {
							locations.add(CatalogLocation.of(cityName, districtName, neighborhood.textValue()));
						}
					}
				}
			}
		} catch (IOException exception) {
			throw new SimulationWorldManifestException(
					"Unable to read simulation location catalog: " + resource.getDescription(), exception);
		}
		if (locations.isEmpty()) {
			throw new SimulationWorldManifestException("Simulation location catalog is empty");
		}
		return Set.copyOf(locations);
	}

	private static Set<String> loadInstrumentCatalog(ObjectMapper objectMapper, Resource resource) {
		Set<String> instruments = new HashSet<>();
		try (InputStream input = resource.getInputStream()) {
			JsonNode values = objectMapper.readTree(input);
			if (values == null || !values.isArray()) {
				throw new SimulationWorldManifestException("Instrument catalog root must be an array");
			}
			for (JsonNode value : values) {
				if (value.isTextual() && !value.textValue().isBlank()) {
					instruments.add(canonicalText(value.textValue()));
				}
			}
		} catch (IOException exception) {
			throw new SimulationWorldManifestException(
					"Unable to read simulation instrument catalog: " + resource.getDescription(), exception);
		}
		if (instruments.isEmpty()) {
			throw new SimulationWorldManifestException("Simulation instrument catalog is empty");
		}
		return Set.copyOf(instruments);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return value != null && value.isTextual() ? value.textValue() : null;
	}

	private record SceneExpectation(
			List<String> cityFocus,
			int musicians,
			int listeners,
			int venues,
			int studios,
			int bands
	) {
		private boolean matches(ScenePlan plan) {
			Set<String> actualCities = new HashSet<>(plan.cityFocus().stream()
					.map(SimulationWorldManifestValidator::canonicalText).toList());
			Set<String> expectedCities = new HashSet<>(cityFocus.stream()
					.map(SimulationWorldManifestValidator::canonicalText).toList());
			return plan.cityFocus().size() == actualCities.size()
					&& actualCities.equals(expectedCities)
					&& plan.musicians() == musicians
					&& plan.listeners() == listeners
					&& plan.venues() == venues
					&& plan.studios() == studios
					&& plan.bands() == bands;
		}
	}

	private record CatalogLocation(String city, String district, String neighborhood) {
		private static CatalogLocation of(String city, String district, String neighborhood) {
			String normalizedCity = canonicalText(city);
			String normalizedDistrict = canonicalText(district);
			String normalizedNeighborhood = canonicalText(neighborhood);
			if (normalizedCity == null || normalizedCity.isBlank()
					|| normalizedDistrict == null || normalizedDistrict.isBlank()
					|| normalizedNeighborhood == null || normalizedNeighborhood.isBlank()) {
				return null;
			}
			return new CatalogLocation(normalizedCity, normalizedDistrict, normalizedNeighborhood);
		}
	}
}

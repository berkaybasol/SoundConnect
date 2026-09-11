package com.berkayb.soundconnect.tools.simulation.world;

import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ListenerVisibilityState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ObserverProfile;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Scene;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationWorldManifestTest {
	private static ObjectMapper objectMapper;
	private static SimulationWorldManifestLoader loader;
	private static ObjectNode source;
	private static String sourceJson;
	private static SimulationWorldManifest manifest;

	@BeforeAll
	static void loadCanonicalWorld() throws IOException {
		objectMapper = new ObjectMapper();
		loader = new SimulationWorldManifestLoader(objectMapper);
		ClassPathResource resource = new ClassPathResource(SimulationWorldManifestLoader.DEFAULT_RESOURCE);
		sourceJson = resource.getContentAsString(StandardCharsets.UTF_8);
		source = (ObjectNode) objectMapper.readTree(sourceJson);
		manifest = loader.loadDefault();
	}

	@Test
	void canonicalManifestHasTheExactWorldAndControlStateCounts() {
		assertThat(manifest.schemaVersion()).isEqualTo(1);
		assertThat(manifest.worldId()).isEqualTo("soundconnect-local-world-v1");
		assertThat(manifest.seed()).isPositive();
		assertThat(manifest.accounts()).hasSize(50);
		assertThat(manifest.bands()).hasSize(4);

		assertThat(countByRole()).containsExactlyInAnyOrderEntriesOf(Map.of(
				AccountRole.MUSICIAN, 24L,
				AccountRole.LISTENER, 10L,
				AccountRole.VENUE, 8L,
				AccountRole.STUDIO, 8L
		));
		assertThat(countAccounts(account -> account.listenerVisibility() == ListenerVisibilityState.STANDARD))
				.isEqualTo(7);
		assertThat(countAccounts(account -> account.listenerVisibility() == ListenerVisibilityState.GHOST))
				.isEqualTo(2);
		assertThat(countAccounts(account -> account.listenerVisibility() == ListenerVisibilityState.VISIBILITY_PENDING))
				.isEqualTo(1);
		assertThat(countAccounts(account -> account.role() == AccountRole.VENUE
				&& account.institutionState() == InstitutionState.APPROVED)).isEqualTo(6);
		assertThat(countAccounts(account -> account.role() == AccountRole.VENUE
				&& account.institutionState() == InstitutionState.PENDING)).isEqualTo(2);
		assertThat(countAccounts(account -> account.role() == AccountRole.STUDIO
				&& account.institutionState() == InstitutionState.APPROVED)).isEqualTo(6);
		assertThat(countAccounts(account -> account.role() == AccountRole.STUDIO
				&& account.institutionState() == InstitutionState.PENDING)).isEqualTo(1);
		assertThat(countAccounts(account -> account.role() == AccountRole.STUDIO
				&& account.institutionState() == InstitutionState.REJECTED)).isEqualTo(1);
		assertThat(countAccounts(account -> account.emailVerification() == EmailVerificationState.UNVERIFIED))
				.isEqualTo(1);
		assertThat(manifest.accounts().stream()
				.filter(account -> account.role() == AccountRole.VENUE || account.role() == AccountRole.STUDIO)
				.map(Account::contactPhone))
				.allMatch(phone -> phone.matches("0[0-9]{10}"));
	}

	@Test
	void observersAndProfileCompletionPlansAreExplicitAndOrthogonalToColdStart() {
		Map<ObserverProfile, Account> observers = manifest.accounts().stream()
				.filter(account -> account.observer() != ObserverProfile.NONE)
				.collect(Collectors.toMap(Account::observer, account -> account));

		assertThat(observers).containsOnlyKeys(
				ObserverProfile.COMPLETE,
				ObserverProfile.INCOMPLETE,
				ObserverProfile.COLD_START
		);
		assertThat(observers.values()).allMatch(account -> account.role() == AccountRole.MUSICIAN);
		assertThat(observers.get(ObserverProfile.COMPLETE).musicianProfile().isComplete()).isTrue();
		assertThat(observers.get(ObserverProfile.COLD_START).musicianProfile().isComplete()).isTrue();
		assertThat(observers.get(ObserverProfile.COLD_START).instrumentNames()).isNotEmpty();
		assertThat(observers.get(ObserverProfile.INCOMPLETE).musicianProfile().isComplete()).isFalse();
		assertThat(observers.get(ObserverProfile.INCOMPLETE).instrumentNames()).isEmpty();
	}

	@Test
	void actualAccountsAndBandsMatchEveryDeclaredSceneRow() {
		for (SimulationWorldManifest.ScenePlan plan : manifest.scenes()) {
			assertThat(countInScene(plan.scene(), AccountRole.MUSICIAN)).isEqualTo(plan.musicians());
			assertThat(countInScene(plan.scene(), AccountRole.LISTENER)).isEqualTo(plan.listeners());
			assertThat(countInScene(plan.scene(), AccountRole.VENUE)).isEqualTo(plan.venues());
			assertThat(countInScene(plan.scene(), AccountRole.STUDIO)).isEqualTo(plan.studios());
			assertThat(manifest.bands().stream().filter(band -> band.scene() == plan.scene()).count())
					.isEqualTo(plan.bands());
		}
	}

	@Test
	void identitiesArePersistentNonDeliverableAndContainNoPasswordMaterial() {
		assertThat(manifest.accounts().stream().map(Account::username)).doesNotHaveDuplicates();
		assertThat(manifest.accounts().stream().map(Account::email)).doesNotHaveDuplicates()
				.allMatch(email -> email.endsWith("@soundconnect.invalid"));
		assertThat(sourceJson.toLowerCase()).doesNotContain("\"password\"");
		assertThat(source.findValues("password")).isEmpty();
	}

	@Test
	void everyLocationAndMusicianInstrumentWasAcceptedFromTheCheckedInCatalogs() {
		assertThat(manifest.accounts()).allMatch(account -> account.location() != null);
		assertThat(manifest.accounts().stream()
				.filter(account -> account.role() == AccountRole.MUSICIAN)
				.filter(account -> account.observer() != ObserverProfile.INCOMPLETE))
				.allMatch(account -> !account.instrumentNames().isEmpty());
		assertThat(manifest.accounts().stream()
				.filter(account -> account.role() != AccountRole.MUSICIAN))
				.allMatch(account -> account.instrumentNames().isEmpty());
	}

	@Test
	void manifestCollectionsAreDefensivelyImmutable() {
		assertThatThrownBy(() -> manifest.accounts().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> manifest.scenes().getFirst().cityFocus().add("Adana"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> manifest.accounts().getFirst().instrumentNames().add("Trompet"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> manifest.bands().getFirst().members().clear())
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@TestFactory
	Stream<DynamicTest> validatorRejectsCorruptedWorldsBeforeMaterialization() {
		List<Corruption> corruptions = List.of(
				new Corruption("account cap", root -> accounts(root).remove(49),
						"accounts must contain exactly 50 entries"),
				new Corruption("duplicate username", root -> account(root, 1).put(
						"username", account(root, 0).get("username").textValue()), "duplicate normalized username"),
				new Corruption("duplicate email", root -> account(root, 1).put(
						"email", account(root, 0).get("email").textValue()), "duplicate normalized email"),
				new Corruption("deliverable email", root -> account(root, 0).put(
						"email", "deniz@example.com"), "@soundconnect.invalid"),
				new Corruption("extra unverified account", root -> account(root, 1).put(
						"emailVerification", "UNVERIFIED"), "UNVERIFIED account count must be 1"),
				new Corruption("unverified institution", root -> account(root, 34).put(
						"emailVerification", "UNVERIFIED"), "institution must be email verified"),
				new Corruption("observer role mismatch", root -> account(root, 24).put(
						"observer", "COMPLETE"), "only musicians may be reserved observers"),
				new Corruption("observer count", root -> account(root, 1).put(
						"observer", "COMPLETE"), "COMPLETE observer count must be 1"),
				new Corruption("listener state count", root -> account(root, 24).put(
						"listenerVisibility", "GHOST"), "STANDARD listener count must be 7"),
				new Corruption("venue state count", root -> account(root, 34).put(
						"institutionState", "PENDING"), "APPROVED venue count must be 6"),
				new Corruption("scene row", root -> ((ObjectNode) scenes(root).get(0)).put(
						"musicians", 9), "scene distribution does not match specification"),
				new Corruption("actual scene distribution", root -> account(root, 0).put(
						"scene", "ANKARA_POP_ELECTRONIC"), "actual account/band distribution is invalid"),
				new Corruption("content target", root -> ((ObjectNode) root.get("contentTargets")).put(
						"tracks", 33), "contentTargets must match"),
				new Corruption("unknown location", root -> ((ObjectNode) account(root, 0).get("location")).put(
						"neighborhood", "Kurgu Mahallesi"), "account location is not catalog-backed"),
				new Corruption("cross-scene location", root -> ((ObjectNode) account(root, 0).get("location")).put(
						"city", "Ankara"), "account city is outside its scene"),
				new Corruption("unknown instrument", root -> ((ArrayNode) account(root, 0).get("instrumentNames")).set(
						0, objectMapper.getNodeFactory().textNode("Hayali Gitar")), "instrument is not catalog-backed"),
				new Corruption("non-canonical institution phone", root -> account(root, 34).put(
						"contactPhone", "+90 555 000 10 01"), "institution contact phone is invalid"),
				new Corruption("missing populated instruments", root -> ((ArrayNode) account(root, 0).get(
						"instrumentNames")).removeAll(), "populated musician instruments cannot be empty"),
				new Corruption("incomplete plan drift", root -> ((ObjectNode) account(root, 10).get(
						"musicianProfile")).put("populatePortfolio", true), "INCOMPLETE observer must omit"),
				new Corruption("non-musician instruments", root -> account(root, 24).putArray(
						"instrumentNames").add("Piyano"), "non-musician cannot carry musician profile fields"),
				new Corruption("unknown band owner", root -> band(root, 0).put(
						"ownerAccountKey", "listener-ist-01"), "band owner must be its only FOUNDER"),
				new Corruption("non-musician band member", root -> ((ObjectNode) ((ArrayNode) band(root, 0)
						.get("members")).get(2)).put("accountKey", "listener-ist-01"),
						"band member must reference a musician"),
				new Corruption("unverified band member", root -> ((ObjectNode) ((ArrayNode) band(root, 0)
						.get("members")).get(2)).put("accountKey", "musician-ist-10"),
						"band member must be email verified"),
				new Corruption("duplicate band member", root -> ((ObjectNode) ((ArrayNode) band(root, 0)
						.get("members")).get(2)).put("accountKey", "musician-ist-02"), "duplicate member in band")
		);

		return corruptions.stream().map(corruption -> DynamicTest.dynamicTest(corruption.name(), () -> {
			ObjectNode mutated = source.deepCopy();
			corruption.mutation().accept(mutated);
			assertThatThrownBy(() -> load(mutated))
					.isInstanceOf(SimulationWorldManifestException.class)
					.hasMessageContaining(corruption.expectedMessage());
		}));
	}

	@Test
	void strictLoaderRejectsUnknownPasswordFieldsDuplicateKeysAndTrailingDocuments() throws IOException {
		ObjectNode password = source.deepCopy();
		account(password, 0).put("password", "must-never-be-accepted");
		assertThatThrownBy(() -> load(password))
				.isInstanceOf(SimulationWorldManifestException.class)
				.hasMessageContaining("Unable to load simulation world manifest");

		String duplicateRootKey = sourceJson.replaceFirst("\\{", "{\"schemaVersion\":1,");
		assertThatThrownBy(() -> loader.load(bytes(duplicateRootKey)))
				.isInstanceOf(SimulationWorldManifestException.class)
				.hasMessageContaining("Unable to load simulation world manifest");

		assertThatThrownBy(() -> loader.load(bytes(sourceJson + "{}")))
				.isInstanceOf(SimulationWorldManifestException.class)
				.hasMessageContaining("Unable to load simulation world manifest");
	}

	@Test
	void validatorFailsClosedWhenEitherCatalogCannotBeLoaded() {
		assertThatThrownBy(() -> new SimulationWorldManifestValidator(
				objectMapper,
				bytes("[]"),
				new ClassPathResource(SimulationWorldManifestValidator.INSTRUMENT_CATALOG_RESOURCE)))
				.isInstanceOf(SimulationWorldManifestException.class)
				.hasMessageContaining("location catalog is empty");

		assertThatThrownBy(() -> new SimulationWorldManifestValidator(
				objectMapper,
				new ClassPathResource(SimulationWorldManifestValidator.LOCATION_CATALOG_RESOURCE),
				bytes("[]")))
				.isInstanceOf(SimulationWorldManifestException.class)
				.hasMessageContaining("instrument catalog is empty");
	}

	private static Map<AccountRole, Long> countByRole() {
		return manifest.accounts().stream().collect(Collectors.groupingBy(Account::role, Collectors.counting()));
	}

	private static long countAccounts(java.util.function.Predicate<Account> predicate) {
		return manifest.accounts().stream().filter(predicate).count();
	}

	private static long countInScene(Scene scene, AccountRole role) {
		return manifest.accounts().stream()
				.filter(account -> account.scene() == scene && account.role() == role)
				.count();
	}

	private static void load(JsonNode value) {
		try {
			loader.load(new ByteArrayResource(objectMapper.writeValueAsBytes(value)));
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
	}

	private static ByteArrayResource bytes(String value) {
		return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
	}

	private static ArrayNode accounts(ObjectNode root) {
		return (ArrayNode) root.get("accounts");
	}

	private static ObjectNode account(ObjectNode root, int index) {
		return (ObjectNode) accounts(root).get(index);
	}

	private static ArrayNode scenes(ObjectNode root) {
		return (ArrayNode) root.get("scenes");
	}

	private static ObjectNode band(ObjectNode root, int index) {
		return (ObjectNode) ((ArrayNode) root.get("bands")).get(index);
	}

	private record Corruption(String name, Consumer<ObjectNode> mutation, String expectedMessage) {
	}
}

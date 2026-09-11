package com.berkayb.soundconnect.tools.simulation.seed.content.opportunity;

import com.berkayb.soundconnect.modules.collab.enums.CollabBranch;
import com.berkayb.soundconnect.modules.collab.enums.CollabCadence;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.CollabFinalState;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.CollabStory;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventFinalState;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventPerformerStory;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventStory;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.Publisher;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Scene;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Pure, repeatable story planner for Collab and venue-owned Events. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationOpportunityPlanner {

	private static final List<CollabWantedType> WANTED_ROTATION = List.of(
			CollabWantedType.MUSICIAN,
			CollabWantedType.MUSICIAN,
			CollabWantedType.BAND,
			CollabWantedType.MUSICIAN,
			CollabWantedType.VENUE,
			CollabWantedType.STUDIO
	);

	private static final List<String> COLLAB_MOTIFS = List.of(
			"Gece Provası", "Yeni Repertuvar", "Canlı Kayıt", "Festival Hazırlığı",
			"Akustik Oturum", "Şehirlerarası Set", "Bağımsız Sahne", "Stüdyo Buluşması",
			"Ritim Laboratuvarı", "Vokal Arayışı", "Analog Dokunuş", "Yeni Dönem",
			"Kulüp Gecesi", "Açık Hava", "Demo Haftası", "Turne Ekibi",
			"İlk Tekli", "Gece Seansı", "Doğaçlama Akşamı", "Kayıt Öncesi",
			"Sahne Arkası", "Yaz Programı", "Kış Sezonu", "Mahalle Konseri",
			"Elektronik Dokular", "Gitar Gecesi", "Caz Buluşması", "Rock Maratonu",
			"Pop Atölyesi", "Funk Provası", "Metal Seansı", "Prodüksiyon Günü",
			"Ritim Ekibi", "Yeni Kadro", "Hızlı İhtiyaç", "Sessiz Prova"
	);

	private static final List<String> EVENT_TITLES = List.of(
			"Şehrin Altında", "Geceye Açılan Sahne", "Analog Rüyalar", "Kıyı Sesleri",
			"Yeni Dalga Buluşması", "Ritim ve Işık", "Bağımsızlar Gecesi", "Akustik Pazar",
			"Elektronik Nabız", "Plak Arası", "Cazın Yeni Yüzü", "Karanlık Tonlar",
			"Sokaktan Sahneye", "İki Şehir Bir Ses", "Gece Vardiyası", "Canlı Oda",
			"Funk Hattı", "Gitarın İzinde", "Synth Sonrası", "Mahalle Sesi",
			"Yüksek Ses Düşük Işık", "Yeni Şarkılar Kulübü", "Doğaçlama Atlası",
			"Dünden Kalan Melodiler", "Arşiv Gecesi", "Eski Sahne Yeni Ses",
			"Konuk Sanatçı Seansı", "Grup Buluşması", "Birlikte Söyle", "Silinen Prova Etkinliği"
	);

	private static final Map<Scene, List<String>> GENRES = genres();

	public SimulationOpportunityPlan plan(
			SimulationWorldManifest manifest,
			SimulationOpportunityWindow window
	) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(window, "window");
		Objects.requireNonNull(manifest.contentTargets(), "manifest content targets");

		List<Account> musicians = eligibleMusicians(manifest);
		List<Account> venues = approvedInstitutions(manifest, AccountRole.VENUE);
		List<Account> studios = approvedInstitutions(manifest, AccountRole.STUDIO);
		if (musicians.isEmpty() || venues.isEmpty() || studios.isEmpty() || manifest.bands().isEmpty()) {
			throw new IllegalStateException("Opportunity planning requires active actors of every supported type");
		}

		List<PublisherCandidate> publishers = publisherRotation(manifest, musicians, venues, studios);
		List<CollabStory> collabs = collabStories(manifest, window, publishers, musicians);
		List<EventStory> events = eventStories(manifest, window, venues, musicians);
		return new SimulationOpportunityPlan(collabs, events);
	}

	private List<CollabStory> collabStories(
			SimulationWorldManifest manifest,
			SimulationOpportunityWindow window,
			List<PublisherCandidate> publishers,
			List<Account> musicians
	) {
		int count = manifest.contentTargets().collabListings();
		if (count < 7 || count > COLLAB_MOTIFS.size()) {
			throw new IllegalStateException("Simulation Collab target is outside the supported story range");
		}
		List<CollabStory> result = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			PublisherCandidate publisher = publishers.get(index % publishers.size());
			CollabWantedType wanted = WANTED_ROTATION.get(index % WANTED_ROTATION.size());
			List<Account> sceneMusicians = musicians.stream()
					.filter(account -> account.scene() == publisher.scene())
					.toList();
			if (sceneMusicians.isEmpty()) sceneMusicians = musicians;
			Account specialtySource = sceneMusicians.get((index * 3) % sceneMusicians.size());
			boolean branchSpecialty = wanted == CollabWantedType.MUSICIAN && index % 11 == 0;
			String instrument = wanted == CollabWantedType.MUSICIAN && !branchSpecialty
					? specialtySource.instrumentNames().get(index % specialtySource.instrumentNames().size())
					: null;
			CollabBranch branch = branchSpecialty
					? (index % 22 == 0 ? CollabBranch.DJ : CollabBranch.SOUND_ENGINEER)
					: null;
			CollabCadence cadence = index % 6 == 0 ? CollabCadence.EXTRA : CollabCadence.REGULAR;
			Long fee = null;
			if (cadence == CollabCadence.EXTRA) {
				fee = 75_000L + index * 2_500L;
			} else if (publisher.profileType() == ProfileType.VENUE && index % 2 == 0) {
				fee = 120_000L + index * 1_000L;
			}
			String targetLabel = targetLabel(wanted, instrument, branch);
			List<String> genres = GENRES.get(publisher.scene());
			String city = publisher.account().location().city();
			String title = targetLabel + " aranıyor: " + COLLAB_MOTIFS.get(index);
			String description = publisher.account().displayName() + ", " + city
					+ " sahnesinde " + genres.getFirst() + " ağırlıklı "
					+ COLLAB_MOTIFS.get(index).toLowerCase(java.util.Locale.forLanguageTag("tr-TR"))
					+ " için iletişimi güçlü ve takvime sadık bir ekip arkadaşı arıyor.";
			CollabFinalState finalState = index >= count - 2
					? CollabFinalState.DRAFT
					: index >= count - 6 ? CollabFinalState.CLOSED : CollabFinalState.OPEN;
			result.add(new CollabStory(
					key("collab", index),
					stableUuid(manifest.worldId(), "collab", index),
					new Publisher(publisher.profileType(), publisher.sourceKey(), publisher.representativeKey()),
					publisher.account().key(),
					cadence,
					wanted,
					instrument,
					branch,
					null,
					title,
					description,
					genres,
					cadence == CollabCadence.EXTRA
							? window.collabAnchor().plus(Duration.ofDays(2L + index % 5L))
									.plus(Duration.ofHours(index % 12L))
							: null,
					fee,
					fee == null ? null : "TRY",
					finalState
			));
		}
		return List.copyOf(result);
	}

	private List<EventStory> eventStories(
			SimulationWorldManifest manifest,
			SimulationOpportunityWindow window,
			List<Account> venues,
			List<Account> musicians
	) {
		int count = manifest.contentTargets().events();
		if (count < 10 || count > EVENT_TITLES.size()) {
			throw new IllegalStateException("Simulation Event target is outside the supported story range");
		}
		Map<Scene, List<Account>> musiciansByScene = groupAccountsByScene(musicians);
		Map<Scene, List<SimulationWorldManifest.Band>> bandsByScene = groupBandsByScene(manifest);
		List<EventStory> result = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			Account venue = venues.get(index % venues.size());
			EventPerformerStory performerStory = eventPerformerStory(index, count);
			Account musician = pickMusician(venue.scene(), index, musiciansByScene, musicians);
			SimulationWorldManifest.Band band = pickBand(
					venue.scene(), index, bandsByScene, manifest.bands());
			String performerKey = null;
			String performerActor = null;
			String manualName = null;
			if (performerStory == EventPerformerStory.MANUAL) {
				manualName = musician.displayName() + " Akustik Set";
			} else if (performerStory == EventPerformerStory.MUSICIAN_PENDING
					|| performerStory == EventPerformerStory.MUSICIAN_ACCEPTED) {
				performerKey = musician.key();
				performerActor = musician.key();
			} else if (performerStory == EventPerformerStory.BAND_PENDING
					|| performerStory == EventPerformerStory.BAND_ACCEPTED) {
				performerKey = band.key();
				performerActor = band.ownerAccountKey();
			}

			boolean past = index >= count - 7 && index <= count - 5;
			long dateOffset = past ? -List.of(7L, 30L, 90L).get(index - (count - 7))
					: 2L + index % 25L;
			LocalTime starts = List.of(
					LocalTime.of(18, 30), LocalTime.of(19, 30), LocalTime.of(20, 0),
					LocalTime.of(20, 30), LocalTime.of(21, 0)).get(index % 5);
			List<String> genres = GENRES.get(venue.scene());
			String description = venue.displayName() + " tarafından hazırlanan "
					+ genres.getFirst() + " odaklı bir buluşma. Kapılar başlangıçtan bir saat önce açılır; "
					+ "program ve sahne akışı SoundConnect üzerinden güncellenir.";
			result.add(new EventStory(
					key("event", index),
					venue.key(),
					EVENT_TITLES.get(index),
					description,
					window.eventAnchor().plusDays(dateOffset),
					starts,
					starts.plusHours(2),
					performerStory,
					performerKey,
					performerActor,
					manualName,
					performerStory == EventPerformerStory.MUSICIAN_ACCEPTED
							|| performerStory == EventPerformerStory.BAND_ACCEPTED,
					index == count - 1 ? EventFinalState.DELETED : EventFinalState.VISIBLE
			));
		}
		return List.copyOf(result);
	}

	private EventPerformerStory eventPerformerStory(int index, int count) {
		if (index == count - 4) return EventPerformerStory.MUSICIAN_PENDING;
		if (index == count - 3) return EventPerformerStory.BAND_PENDING;
		if (index == count - 2) return EventPerformerStory.MUSICIAN_ACCEPTED;
		if (index == count - 1) return EventPerformerStory.NONE;
		return index % 4 == 0 ? EventPerformerStory.MANUAL : EventPerformerStory.NONE;
	}

	private List<PublisherCandidate> publisherRotation(
			SimulationWorldManifest manifest,
			List<Account> musicians,
			List<Account> venues,
			List<Account> studios
	) {
		Map<String, Account> accounts = manifest.accounts().stream()
				.collect(java.util.stream.Collectors.toMap(Account::key, value -> value));
		List<PublisherCandidate> result = new ArrayList<>();
		int rounds = Math.max(Math.max(musicians.size(), venues.size()),
				Math.max(studios.size(), manifest.bands().size()));
		for (int index = 0; index < rounds; index++) {
			if (index < musicians.size()) {
				Account account = musicians.get(index);
				result.add(new PublisherCandidate(ProfileType.MUSICIAN, account.key(), account.key(), account));
			}
			if (index < venues.size()) {
				Account account = venues.get(index);
				result.add(new PublisherCandidate(ProfileType.VENUE, account.key(), account.key(), account));
			}
			if (index < studios.size()) {
				Account account = studios.get(index);
				result.add(new PublisherCandidate(ProfileType.STUDIO, account.key(), account.key(), account));
			}
			if (index < manifest.bands().size()) {
				SimulationWorldManifest.Band band = manifest.bands().get(index);
				Account representative = accounts.get(band.ownerAccountKey());
				if (representative == null
						|| representative.role() != AccountRole.MUSICIAN
						|| representative.emailVerification() != EmailVerificationState.VERIFIED) {
					throw new IllegalStateException("Band publisher owner is absent: " + band.key());
				}
				result.add(new PublisherCandidate(
						ProfileType.BAND, band.key(), band.ownerAccountKey(), representative));
			}
		}
		return List.copyOf(result);
	}

	private List<Account> eligibleMusicians(SimulationWorldManifest manifest) {
		return manifest.accounts().stream()
				.filter(account -> account.role() == AccountRole.MUSICIAN)
				.filter(account -> account.emailVerification() == EmailVerificationState.VERIFIED)
				.filter(account -> !account.instrumentNames().isEmpty())
				.sorted(Comparator.comparing(Account::key))
				.toList();
	}

	private List<Account> approvedInstitutions(
			SimulationWorldManifest manifest,
			AccountRole role
	) {
		return manifest.accounts().stream()
				.filter(account -> account.role() == role)
				.filter(account -> account.emailVerification() == EmailVerificationState.VERIFIED)
				.filter(account -> account.institutionState() == InstitutionState.APPROVED)
				.sorted(Comparator.comparing(Account::key))
				.toList();
	}

	private Map<Scene, List<Account>> groupAccountsByScene(List<Account> accounts) {
		Map<Scene, List<Account>> result = new EnumMap<>(Scene.class);
		for (Scene scene : Scene.values()) {
			result.put(scene, accounts.stream().filter(value -> value.scene() == scene).toList());
		}
		return result;
	}

	private Map<Scene, List<SimulationWorldManifest.Band>> groupBandsByScene(
			SimulationWorldManifest manifest
	) {
		Map<Scene, List<SimulationWorldManifest.Band>> result = new EnumMap<>(Scene.class);
		for (Scene scene : Scene.values()) {
			result.put(scene, manifest.bands().stream().filter(value -> value.scene() == scene).toList());
		}
		return result;
	}

	private Account pickMusician(
			Scene scene,
			int index,
			Map<Scene, List<Account>> byScene,
			List<Account> fallback
	) {
		List<Account> candidates = byScene.getOrDefault(scene, List.of());
		if (candidates.isEmpty()) candidates = fallback;
		return candidates.get(index % candidates.size());
	}

	private SimulationWorldManifest.Band pickBand(
			Scene scene,
			int index,
			Map<Scene, List<SimulationWorldManifest.Band>> byScene,
			List<SimulationWorldManifest.Band> fallback
	) {
		List<SimulationWorldManifest.Band> candidates = byScene.getOrDefault(scene, List.of());
		if (candidates.isEmpty()) candidates = fallback;
		return candidates.get(index % candidates.size());
	}

	private String targetLabel(CollabWantedType wanted, String instrument, CollabBranch branch) {
		return switch (wanted) {
			case MUSICIAN -> instrument != null ? instrument : switch (branch) {
				case DJ -> "DJ";
				case SOUND_ENGINEER -> "Ses mühendisi";
				case VOCAL -> "Vokalist";
				case PRODUCER -> "Prodüktör";
				case OTHER -> "Uzman";
				case null -> throw new IllegalStateException("Musician Collab requires a specialty");
			};
			case BAND -> "Grup";
			case VENUE -> "Mekân";
			case STUDIO -> "Stüdyo";
		};
	}

	private static UUID stableUuid(String worldId, String kind, int index) {
		String source = worldId + "|" + kind + "|" + String.format(java.util.Locale.ROOT, "%03d", index + 1);
		return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
	}

	private static String key(String type, int index) {
		return type + "-" + String.format(java.util.Locale.ROOT, "%02d", index + 1);
	}

	private static Map<Scene, List<String>> genres() {
		Map<Scene, List<String>> values = new EnumMap<>(Scene.class);
		values.put(Scene.ISTANBUL_ALTERNATIVE_ROCK, List.of("Alternatif Rock", "Indie"));
		values.put(Scene.ANKARA_POP_ELECTRONIC, List.of("Elektronik", "Pop"));
		values.put(Scene.IZMIR_JAZZ_FUNK, List.of("Funk", "Jazz"));
		values.put(Scene.BURSA_METAL_ACOUSTIC, List.of("Akustik", "Metal"));
		values.put(Scene.ANTALYA_ESKISEHIR_BRIDGES, List.of("Alternatif", "Elektronik"));
		return Map.copyOf(values);
	}

	private record PublisherCandidate(
			ProfileType profileType,
			String sourceKey,
			String representativeKey,
			Account account
	) {
		private Scene scene() {
			return account.scene();
		}
	}
}

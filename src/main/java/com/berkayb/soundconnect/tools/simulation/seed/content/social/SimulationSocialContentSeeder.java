package com.berkayb.soundconnect.tools.simulation.seed.content.social;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.event.audience.EventAudienceService;
import com.berkayb.soundconnect.modules.event.audience.EventIntent;
import com.berkayb.soundconnect.modules.event.audience.EventIntentUpdate;
import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.overthinking.profileshare.OverthinkingProfileShareService;
import com.berkayb.soundconnect.modules.overthinking.profileshare.OverthinkingProfileShareUpdate;
import com.berkayb.soundconnect.modules.overthinking.service.OverthinkingPostCommandService;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupParticipantDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.profileshare.TableGroupProfileShareService;
import com.berkayb.soundconnect.modules.tablegroup.profileshare.TableGroupProfileShareUpdate;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventFinalState;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventStory;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunitySeedResult;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationPreflightResult;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationResolvedLocation;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ListenerVisibilityState;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Seeds listener-authored feed content exclusively through production application services. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationSocialContentSeeder {

	private static final String PHASE_OVERTHINKING = "social-overthinking";
	private static final String PHASE_TABLE_GROUP = "social-table-group";
	private static final String PHASE_EVENT = "social-event-publication";
	private static final ZoneId PRODUCT_ZONE = ZoneId.of("Europe/Istanbul");
	private static final int TABLE_OWNER_COUNT = 3;
	private static final int TABLE_CAPACITY = 6;
	private static final int TABLE_AGE_MIN = 21;
	private static final int TABLE_AGE_MAX = 45;
	private static final List<String> TABLE_VENUE_NAMES = List.of("Plak Arası", "Canlı Oda", "Kıyı Buluşması");
	private static final List<String> TABLE_GENDER_PREFERENCES =
			List.of("FEMALE", "MALE", "OTHER", "FEMALE", "MALE", "OTHER");
	private static final String TABLE_SHARE_NOTE =
			"Bu masada yeni müzikler ve yaklaşan etkinlikler üzerine buluşuyoruz.";
	private static final List<SimulationWorldManifest.Scene> TABLE_OWNER_SCENES = List.of(
			SimulationWorldManifest.Scene.ISTANBUL_ALTERNATIVE_ROCK,
			SimulationWorldManifest.Scene.ANKARA_POP_ELECTRONIC,
			SimulationWorldManifest.Scene.IZMIR_JAZZ_FUNK);

	private static final List<String> OVERTHINKING_TITLES = List.of(
			"Bir şarkının ilk on saniyesi", "Konser sonrası sessizlik", "Kulaklıkta kalan detay",
			"Canlı kayıt neden başka hissettiriyor?", "Bir nakaratın peşinde", "Gece yürüyüşü albümleri",
			"Eski bir kayda yeniden dönmek", "Sahnede küçük bir an", "Yeni müzik keşfetme ritüeli",
			"Bas yürüyüşünün hikâyesi", "Şehir değişince playlist de değişiyor", "İlk dinleyiş mi üçüncü dinleyiş mi?",
			"Davulun girdiği o an", "Bir mekânın ses hafızası", "Doğaçlamanın bıraktığı iz",
			"Kapanış şarkısı meselesi", "Bir demo ne zaman tamamdır?", "Bugünün tekrar tuşu"
	);

	private static final List<String> OVERTHINKING_BODIES = List.of(
			"Bazı parçalar daha söz başlamadan dünyasını kuruyor. Son günlerde giriş düzenlemelerine özellikle takılıyorum.",
			"İyi bir konser bittikten sonra eve dönerken duyulan şehir sesi bile setin devamı gibi geliyor.",
			"Defalarca dinlediğim kayıtta ilk kez arka plandaki küçük gitar katmanını fark ettim; şarkı yeniden açıldı.",
			"Kusursuz olmayan nefesler ve oda sesi bazen stüdyo temizliğinden daha fazla şey anlatıyor.",
			"Gün boyu aklımda kalan melodinin hangi şarkıdan olduğunu bulamadım; belki de henüz yazılmamıştır.",
			"Gece yürürken tempo seçimi değişiyor. Şehrin ritmine uyan albümler ayrı bir kategori olmalı.",
			"Yıllar önce sevmediğim bir parçanın bugün tam yerine oturması, dinleyenin de değiştiğini hatırlatıyor.",
			"Sanatçının seyirciyle göz göze gelip gülümsediği bir saniye, bütün prodüksiyondan daha kalıcı olabiliyor.",
			"Algoritmadan çıkan tek parçayla yetinmeyip albümün tamamına gitmek hâlâ en sevdiğim keşif biçimi.",
			"Bir parçayı ileri taşıyan şey bazen en yüksek ses değil, basın doğru anda geri çekilmesi oluyor.",
			"İstanbul'da hızlı, Ankara'da daha elektronik, İzmir'de daha ferah listeler açtığımı yeni fark ettim.",
			"Bazı şarkılar hemen vuruyor, bazıları üçüncü dinleyişte kapıyı açıyor. İkinciler daha uzun kalıyor sanki.",
			"Ritmin gecikerek girdiği düzenlemelerde oluşan beklentiye bayılıyorum; boşluk da enstrüman gibi çalışıyor.",
			"Aynı grup farklı mekânlarda bambaşka duyuluyor. Duvarların da konser kadrosuna yazılması gerekebilir.",
			"İyi bir doğaçlama tekrar edilemediği için güzel; o ana yetişmiş olmak küçük bir ayrıcalık gibi.",
			"Finalde herkesin bildiği şarkı mı, yeni ve sakin bir parça mı? Eve dönüş hissini tamamen değiştiriyor.",
			"Demo kusurlarını kaybedince bazen fikrin heyecanını da kaybediyor. Bitirmek ile parlatmak arasındaki çizgi ince.",
			"Bugün bir parçayı üst üste dinledim. Her tekrarında başka bir enstrüman öne çıktı; iyi düzenleme tam da bu galiba."
	);

	private static final List<String> TABLE_DESCRIPTIONS = List.of(
			"Yeni çıkan bağımsız albümleri konuşup yaklaşan konserler için küçük bir keşif listesi hazırlıyoruz.",
			"Canlı performans kayıtları, sevdiğimiz sahneler ve son dönemde tekrar dinlediğimiz parçalar üzerine sohbet ediyoruz.",
			"Farklı şehirlerin müzik kültürünü ve kaçırılmaması gereken küçük etkinlikleri birlikte keşfediyoruz."
	);

	private static final List<String> EVENT_NOTES = List.of(
			"Bu programı bir süredir bekliyordum; aynı gece orada olacaklarla karşılaşmak güzel olur.",
			"Mekânın önceki etkinliği çok iyiydi, bu programı da takvime ekledim.",
			"Kadroyu ve sahne akışını merak ediyorum. Gitmeyi düşünen varsa haberleşelim.",
			"Yeni isimler keşfetmek için güzel bir geceye benziyor.",
			"Canlı dinlemek istediğim parçalardan birkaçını bu programda yakalayabilirim."
	);

	private final SimulationRuntimeGuard runtimeGuard;
	private final OverthinkingPostCommandService overthinkingPosts;
	private final OverthinkingProfileShareService overthinkingShares;
	private final TableGroupService tableGroups;
	private final TableGroupProfileShareService tableGroupShares;
	private final EventAudienceService eventAudience;
	private final Validator validator;
	private final Clock clock;
	private final SimulationProperties properties;
	private final SimulationSocialContentCheckpointStore checkpoints;
	private final SimulationSocialContentVerifier verifier;

	public SimulationSocialContentSeeder(
			SimulationRuntimeGuard runtimeGuard,
			OverthinkingPostCommandService overthinkingPosts,
			OverthinkingProfileShareService overthinkingShares,
			TableGroupService tableGroups,
			TableGroupProfileShareService tableGroupShares,
			EventAudienceService eventAudience,
			Validator validator,
			Clock clock,
			SimulationProperties properties,
			SimulationSocialContentCheckpointStore checkpoints,
			SimulationSocialContentVerifier verifier
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.overthinkingPosts = Objects.requireNonNull(overthinkingPosts, "overthinkingPosts");
		this.overthinkingShares = Objects.requireNonNull(overthinkingShares, "overthinkingShares");
		this.tableGroups = Objects.requireNonNull(tableGroups, "tableGroups");
		this.tableGroupShares = Objects.requireNonNull(tableGroupShares, "tableGroupShares");
		this.eventAudience = Objects.requireNonNull(eventAudience, "eventAudience");
		this.validator = Objects.requireNonNull(validator, "validator");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
		this.verifier = Objects.requireNonNull(verifier, "verifier");
	}

	public SimulationSocialContentSeedResult seed(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			SimulationPreflightResult preflight,
			SimulationOpportunityPlan opportunityPlan,
			SimulationOpportunitySeedResult opportunityResult,
			SimulationRunLedger ledger
	) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(accountUserIds, "accountUserIds");
		Objects.requireNonNull(preflight, "preflight");
		Objects.requireNonNull(opportunityPlan, "opportunityPlan");
		Objects.requireNonNull(opportunityResult, "opportunityResult");
		Objects.requireNonNull(ledger, "ledger");
		SimulationMode mode = Objects.requireNonNull(properties.getMode(), "simulation mode");

		List<Account> listeners = eligibleListeners(manifest);
		if (listeners.size() < 7) {
			throw new IllegalStateException("Social simulation requires at least seven STANDARD listeners");
		}

		return switch (mode) {
			case FRESH -> seedFresh(manifest, listeners, accountUserIds, preflight,
					opportunityPlan, opportunityResult, ledger);
			case RESUME, FAST_FORWARD -> resume(manifest, listeners, accountUserIds, preflight,
					opportunityPlan, opportunityResult, ledger);
			case PAUSE -> throw new IllegalStateException("PAUSE mode cannot materialize social content");
		};
	}

	private SimulationSocialContentSeedResult seedFresh(
			SimulationWorldManifest manifest,
			List<Account> listeners,
			Map<String, UUID> accountUserIds,
			SimulationPreflightResult preflight,
			SimulationOpportunityPlan opportunityPlan,
			SimulationOpportunitySeedResult opportunityResult,
			SimulationRunLedger ledger
	) {
		Map<String, UUID> sourceIds = new LinkedHashMap<>();
		Map<String, UUID> tableIds = new LinkedHashMap<>();
		List<SimulationEngagementTarget> targets = new ArrayList<>();
		List<SimulationSocialContentCheckpoint.OverthinkingPublication> overthinking = new ArrayList<>();
		List<SimulationSocialContentCheckpoint.TableGroupAggregate> tables = new ArrayList<>();
		List<SimulationSocialContentCheckpoint.TableGroupPublication> tableShares = new ArrayList<>();
		List<SimulationSocialContentCheckpoint.EventPublication> eventPosts = new ArrayList<>();

		seedOverthinking(manifest, listeners, accountUserIds, sourceIds, targets, overthinking, ledger);
		seedTableGroups(manifest, listeners, accountUserIds, preflight, tableIds, targets,
				tables, tableShares, ledger);
		seedEventPublications(manifest, listeners, accountUserIds, opportunityPlan,
				opportunityResult, targets, eventPosts, ledger);
		assertTargetCount(manifest, targets);

		String fingerprint = storyFingerprint(manifest, opportunityPlan, overthinking, tables, tableShares, eventPosts);
		SimulationSocialContentCheckpoint checkpoint = new SimulationSocialContentCheckpoint(
				SimulationSocialContentCheckpoint.CURRENT_SCHEMA_VERSION,
				manifest.schemaVersion(), manifest.worldId(), manifest.seed(), fingerprint,
				clock.instant().truncatedTo(ChronoUnit.SECONDS), overthinking, tables, tableShares, eventPosts);
		verifyCheckpoint(checkpoint, manifest, listeners, accountUserIds, preflight,
				opportunityPlan, opportunityResult);
		checkpoints.saveFresh(manifest, checkpoint);
		return new SimulationSocialContentSeedResult(sourceIds, tableIds, targets);
	}

	private SimulationSocialContentSeedResult resume(
			SimulationWorldManifest manifest,
			List<Account> listeners,
			Map<String, UUID> accountUserIds,
			SimulationPreflightResult preflight,
			SimulationOpportunityPlan opportunityPlan,
			SimulationOpportunitySeedResult opportunityResult,
			SimulationRunLedger ledger
	) {
		SimulationSocialContentCheckpoint checkpoint = checkpoints.loadExisting(manifest);
		String expectedFingerprint = storyFingerprint(
				manifest, opportunityPlan, checkpoint.overthinkingPublications(), checkpoint.tableGroups(),
				checkpoint.tableGroupPublications(), checkpoint.eventPublications());
		if (!expectedFingerprint.equals(checkpoint.storyFingerprint())) {
			throw new IllegalStateException("Social-content story definition differs from the completed FRESH run");
		}
		try {
			verifyCheckpoint(checkpoint, manifest, listeners, accountUserIds, preflight,
					opportunityPlan, opportunityResult);
		} catch (RuntimeException failure) {
			ledger.failed("social-resume", "VERIFY", null, null, failure);
			throw failure;
		}

		Map<String, Account> accounts = accountsByKey(manifest);
		Map<String, UUID> sourceIds = new LinkedHashMap<>();
		Map<String, UUID> tableIds = new LinkedHashMap<>();
		List<SimulationEngagementTarget> targets = new ArrayList<>();
		for (var item : checkpoint.overthinkingPublications()) {
			Account owner = requireAccount(accounts, item.ownerAccountKey());
			sourceIds.put(item.logicalKey(), item.sourcePostId());
			targets.add(target(item.logicalKey(), EngagementTargetType.OVERTHINKING_PROFILE_SHARE,
					item.profileShareId(), owner, item.ownerUserId()));
			ledger.skipped(PHASE_OVERTHINKING, "VERIFY_RESUME", item.ownerAccountKey(),
					item.logicalKey(), "checkpoint identity verified; no create command invoked");
		}
		for (var item : checkpoint.tableGroups()) {
			tableIds.put(item.logicalKey(), item.tableGroupId());
		}
		for (var item : checkpoint.tableGroupPublications()) {
			Account publisher = requireAccount(accounts, item.publisherAccountKey());
			targets.add(target(item.logicalKey(), EngagementTargetType.TABLE_GROUP_POST,
					item.profileShareId(), publisher, item.publisherUserId()));
			ledger.skipped(PHASE_TABLE_GROUP, "VERIFY_RESUME", item.publisherAccountKey(),
					item.logicalKey(), "checkpoint identity verified; no create command invoked");
		}
		for (var item : checkpoint.eventPublications()) {
			Account listener = requireAccount(accounts, item.listenerAccountKey());
			targets.add(target(item.logicalKey(), EngagementTargetType.EVENT_POST,
					item.profilePostId(), listener, item.listenerUserId()));
			ledger.skipped(PHASE_EVENT, "VERIFY_RESUME", item.listenerAccountKey(),
					item.eventStoryKey(), "checkpoint identity verified; no update command invoked");
		}
		assertTargetCount(manifest, targets);
		return new SimulationSocialContentSeedResult(sourceIds, tableIds, targets);
	}

	private void verifyCheckpoint(
			SimulationSocialContentCheckpoint checkpoint,
			SimulationWorldManifest manifest,
			List<Account> listeners,
			Map<String, UUID> accountUserIds,
			SimulationPreflightResult preflight,
			SimulationOpportunityPlan opportunityPlan,
			SimulationOpportunitySeedResult opportunityResult
	) {
		for (int index = 0; index < checkpoint.overthinkingPublications().size(); index++) {
			var item = checkpoint.overthinkingPublications().get(index);
			Account listener = listeners.get(index % listeners.size());
			assertIdentity(item.logicalKey().equals(numbered("overthinking", index))
					&& item.ownerAccountKey().equals(listener.key())
					&& item.ownerUserId().equals(requireId(accountUserIds, listener.key())),
					"Overthinking checkpoint identity differs from the story", item.logicalKey());
			verifier.verifyOverthinking(item, OVERTHINKING_TITLES.get(index),
					OVERTHINKING_BODIES.get(index), overthinkingNote(index));
		}

		List<Account> owners = tableOwners(listeners);
		List<Account> participants = listeners.stream().filter(value -> !owners.contains(value)).toList();
		Map<String, SimulationSocialContentCheckpoint.TableGroupAggregate> tablesByKey = new LinkedHashMap<>();
		Map<String, Map<UUID, String>> membershipByTableKey = new LinkedHashMap<>();
		for (int index = 0; index < checkpoint.tableGroups().size(); index++) {
			var item = checkpoint.tableGroups().get(index);
			Account owner = owners.get(index);
			String expectedKey = numbered("table-group", index);
			assertIdentity(item.logicalKey().equals(expectedKey)
					&& item.ownerAccountKey().equals(owner.key())
					&& item.ownerUserId().equals(requireId(accountUserIds, owner.key())),
					"TableGroup checkpoint identity differs from the story", item.logicalKey());
			tablesByKey.put(expectedKey, item);
			Map<UUID, String> membership = new LinkedHashMap<>();
			membership.put(item.ownerUserId(), null);
			membershipByTableKey.put(expectedKey, membership);
		}

		int expectedShareCount = manifest.contentTargets().tableGroupProfileShares();
		for (int index = 0; index < checkpoint.tableGroupPublications().size(); index++) {
			var item = checkpoint.tableGroupPublications().get(index);
			if (index < owners.size()) {
				Account owner = owners.get(index);
				String tableKey = numbered("table-group", index);
				var table = tablesByKey.get(tableKey);
				assertTableShareIdentity(item, tableKey + "-owner", owner,
						requireId(accountUserIds, owner.key()), table);
			} else {
				int memberIndex = index - owners.size();
				if (memberIndex >= expectedShareCount - owners.size()) {
					throw new IllegalStateException("TableGroup checkpoint has an unexpected member publication");
				}
				Account participant = participants.get(memberIndex % participants.size());
				int tableIndex = memberIndex / participants.size();
				String tableKey = numbered("table-group", tableIndex);
				var table = tablesByKey.get(tableKey);
				UUID participantId = requireId(accountUserIds, participant.key());
				assertTableShareIdentity(item, numbered("table-group-member", memberIndex),
						participant, participantId, table);
				membershipByTableKey.get(tableKey).put(participantId, joinNote(participant));
			}
			verifier.verifyTableGroupPublication(item, TABLE_SHARE_NOTE);
		}
		for (int index = 0; index < owners.size(); index++) {
			String tableKey = numbered("table-group", index);
			var item = tablesByKey.get(tableKey);
			SimulationResolvedLocation location = Objects.requireNonNull(
					preflight.locationsByAccountKey().get(item.ownerAccountKey()),
					"Missing listener location: " + item.ownerAccountKey());
			verifier.verifyTableGroup(item, tableRequest(index, item.meetingAt(), location),
					membershipByTableKey.get(tableKey));
		}

		Map<String, EventStory> events = new LinkedHashMap<>();
		for (EventStory story : opportunityPlan.events()) events.put(story.key(), story);
		Set<String> pairs = new HashSet<>();
		for (int index = 0; index < checkpoint.eventPublications().size(); index++) {
			var item = checkpoint.eventPublications().get(index);
			Account listener = listeners.get(index % listeners.size());
			EventStory story = events.get(item.eventStoryKey());
			UUID currentEventId = opportunityResult.events().ids().get(item.eventStoryKey());
			EventIntent expectedIntent = index % 3 == 0 ? EventIntent.THINKING : EventIntent.GOING;
			String expectedNote = EVENT_NOTES.get(index % EVENT_NOTES.size());
			assertIdentity(story != null
					&& story.finalState() == EventFinalState.VISIBLE
					&& item.logicalKey().equals("event-post-" + listener.key() + "-" + item.eventStoryKey())
					&& item.listenerAccountKey().equals(listener.key())
					&& item.listenerUserId().equals(requireId(accountUserIds, listener.key()))
					&& item.eventId().equals(currentEventId)
					&& item.intent() == expectedIntent
					&& item.note().equals(expectedNote)
					&& pairs.add(item.listenerUserId() + "|" + item.eventId()),
					"Event publication checkpoint differs from the story", item.logicalKey());
			verifier.verifyEventPublication(item);
		}
	}

	private static void assertTableShareIdentity(
			SimulationSocialContentCheckpoint.TableGroupPublication item,
			String expectedLogicalKey,
			Account expectedPublisher,
			UUID expectedPublisherId,
			SimulationSocialContentCheckpoint.TableGroupAggregate table
	) {
		assertIdentity(table != null
				&& item.logicalKey().equals(expectedLogicalKey)
				&& item.publisherAccountKey().equals(expectedPublisher.key())
				&& item.publisherUserId().equals(expectedPublisherId)
				&& item.tableLogicalKey().equals(table.logicalKey())
				&& item.tableGroupId().equals(table.tableGroupId()),
				"TableGroup publication checkpoint differs from the story", item.logicalKey());
	}

	private static void assertIdentity(boolean condition, String message, String logicalKey) {
		if (!condition) throw new IllegalStateException(message + ": " + logicalKey);
	}

	private void seedOverthinking(
			SimulationWorldManifest manifest,
			List<Account> listeners,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> sourceIds,
			List<SimulationEngagementTarget> targets,
			List<SimulationSocialContentCheckpoint.OverthinkingPublication> checkpointItems,
			SimulationRunLedger ledger
	) {
		int count = manifest.contentTargets().overthinkingProfileShares();
		if (count < 0 || count > OVERTHINKING_TITLES.size()) {
			throw new IllegalStateException("Unsupported Overthinking profile-share target: " + count);
		}
		for (int index = 0; index < count; index++) {
			Account listener = listeners.get(index % listeners.size());
			UUID userId = requireId(accountUserIds, listener.key());
			String logicalKey = numbered("overthinking", index);
			OverthinkingPostSaveRequestDto request = new OverthinkingPostSaveRequestDto(
					OVERTHINKING_TITLES.get(index), OVERTHINKING_BODIES.get(index),
					OverthinkingVisibilityType.VISIBLE, null, null, null, null, null,
					null, null, stableUuid(manifest.worldId(), logicalKey));
			validate(request, logicalKey);
			try {
				var post = overthinkingPosts.create(userId, request);
				if (post == null || post.id() == null) {
					throw new IllegalStateException("Overthinking service returned no post id");
				}
				String note = overthinkingNote(index);
				var share = overthinkingShares.publish(userId, post.id(), new OverthinkingProfileShareUpdate(note));
				if (share == null || share.shareId() == null || !share.publishedOnProfile()) {
					throw new IllegalStateException("Overthinking profile publication was not created");
				}
				sourceIds.put(logicalKey, post.id());
				targets.add(target(logicalKey, EngagementTargetType.OVERTHINKING_PROFILE_SHARE,
						share.shareId(), listener, userId));
				checkpointItems.add(new SimulationSocialContentCheckpoint.OverthinkingPublication(
						logicalKey, listener.key(), userId, post.id(), share.shareId()));
				ledger.succeeded(PHASE_OVERTHINKING, "PUBLISH_PROFILE_SHARE", listener.key(), logicalKey,
						"visible source published through listener profile");
			} catch (RuntimeException failure) {
				ledger.failed(PHASE_OVERTHINKING, "PUBLISH_PROFILE_SHARE", listener.key(), logicalKey, failure);
				throw failure;
			}
		}
	}

	private void seedTableGroups(
			SimulationWorldManifest manifest,
			List<Account> listeners,
			Map<String, UUID> accountUserIds,
			SimulationPreflightResult preflight,
			Map<String, UUID> tableIds,
			List<SimulationEngagementTarget> targets,
			List<SimulationSocialContentCheckpoint.TableGroupAggregate> checkpointTables,
			List<SimulationSocialContentCheckpoint.TableGroupPublication> checkpointShares,
			SimulationRunLedger ledger
	) {
		int shareTarget = manifest.contentTargets().tableGroupProfileShares();
		if (shareTarget != 14 || listeners.size() < 7) {
			throw new IllegalStateException("TableGroup story currently requires seven listeners and 14 shares");
		}

		List<Account> owners = tableOwners(listeners);
		List<Account> participants = listeners.stream().filter(value -> !owners.contains(value)).toList();
		List<TableGroupResponseDto> tables = new ArrayList<>(TABLE_OWNER_COUNT);
		for (int index = 0; index < owners.size(); index++) {
			Account owner = owners.get(index);
			String key = numbered("table-group", index);
			UUID ownerId = requireId(accountUserIds, owner.key());
			try {
				TableGroupResponseDto table = ensureOwnedTable(owner, ownerId, index, preflight);
				tables.add(table);
				tableIds.put(key, table.id());
				checkpointTables.add(new SimulationSocialContentCheckpoint.TableGroupAggregate(
						key, owner.key(), ownerId, table.id(),
						Objects.requireNonNull(table.meetingAt(), "Created TableGroup has no meetingAt")));
				String shareKey = key + "-owner";
				UUID shareId = publishTableShare(owner, ownerId, table.id(), shareKey, targets);
				checkpointShares.add(new SimulationSocialContentCheckpoint.TableGroupPublication(
						shareKey, owner.key(), ownerId, key, table.id(), shareId));
				ledger.succeeded(PHASE_TABLE_GROUP, "CREATE_OR_RESUME", owner.key(), key,
						"active table and owner publication ready");
			} catch (RuntimeException failure) {
				ledger.failed(PHASE_TABLE_GROUP, "CREATE_OR_RESUME", owner.key(), key, failure);
				throw failure;
			}
		}

		int remainingShares = shareTarget - owners.size();
		for (int index = 0; index < remainingShares; index++) {
			Account participant = participants.get(index % participants.size());
			int tableIndex = index / participants.size();
			TableGroupResponseDto table = tables.get(tableIndex);
			Account owner = owners.get(tableIndex);
			UUID participantId = requireId(accountUserIds, participant.key());
			String shareKey = numbered("table-group-member", index);
			try {
				ensureAccepted(owner, requireId(accountUserIds, owner.key()), participant,
						participantId, table.id());
				UUID shareId = publishTableShare(participant, participantId, table.id(), shareKey, targets);
				String tableKey = numbered("table-group", tableIndex);
				checkpointShares.add(new SimulationSocialContentCheckpoint.TableGroupPublication(
						shareKey, participant.key(), participantId, tableKey, table.id(), shareId));
				ledger.succeeded(PHASE_TABLE_GROUP, "JOIN_APPROVE_AND_PUBLISH", participant.key(),
						shareKey, "accepted membership and profile publication ready");
			} catch (RuntimeException failure) {
				ledger.failed(PHASE_TABLE_GROUP, "JOIN_APPROVE_AND_PUBLISH", participant.key(),
						shareKey, failure);
				throw failure;
			}
		}
	}

	private TableGroupResponseDto ensureOwnedTable(
			Account owner,
			UUID ownerId,
			int index,
			SimulationPreflightResult preflight
	) {
		List<TableGroupResponseDto> owned = tableGroups
				.listMyActiveTableGroups(ownerId, PageRequest.of(0, 10))
				.getContent().stream()
				.filter(table -> ownerId.equals(table.ownerId()))
				.toList();
		if (!owned.isEmpty()) {
			throw new IllegalStateException(
					"FRESH social seed requires no active TableGroup for listener: " + owner.key());
		}

		SimulationResolvedLocation location = Objects.requireNonNull(
				preflight.locationsByAccountKey().get(owner.key()),
				"Missing listener location: " + owner.key());
		Instant meetingAt = clock.instant().plus(Duration.ofHours(4L + index * 2L))
				.truncatedTo(ChronoUnit.SECONDS);
		TableGroupCreateRequestDto request = tableRequest(index, meetingAt, location);
		validate(request, owner.key());
		TableGroupResponseDto created = tableGroups.createTableGroup(ownerId, request);
		if (created == null || created.id() == null || !ownerId.equals(created.ownerId())) {
			throw new IllegalStateException("TableGroup service returned an invalid aggregate: " + owner.key());
		}
		return created;
	}

	private void ensureAccepted(
			Account owner,
			UUID ownerId,
			Account participant,
			UUID participantId,
			UUID tableId
	) {
		TableGroupParticipantDto current = participant(tableGroups.getTableGroupDetail(ownerId, tableId), participantId);
		if (current == null || terminal(current.status())) {
			tableGroups.joinTableGroup(participantId, tableId, joinNote(participant));
			current = participant(tableGroups.getTableGroupDetail(ownerId, tableId), participantId);
		}
		if (current == null || current.status() == ParticipantStatus.PENDING) {
			tableGroups.approveJoinRequest(ownerId, tableId, participantId);
		}
		TableGroupParticipantDto accepted = participant(tableGroups.getTableGroupDetail(ownerId, tableId), participantId);
		if (accepted == null || accepted.status() != ParticipantStatus.ACCEPTED) {
			throw new IllegalStateException("TableGroup membership did not reach ACCEPTED: "
					+ owner.key() + " / " + participant.key());
		}
	}

	private UUID publishTableShare(
			Account listener,
			UUID userId,
			UUID tableId,
			String logicalKey,
			List<SimulationEngagementTarget> targets
	) {
		var share = tableGroupShares.publish(userId, tableId, new TableGroupProfileShareUpdate(TABLE_SHARE_NOTE));
		if (share == null || share.shareId() == null || !share.publishedOnProfile()) {
			throw new IllegalStateException("TableGroup profile publication was not created: " + logicalKey);
		}
		targets.add(target(logicalKey, EngagementTargetType.TABLE_GROUP_POST,
				share.shareId(), listener, userId));
		return share.shareId();
	}

	private void seedEventPublications(
			SimulationWorldManifest manifest,
			List<Account> listeners,
			Map<String, UUID> accountUserIds,
			SimulationOpportunityPlan plan,
			SimulationOpportunitySeedResult result,
			List<SimulationEngagementTarget> targets,
			List<SimulationSocialContentCheckpoint.EventPublication> checkpointItems,
			SimulationRunLedger ledger
	) {
		int count = manifest.contentTargets().listenerEventProfilePublications();
		Map<String, Account> accounts = accountsByKey(manifest);
		LocalDate today = LocalDate.ofInstant(clock.instant(), PRODUCT_ZONE);
		List<EventStory> available = plan.events().stream()
				.filter(story -> story.finalState() == EventFinalState.VISIBLE)
				.filter(story -> !story.eventDate().isBefore(today))
				.filter(story -> result.events().ids().containsKey(story.key()))
				.sorted(Comparator.comparing(EventStory::key))
				.toList();
		if (available.isEmpty()) {
			throw new IllegalStateException("No future public Event is available for listener publications");
		}

		Set<String> usedActorEventPairs = new HashSet<>();
		for (int index = 0; index < count; index++) {
			Account listener = listeners.get(index % listeners.size());
			UUID userId = requireId(accountUserIds, listener.key());
			EventStory story = chooseEvent(listener, available, accounts, index, usedActorEventPairs);
			UUID eventId = Objects.requireNonNull(result.events().ids().get(story.key()),
					"Missing Event id: " + story.key());
			String logicalKey = "event-post-" + listener.key() + "-" + story.key();
			EventIntent intent = index % 3 == 0 ? EventIntent.THINKING : EventIntent.GOING;
			String note = EVENT_NOTES.get(index % EVENT_NOTES.size());
			try {
				var current = eventAudience.get(userId, eventId);
				if (!current.eventAvailable() || current.eventEnded() || !current.canPublish()) {
					throw new IllegalStateException("Event cannot be published by listener: " + logicalKey);
				}
				if (current.intent() != EventIntent.NONE
						&& (current.intent() != intent || !current.publishedOnProfile()
						|| !Objects.equals(current.note(), note))) {
					throw new IllegalStateException("Existing Event intent conflicts with story: " + logicalKey);
				}
				var published = current.intent() == intent && current.publishedOnProfile()
						? current
						: eventAudience.update(userId, eventId,
								new EventIntentUpdate(intent, true, note, current.version()));
				if (published.postId() == null || !published.publishedOnProfile()) {
					throw new IllegalStateException("Event profile publication was not created: " + logicalKey);
				}
				targets.add(target(logicalKey, EngagementTargetType.EVENT_POST,
						published.postId(), listener, userId));
				checkpointItems.add(new SimulationSocialContentCheckpoint.EventPublication(
						logicalKey, listener.key(), userId, story.key(), eventId,
						published.postId(), intent, note, published.version()));
				ledger.succeeded(PHASE_EVENT, "PUBLISH_EVENT_INTENT", listener.key(), story.key(),
						intent.name());
			} catch (RuntimeException failure) {
				ledger.failed(PHASE_EVENT, "PUBLISH_EVENT_INTENT", listener.key(), story.key(), failure);
				throw failure;
			}
		}
	}

	private EventStory chooseEvent(
			Account listener,
			List<EventStory> events,
			Map<String, Account> accounts,
			int index,
			Set<String> usedPairs
	) {
		for (int pass = 0; pass < 2; pass++) {
			for (int offset = 0; offset < events.size(); offset++) {
				EventStory candidate = events.get(Math.floorMod(index * 3 + offset, events.size()));
				Account venue = accounts.get(candidate.venueAccountKey());
				if (venue == null) continue;
				if (pass == 0 && venue.scene() != listener.scene()) continue;
				String pair = listener.key() + "|" + candidate.key();
				if (usedPairs.add(pair)) return candidate;
			}
		}
		throw new IllegalStateException("No unique Event publication candidate for " + listener.key());
	}

	private static TableGroupParticipantDto participant(TableGroupResponseDto table, UUID userId) {
		if (table == null || table.participants() == null) return null;
		return table.participants().stream()
				.filter(value -> userId.equals(value.userId()))
				.findFirst().orElse(null);
	}

	private static boolean terminal(ParticipantStatus status) {
		return status == ParticipantStatus.REJECTED
				|| status == ParticipantStatus.KICKED
				|| status == ParticipantStatus.LEFT;
	}

	private static List<Account> eligibleListeners(SimulationWorldManifest manifest) {
		return manifest.accounts().stream()
				.filter(account -> account.role() == AccountRole.LISTENER)
				.filter(account -> account.emailVerification() == EmailVerificationState.VERIFIED)
				.filter(account -> account.listenerVisibility() == ListenerVisibilityState.STANDARD)
				.sorted(Comparator.comparing(Account::key))
				.toList();
	}

	private static List<Account> tableOwners(List<Account> listeners) {
		List<Account> result = TABLE_OWNER_SCENES.stream()
				.map(scene -> listeners.stream().filter(account -> account.scene() == scene)
						.findFirst().orElseThrow(() -> new IllegalStateException(
								"No STANDARD listener available for TableGroup scene: " + scene)))
				.toList();
		if (result.size() != TABLE_OWNER_COUNT || result.stream().distinct().count() != TABLE_OWNER_COUNT) {
			throw new IllegalStateException("TableGroup owner selection is not unique");
		}
		return result;
	}

	private static Map<String, Account> accountsByKey(SimulationWorldManifest manifest) {
		Map<String, Account> result = new LinkedHashMap<>();
		for (Account account : manifest.accounts()) result.put(account.key(), account);
		return Map.copyOf(result);
	}

	private static Account requireAccount(Map<String, Account> accounts, String key) {
		Account account = accounts.get(key);
		if (account == null) throw new IllegalStateException("Social checkpoint account is absent: " + key);
		return account;
	}

	private static TableGroupCreateRequestDto tableRequest(
			int index,
			Instant meetingAt,
			SimulationResolvedLocation location
	) {
		Objects.requireNonNull(location, "location");
		return new TableGroupCreateRequestDto(
				null,
				TABLE_VENUE_NAMES.get(index),
				TABLE_DESCRIPTIONS.get(index),
				TABLE_CAPACITY,
				TABLE_GENDER_PREFERENCES,
				TABLE_AGE_MIN,
				TABLE_AGE_MAX,
				Objects.requireNonNull(meetingAt, "meetingAt"),
				Objects.requireNonNull(location.city(), "location city").getId(),
				Objects.requireNonNull(location.district(), "location district").getId(),
				Objects.requireNonNull(location.neighborhood(), "location neighborhood").getId());
	}

	private static String joinNote(Account participant) {
		return participant.displayName() + ": müzik keşifleri üzerine sohbete katılmak istiyorum.";
	}

	private static String overthinkingNote(int index) {
		return "Profilimde kalsın: "
				+ OVERTHINKING_TITLES.get(index)
				.toLowerCase(java.util.Locale.forLanguageTag("tr-TR")) + ".";
	}

	private static void assertTargetCount(
			SimulationWorldManifest manifest,
			List<SimulationEngagementTarget> targets
	) {
		int expected = manifest.contentTargets().overthinkingProfileShares()
				+ manifest.contentTargets().tableGroupProfileShares()
				+ manifest.contentTargets().listenerEventProfilePublications();
		if (targets.size() != expected) {
			throw new IllegalStateException("Social target count mismatch: expected " + expected
					+ ", materialized " + targets.size());
		}
	}

	private static String storyFingerprint(
			SimulationWorldManifest manifest,
			SimulationOpportunityPlan opportunityPlan,
			List<SimulationSocialContentCheckpoint.OverthinkingPublication> overthinking,
			List<SimulationSocialContentCheckpoint.TableGroupAggregate> tables,
			List<SimulationSocialContentCheckpoint.TableGroupPublication> tableShares,
			List<SimulationSocialContentCheckpoint.EventPublication> eventPosts
	) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateDigest(digest, "social-story-v1", manifest.schemaVersion(), manifest.worldId(), manifest.seed(),
					manifest.contentTargets().overthinkingProfileShares(),
					manifest.contentTargets().tableGroupProfileShares(),
					manifest.contentTargets().listenerEventProfilePublications(),
					TABLE_OWNER_COUNT, TABLE_CAPACITY, TABLE_AGE_MIN, TABLE_AGE_MAX, TABLE_SHARE_NOTE);
			OVERTHINKING_TITLES.forEach(value -> updateDigest(digest, value));
			OVERTHINKING_BODIES.forEach(value -> updateDigest(digest, value));
			TABLE_VENUE_NAMES.forEach(value -> updateDigest(digest, value));
			TABLE_DESCRIPTIONS.forEach(value -> updateDigest(digest, value));
			TABLE_GENDER_PREFERENCES.forEach(value -> updateDigest(digest, value));
			EVENT_NOTES.forEach(value -> updateDigest(digest, value));
			TABLE_OWNER_SCENES.forEach(value -> updateDigest(digest, value));
			for (EventStory story : opportunityPlan.events()) {
				updateDigest(digest, story.key(), story.venueAccountKey(), story.title(), story.description(),
						story.eventDate(), story.startTime(), story.endTime(), story.performerStory(),
						story.performerKey(), story.performerActorAccountKey(), story.manualPerformerName(),
						story.showOnProfile(), story.finalState());
			}
			for (var item : overthinking) {
				updateDigest(digest, item.logicalKey(), item.ownerAccountKey(), item.ownerUserId(),
						item.sourcePostId(), item.profileShareId());
			}
			for (var item : tables) {
				updateDigest(digest, item.logicalKey(), item.ownerAccountKey(), item.ownerUserId(),
						item.tableGroupId(), item.meetingAt());
			}
			for (var item : tableShares) {
				updateDigest(digest, item.logicalKey(), item.publisherAccountKey(), item.publisherUserId(),
						item.tableLogicalKey(), item.tableGroupId(), item.profileShareId());
			}
			for (var item : eventPosts) {
				updateDigest(digest, item.logicalKey(), item.listenerAccountKey(), item.listenerUserId(),
						item.eventStoryKey(), item.eventId(), item.profilePostId(), item.intent(),
						item.note(), item.version());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static void updateDigest(MessageDigest digest, Object... values) {
		for (Object value : values) {
			if (value == null) {
				digest.update(new byte[]{-1, -1, -1, -1});
				continue;
			}
			byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
			int length = bytes.length;
			digest.update((byte) (length >>> 24));
			digest.update((byte) (length >>> 16));
			digest.update((byte) (length >>> 8));
			digest.update((byte) length);
			digest.update(bytes);
		}
	}

	private static SimulationEngagementTarget target(
			String key,
			EngagementTargetType type,
			UUID id,
			Account owner,
			UUID ownerId
	) {
		return new SimulationEngagementTarget(key, type, id, owner.key(), ownerId, owner.scene());
	}

	private <T> void validate(T request, String logicalKey) {
		var violations = validator.validate(request);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException("Invalid simulation DTO: " + logicalKey, violations);
		}
	}

	private static UUID requireId(Map<String, UUID> ids, String accountKey) {
		UUID id = ids.get(accountKey);
		if (id == null) throw new IllegalStateException("No registered account for manifest key: " + accountKey);
		return id;
	}

	private static String numbered(String prefix, int index) {
		return prefix + "-" + String.format(java.util.Locale.ROOT, "%02d", index + 1);
	}

	private static UUID stableUuid(String worldId, String logicalKey) {
		return UUID.nameUUIDFromBytes((worldId + "|" + logicalKey).getBytes(StandardCharsets.UTF_8));
	}
}

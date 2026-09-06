package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.repository;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.publication.*;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar.BandCalendarService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar.BandCalendarSettingsRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar.BandCalendarSettings;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.calendar.BandCalendarEventRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.dto.MusicianCalendarSettingsUpdate;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.entity.MusicianCalendarSettings;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.service.MusicianCalendarService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MusicianCalendarService.class, BandCalendarService.class, EventMapper.class, EventProfilePublicationService.class,
        com.berkayb.soundconnect.modules.event.support.EventScheduleClock.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MusicianCalendarPostgresTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("musician_calendar").withUsername("soundconnect").withPassword("soundconnect");
	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
		registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
		registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
	}
	@Autowired MusicianCalendarService service;
	@Autowired MusicianCalendarSettingsRepository settings;
	@Autowired MusicianCalendarEventRepository events;
	@Autowired BandCalendarService bandService;
	@Autowired BandCalendarSettingsRepository bandSettings;
	@Autowired BandCalendarEventRepository bandEvents;
	@Autowired EventProfilePublicationService publications;
	@Autowired EventMemberPublicationRepository memberPublications;
	@Autowired EventProfilePublicationRepository publicationRepository;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired EntityManager em;
	@MockitoBean MediaAssetService media;
	@MockitoBean EventShareUrlBuilder shareUrls;
	private final LocalDate date = LocalDate.of(2026, 9, 5);

	@Test
	void onlyApprovedDirectAndActiveBandEventsAppearWithTheVenueDtoContract() {
		Fixture fixture = fixture();
		service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		UUID[] expected = tx().execute(status -> {
			User owner = em.find(User.class, fixture.userId());
			MusicianProfile musician = em.find(MusicianProfile.class, fixture.profileId());
			Venue venue = em.find(Venue.class, fixture.venueId());
			Event direct = event(venue, musician, null, EventPerformerApprovalStatus.APPROVED, "Direct");
			Band active = band(owner, BandMemberShipStatus.ACTIVE);
			enableBand(active);
			Event bandEvent = event(venue, null, active, EventPerformerApprovalStatus.APPROVED, "Active band");
			memberChoice(bandEvent.getId(), fixture.profileId(), true);
			for (BandMemberShipStatus inactive : List.of(BandMemberShipStatus.PENDING, BandMemberShipStatus.REJECTED, BandMemberShipStatus.LEFT)) {
				Band inactiveBand = band(owner, inactive);
				enableBand(inactiveBand);
				Event inactiveEvent = event(venue, null, inactiveBand, EventPerformerApprovalStatus.APPROVED, inactive.name());
				memberChoice(inactiveEvent.getId(), fixture.profileId(), true);
			}
			event(venue, null, null, EventPerformerApprovalStatus.PENDING, "Pending invitation");
			event(venue, null, null, EventPerformerApprovalStatus.REJECTED, "Rejected invitation");
			event(venue, null, null, EventPerformerApprovalStatus.NOT_REQUIRED, "Manual name match");
			event(venue, null, band(user(), BandMemberShipStatus.ACTIVE), EventPerformerApprovalStatus.APPROVED, "Unrelated band");
			Event outOfRange = event(venue, musician, null, EventPerformerApprovalStatus.APPROVED, "Other week");
			outOfRange.setEventDate(date.plusDays(7));
			return new UUID[]{direct.getId(), bandEvent.getId()};
		});
		var response = service.getCalendar(fixture.profileId(), date, date.plusDays(6), 0, 20);
		assertThat(response.events()).extracting(item -> item.id()).containsExactlyInAnyOrder(expected);
		assertThat(response.events()).allSatisfy(item -> {
			assertThat(item.venueId()).isEqualTo(fixture.venueId());
			assertThat(item.venueName()).isEqualTo("Calendar venue");
			assertThat(item.performerType()).isNotNull();
		});
	}

	@Test
	void acceptanceAndDeletionReflectImmediatelyWithoutCopyingEvents() {
		Fixture fixture = fixture();
		service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		UUID eventId = tx().execute(status -> event(em.find(Venue.class, fixture.venueId()), null, null,
				EventPerformerApprovalStatus.PENDING, "Awaiting consent").getId());
		assertThat(service.getCalendar(fixture.profileId(), date, date, 0, 20).events()).isEmpty();
		tx().executeWithoutResult(status -> {
			Event event = em.find(Event.class, eventId);
			event.setMusicianProfile(em.find(MusicianProfile.class, fixture.profileId()));
			event.setManualPerformerName(null);
			event.setPerformerApprovalStatus(EventPerformerApprovalStatus.APPROVED);
			event.setProfileCalendarApproved(true);
		});
		assertThat(service.getCalendar(fixture.profileId(), date, date, 0, 20).events())
				.extracting(item -> item.id()).containsExactly(eventId);
		tx().executeWithoutResult(status -> em.remove(em.find(Event.class, eventId)));
		assertThat(service.getCalendar(fixture.profileId(), date, date, 0, 20).events()).isEmpty();
	}

	@Test
	void detailQueryRechecksMembershipAfterIdSelection() {
		Fixture fixture = fixture();
		UUID[] targets = tx().execute(status -> {
			Band band = band(em.find(User.class, fixture.userId()), BandMemberShipStatus.ACTIVE);
			enableBand(band);
			Event event = event(em.find(Venue.class, fixture.venueId()), null, band,
					EventPerformerApprovalStatus.APPROVED, "Membership race");
			memberChoice(event.getId(), fixture.profileId(), true);
			return new UUID[]{band.getId(), event.getId()};
		});
		UUID eventId = targets[1];
		var ids = tx().execute(status -> events.findApprovedEventIds(fixture.profileId(), date, date, List.of(targets[0]), PageRequest.of(0, 20)));
		assertThat(ids.getContent()).containsExactly(eventId);
		tx().executeWithoutResult(status -> em.createQuery("update BandMember member set member.status = :left where member.user.id = :user")
				.setParameter("left", BandMemberShipStatus.LEFT).setParameter("user", fixture.userId()).executeUpdate());
		List<Event> details = tx().execute(status -> events.findCalendarDetails(ids.getContent(), fixture.profileId(), List.of(targets[0])));
		assertThat(details).isEmpty();
	}

	@Test
	void paginationIsStableAndBoundedEvenForEventsAtTheSameTime() {
		Fixture fixture = fixture();
		service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		tx().executeWithoutResult(status -> {
			for (int i = 0; i < 5; i++) event(em.find(Venue.class, fixture.venueId()),
					em.find(MusicianProfile.class, fixture.profileId()), null, EventPerformerApprovalStatus.APPROVED, "Same time " + i);
		});
		var all = service.getCalendar(fixture.profileId(), date, date, 0, 20).events();
		var page0 = service.getCalendar(fixture.profileId(), date, date, 0, 2);
		var page1 = service.getCalendar(fixture.profileId(), date, date, 1, 2);
		var page2 = service.getCalendar(fixture.profileId(), date, date, 2, 2);
		assertThat(page0.events()).isEqualTo(all.subList(0, 2));
		assertThat(page1.events()).isEqualTo(all.subList(2, 4));
		assertThat(page2.events()).isEqualTo(all.subList(4, 5));
		assertThat(page0.hasNext()).isTrue();
		assertThat(page1.hasNext()).isTrue();
		assertThat(page2.hasNext()).isFalse();
	}

	@Test
	void hideShowAndGeneralProfileUpdatesNeverOverwriteEachOther() {
		Fixture fixture = fixture();
		assertThat(service.getSettings(fixture.userId()).visible()).isFalse();
		assertThat(settings.findById(fixture.profileId())).isEmpty();
		service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		tx().executeWithoutResult(status -> em.find(MusicianProfile.class, fixture.profileId()).setDescription("New biography"));
		assertThat(service.getCalendar(fixture.profileId(), date, date, 0, 20).visible()).isFalse();
		assertThat(service.getSettings(fixture.userId()).version()).isEqualTo(1);
		assertThatThrownBy(() -> service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(false, 0L)))
				.isInstanceOfSatisfying(SoundConnectException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.MUSICIAN_CALENDAR_VERSION_CONFLICT));
		assertThat(service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(false, 1L)).version()).isEqualTo(2);
	}

	@Test
	void publicReaderSerializesWithPrivacyWriterEvenWithoutSettingsRow() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch acquired = new CountDownLatch(1), release = new CountDownLatch(1), attempting = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var reader = executor.submit(() -> tx().executeWithoutResult(status -> {
				assertThat(settings.lockProfileForRead(fixture.profileId())).contains(fixture.profileId());
				acquired.countDown(); await(release);
			}));
			assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
			var writer = executor.submit(() -> {
				attempting.countDown();
				return service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
			});
			try {
				assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> writer.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			} finally { release.countDown(); }
			reader.get(10, TimeUnit.SECONDS);
			assertThat(writer.get(10, TimeUnit.SECONDS).visible()).isTrue();
		}
		assertThat(service.getCalendar(fixture.profileId(), date, date, 0, 20).visible()).isFalse();
	}

	@Test
	void duplicateConcurrentFirstWritesCommitOnceAndReturnTheSameRevision() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> { await(start); return service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L)); });
			var second = executor.submit(() -> { await(start); return service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L)); });
			start.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
			assertThat(service.getSettings(fixture.userId()).version()).isEqualTo(1);
		}
	}

	@Test
	void bandCalendarRequiresOptInAndOnlyExposesOwnApprovedEventsWithoutChangingVenueEvents() {
		Fixture fixture = fixture();
		UUID[] ids = tx().execute(status -> {
			User user = em.find(User.class, fixture.userId());
			Venue venue = em.find(Venue.class, fixture.venueId());
			Band ownBand = founderBand(user);
			Event approved = event(venue, null, ownBand, EventPerformerApprovalStatus.APPROVED, "Own band approved");
			event(venue, null, null, EventPerformerApprovalStatus.PENDING, "Pending");
			event(venue, null, null, EventPerformerApprovalStatus.REJECTED, "Rejected");
			event(venue, null, founderBand(user), EventPerformerApprovalStatus.APPROVED, "Other band");
			event(venue, em.find(MusicianProfile.class, fixture.profileId()), null, EventPerformerApprovalStatus.APPROVED, "Personal");
			Event outOfRange = event(venue, null, ownBand, EventPerformerApprovalStatus.APPROVED, "Next week");
			outOfRange.setEventDate(date.plusDays(7));
			return new UUID[]{ownBand.getId(), approved.getId()};
		});
		UUID bandId = ids[0];
		assertThat(bandService.getSettings(bandId, fixture.userId()).visible()).isFalse();
		assertThat(bandSettings.findById(bandId)).isEmpty();
		assertThat(bandService.getCalendar(bandId, date, date.plusDays(6), 0, 20).events()).extracting(item -> item.id()).containsExactly(ids[1]);
		bandService.updateSettings(bandId, fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		var visible = bandService.getCalendar(bandId, date, date.plusDays(6), 0, 20);
		assertThat(visible.profileId()).isEqualTo(bandId);
		assertThat(visible.events()).extracting(item -> item.id()).containsExactly(ids[1]);
		assertThat(visible.events().getFirst().bandId()).isEqualTo(bandId);
		assertThat(service.getCalendar(fixture.profileId(), date, date, 0, 20).events()).extracting(item -> item.id()).doesNotContain(ids[1]);
		bandService.updateSettings(bandId, fixture.userId(), new MusicianCalendarSettingsUpdate(false, 1L));
		assertThat(bandService.getCalendar(bandId, date, date, 0, 20).events()).extracting(item -> item.id()).containsExactly(ids[1]);
		EventPerformerApprovalStatus unchanged = tx().execute(status -> em.find(Event.class, ids[1]).getPerformerApprovalStatus());
		assertThat(unchanged).isEqualTo(EventPerformerApprovalStatus.APPROVED);
	}

	@Test
	void onlyActiveFoundersCanReadAndChangeBandPreferenceAndOtherBandsAreIndependent() {
		Fixture fixture = fixture();
		UUID bandId = tx().execute(status -> founderBand(em.find(User.class, fixture.userId())).getId());
		bandService.requireFounder(bandId, fixture.userId());
		for (BandRole role : BandRole.values()) {
			if (role == BandRole.FOUNDER) continue;
			tx().executeWithoutResult(status -> em.createQuery("update BandMember member set member.bandRole = :role where member.band.id = :band")
					.setParameter("role", role).setParameter("band", bandId).executeUpdate());
			assertBandForbidden(bandId, fixture.userId());
		}
		for (BandMemberShipStatus state : BandMemberShipStatus.values()) {
			if (state == BandMemberShipStatus.ACTIVE) continue;
			tx().executeWithoutResult(status -> em.createQuery("update BandMember member set member.bandRole = :role, member.status = :state where member.band.id = :band")
					.setParameter("role", BandRole.FOUNDER).setParameter("state", state).setParameter("band", bandId).executeUpdate());
			assertBandForbidden(bandId, fixture.userId());
		}
		assertBandForbidden(bandId, UUID.randomUUID());
		assertThat(bandSettings.findById(bandId)).isEmpty();
	}

	@Test
	void bandCalendarRechecksConsentAndVenueApprovalAndPaginatesStably() {
		Fixture fixture = fixture();
		UUID bandId = tx().execute(status -> {
			Band band = founderBand(em.find(User.class, fixture.userId()));
			for (int i = 0; i < 5; i++) event(em.find(Venue.class, fixture.venueId()), null, band, EventPerformerApprovalStatus.APPROVED, "Band show " + i);
			return band.getId();
		});
		bandService.updateSettings(bandId, fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		var all = bandService.getCalendar(bandId, date, date, 0, 20).events();
		assertThat(bandService.getCalendar(bandId, date, date, 0, 2).events()).isEqualTo(all.subList(0, 2));
		assertThat(bandService.getCalendar(bandId, date, date, 1, 2).events()).isEqualTo(all.subList(2, 4));
		assertThat(bandService.getCalendar(bandId, date, date, 2, 2).hasNext()).isFalse();
		tx().executeWithoutResult(status -> em.find(Venue.class, fixture.venueId()).setStatus(VenueStatus.PENDING));
		assertThat(bandService.getCalendar(bandId, date, date, 0, 20).events()).isEmpty();
	}

	@Test
	void bandPrivacyWriterSerializesWithReadersAndDuplicateFoundersCommitOnlyOnce() throws Exception {
		Fixture fixture = fixture();
		UUID bandId = tx().execute(status -> founderBand(em.find(User.class, fixture.userId())).getId());
		UUID otherFounder = tx().execute(status -> {
			User other = user();
			em.persist(BandMember.builder().band(em.find(Band.class, bandId)).user(other).bandRole(BandRole.FOUNDER)
					.status(BandMemberShipStatus.ACTIVE).build());
			return other.getId();
		});
		CountDownLatch acquired = new CountDownLatch(1), release = new CountDownLatch(1), attempting = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(3)) {
			var reader = executor.submit(() -> tx().executeWithoutResult(status -> {
				assertThat(bandSettings.lockBandForRead(bandId)).contains(bandId);
				acquired.countDown(); await(release);
			}));
			assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
			var writer = executor.submit(() -> {
				attempting.countDown();
				return bandService.updateSettings(bandId, fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
			});
			try {
				assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> writer.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			} finally { release.countDown(); }
			reader.get(10, TimeUnit.SECONDS);
			assertThat(writer.get(10, TimeUnit.SECONDS).visible()).isTrue();
			CountDownLatch start = new CountDownLatch(1);
			var first = executor.submit(() -> { await(start); return bandService.updateSettings(bandId, fixture.userId(), new MusicianCalendarSettingsUpdate(false, 1L)); });
			var second = executor.submit(() -> { await(start); return bandService.updateSettings(bandId, otherFounder, new MusicianCalendarSettingsUpdate(false, 1L)); });
			start.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
			assertThat(bandService.getSettings(bandId, fixture.userId()).version()).isEqualTo(2);
		}
	}

	@Test
	void bandPublicationNeverControlsMemberPersonalChoiceOrDirectEvents() {
		Fixture fixture = fixture();
		service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		UUID directId = tx().execute(status -> event(em.find(Venue.class, fixture.venueId()),
				em.find(MusicianProfile.class, fixture.profileId()), null, EventPerformerApprovalStatus.APPROVED, "Personal event").getId());
		CalendarBandFixture group = calendarBand(fixture, "Hidden group", true, false);
		assertThat(bandSettings.findById(group.bandId())).isEmpty();
		assertCalendarIds(fixture, directId);
		bandService.updateSettings(group.bandId(), fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		assertCalendarIds(fixture, directId);
		publications.update(fixture.userId(), group.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L));
		assertCalendarIds(fixture, directId, group.eventId());
		publications.update(fixture.userId(), group.eventId(), new EventProfilePublicationUpdate(PerformerType.BAND, group.bandId(), false, 0L));
		assertCalendarIds(fixture, directId, group.eventId());
		assertThat(bandService.getCalendar(group.bandId(), date, date, 0, 20).events()).isEmpty();
		tx().executeWithoutResult(status -> {
			Event event = em.find(Event.class, group.eventId());
			assertThat(event.getBand().getId()).isEqualTo(group.bandId());
			assertThat(event.getVenue().getId()).isEqualTo(fixture.venueId());
			assertThat(event.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
			assertThat(event.isProfileCalendarApproved()).isFalse();
		});
	}

	@Test
	void directBandAndMemberEachRequireTheirOwnPerEventPublication() {
		Fixture fixture = fixture();
		service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		CalendarBandFixture group = calendarBand(fixture, "Profile consent pending", false, true);
		UUID directId = tx().execute(status -> {
			Event direct = event(em.find(Venue.class, fixture.venueId()), em.find(MusicianProfile.class, fixture.profileId()),
					null, EventPerformerApprovalStatus.APPROVED, "Direct profile consent pending");
			direct.setProfileCalendarApproved(false);
			return direct.getId();
		});
		assertCalendarIds(fixture);
		assertThat(bandService.getCalendar(group.bandId(), date, date, 0, 20).events()).isEmpty();
		tx().executeWithoutResult(status -> {
			for (UUID id : List.of(directId, group.eventId())) {
				Event event = em.find(Event.class, id);
				assertThat(event.getPerformerApprovalStatus()).isEqualTo(EventPerformerApprovalStatus.APPROVED);
				assertThat(event.getVenue().getId()).isEqualTo(fixture.venueId());
				event.setProfileCalendarApproved(true);
			}
		});
		assertCalendarIds(fixture, directId);
		publications.update(fixture.userId(), group.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L));
		assertCalendarIds(fixture, directId, group.eventId());
		assertThat(bandService.getCalendar(group.bandId(), date, date, 0, 20).events())
				.extracting(item -> item.id()).containsExactly(group.eventId());
	}

	@Test
	void twoBandMemberPublicationChoicesRemainIndependentOfLegacyPreferences() {
		Fixture fixture = fixture();
		service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		CalendarBandFixture first = calendarBand(fixture, "First group", true, true);
		CalendarBandFixture second = calendarBand(fixture, "Second group", true, false);
		publications.update(fixture.userId(), first.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L));
		assertCalendarIds(fixture, first.eventId());
		bandService.updateSettings(first.bandId(), fixture.userId(), new MusicianCalendarSettingsUpdate(false, 1L));
		bandService.updateSettings(second.bandId(), fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		assertCalendarIds(fixture, first.eventId());
		publications.update(fixture.userId(), first.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), false, 1L));
		publications.update(fixture.userId(), second.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L));
		assertCalendarIds(fixture, second.eventId());
		service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(false, 1L));
		assertCalendarIds(fixture, second.eventId());
		assertThat(bandService.getCalendar(second.bandId(), date, date, 0, 20).events())
				.extracting(item -> item.id()).containsExactly(second.eventId());
	}

	@Test
	void detailQueriesRecheckMemberAndGroupPublicationIndependently() {
		Fixture fixture = fixture();
		CalendarBandFixture group = calendarBand(fixture, "Details permission fence", true, true);
		publications.update(fixture.userId(), group.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L));
		List<UUID> bands = List.of(group.bandId());
		var ids = tx().execute(status -> events.findApprovedEventIds(fixture.profileId(), date, date, bands, PageRequest.of(0, 20)));
		assertThat(ids.getContent()).containsExactly(group.eventId());
		tx().executeWithoutResult(status -> em.find(Event.class, group.eventId()).setProfileCalendarApproved(false));
		List<Event> personalDetails = tx().execute(status -> events.findCalendarDetails(ids.getContent(), fixture.profileId(), bands));
		List<Event> groupDetails = tx().execute(status -> bandEvents.findCalendarDetails(ids.getContent(), group.bandId()));
		assertThat(personalDetails).extracting(Event::getId).containsExactly(group.eventId());
		assertThat(groupDetails).isEmpty();
		tx().executeWithoutResult(status -> {
			em.find(Event.class, group.eventId()).setProfileCalendarApproved(true);
			em.find(BandCalendarSettings.class, group.bandId()).setVisible(false);
		});
		publications.update(fixture.userId(), group.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), false, 1L));
		List<Event> hiddenDetails = tx().execute(status -> events.findCalendarDetails(ids.getContent(), fixture.profileId(), bands));
		assertThat(hiddenDetails).isEmpty();
		assertThat(bandService.getCalendar(group.bandId(), date, date, 0, 20).events()).extracting(item -> item.id()).containsExactly(group.eventId());
	}

	@Test
	void memberCalendarReaderWaitsForConcurrentMemberHideAndThenSeesNoGroupEvent() throws Exception {
		Fixture fixture = fixture();
		service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		CalendarBandFixture group = calendarBand(fixture, "Hide wins", true, true);
		publications.update(fixture.userId(), group.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L));
		CountDownLatch hidden = new CountDownLatch(1), release = new CountDownLatch(1), attempting = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var writer = executor.submit(() -> tx().executeWithoutResult(status -> {
				publications.update(fixture.userId(), group.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), false, 1L));
				hidden.countDown(); await(release);
			}));
			try {
				assertThat(hidden.await(10, TimeUnit.SECONDS)).isTrue();
				var reader = executor.submit(() -> {
					attempting.countDown();
					return service.getCalendar(fixture.profileId(), date, date, 0, 20);
				});
				assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> reader.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				release.countDown();
				writer.get(10, TimeUnit.SECONDS);
				assertThat(reader.get(10, TimeUnit.SECONDS).events()).isEmpty();
			} finally { release.countDown(); }
		}
	}

	@Test
	void memberCalendarSnapshotRetainsBandPrivacyLocksUntilItCompletes() throws Exception {
		Fixture fixture = fixture();
		service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		CalendarBandFixture group = calendarBand(fixture, "Reader wins", true, true);
		publications.update(fixture.userId(), group.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L));
		CountDownLatch acquired = new CountDownLatch(1), release = new CountDownLatch(1), attempting = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var reader = executor.submit(() -> tx().execute(status -> {
				var snapshot = service.getCalendar(fixture.profileId(), date, date, 0, 20);
				acquired.countDown(); await(release);
				return snapshot;
			}));
			try {
				assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
				var writer = executor.submit(() -> {
					attempting.countDown();
					return publications.update(fixture.userId(), group.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), false, 1L));
				});
				assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> writer.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				release.countDown();
				assertThat(reader.get(10, TimeUnit.SECONDS).events()).extracting(item -> item.id()).containsExactly(group.eventId());
				assertThat(writer.get(10, TimeUnit.SECONDS).visible()).isFalse();
			} finally { release.countDown(); }
		}
		assertCalendarIds(fixture);
	}

	@Test
	void membershipAddedAfterBandLockEnumerationCannotEnterEitherPhaseOfThatSnapshot() throws Exception {
		Fixture fixture = fixture();
		service.updateSettings(fixture.userId(), new MusicianCalendarSettingsUpdate(true, 0L));
		UUID[] targets = tx().execute(status -> {
			Band group = founderBand(user());
			enableBand(group);
			Event show = event(em.find(Venue.class, fixture.venueId()), null, group, EventPerformerApprovalStatus.APPROVED, "New membership");
			Event direct = event(em.find(Venue.class, fixture.venueId()), em.find(MusicianProfile.class, fixture.profileId()),
					null, EventPerformerApprovalStatus.APPROVED, "Personal survives empty band IN");
			return new UUID[]{group.getId(), show.getId(), direct.getId()};
		});
		try (var executor = Executors.newSingleThreadExecutor()) {
			tx().executeWithoutResult(status -> {
				List<UUID> lockedBands = settings.lockCalendarBandsForRead(fixture.profileId(), date, date, 129);
				assertThat(lockedBands).isEmpty();
				settings.lockProfileForRead(fixture.profileId()).orElseThrow();
				try {
					executor.submit(() -> tx().executeWithoutResult(insert -> em.persist(BandMember.builder()
							.band(em.find(Band.class, targets[0])).user(em.find(User.class, fixture.userId()))
							.bandRole(BandRole.MEMBER).status(BandMemberShipStatus.ACTIVE).build()))).get(10, TimeUnit.SECONDS);
				} catch (Exception exception) { throw new IllegalStateException(exception); }
				var ids = events.findApprovedEventIds(fixture.profileId(), date, date, lockedBands, PageRequest.of(0, 20));
				assertThat(ids.getContent()).containsExactly(targets[2]);
				assertThat(events.findCalendarDetails(List.of(targets[1], targets[2]), fixture.profileId(), lockedBands))
						.extracting(Event::getId).containsExactly(targets[2]);
			});
		}
		assertCalendarIds(fixture, targets[2]);
		publications.update(fixture.userId(), targets[1], new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L));
		assertCalendarIds(fixture, targets[1], targets[2]);
	}

	@Test
	void bandCandidateLocksAreDateBoundedSortedAndDoNotTrustPreLockVisibilityOrApproval() {
		Fixture fixture = fixture();
		CalendarBandFixture first = calendarBand(fixture, "Hidden and unapproved", false, false);
		CalendarBandFixture second = calendarBand(fixture, "Hidden but approved", true, false);
		CalendarBandFixture outside = calendarBand(fixture, "Outside range", true, true);
		tx().executeWithoutResult(status -> em.find(Event.class, outside.eventId()).setEventDate(date.plusDays(7)));
		List<UUID> expected = List.of(first.bandId(), second.bandId()).stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
		List<UUID> all = tx().execute(status -> settings.lockCalendarBandsForRead(fixture.profileId(), date, date, 129));
		List<UUID> bounded = tx().execute(status -> settings.lockCalendarBandsForRead(fixture.profileId(), date, date, 1));
		assertThat(all).containsExactlyElementsOf(expected);
		assertThat(bounded).containsExactly(expected.getFirst());
	}

	private CalendarBandFixture calendarBand(Fixture fixture, String title, boolean approvedForProfile, boolean visible) {
		return tx().execute(status -> {
			Band group = founderBand(em.find(User.class, fixture.userId()));
			if (visible) enableBand(group);
			Event show = event(em.find(Venue.class, fixture.venueId()), null, group, EventPerformerApprovalStatus.APPROVED, title);
			show.setProfileCalendarApproved(approvedForProfile);
			return new CalendarBandFixture(group.getId(), show.getId());
		});
	}

	@Test
	void managementListsHiddenDirectAndBandEventsButNeverPublishesMembershipImplicitly() {
		Fixture fixture = fixture();
		CalendarBandFixture group = calendarBand(fixture, "Group available", true, false);
		UUID directId = tx().execute(status -> {
			Event direct = event(em.find(Venue.class, fixture.venueId()), em.find(MusicianProfile.class, fixture.profileId()),
					null, EventPerformerApprovalStatus.APPROVED, "Hidden direct");
			direct.setProfileCalendarApproved(false); return direct.getId();
		});
		var personal = publications.getMine(fixture.userId(), PerformerType.MUSICIAN, fixture.profileId(), 0, 20);
		assertThat(personal.content()).extracting(EventProfilePublicationDto::eventId).containsExactlyInAnyOrder(directId, group.eventId());
		assertThat(personal.content()).allSatisfy(item -> assertThat(item.visible()).isFalse());
		assertThat(publications.getMine(fixture.userId(), PerformerType.BAND, group.bandId(), 0, 20).content())
				.extracting(EventProfilePublicationDto::eventId).containsExactly(group.eventId());
		assertCalendarIds(fixture);
		publications.update(fixture.userId(), directId, new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L));
		assertCalendarIds(fixture, directId);
	}

	@Test
	void managementDetailScopeRechecksMembershipAndCannotReadAnUnrelatedTarget() {
		Fixture fixture = fixture();
		CalendarBandFixture group = calendarBand(fixture, "Membership revoked", true, false);
		var ids = tx().execute(status -> publicationRepository.findForMusician(fixture.profileId(), PageRequest.of(0, 20)));
		assertThat(ids.getContent()).containsExactly(group.eventId());
		tx().executeWithoutResult(status -> em.createQuery("update BandMember member set member.status = :state where member.band.id = :band")
				.setParameter("state", BandMemberShipStatus.LEFT).setParameter("band", group.bandId()).executeUpdate());
		List<Event> scopedDetails = tx().execute(status -> publicationRepository.findMusicianDetails(ids.getContent(), fixture.profileId()));
		assertThat(scopedDetails).isEmpty();
		assertThatThrownBy(() -> publications.update(fixture.userId(), group.eventId(),
				new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L)))
				.isInstanceOf(SoundConnectException.class);
		assertThatThrownBy(() -> publications.getMine(fixture.userId(), PerformerType.MUSICIAN, UUID.randomUUID(), 0, 20))
				.isInstanceOf(SoundConnectException.class);
	}

	@Test
	void duplicateConcurrentPublicationWritesCommitOneRevision() throws Exception {
		Fixture fixture = fixture();
		CalendarBandFixture group = calendarBand(fixture, "Concurrent opt in", true, false);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> { await(start); return publications.update(fixture.userId(), group.eventId(),
					new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L)); });
			var second = executor.submit(() -> { await(start); return publications.update(fixture.userId(), group.eventId(),
					new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L)); });
			start.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
		}
		assertThat(memberPublications.findById(new EventMemberPublication.Id(group.eventId(), fixture.profileId())).orElseThrow().getVersion()).isEqualTo(1);
	}

	@Test
	void membershipResetRetainsRevisionAndDoesNotRevivePublicationAfterRejoin() {
		Fixture fixture = fixture();
		CalendarBandFixture group = calendarBand(fixture, "Rejoin", true, false);
		publications.update(fixture.userId(), group.eventId(), new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L));
		tx().executeWithoutResult(status -> {
			bandSettings.lockBandForUpdate(group.bandId()).orElseThrow();
			memberPublications.hideForBandMember(group.bandId(), fixture.userId());
		});
		assertCalendarIds(fixture);
		assertThatThrownBy(() -> publications.update(fixture.userId(), group.eventId(),
				new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L))).isInstanceOf(SoundConnectException.class);
		var hidden = memberPublications.findById(new EventMemberPublication.Id(group.eventId(), fixture.profileId())).orElseThrow();
		assertThat(hidden.isVisible()).isFalse(); assertThat(hidden.getVersion()).isEqualTo(2);
	}

	@Test
	void membershipResetCreatesTombstoneForMissingVersionZeroAndFencesAnAlreadyHiddenRow() {
		Fixture fixture = fixture();
		CalendarBandFixture group = calendarBand(fixture, "Old version zero", true, false);
		assertThat(memberPublications.findById(new EventMemberPublication.Id(group.eventId(), fixture.profileId()))).isEmpty();
		for (int i = 0; i < 2; i++) {
			tx().executeWithoutResult(status -> {
				bandSettings.lockBandForUpdate(group.bandId()).orElseThrow();
				memberPublications.hideForBandMember(group.bandId(), fixture.userId());
			});
		}
		var hidden = memberPublications.findById(new EventMemberPublication.Id(group.eventId(), fixture.profileId())).orElseThrow();
		assertThat(hidden.isVisible()).isFalse();
		assertThat(hidden.getVersion()).isEqualTo(2);
		assertThatThrownBy(() -> publications.update(fixture.userId(), group.eventId(),
				new EventProfilePublicationUpdate(PerformerType.MUSICIAN, fixture.profileId(), true, 0L)))
				.isInstanceOf(SoundConnectException.class);
	}
	private void memberChoice(UUID eventId, UUID profileId, boolean visible) {
		EventMemberPublication publication = new EventMemberPublication(eventId, profileId);
		publication.setVisible(visible);
		em.persist(publication);
	}
	private void assertCalendarIds(Fixture fixture, UUID... ids) {
		assertThat(service.getCalendar(fixture.profileId(), date, date, 0, 20).events())
				.extracting(item -> item.id()).containsExactlyInAnyOrder(ids);
	}
	private record CalendarBandFixture(UUID bandId, UUID eventId) {}

	private void assertBandForbidden(UUID bandId, UUID userId) {
		assertThatThrownBy(() -> bandService.requireFounder(bandId, userId)).isInstanceOfSatisfying(SoundConnectException.class,
				e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));
		assertThatThrownBy(() -> bandService.getSettings(bandId, userId)).isInstanceOfSatisfying(SoundConnectException.class,
				e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));
		assertThatThrownBy(() -> bandService.updateSettings(bandId, userId, new MusicianCalendarSettingsUpdate(true, 0L)))
				.isInstanceOfSatisfying(SoundConnectException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));
	}
	private Band founderBand(User owner) {
		Band band = Band.builder().name("Founder band " + UUID.randomUUID()).build(); em.persist(band);
		em.persist(BandMember.builder().band(band).user(owner).bandRole(BandRole.FOUNDER).status(BandMemberShipStatus.ACTIVE).build());
		return band;
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource({"false,Europe/Istanbul", "true,Europe/Istanbul",
			"false,UTC", "true,UTC", "false,America/New_York", "true,America/New_York"})
	@org.junit.jupiter.api.parallel.ResourceLock("java.util.TimeZone.default")
	void publicationPeriodFiltersBeforePaginationWithExactBoundaryCounts(boolean bandTarget, String timezone) {
		java.util.TimeZone previous = java.util.TimeZone.getDefault();
		try {
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(timezone));
		Fixture f = fixture();
		LocalDateTime now = LocalDateTime.of(2026, 9, 6, 12, 0);
		LocalDate weekEnd = now.toLocalDate().plusDays(6);
		UUID target = tx().execute(status -> {
			MusicianProfile musician = em.find(MusicianProfile.class, f.profileId());
			Venue venue = em.find(Venue.class, f.venueId());
			Band band = bandTarget ? founderBand(musician.getUser()) : null;
			for (int index = 0; index < 7; index++) {
				Event value = event(venue, bandTarget ? null : musician, band, EventPerformerApprovalStatus.APPROVED, "Period " + index);
				value.setProfileCalendarApproved(false); // management retains deliberately hidden events
				value.setEventDate(now.toLocalDate());
				switch (index) {
					case 0 -> { value.setEventDate(now.toLocalDate().minusDays(1)); value.setStartTime(LocalTime.NOON); }
					case 1 -> { value.setStartTime(LocalTime.of(11, 0)); } // absent end => 12:00, exactly past
					case 2 -> { value.setStartTime(LocalTime.of(10, 0)); value.setEndTime(LocalTime.NOON); }
					case 3 -> { value.setStartTime(LocalTime.of(11, 1)); value.setEndTime(LocalTime.of(9, 0)); } // invalid legacy end => +1h
					case 4 -> { value.setEventDate(weekEnd); value.setStartTime(LocalTime.of(23, 0)); }
					case 5, 6 -> { value.setEventDate(weekEnd.plusDays(1)); value.setStartTime(index == 5 ? LocalTime.MIDNIGHT : LocalTime.of(23, 0)); }
				}
			}
			// Rejected participation must not leak into any period or its count.
			Event rejected = event(venue, null, null, EventPerformerApprovalStatus.REJECTED, "Excluded");
			rejected.setEventDate(weekEnd.plusDays(1));
			return bandTarget ? band.getId() : musician.getId();
		});
		for (String period : List.of("CURRENT", "FUTURE", "PAST")) {
			long expected = period.equals("PAST") ? 3 : 2;
			var found = new HashSet<UUID>();
			for (int page = 0; page <= expected; page++) {
				final int index = page;
				var result = tx().execute(status -> bandTarget
						? publicationRepository.findForBandPeriod(target, period, now.toLocalDate(), now.toLocalTime(), LocalTime.MIDNIGHT, weekEnd, PageRequest.of(index, 1))
						: publicationRepository.findForMusicianPeriod(target, period, now.toLocalDate(), now.toLocalTime(), LocalTime.MIDNIGHT, weekEnd, PageRequest.of(index, 1)));
				assertThat(result.getTotalElements()).isEqualTo(expected);
				assertThat(result.getTotalPages()).isEqualTo(expected);
				assertThat(result.getContent()).hasSize(page < expected ? 1 : 0);
				assertThat(result.hasNext()).isEqualTo(page < expected - 1);
				for (UUID id : result.getContent()) assertThat(found.add(id)).isTrue();
				if (period.equals("FUTURE") && page < expected) {
					LocalTime time = tx().execute(status -> em.find(Event.class, result.getContent().getFirst()).getStartTime());
					assertThat(time).isEqualTo(page == 0 ? LocalTime.MIDNIGHT : LocalTime.of(23, 0));
				}
			}
			assertThat(found).hasSize((int) expected);
		}
		} finally { java.util.TimeZone.setDefault(previous); }
	}

	@Test void midnightFallbackRemainsCurrentUntilItsActualNextDayEnd() {
		Fixture f = fixture();
		UUID id = tx().execute(status -> {
			Event value = event(em.find(Venue.class, f.venueId()), em.find(MusicianProfile.class, f.profileId()),
					null, EventPerformerApprovalStatus.APPROVED, "Midnight fallback");
			value.setEventDate(LocalDate.of(2026, 9, 5)); value.setStartTime(LocalTime.of(23, 30));
			return value.getId();
		});
		LocalDate today = LocalDate.of(2026, 9, 6);
		var current = tx().execute(status -> publicationRepository.findForMusicianPeriod(f.profileId(), "CURRENT", today,
				LocalTime.of(0, 29, 59), LocalTime.MIDNIGHT, today.plusDays(6), PageRequest.of(0, 20)));
		assertThat(current.getContent()).containsExactly(id);
		var past = tx().execute(status -> publicationRepository.findForMusicianPeriod(f.profileId(), "PAST", today,
				LocalTime.of(0, 30), LocalTime.MIDNIGHT, today.plusDays(6), PageRequest.of(0, 20)));
		assertThat(past.getContent()).containsExactly(id);
	}

	@Test void musicianPeriodQueryIncludesOnlyActiveMembershipWithoutDuplicatingBandEvents() {
		Fixture f = fixture();
		LocalDateTime now = LocalDateTime.of(2026, 9, 5, 12, 0);
		UUID expected = tx().execute(status -> {
			User owner = em.find(User.class, f.userId()); Venue venue = em.find(Venue.class, f.venueId());
			Band active = band(owner, BandMemberShipStatus.ACTIVE);
			Event included = event(venue, null, active, EventPerformerApprovalStatus.APPROVED, "Active member");
			for (BandMemberShipStatus statusValue : List.of(BandMemberShipStatus.LEFT, BandMemberShipStatus.REJECTED, BandMemberShipStatus.PENDING)) {
				event(venue, null, band(owner, statusValue), EventPerformerApprovalStatus.APPROVED, "Inactive member");
			}
			return included.getId();
		});
		var result = tx().execute(status -> publicationRepository.findForMusicianPeriod(
				f.profileId(), "CURRENT", now.toLocalDate(), now.toLocalTime(), LocalTime.MIDNIGHT, now.toLocalDate().plusDays(6), PageRequest.of(0, 1)));
		assertThat(result.getContent()).containsExactly(expected);
		assertThat(result.getTotalElements()).isEqualTo(1);
	}

	private Fixture fixture() {
		return tx().execute(status -> {
			User user = user();
			MusicianProfile profile = MusicianProfile.builder().user(user).stageName("Calendar musician").build(); em.persist(profile);
			City city = City.builder().name("City " + UUID.randomUUID()).build(); em.persist(city);
			District district = District.builder().name("District").city(city).build(); em.persist(district);
			Neighborhood neighborhood = Neighborhood.builder().name("Neighborhood").district(district).build(); em.persist(neighborhood);
			Venue venue = Venue.builder().name("Calendar venue").owner(user).address("Ankara").phone("05000000000")
					.city(city).district(district).neighborhood(neighborhood).status(VenueStatus.APPROVED).build(); em.persist(venue);
			return new Fixture(user.getId(), profile.getId(), venue.getId());
		});
	}
	private User user() {
		String name = "cal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		User user = User.builder().username(name).email(name + "@example.com").password("test")
				.emailVerified(true).status(UserStatus.ACTIVE).build(); em.persist(user); return user;
	}
	private Band band(User owner, BandMemberShipStatus membership) {
		Band band = Band.builder().name("Band " + UUID.randomUUID()).build(); em.persist(band);
		BandMember member = BandMember.builder().band(band).user(owner).bandRole(BandRole.MEMBER).status(membership).build();
		em.persist(member); return band;
	}
	private Event event(Venue venue, MusicianProfile musician, Band band, EventPerformerApprovalStatus approval, String title) {
		Event event = Event.builder().venue(venue).musicianProfile(musician).band(band).performerApprovalStatus(approval)
				.profileCalendarApproved(approval == EventPerformerApprovalStatus.APPROVED)
				.manualPerformerName(approval == EventPerformerApprovalStatus.APPROVED ? null : "Calendar musician")
				.title(title).eventDate(date).startTime(LocalTime.of(21, 0)).build(); em.persist(event); return event;
	}
	private void enableBand(Band band) {
		BandCalendarSettings preference = new BandCalendarSettings(band.getId());
		preference.setVisible(true); preference.setVersion(1);
		em.persist(preference);
	}
	private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
	private void await(CountDownLatch latch) {
		try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Calendar concurrency latch timed out"); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
	}
	private record Fixture(UUID userId, UUID profileId, UUID venueId) {}
}

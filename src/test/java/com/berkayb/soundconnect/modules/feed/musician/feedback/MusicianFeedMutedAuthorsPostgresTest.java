package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedMutedAuthorResponse;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedViewerGuard;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MusicianFeedMutedAuthorsPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("musician_muted_authors").withUsername("soundconnect").withPassword("soundconnect");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static final Instant NOW = Instant.parse("2026-09-13T15:20:00.123456Z");
    @Autowired DataSource dataSource;
    @Autowired MusicianFeedFeedbackRepository feedbacks;
    private JdbcTemplate sql;
    private NamedParameterJdbcTemplate jdbc;
    private MusicianFeedMutedAuthorsRepository repository;
    private MusicianFeedMutedAuthorsCursorCodec cursors;
    private MusicianFeedViewerGuard viewers;
    private MusicianFeedMutedAuthorsService service;
    private UUID viewer;

    @BeforeEach
    void setUp() {
        sql = new JdbcTemplate(dataSource);
        jdbc = spy(new NamedParameterJdbcTemplate(dataSource));
        repository = new MusicianFeedMutedAuthorsRepository(jdbc);
        cursors = new MusicianFeedMutedAuthorsCursorCodec(new ObjectMapper(), new MusicianFeedProperties());
        viewers = mock(MusicianFeedViewerGuard.class);
        viewer = person("MUSICIAN").user();
        service = serviceAt(NOW);
    }

    @Test
    void resolvesAllFivePublicProfileTypesInOneBoundedQueryWithCanonicalMusicianName() {
        List<Person> authors = new ArrayList<>();
        for (String type : List.of("MUSICIAN", "LISTENER", "STUDIO", "VENUE", "BAND")) {
            Person author = person(type);
            authors.add(author);
            mute(viewer, author.type(), author.profile(), UUID.randomUUID(), NOW.minusSeconds(1));
        }
        clearInvocations(jdbc);
        var page = service.get(viewer, 30, null);
        assertThat(page.items()).hasSize(5).allMatch(MusicianFeedMutedAuthorResponse::available);
        for (Person author : authors) {
            assertThat(page.items()).filteredOn(row -> row.profileId().equals(author.profile())).singleElement()
                    .satisfies(row -> {
                        assertThat(row.profileType()).isEqualTo(author.type());
                        assertThat(row.displayName()).isEqualTo(author.displayName());
                        assertThat(row.avatarUrl()).isEqualTo("https://cdn.test/legacy/" + author.user());
                        assertThat(row.mutedAt()).isEqualTo(NOW.minusSeconds(1));
                    });
        }
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasMore()).isFalse();
        verify(jdbc, times(1)).query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<MusicianFeedMutedAuthorsRepository.Row>>any());
        verify(viewers).requireMusicianProfile(viewer);
    }

    @Test
    void unavailableListenerRowsKeepOnlyThePersistedMuteIdentityAndTimestamp() {
        Person listener = person("LISTENER");
        mute(viewer, listener.type(), listener.profile(), UUID.randomUUID(), NOW.minusSeconds(1));
        assertThat(single().available()).isTrue();
        sql.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where id=?", listener.profile());
        unavailable(listener);
        sql.update("update \"tbl_listener-profile\" set visibility_mode='STANDARD',visibility_choice_completed=false where id=?", listener.profile());
        unavailable(listener);
        sql.update("update \"tbl_listener-profile\" set visibility_choice_completed=true where id=?", listener.profile());
        for (String forbiddenRole : List.of("ROLE_ADMIN", "ROLE_OWNER", "ROLE_MUSICIAN", "ROLE_VENUE",
                "ROLE_STUDIO", "ROLE_ORGANIZER", "ROLE_PRODUCER")) {
            UUID role = role(listener.user(), forbiddenRole);
            unavailable(listener);
            sql.update("delete from user_roles where user_id=? and role_id=?", listener.user(), role);
        }
        UUID producer = UUID.randomUUID();
        sql.update("insert into tbl_producer_profile(id,user_id) values (?,?)", producer, listener.user());
        unavailable(listener);
        sql.update("delete from tbl_producer_profile where id=?", producer);
        sql.update("update tbl_user set email_verified=false where id=?", listener.user());
        unavailable(listener);
        sql.update("update tbl_user set email_verified=true,status='INACTIVE' where id=?", listener.user());
        unavailable(listener);
        sql.update("update tbl_user set status='ACTIVE',erased_at=? where id=?", Timestamp.from(NOW), listener.user());
        unavailable(listener);
        sql.update("update tbl_user set erased_at=null where id=?", listener.user());
        sql.update("delete from \"tbl_listener-profile\" where id=?", listener.profile());
        unavailable(listener);
    }

    @Test
    void privateConfiguredAvatarNeverFallsBackToItsOldLegacyUrl() {
        Person musician = person("MUSICIAN");
        mute(viewer, musician.type(), musician.profile(), UUID.randomUUID(), NOW.minusSeconds(1));
        UUID media = UUID.randomUUID();
        String oldUrl = "https://cdn.test/legacy/" + musician.user();
        sql.update("""
                insert into tbl_media_asset(id,created_at,updated_at,kind,status,visibility,owner_type,owner_id,
                    source_url,mime_type,size,streaming_protocol,transcode_attempt_count,
                    transcode_retry_pending,transcode_retain_source_after_cleanup) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, media, stamp(), stamp(), "IMAGE", "READY", "PUBLIC", "MUSICIAN_PROFILE", musician.profile(),
                oldUrl, "image/jpeg", 123L, "PROGRESSIVE", 0, false, false);
        sql.update("update tbl_musician_profile set profile_picture_media_id=? where id=?", media, musician.profile());
        assertThat(single().avatarUrl()).isEqualTo(oldUrl);
        sql.update("update tbl_media_asset set visibility='PRIVATE' where id=?", media);
        assertThat(single().available()).isTrue();
        assertThat(single().avatarUrl()).isNull();
        sql.update("update tbl_musician_profile set profile_picture_media_id=null where id=?", musician.profile());
        assertThat(single().avatarUrl()).isEqualTo(oldUrl);
    }

    @Test
    void venueApprovalAndAnyPublicBandMemberDetermineAvailabilityWithoutDroppingPreferences() {
        Person venue = person("VENUE");
        mute(viewer, venue.type(), venue.profile(), UUID.randomUUID(), NOW.minusSeconds(1));
        sql.update("update tbl_venues set status='PENDING' where id=?", venue.profile());
        unavailable(venue);
        sql.update("delete from tbl_musician_feed_feedback where viewer_user_id=?", viewer);
        Person band = person("BAND");
        mute(viewer, band.type(), band.profile(), UUID.randomUUID(), NOW.minusSeconds(1));
        sql.update("update tbl_user set status='INACTIVE' where id=?", band.user());
        unavailable(band);
        Person publicMember = person("MUSICIAN");
        bandMember(band.profile(), publicMember.user(), "MEMBER");
        assertThat(single().available()).isTrue();
        assertThat(single().displayName()).isEqualTo(band.displayName());
    }

    @Test
    void keysetSurvivesDeletedBoundaryEqualTimestampsAndNewMutesWithoutDuplicates() {
        Instant mutedAt = NOW.minusNanos(1000);
        for (int index = 1; index <= 7; index++) {
            mute(viewer, "MUSICIAN", new UUID(0, index), new UUID(0, index), mutedAt);
        }
        var first = service.get(viewer, 2, null);
        assertThat(first.items()).extracting(MusicianFeedMutedAuthorResponse::profileId)
                .containsExactly(new UUID(0, 7), new UUID(0, 6));
        assertThat(first.hasMore()).isTrue();
        // The boundary is a value, not a row lookup: unmuting it must not break the next page.
        sql.update("delete from tbl_musician_feed_feedback where id in (?,?)", new UUID(0, 6), new UUID(0, 4));
        mute(viewer, "MUSICIAN", new UUID(0, 8), new UUID(0, 8), NOW.plusSeconds(1));
        var second = serviceAt(NOW.plusSeconds(2)).get(viewer, 2, first.nextCursor());
        assertThat(second.items()).extracting(MusicianFeedMutedAuthorResponse::profileId)
                .containsExactly(new UUID(0, 5), new UUID(0, 3));
        var last = serviceAt(NOW.plusSeconds(2)).get(viewer, 50, second.nextCursor());
        assertThat(last.items()).extracting(MusicianFeedMutedAuthorResponse::profileId)
                .containsExactly(new UUID(0, 2), new UUID(0, 1));
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
        assertThat(serviceAt(NOW.plusSeconds(2)).get(viewer, 1, null).items().getFirst().profileId())
                .isEqualTo(new UUID(0, 8));
    }

    @Test
    void listAndUnmuteRemainViewerScopedEvenAfterTheAuthorIsGone() {
        UUID otherViewer = person("MUSICIAN").user();
        UUID gone = UUID.randomUUID();
        mute(viewer, "LISTENER", gone, UUID.randomUUID(), NOW.minusSeconds(1));
        mute(otherViewer, "LISTENER", gone, UUID.randomUUID(), NOW.minusSeconds(1));
        mute(otherViewer, "BAND", UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(2));
        assertThat(service.get(viewer, 30, null).items()).hasSize(1);
        var authorGuard = spy(new MusicianFeedAuthorProfileGuard(jdbc));
        var feedback = new MusicianFeedFeedbackService(feedbacks, viewers, authorGuard,
                mock(MusicianFeedDeliveryService.class), mock(MusicianFeedReportDispatcher.class),
                mock(MusicianFeedFeedbackLock.class), mock(MusicianFeedFeedbackReader.class));
        feedback.unmute(viewer, " listener ", gone);
        feedbacks.flush();
        feedback.unmute(viewer, "LISTENER", gone);
        feedbacks.flush();
        assertThat(service.get(viewer, null, null).items()).isEmpty();
        assertThat(service.get(otherViewer, null, null).items()).hasSize(2);
        verify(authorGuard, never()).requireEligibleNotOwned(any(), anyString(), any());
    }

    @Test
    void defaultLimitAndViewerBoundCursorNeverReadAnotherViewersMutes() {
        for (int index = 0; index < 35; index++) {
            mute(viewer, "STUDIO", UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(1));
        }
        var first = service.get(viewer, null, null);
        assertThat(first.items()).hasSize(30);
        assertThat(first.hasMore()).isTrue();
        assertThat(service.get(viewer, 50, null).items()).hasSize(35);
        UUID otherViewer = person("MUSICIAN").user();
        clearInvocations(jdbc);
        assertThatThrownBy(() -> service.get(otherViewer, 30, first.nextCursor()))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_CURSOR_INVALID));
        verifyNoInteractions(jdbc);
    }

    private MusicianFeedMutedAuthorsService serviceAt(Instant now) {
        return new MusicianFeedMutedAuthorsService(viewers, repository, cursors, Clock.fixed(now, ZoneOffset.UTC));
    }

    private MusicianFeedMutedAuthorResponse single() {
        var page = service.get(viewer, null, null);
        assertThat(page.items()).hasSize(1);
        return page.items().getFirst();
    }

    private void unavailable(Person person) {
        var author = single();
        assertThat(author.profileType()).isEqualTo(person.type());
        assertThat(author.profileId()).isEqualTo(person.profile());
        assertThat(author.mutedAt()).isEqualTo(NOW.minusSeconds(1));
        assertThat(author.available()).isFalse();
        assertThat(author.displayName()).isNull();
        assertThat(author.avatarUrl()).isNull();
    }

    private void mute(UUID user, String type, UUID profile, UUID id, Instant at) {
        sql.update("""
                insert into tbl_musician_feed_feedback(id,viewer_user_id,action,scope_key,
                    author_profile_type,author_profile_id,created_at,updated_at) values (?,?,?,?,?,?,?,?)
                """, id, user, "MUTE_AUTHOR", "AUTHOR:" + type + ":" + profile, type, profile,
                Timestamp.from(at), Timestamp.from(at));
    }

    private Person person(String type) {
        UUID user = UUID.randomUUID(), profile = UUID.randomUUID();
        String username = "u" + user.toString().replace("-", "").substring(0, 20);
        String displayName = type.equals("MUSICIAN") ? username : "Public " + type;
        sql.update("""
                insert into tbl_user(id,created_at,updated_at,public_code,user_name,password,email,status,
                    provider,email_verified,profile_picture) values (?,?,?,?,?,?,?,?,?,?,?)
                """, user, stamp(), stamp(), "SC-" + user.toString().substring(0, 18).toUpperCase(Locale.ROOT),
                username, "unused", username + "@soundconnect.test", "ACTIVE", "LOCAL", true,
                "https://cdn.test/legacy/" + user);
        switch (type) {
            case "MUSICIAN" -> sql.update("insert into tbl_musician_profile(id,created_at,updated_at,user_id,stage_name) values (?,?,?,?,?)",
                    profile, stamp(), stamp(), user, "Never expose stage name");
            case "LISTENER" -> {
                sql.update("""
                        insert into "tbl_listener-profile"(id,created_at,updated_at,user_id,name,visibility_mode,
                            visibility_choice_completed,version,playlist_revision) values (?,?,?,?,?,?,?,?,?)
                        """, profile, stamp(), stamp(), user, displayName, "STANDARD", true, 0L, 0L);
                role(user, "ROLE_LISTENER");
            }
            case "STUDIO" -> sql.update("""
                    insert into tbl_studio_profile(id,created_at,updated_at,user_id,name,time_zone,version)
                    values (?,?,?,?,?,?,?)
                    """, profile, stamp(), stamp(), user, displayName, "Europe/Istanbul", 0L);
            case "VENUE" -> venue(profile, user, displayName);
            case "BAND" -> {
                sql.update("insert into tbl_band(id,created_at,updated_at,name) values (?,?,?,?)",
                        profile, stamp(), stamp(), displayName);
                bandMember(profile, user, "FOUNDER");
            }
            default -> throw new IllegalArgumentException(type);
        }
        return new Person(user, profile, type, displayName);
    }

    private void venue(UUID venue, UUID user, String name) {
        UUID city = UUID.randomUUID(), district = UUID.randomUUID(), neighborhood = UUID.randomUUID();
        sql.update("insert into tbl_city(id,created_at,updated_at,name) values (?,?,?,?)", city, stamp(), stamp(), "City " + city);
        sql.update("insert into tbl_district(id,created_at,updated_at,name,city_id) values (?,?,?,?,?)", district, stamp(), stamp(), "District", city);
        sql.update("insert into tbl_neighborhood(id,created_at,updated_at,name,district_id) values (?,?,?,?,?)", neighborhood, stamp(), stamp(), "Neighborhood", district);
        sql.update("""
                insert into tbl_venues(id,created_at,updated_at,name,address,city_id,district_id,neighborhood_id,status,owner_id)
                values (?,?,?,?,?,?,?,?,?,?)
                """, venue, stamp(), stamp(), name, "Address", city, district, neighborhood, "APPROVED", user);
    }

    private void bandMember(UUID band, UUID user, String role) {
        sql.update("""
                insert into tbl_band_member(id,created_at,updated_at,band_id,user_id,band_role,status,title_version)
                values (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), stamp(), stamp(), band, user, role, "ACTIVE", 0L);
    }

    private UUID role(UUID user, String name) {
        var existing = sql.queryForList("select id from tbl_role where name=?", UUID.class, name);
        UUID role = existing.isEmpty() ? UUID.randomUUID() : existing.getFirst();
        if (existing.isEmpty()) sql.update("insert into tbl_role(id,created_at,updated_at,name) values (?,?,?,?)", role, stamp(), stamp(), name);
        sql.update("insert into user_roles(user_id,role_id) values (?,?)", user, role);
        return role;
    }

    private Timestamp stamp() { return Timestamp.from(NOW.minusSeconds(120)); }
    private record Person(UUID user, UUID profile, String type, String displayName) { }
}

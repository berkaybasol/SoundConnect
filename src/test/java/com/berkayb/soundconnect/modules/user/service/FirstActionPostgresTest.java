package com.berkayb.soundconnect.modules.user.service;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirstActionPostgresTest {
    @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.4-alpine");
    JdbcTemplate db;
    TransactionTemplate tx;
    MemberAnalyticsPreferenceService preferences;

    @BeforeAll void schema() throws Exception {
        var ds = new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
        db = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        preferences = new MemberAnalyticsPreferenceService(new NamedParameterJdbcTemplate(ds));
        db.execute("""
            create table tbl_user(id uuid primary key, status text default 'ACTIVE', email_verified boolean default true,
                erased_at timestamptz, access_suspended boolean default false, session_version integer default 1);
            create table tbl_role(id int primary key,name text);
            insert into tbl_role values (1,'ROLE_LISTENER'),(2,'ROLE_MUSICIAN'),(3,'ROLE_VENUE'),(4,'ROLE_STUDIO');
            create table user_roles(user_id uuid,role_id int);
            create table "tbl_listener-profile"(user_id uuid);
            create table tbl_musician_profile(id uuid primary key,user_id uuid);
            create table tbl_studio_profile(id uuid primary key,user_id uuid);
            create table tbl_organizer_profile(user_id uuid);
            create table tbl_producer_profile(user_id uuid);
            create table tbl_venues(id uuid primary key,owner_id uuid);
            create table tbl_venue_profile(id uuid,venue_id uuid,profile_picture_media_id uuid);
            create table tbl_table_group(owner_id uuid);
            create table tbl_table_group_participants(user_id uuid,application_id uuid,joined_at timestamptz,status text);
            create table tbl_overthinking_post(author_id uuid);
            create table tbl_comment(user_id uuid,is_deleted boolean default false);
            create table tbl_table_group_profile_share(owner_user_id uuid);
            create table tbl_overthinking_profile_share(owner_user_id uuid);
            create table tbl_event_audience_intent(user_id uuid,published_on_profile boolean);
            create table tbl_band_member(user_id uuid,status text,band_role text);
            create table artist_venue_connection_requests(musician_profile_id uuid,band_id uuid,venue_id uuid,status text);
            create table tbl_collab(owner_user_id uuid,status text,published_at timestamptz);
            create table tbl_marketplace_listing(owner_user_id uuid,status text,published_at timestamptz);
            create table tbl_collab_application(applicant_user_id uuid,status text);
            create table tbl_event(venue_id uuid,venue_calendar_approved boolean);
            create table tbl_studio_room(studio_profile_id uuid);
            create table tbl_studio_equipment(studio_profile_id uuid);
            create table tbl_tracks(owner_type text,owner_id uuid);
            create table tbl_profile_media(profile_type text,profile_id uuid,media_asset_id uuid);
            create table tbl_media_asset(id uuid,status text,kind text);
            """);
        for (String name : List.of("member-analytics-preference","analytics-reports","analytics-first-action","analytics-live-presence")) {
            db.execute(Files.readString(Path.of("scripts/db/2026-11-26-"+name+".sql")));
        }
    }

    UUID member(int role, boolean consent) {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            db.update("insert into tbl_user(id) values (?)",id);
            db.update("insert into user_roles values (?,?)",id,role);
            switch (role) {
                case 2 -> db.update("insert into tbl_musician_profile values (?,?)",id,id);
                case 3 -> db.update("insert into tbl_venues values (?,?)",id,id);
                case 4 -> db.update("insert into tbl_studio_profile values (?,?)",id,id);
            }
        });
        if (consent) grant(id);
        return id;
    }

    UUID grant(UUID id) {
        var result = tx.execute(s -> preferences.change(actor(id), new MemberAnalyticsPreferenceService.Change(
                MemberAnalyticsPreferenceService.Decision.GRANTED,MemberAnalyticsPreferenceService.POLICY_VERSION,0L)));
        return result.analyticsUserId();
    }

    static UserDetailsImpl actor(UUID id) {
        return UserDetailsImpl.fromUser(User.builder().id(id).sessionVersion(1).build());
    }

    String first(UUID id) {
        return db.queryForObject("select max(first_action) from tbl_analytics_week_cohort where user_id=?",String.class,id);
    }

    void expect(int role, String command, String expected) {
        UUID id = member(role,true);
        db.update(command,id);
        assertEquals(expected,first(id),command);
    }

    @Test void publishedRowsAttributeTheApprovedRoleAndAction() {
        expect(1,"insert into tbl_table_group values (?)","table_created");
        expect(1,"insert into tbl_table_group_participants values (?,gen_random_uuid(),clock_timestamp(),'PENDING')","table_join_requested");
        expect(2,"insert into tbl_overthinking_post values (?)","overthinking_published");
        expect(1,"insert into tbl_comment(user_id) values (?)","comment_published");
        expect(1,"insert into tbl_table_group_profile_share values (?)","profile_share_published");
        expect(1,"insert into tbl_overthinking_profile_share values (?)","profile_share_published");
        expect(1,"insert into tbl_event_audience_intent values (?,true)","profile_share_published");
        expect(2,"insert into tbl_event_audience_intent values (?,true)",null);
        expect(2,"insert into tbl_band_member values (?,'ACTIVE','FOUNDER')","band_created");
        expect(2,"insert into tbl_tracks values ('MUSICIAN_PROFILE',?)","profile_media_uploaded");
        expect(3,"insert into tbl_event values (?,true)","venue_event_published");
        expect(3,"insert into tbl_venue_profile values (gen_random_uuid(),?,gen_random_uuid())","profile_media_uploaded");
        expect(3,"insert into tbl_collab values (?,'OPEN',now())","collab_published");
        expect(4,"insert into tbl_marketplace_listing values (?,'PUBLISHED',now())","market_listing_published");
        expect(2,"insert into tbl_collab_application values (?,'PENDING')","collab_applied");
        expect(4,"insert into tbl_studio_room values (?)","studio_room_created");
        expect(4,"insert into tbl_studio_equipment values (?)","backline_equipment_added");
        expect(1,"insert into tbl_marketplace_listing values (?,'PUBLISHED',now())",null);
    }

    @Test void remoteAcceptanceNotRequestAndQueuedVideoNotFailedUpload() {
        UUID venue=member(3,true), musician=member(2,true), bandVenue=member(3,true);
        db.update("insert into artist_venue_connection_requests values (?,null,?,'PENDING')",musician,venue);
        assertNull(first(venue));
        assertNull(first(musician));
        db.update("update artist_venue_connection_requests set status='ACCEPTED' where musician_profile_id=?",musician);
        assertEquals("venue_musician_connected",first(musician));
        assertEquals("venue_musician_connected",first(venue));
        db.update("insert into artist_venue_connection_requests values (null,gen_random_uuid(),?,'ACCEPTED')",bandVenue);
        assertEquals("venue_band_connected",first(bandVenue));

        UUID invitee=member(2,true), video=member(2,true);
        db.update("insert into tbl_band_member values (?,'PENDING','MEMBER')",invitee);
        assertNull(first(invitee));
        db.update("update tbl_band_member set status='ACTIVE' where user_id=?",invitee);
        assertEquals("band_joined",first(invitee));
        db.update("insert into tbl_media_asset values (?,'PROCESSING','VIDEO')",video);
        db.update("insert into tbl_profile_media values ('MUSICIAN',?,?)",video,video);
        assertNull(first(video));
        db.update("update tbl_media_asset set status='READY' where id=?",video);
        assertEquals("profile_media_uploaded",first(video));
        UUID venueImage=member(3,true);
        db.update("insert into tbl_media_asset values (?,'READY','IMAGE')",venueImage);
        db.update("insert into tbl_profile_media values ('VENUE',?,?)",venueImage,venueImage);
        assertEquals("profile_media_uploaded",first(venueImage));
    }

    @Test void consentWindowRollbackAndPublicationBoundaries() {
        UUID listener=member(1,false);
        db.update("insert into tbl_comment(user_id) values (?)",listener);
        grant(listener);
        assertNull(first(listener));
        db.update("insert into tbl_table_group_participants values (?,gen_random_uuid(),now()-interval '2 days','PENDING')",listener);
        assertNull(first(listener));
        tx.executeWithoutResult(s -> {
            db.update("insert into tbl_table_group values (?)",listener);
            s.setRollbackOnly();
        });
        assertNull(first(listener));
        UUID studio=member(4,true);
        db.update("insert into tbl_marketplace_listing values (?,'DRAFT',null)",studio);
        assertNull(first(studio));
        db.update("update tbl_marketplace_listing set status='PUBLISHED',published_at=now() where owner_user_id=?",studio);
        db.update("insert into tbl_studio_room values (?)",studio);
        assertEquals("market_listing_published",first(studio));
        db.update("update tbl_analytics_account_opening set opened_at=now()-interval '168 hours' where user_id=?",listener);
        db.update("insert into tbl_table_group values (?)",listener);
        assertNull(first(listener));
        UUID changedRole=member(1,true);
        db.update("update user_roles set role_id=2 where user_id=?",changedRole);
        db.update("insert into tbl_comment(user_id) values (?)",changedRole);
        assertNull(first(changedRole));
    }

    @Test void twoDevicesCanClaimOnceOnlyWithinTheSameGrant() throws Exception {
        UUID member=member(2,false), consent=grant(member);
        db.queryForObject("select sc_record_first_action(?,'spotify_track_added',array['MUSICIAN']) is not null",Boolean.class,member);
        assertEquals("spotify_track_added",first(member));
        assertNull(tx.execute(s -> preferences.claimFirstAction(actor(member),UUID.randomUUID())));
        try (var workers=Executors.newFixedThreadPool(2)) {
            var work=(java.util.concurrent.Callable<Boolean>) () -> tx.execute(s -> preferences.claimFirstAction(actor(member),consent))!=null;
            var a=workers.submit(work);
            var b=workers.submit(work);
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        UUID withdrawn=member(1,false), old=grant(withdrawn);
        db.update("insert into tbl_table_group values (?)",withdrawn);
        var user=actor(withdrawn);
        tx.execute(s -> preferences.change(user,new MemberAnalyticsPreferenceService.Change(
                MemberAnalyticsPreferenceService.Decision.DENIED,MemberAnalyticsPreferenceService.POLICY_VERSION,1L)));
        assertNull(tx.execute(s -> preferences.claimFirstAction(user,old)));
        var fresh=tx.execute(s -> preferences.change(user,new MemberAnalyticsPreferenceService.Change(
                MemberAnalyticsPreferenceService.Decision.GRANTED,MemberAnalyticsPreferenceService.POLICY_VERSION,2L)));
        assertNull(tx.execute(s -> preferences.claimFirstAction(user,fresh.analyticsUserId())));
        db.update("update tbl_user set erased_at=now() where id=?",withdrawn);
        assertEquals(0,db.queryForObject("select count(*) from tbl_analytics_first_action where user_id=?",Integer.class,withdrawn));
        assertTrue(db.queryForObject("select exists(select from tbl_analytics_week_cohort where user_id is null and first_action='table_created')",Boolean.class));
    }

    @Test void profileChangeStopsFirstWeekSamplesAndPendingActionDeliveryForTheOldRole() {
        UUID member = member(1,false);
        UUID identity = grant(member);
        db.update("insert into tbl_table_group values (?)",member);
        assertEquals("table_created",first(member));
        db.update("update user_roles set role_id=2 where user_id=?",member);

        var duration = new com.berkayb.soundconnect.modules.analytics.report.FirstWeekDuration(new NamedParameterJdbcTemplate(db));
        var sample = new com.berkayb.soundconnect.modules.analytics.report.FirstWeekDuration.Sample(
                identity,UUID.randomUUID(),java.time.Instant.now().minusSeconds(1),1000L);
        assertFalse(tx.execute(s -> duration.add(actor(member),sample)).windowOpen());
        assertNull(tx.execute(s -> preferences.claimFirstAction(actor(member),identity)));
        assertEquals(0,db.queryForObject("select duration_ms from tbl_analytics_week_cohort where user_id=?",Integer.class,member));
        assertEquals("table_created",first(member));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-26", "2026-11-26-r2"})
    void oldConsentCannotEnrollRecordClaimOrSubmitTimeBeforeRenewal(String oldVersion) {
        UUID member=member(1,false), previousId=UUID.randomUUID();
        db.update("insert into tbl_member_analytics_preference values (?,'GRANTED',?,1,now(),?)",member,oldVersion,previousId);
        db.update("insert into tbl_table_group values (?)",member);
        assertEquals(0,db.queryForObject("select count(*) from tbl_analytics_week_cohort where user_id=?",Integer.class,member));
        assertNull(tx.execute(s -> preferences.claimFirstAction(actor(member),previousId)));
        var duration=new com.berkayb.soundconnect.modules.analytics.report.FirstWeekDuration(
                new NamedParameterJdbcTemplate(db));
        var sample=new com.berkayb.soundconnect.modules.analytics.report.FirstWeekDuration.Sample(previousId,UUID.randomUUID(),java.time.Instant.now(),1L);
        assertFalse(tx.execute(s -> duration.add(actor(member),sample)).windowOpen());
        var granted=tx.execute(s -> preferences.change(actor(member),new MemberAnalyticsPreferenceService.Change(
                MemberAnalyticsPreferenceService.Decision.GRANTED,MemberAnalyticsPreferenceService.POLICY_VERSION,1L)));
        assertNotEquals(previousId,granted.analyticsUserId());
        assertNull(first(member));
        db.update("insert into tbl_comment(user_id) values (?)",member);
        assertNull(tx.execute(s -> preferences.claimFirstAction(actor(member),previousId)));
        assertEquals("comment_published",tx.execute(s -> preferences.claimFirstAction(actor(member),granted.analyticsUserId())).actionType());
    }
}

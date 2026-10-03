package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.studio.reservation.enums.StudioReservationStatus;
import com.berkayb.soundconnect.modules.studio.reservation.support.StudioReservationTimeProvider;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL authorization/projection queries; no shared database or notification writes. */
@Testcontainers
class StudioReservationNotificationTargetPostgresTest {
    private static final String LISTENER_TABLE = "\"" + com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile.class
            .getAnnotation(jakarta.persistence.Table.class).name() + "\"";
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("studio_notification_target").withUsername("target_test").withPassword("target_test");
    private JdbcTemplate jdbc;
    private StudioReservationNotificationTargetService service;
    private final UUID owner=UUID.randomUUID(),customer=UUID.randomUUID(),stranger=UUID.randomUUID(),
            profile=UUID.randomUUID(),room=UUID.randomUUID(),reservation=UUID.randomUUID(),notification=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-09-24T10:00:00Z"),start=now.plusSeconds(86400),end=start.plusSeconds(3600);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();

    @BeforeEach void fixture() {
        var dataSource=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        assertThat(dataSource.getUrl()).isEqualTo(DB.getJdbcUrl());
        jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("drop schema public cascade"); jdbc.execute("create schema public");
        jdbc.execute("create table tbl_user(id uuid primary key,status text,email_verified boolean,erased_at timestamp)");
        jdbc.execute("create table tbl_role(id uuid primary key,name text)");
        jdbc.execute("create table user_roles(user_id uuid,role_id uuid)");
        jdbc.execute("create table " + LISTENER_TABLE + "(user_id uuid)");
        jdbc.execute("create table tbl_studio_profile(id uuid primary key,user_id uuid,name text,time_zone text)");
        jdbc.execute("create table tbl_studio_room(id uuid primary key,studio_profile_id uuid,name text,archived_at timestamptz)");
        jdbc.execute("create table tbl_studio_room_reservation(id uuid primary key,room_id uuid,requester_id uuid,status text,starts_at timestamptz,ends_at timestamptz)");
        jdbc.execute("create table tbl_notification(id uuid primary key,recipient_id uuid,type text,payload jsonb,is_read boolean default false)");
        for(var user:List.of(owner,customer,stranger)) jdbc.update("insert into tbl_user values(?,'ACTIVE',true,null)",user);
        role(owner,"ROLE_STUDIO"); role(customer,"ROLE_MUSICIAN"); role(stranger,"ROLE_PRODUCER");
        jdbc.update("insert into tbl_studio_profile values(?,?,?,?)",profile,owner,"Güncel Stüdyo","Europe/Istanbul");
        jdbc.update("insert into tbl_studio_room values(?,?,?,null)",room,profile,"Güncel Oda");
        jdbc.update("insert into tbl_studio_room_reservation values(?,?,?,'PENDING_APPROVAL',?,?)",
                reservation,room,customer,Timestamp.from(start),Timestamp.from(end));
        var time=mock(StudioReservationTimeProvider.class); when(time.now()).thenReturn(now);
        service=new StudioReservationNotificationTargetService(new NamedParameterJdbcTemplate(dataSource),time);
    }

    @ParameterizedTest @CsvSource({
            "CREATED,CREATED,true", "CONFLICTING_REQUESTS,CONFLICTING_REQUESTS,true",
            "APPROVED,APPROVED,false", "REJECTED,REJECTED,false", "REJECTED,AUTO_REJECTED_CONFLICT,false",
            "CANCELLED_BY_CUSTOMER,CANCELLED_BY_CUSTOMER,true", "CANCELLED_BY_STUDIO,CANCELLED_BY_STUDIO,false",
            "CANCELLED_BY_STUDIO,CANCELLED_BY_STUDIO_ROOM_ARCHIVED,false"})
    void allEventActionsResolveOnlyTheirCurrentAuthorizedAudience(String suffix,String action,boolean ownerMode) throws Exception {
        UUID reader=ownerMode?owner:customer;
        notification(reader,suffix,action);
        var target=service.resolve(reader,notification);
        assertThat(target.notificationId()).isEqualTo(notification);
        assertThat(target.recipientId()).isEqualTo(reader);
        assertThat(target.reservationId()).isEqualTo(reservation);
        assertThat(target.roomId()).isEqualTo(room);
        assertThat(target.studioProfileId()).isEqualTo(profile);
        assertThat(target.ownerMode()).isEqualTo(ownerMode);
        assertThat(target.studioName()).isEqualTo("Güncel Stüdyo");
        assertThat(target.roomName()).isEqualTo("Güncel Oda");
        assertThat(target.localDate()).isEqualTo(LocalDate.of(2026,9,25));
        assertThat(target.localStartTime()).isEqualTo(LocalTime.of(13,0));
        assertThat(target.status()).isEqualTo(StudioReservationStatus.PENDING_APPROVAL);
        unavailable(ownerMode?customer:owner);
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification where is_read",Integer.class)).isZero();
        assertThat(json.writeValueAsString(target)).doesNotContain("requesterId","phone","price","payload","clientRequestId");
    }

    @Test void oldApprovalResolvesCurrentCancellationAndArchivedHistoryWithoutReadingSiblings() throws Exception {
        notification(customer,"APPROVED","APPROVED");
        jdbc.update("insert into tbl_notification select ?,recipient_id,type,payload,false from tbl_notification",UUID.randomUUID());
        jdbc.update("update tbl_studio_room_reservation set status='CANCELLED_BY_STUDIO',starts_at=?,ends_at=?",
                Timestamp.from(now.minusSeconds(172800)),Timestamp.from(now.minusSeconds(169200)));
        jdbc.update("update tbl_studio_room set archived_at=?",Timestamp.from(now.minusSeconds(86400)));
        var result=service.resolve(customer,notification);
        assertThat(result.status()).isEqualTo(StudioReservationStatus.CANCELLED_BY_STUDIO);
        assertThat(result.roomArchived()).isTrue(); assertThat(result.completed()).isFalse();
        assertThat(result.localDate()).isEqualTo(LocalDate.of(2026,9,22));
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification where not is_read",Integer.class)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings={"PENDING_APPROVAL","CONFIRMED","REJECTED_BY_STUDIO","CANCELLED_BY_CUSTOMER","CANCELLED_BY_STUDIO","EXPIRED"})
    void pastStatesRemainReadableAndPendingExpiresWithoutMutatingDomain(String state) throws Exception {
        notification(owner,"CREATED","CREATED");
        jdbc.update("update tbl_studio_room_reservation set status=?,starts_at=?,ends_at=?",state,
                Timestamp.from(now.minusSeconds(7200)),Timestamp.from(now.minusSeconds(3600)));
        var result=service.resolve(owner,notification);
        assertThat(result.status().name()).isEqualTo(state.equals("PENDING_APPROVAL")?"EXPIRED":state);
        assertThat(result.completed()).isEqualTo(state.equals("CONFIRMED"));
        assertThat(jdbc.queryForObject("select status from tbl_studio_room_reservation",String.class)).isEqualTo(state);
    }

    @ParameterizedTest @ValueSource(strings={"reservationId","roomId","studioProfileId","requesterId"})
    void wrongPayloadSourceIdsNeverGrantAccess(String key) throws Exception {
        notification(customer,"APPROVED","APPROVED");
        payload(key,UUID.randomUUID().toString()); unavailable(customer);
    }

    @ParameterizedTest @ValueSource(strings={"reservationId","roomId","studioProfileId","requesterId","module","action"})
    void malformedOrMissingPayloadDoesNotResolve(String key) throws Exception {
        notification(customer,"APPROVED","APPROVED");
        payload(key,"not-an-id"); unavailable(customer);
        jdbc.update("update tbl_notification set payload=payload-?",key); unavailable(customer);
    }

    @Test void payloadCannotOverrideRecipientOrModeAndStaleNotificationStateIsNotAuthority() throws Exception {
        notification(stranger,"APPROVED","APPROVED");
        payload("ownerMode","true"); payload("recipientId",customer.toString()); unavailable(stranger);
        jdbc.update("update tbl_notification set recipient_id=?",customer);
        assertThat(service.resolve(customer,notification).ownerMode()).isFalse();
    }

    @Test void wrongTypeOrActionAndNonObjectPayloadAreRejected() throws Exception {
        notification(customer,"APPROVED","REJECTED"); unavailable(customer);
        jdbc.update("update tbl_notification set type='DM_NEW_MESSAGE'"); unavailable(customer);
        jdbc.update("update tbl_notification set type='STUDIO_RESERVATION_APPROVED',payload='[]'"); unavailable(customer);
    }

    @ParameterizedTest @ValueSource(strings={"INACTIVE","PENDING","PENDING_VENUE_REQUEST","PENDING_STUDIO_REQUEST"})
    void inactiveOrPendingReaderCannotResolveEvenWithStaleSession(String status) throws Exception {
        notification(customer,"APPROVED","APPROVED");
        jdbc.update("update tbl_user set status=? where id=?",status,customer); unavailable(customer);
    }

    @Test void verificationErasureRolelessAndListenerBoundariesAreFresh() throws Exception {
        notification(customer,"APPROVED","APPROVED");
        jdbc.update("update tbl_user set email_verified=false where id=?",customer); unavailable(customer);
        jdbc.update("update tbl_user set email_verified=true,erased_at=now() where id=?",customer); unavailable(customer);
        jdbc.update("update tbl_user set erased_at=null where id=?",customer);
        role(customer,"ROLE_LISTENER"); unavailable(customer);
        jdbc.update("delete from user_roles where role_id in(select id from tbl_role where name='ROLE_LISTENER')");
        jdbc.update("insert into " + LISTENER_TABLE + " values(?)",customer); unavailable(customer);
        jdbc.update("delete from " + LISTENER_TABLE);
        assertThat(service.resolve(customer,notification)).isNotNull();
        jdbc.update("delete from user_roles where user_id=?",customer); unavailable(customer);
    }

    @Test void lostStudioRoleOrTransferredProfileCannotOpenAnOwnerNotification() throws Exception {
        notification(owner,"CREATED","CREATED");
        jdbc.update("delete from user_roles where user_id=?",owner); role(owner,"ROLE_MUSICIAN"); unavailable(owner);
        role(owner,"ROLE_STUDIO");
        jdbc.update("update tbl_studio_profile set user_id=?",stranger); unavailable(owner);
    }

    @Test void reassignedRequesterCannotReadOldRecipientNotification() throws Exception {
        notification(customer,"APPROVED","APPROVED");
        jdbc.update("update tbl_studio_room_reservation set requester_id=?",stranger); unavailable(customer);
        payload("requesterId",stranger.toString()); unavailable(customer);
    }

    @Test void deletedNotificationOrDomainTargetCannotResolve() throws Exception {
        notification(customer,"APPROVED","APPROVED");
        assertThatThrownBy(()->service.resolve(customer,UUID.randomUUID())).isInstanceOf(SoundConnectException.class);
        jdbc.update("delete from tbl_studio_room_reservation"); unavailable(customer);
        jdbc.update("delete from tbl_notification"); unavailable(customer);
        assertThatThrownBy(()->service.resolve(null,notification)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(()->service.resolve(customer,null)).isInstanceOf(SoundConnectException.class);
    }

    @Test void currentTimezoneControlsDateAndLegacyInvalidZoneUsesBookingFallback() throws Exception {
        notification(customer,"APPROVED","APPROVED");
        jdbc.update("update tbl_studio_profile set time_zone='Pacific/Honolulu'");
        var result=service.resolve(customer,notification);
        assertThat(result.zoneId()).isEqualTo("Pacific/Honolulu");
        assertThat(result.localStartTime()).isEqualTo(LocalTime.MIDNIGHT);
        jdbc.update("update tbl_studio_profile set time_zone='bogus'");
        assertThat(service.resolve(customer,notification).zoneId()).isEqualTo("Europe/Istanbul");
    }

    private void notification(UUID recipient,String suffix,String action) throws Exception {
        var data=new HashMap<String,Object>(); data.put("module","STUDIO"); data.put("action",action);
        data.put("reservationId",reservation.toString()); data.put("roomId",room.toString());
        data.put("studioProfileId",profile.toString()); data.put("requesterId",customer.toString());
        data.put("studioName","stale name"); data.put("roomName","stale room"); data.put("status","CONFIRMED");
        jdbc.update("insert into tbl_notification(id,recipient_id,type,payload) values(?,?,?,?::jsonb)",
                notification,recipient,"STUDIO_RESERVATION_"+suffix,json.writeValueAsString(data));
    }
    private void role(UUID user,String name) {
        var id=UUID.randomUUID(); jdbc.update("insert into tbl_role values(?,?)",id,name);
        jdbc.update("insert into user_roles values(?,?)",user,id);
    }
    private void payload(String key,String value) throws Exception {
        jdbc.update("update tbl_notification set payload=jsonb_set(payload,array[?],?::jsonb)",key,json.writeValueAsString(value));
    }
    private void unavailable(UUID reader) {
        assertThatThrownBy(()->service.resolve(reader,notification)).isInstanceOf(SoundConnectException.class);
    }
}

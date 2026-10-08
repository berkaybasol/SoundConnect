package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.campaign.*;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.service.*;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.berkayb.soundconnect.modules.notification.campaign.CampaignContract.*;

/** Runs the real campaign SQL, inbox transaction and push planner against one disposable PostgreSQL. */
@Testcontainers
class CampaignPostgresTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.4-alpine")
        .withDatabaseName("soundconnect_push_test").withUsername("push_test").withPassword("isolated-fixture");
    final PushFoundationPostgresTest f=new PushFoundationPostgresTest();
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    CampaignService service;CampaignWorker worker;CampaignAccess access;CampaignEligibility eligibility;
    CampaignTargets targetResolver;
    AtomicBoolean fail=new AtomicBoolean();
    Instant now=Instant.parse("2026-10-07T12:00:00Z");
    @BeforeEach void setup()throws Exception {
        f.clock=Clock.fixed(now,ZoneOffset.UTC);
        f.setup("jdbc:postgresql://127.0.0.1:"+DB.getMappedPort(5432)+"/soundconnect_push_test",DB.getUsername(),DB.getPassword());
        f.jdbc.execute("alter table tbl_user add user_name text,add created_at timestamp default '2020-01-01'");
        f.jdbc.execute("alter table tbl_notification add source_event_id uuid unique,add title text,add message text,add payload jsonb,add occurred_at timestamptz");
        f.jdbc.execute("create table tbl_notification_receipt(source_event_id uuid primary key,recipient_id uuid,recorded_at timestamptz default now())");
        f.jdbc.execute("create table tbl_role(id uuid primary key,name text);create table user_roles(user_id uuid,role_id uuid)");
        for (String table : PROFILE_TABLES.values()) {
            if (!table.equals("tbl_venues")) {
                f.jdbc.execute("create table " + table + "(id uuid primary key,user_id uuid)");
            }
        }
        for(UUID id:List.of(f.user,f.other)){f.jdbc.update("update tbl_user set user_name=? where id=?","user-"+id,id);profile(id,"MUSICIAN");}
        role(f.user,"ADMIN");
        f.jdbc.execute("insert into soundconnect_schema_migrations(migration_id) values('2026-09-30-table-notification-target')");
        for(String family:List.of("table","collab","overthinking")) f.jdbc.execute(Files.readString(Path.of("scripts/db/2026-10-01-push-native-"+family+"-capability.sql")));
        String migration=Files.readString(Path.of("scripts/db/2026-10-07-notification-campaigns.sql"));f.jdbc.execute(migration);f.jdbc.execute(migration);
        var users=mock(UserRepository.class);
        when(users.findRoleNamesByUserId(any())).thenAnswer(c->new HashSet<>(f.jdbc.queryForList("select r.name from tbl_role r join user_roles ur on ur.role_id=r.id where ur.user_id=?",String.class,(UUID)c.getArgument(0))));
        // Use the production native projection, including venue owner_id and the quoted listener table.
        // The repository adapter is mocked; the SQL and database result are not substituted.
        String profileSql = UserRepository.class.getMethod("findExistingPersonalProfileRoleNames", UUID.class)
                .getAnnotation(Query.class).value();
        when(users.findExistingPersonalProfileRoleNames(any())).thenAnswer(c -> Set.copyOf(f.sql.queryForList(
                profileSql, Map.of("userId", c.getArgument(0, UUID.class)), String.class)));
        var campaignStore=new CampaignStore(f.sql,json);access=new CampaignAccess(f.sql,users);eligibility=new CampaignEligibility(campaignStore,access);
        targetResolver=new CampaignTargets(campaignStore,access,mock(com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard.class),
            mock(com.berkayb.soundconnect.modules.event.service.EventService.class),mock(com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository.class),
            mock(com.berkayb.soundconnect.modules.media.mapper.MediaAssetMapper.class),mock(com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService.class),f.transactionManager);
        var policy=new NotificationDeliveryPolicy(new AccountDeliveryFence(f.sql),f.sql,f.transactionManager,mock(AfterCommitDeliveryExecutor.class));
        ReflectionTestUtils.setField(policy,"campaigns",eligibility);ReflectionTestUtils.setField(f.store,"policy",policy);
        var receipts=mock(NotificationReceiptRepository.class);
        when(receipts.claim(any(),any())).thenAnswer(c->f.jdbc.update("insert into tbl_notification_receipt(source_event_id,recipient_id) values(?,?) on conflict do nothing",(UUID)c.getArgument(0),(UUID)c.getArgument(1)));
        when(f.notifications.existsBySourceEventId(any())).thenAnswer(c->f.jdbc.queryForObject("select exists(select 1 from tbl_notification where source_event_id=?)",Boolean.class,(UUID)c.getArgument(0)));
        when(f.notifications.saveAndFlush(any())).thenAnswer(c->{
            if(fail.getAndSet(false))throw new IllegalStateException("injected persistence failure");
            Notification n=c.getArgument(0);ReflectionTestUtils.setField(n,"id",UUID.randomUUID());
            f.jdbc.update("insert into tbl_notification(id,recipient_id,type,source_event_id,title,message,payload,occurred_at) values(?,?,?,?,?,?,cast(? as jsonb),?)",
                n.getId(),n.getRecipientId(),n.getType().name(),n.getSourceEventId(),n.getTitle(),n.getMessage(),json.writeValueAsString(n.getPayload()),Timestamp.from(n.getOccurredAt()));
            f.inbox.put(n.getId(),n);return n;
        });
        ApplicationEventPublisher publisher=event->f.planner.plan((NotificationPersisted)event);
        var persistence=new TransactionalNotificationService(f.notifications,mock(NotificationMapper.class),mock(NotificationBadgeCacheHelper.class),
            mock(NotificationWebSocketService.class),mock(NotificationService.class),receipts,policy,publisher);
        worker=new CampaignWorker(campaignStore,access,persistence);
        service=new CampaignService(campaignStore,access,mock(CampaignTargets.class));ReflectionTestUtils.setField(service,"enabled",true);
        f.properties.setAllowedTypes(Set.of(NotificationType.ADMIN_BROADCAST));
    }
    @AfterEach void cleanup(){f.cleanup();}
    void role(UUID id,String name){UUID role=UUID.randomUUID();f.jdbc.update("insert into tbl_role values(?,?)",role,"ROLE_"+name);f.jdbc.update("insert into user_roles values(?,?)",id,role);}
    static final Map<String, String> PROFILE_TABLES = Map.of(
            "MUSICIAN", "tbl_musician_profile", "LISTENER", "\"tbl_listener-profile\"",
            "VENUE", "tbl_venues", "STUDIO", "tbl_studio_profile",
            "ORGANIZER", "tbl_organizer_profile", "PRODUCER", "tbl_producer_profile");
    static final List<String> SUPPORTED_PROFILES = List.of("MUSICIAN", "LISTENER", "VENUE", "STUDIO");
    void profile(UUID id,String name){role(id,name);profileOnly(id,name);}
    void profileOnly(UUID id, String name) {
        f.jdbc.update("insert into " + PROFILE_TABLES.get(name) + "(id," +
                (name.equals("VENUE") ? "owner_id" : "user_id") + ") values(?,?)", UUID.randomUUID(), id);
    }
    UUID recipient(String name, String profile) {
        UUID id = UUID.randomUUID();
        f.jdbc.update("insert into tbl_user(id,status,email_verified,user_name,created_at) values(?,'ACTIVE',true,?,'2020-01-01')", id, name);
        if (profile != null) profile(id, profile);
        return id;
    }
    void clearSeedProfiles() {
        for (String table : PROFILE_TABLES.values()) f.jdbc.update("delete from " + table);
    }
    Write write(Audience audience,Repeat repeat){return new Write(UUID.randomUUID(),null,"Özel başlık","Özel gövde",audience,new Target(Kind.HOME,null),
        new Schedule(now.minusSeconds(60),"Europe/Istanbul",repeat,null,Set.of(),null,3,null,null));}
    Campaign create(Write w){return f.tx.execute(s->service.create(f.user,w));}
    Campaign schedule(Campaign c){return f.tx.execute(s->service.action(f.user,c.id(),"schedule",new Version(c.version())));}
    void work(UUID id){f.tx.executeWithoutResult(s->worker.process(id,now));}
    long count(String table){return f.jdbc.queryForObject("select count(*) from "+table,Long.class);}
    void device(UUID user,String version){f.tx.executeWithoutResult(s->f.devices.register(user,UUID.randomUUID(),new PushDeviceService.Registration(UUID.randomUUID().toString(),PushDeviceService.Platform.ANDROID,PushDeviceService.Permission.AUTHORIZED,"fixture",1L,version)));}
    @Test void idempotentCreateAndConcurrentWorkersProduceOneOwnedInboxAndV11PushOnly()throws Exception{
        var w=write(new Audience(Mode.USERS,Set.of(),Set.of(f.other)),Repeat.ONCE);var draft=create(w);
        assertThat(create(w).id()).isEqualTo(draft.id());assertThat(count("tbl_notification_campaign")).isEqualTo(1);
        device(f.other,CustomPushPresentation.CAPABILITY);device(f.other,OverthinkingPushPresentation.CAPABILITY);
        var c=schedule(draft);var executor=Executors.newFixedThreadPool(2);
        try{var a=executor.submit(()->work(c.id()));var b=executor.submit(()->work(c.id()));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
        work(c.id());
        assertThat(count("tbl_notification_campaign_occurrence")).isEqualTo(1);assertThat(count("tbl_notification_campaign_recipient")).isEqualTo(1);
        assertThat(count("tbl_notification")).isEqualTo(1);assertThat(count("tbl_push_delivery")).isEqualTo(1);
        var claim=f.store.claimNext().orElseThrow();var envelope=f.store.prepare(claim).orElseThrow();
        assertThat(envelope.data()).hasSize(8).containsEntry("title",w.title()).doesNotContainKeys("campaignId","targetKind","targetId");
        assertThat(service.get(f.user,c.id()).status()).isEqualTo(Status.COMPLETED);
    }
    @Test void rollbackRetainsNoCursorReceiptInboxOrPushAndRestartRetriesTheOccurrence(){
        device(f.other,CustomPushPresentation.CAPABILITY);var c=schedule(create(write(new Audience(Mode.USERS,Set.of(),Set.of(f.other)),Repeat.DAILY)));
        fail.set(true);assertThatThrownBy(()->work(c.id())).isInstanceOf(IllegalStateException.class);
        for(String t:List.of("tbl_notification_campaign_occurrence","tbl_notification_campaign_recipient","tbl_notification_receipt","tbl_notification","tbl_push_delivery"))assertThat(count(t)).as(t).isZero();
        work(c.id());assertThat(count("tbl_notification")).isEqualTo(1);assertThat(service.get(f.user,c.id()).nextRunAt()).isAfter(now);
    }
    @Test void pauseCancelVersionAndLatePreferenceAreLive(){
        device(f.other,CustomPushPresentation.CAPABILITY);var c=schedule(create(write(new Audience(Mode.USERS,Set.of(),Set.of(f.other)),Repeat.DAILY)));
        var paused=f.tx.execute(s->service.action(f.user,c.id(),"pause",new Version(c.version())));work(c.id());assertThat(count("tbl_notification")).isZero();
        assertThatThrownBy(()->f.tx.execute(s->service.action(f.user,c.id(),"resume",new Version(c.version())))).isInstanceOf(RuntimeException.class);
        var resumed=f.tx.execute(s->service.action(f.user,c.id(),"resume",new Version(paused.version())));work(c.id());
        f.tx.executeWithoutResult(s->f.devices.updatePreferences(f.other,new PushDeviceService.Preferences(true,Set.of("CUSTOM"))));
        var claim=f.store.claimNext().orElseThrow();assertThat(f.store.prepare(claim)).isEmpty();
        var current=service.get(f.user,c.id());f.tx.execute(s->service.action(f.user,c.id(),"cancel",new Version(current.version())));
        work(c.id());assertThat(count("tbl_notification")).isEqualTo(1);
    }
    @Test void chunkCursorResumesAndEligibilityIsRecheckedForEachUser(){
        for(int i=0;i<65;i++){UUID id=UUID.randomUUID();f.jdbc.update("insert into tbl_user(id,status,email_verified,user_name,created_at) values(?,'ACTIVE',true,?,'2020-01-01')",id,"recipient-"+i);profile(id,"LISTENER");}
        var c=schedule(create(write(new Audience(Mode.PROFILE_TYPES,Set.of("LISTENER"),Set.of()),Repeat.ONCE)));
        work(c.id());assertThat(count("tbl_notification")).isLessThanOrEqualTo(50);
        work(c.id());work(c.id());assertThat(count("tbl_notification")).isEqualTo(65);
        assertThat(service.get(f.user,c.id()).status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void runningFanoutStopsAtExclusiveEndAndPreservesDeliveredTargets() {
        Instant firstBatch = Instant.now().plusSeconds(60);
        Instant end = firstBatch.plus(Duration.ofDays(1));
        var campaign = schedule(create(boundedAudienceWrite(firstBatch, end, 2)));
        f.tx.executeWithoutResult(s -> worker.process(campaign.id(), firstBatch));
        assertThat(count("tbl_notification")).isEqualTo(50);
        assertThat(f.jdbc.queryForObject("select status from tbl_notification_campaign_occurrence", String.class))
                .isEqualTo("RUNNING");

        f.tx.executeWithoutResult(s -> worker.process(campaign.id(), end));
        assertExpiredPartialFanout(campaign.id());
        f.tx.executeWithoutResult(s -> worker.process(campaign.id(), end.plusSeconds(1)));
        assertExpiredPartialFanout(campaign.id());
    }

    @Test
    void expiredResumeCompletesWithoutRevivingPartialOccurrence() {
        Instant firstBatch = Instant.now().plusSeconds(60);
        Instant end = firstBatch.plus(Duration.ofDays(1));
        var campaign = schedule(create(boundedAudienceWrite(firstBatch, end, 2)));
        f.tx.executeWithoutResult(s -> worker.process(campaign.id(), firstBatch));
        var paused = f.tx.execute(s -> service.action(f.user, campaign.id(), "pause", new Version(campaign.version())));
        Instant afterEnd = end.plusSeconds(1);
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(afterEnd);
            var resumed = f.tx.execute(s -> service.action(f.user, campaign.id(), "resume", new Version(paused.version())));
            assertThat(resumed.status()).isEqualTo(Status.COMPLETED);
            assertThat(resumed.nextRunAt()).isNull();
        }
        f.tx.executeWithoutResult(s -> worker.process(campaign.id(), end.plusSeconds(1)));
        assertExpiredPartialFanout(campaign.id());
    }

    @Test
    void finalAllowedOccurrenceStillFinishesItsRemainingBatchBeforeEnd() {
        Instant firstBatch = Instant.now().plusSeconds(60);
        Instant end = firstBatch.plus(Duration.ofDays(1));
        var campaign = schedule(create(boundedAudienceWrite(firstBatch, end, 1)));
        f.tx.executeWithoutResult(s -> worker.process(campaign.id(), firstBatch));
        assertThat(count("tbl_notification")).isEqualTo(50);
        f.tx.executeWithoutResult(s -> worker.process(campaign.id(), firstBatch.plusSeconds(1)));
        assertThat(count("tbl_notification")).isEqualTo(65);
        assertThat(service.get(f.user, campaign.id()).status()).isEqualTo(Status.COMPLETED);
    }

    private Write boundedAudienceWrite(Instant firstBatch, Instant end, int maxOccurrences) {
        Set<UUID> recipients = new HashSet<>();
        for (int i = 0; i < 65; i++) {
            UUID id = UUID.randomUUID();
            recipients.add(id);
            f.jdbc.update("insert into tbl_user(id,status,email_verified,user_name,created_at) values(?,'ACTIVE',true,?,'2020-01-01')",
                    id, "bounded-recipient-" + i);
            profile(id, "LISTENER");
            device(id, CustomPushPresentation.CAPABILITY);
        }
        return new Write(UUID.randomUUID(), null, "Bitiş sınırı", "Yarım kalan dağıtım",
                new Audience(Mode.USERS, Set.of(), recipients), new Target(Kind.HOME, null),
                new Schedule(firstBatch.minusSeconds(60), "Europe/Istanbul", Repeat.DAILY,
                        null, Set.of(), end, maxOccurrences, null, null));
    }

    private void assertExpiredPartialFanout(UUID campaignId) {
        for (String table : List.of("tbl_notification_campaign_recipient", "tbl_notification_receipt", "tbl_notification")) {
            assertThat(count(table)).as(table).isEqualTo(50);
        }
        assertThat(count("tbl_push_delivery")).isEqualTo(50);
        assertThat(count("tbl_notification_campaign_occurrence")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("select status from tbl_notification_campaign_occurrence", String.class))
                .isEqualTo("COMPLETED");
        var completed = service.get(f.user, campaignId);
        assertThat(completed.status()).isEqualTo(Status.COMPLETED);
        assertThat(completed.nextRunAt()).isNull();
        var delivered = f.jdbc.queryForMap("select id,recipient_id from tbl_notification order by id limit 1");
        var target = targetResolver.resolve((UUID) delivered.get("recipient_id"), (UUID) delivered.get("id"));
        assertThat(target.state()).isEqualTo("AVAILABLE");
        assertThat(target.read()).isFalse();
    }
    @Test void disabledFeatureAndRevokedAdminCannotScheduleAndSearchUsesActualUsernameColumn(){
        assertThat(access.search(f.user,"user-")).hasSize(2);
        ReflectionTestUtils.setField(service,"enabled",false);assertThatThrownBy(()->create(write(new Audience(Mode.ALL,Set.of(),Set.of()),Repeat.ONCE))).isInstanceOf(RuntimeException.class);
        ReflectionTestUtils.setField(service,"enabled",true);var c=create(write(new Audience(Mode.ALL,Set.of(),Set.of()),Repeat.ONCE));
        role(f.user,"LISTENER");assertThatThrownBy(()->schedule(c)).isInstanceOf(RuntimeException.class);assertThat(count("tbl_notification")).isZero();
    }
    @Test
    void createRechecksAdministratorRoleAfterWaitingForItsSerializationLock() throws Exception {
        var request = write(new Audience(Mode.ALL, Set.of(), Set.of()), Repeat.ONCE);
        var executor = Executors.newSingleThreadExecutor();
        var creating = new java.util.concurrent.atomic.AtomicReference<Future<Campaign>>();
        try {
            f.tx.executeWithoutResult(status -> {
                f.jdbc.queryForObject("select id from tbl_user where id=? for update", UUID.class, f.user);
                creating.set(executor.submit(() -> create(request)));
                // Observe the real PostgreSQL lock wait, proving the first authorization read completed.
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
                        Boolean.TRUE.equals(f.jdbc.queryForObject("""
                            select exists(select 1 from pg_stat_activity
                              where datname=current_database() and pid<>pg_backend_pid()
                                and wait_event_type='Lock'
                                and query like 'select id from tbl_user where id=%for update')
                            """, Boolean.class)));
                f.jdbc.update("""
                    delete from user_roles where user_id=?
                      and role_id in (select id from tbl_role where name='ROLE_ADMIN')
                    """, f.user);
            });
            assertThatThrownBy(() -> creating.get().get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class)
                    .satisfies(failure -> assertThat(((com.berkayb.soundconnect.shared.exception.SoundConnectException)
                            failure.getCause()).getErrorType())
                            .isEqualTo(com.berkayb.soundconnect.shared.exception.ErrorType.FORBIDDEN_ACCESS));
            assertThat(count("tbl_notification_campaign")).isZero();
        } finally {
            executor.shutdownNow();
        }
    }
    @Test void ownedClickSurvivesCancellationForeignAndForgedTargetsNeverResolve(){
        var c=schedule(create(write(new Audience(Mode.USERS,Set.of(),Set.of(f.other)),Repeat.DAILY)));work(c.id());
        UUID id=f.jdbc.queryForObject("select id from tbl_notification",UUID.class);
        assertThat(targetResolver.resolve(f.other,id).state()).isEqualTo("AVAILABLE");
        assertThatThrownBy(()->targetResolver.resolve(f.user,id)).isInstanceOf(RuntimeException.class);
        var current=service.get(f.user,c.id());f.tx.execute(s->service.action(f.user,c.id(),"cancel",new Version(current.version())));
        assertThat(targetResolver.resolve(f.other,id).target().kind()).isEqualTo(Kind.HOME);
        assertThat(f.jdbc.queryForObject("select is_read from tbl_notification where id=?",Boolean.class,id)).isFalse();
        f.jdbc.update("update tbl_notification set payload=jsonb_set(payload,'{targetKind}','\"EVENTS\"') where id=?",id);
        assertThatThrownBy(()->targetResolver.resolve(f.other,id)).isInstanceOf(RuntimeException.class);
    }
    @Test void endedScheduleDoesNotBackfillAndMissedDailyCoalescesOneCurrentOccurrence(){
        var d=write(new Audience(Mode.USERS,Set.of(),Set.of(f.other)),Repeat.DAILY);
        var old=new Write(d.requestId(),null,d.title(),d.message(),d.audience(),d.target(),
            new Schedule(now.minus(Duration.ofDays(365)),"Europe/Istanbul",Repeat.DAILY,null,Set.of(),null,3,null,null));
        var c=schedule(create(old));work(c.id());work(c.id());
        assertThat(count("tbl_notification")).isEqualTo(1);assertThat(service.get(f.user,c.id()).nextRunAt()).isAfter(now);
    }

    static Stream<Arguments> audienceCases() {
        var cases = new ArrayList<Arguments>();
        cases.add(Arguments.of(Mode.ALL, 15));
        // Every nonempty subset of the four supported profiles, both by profile and explicit username selection.
        for (Mode mode : List.of(Mode.PROFILE_TYPES, Mode.USERS)) {
            for (int mask = 1; mask < 16; mask++) cases.add(Arguments.of(mode, mask));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "audience {0}, profile subset {1}")
    @MethodSource("audienceCases")
    void allProfileAndExplicitUserSubsetsProduceExactlyTheirRecipients(Mode mode, int mask) {
        clearSeedProfiles();
        Set<UUID> selected = new HashSet<>();
        Set<String> types = new HashSet<>();
        for (int i = 0; i < SUPPORTED_PROFILES.size(); i++) {
            String profile = SUPPORTED_PROFILES.get(i);
            UUID recipient = recipient("matrix_" + profile.toLowerCase(Locale.ROOT), profile);
            device(recipient, CustomPushPresentation.CAPABILITY);
            var found = access.search(f.user, "MATRIX_" + profile);
            assertThat(found).singleElement().satisfies(option -> {
                assertThat(option.id()).isEqualTo(recipient);
                assertThat(option.profileType()).isEqualTo(profile);
            });
            if ((mask & (1 << i)) != 0) { selected.add(recipient); types.add(profile); }
        }
        var audience = new Audience(mode, mode == Mode.PROFILE_TYPES ? types : Set.of(),
                mode == Mode.USERS ? selected : Set.of());
        var campaign = schedule(create(write(audience, Repeat.ONCE)));
        work(campaign.id());
        work(campaign.id());
        assertThat(f.jdbc.queryForList("select recipient_id from tbl_notification", UUID.class))
                .containsExactlyInAnyOrderElementsOf(selected);
        assertThat(f.jdbc.queryForList("select recipient_id from tbl_push_delivery", UUID.class))
                .containsExactlyInAnyOrderElementsOf(selected);
        assertThat(count("tbl_notification_receipt")).isEqualTo(selected.size());
        assertThat(count("tbl_notification_campaign_occurrence")).isEqualTo(1);
        assertThat(service.get(f.user, campaign.id()).status()).isEqualTo(Status.COMPLETED);
    }

    static Stream<Arguments> profileModes() {
        return SUPPORTED_PROFILES.stream().flatMap(profile -> Arrays.stream(Mode.values())
                .map(mode -> Arguments.of(profile, mode)));
    }

    @ParameterizedTest(name = "eligibility {0}, audience {1}")
    @MethodSource("profileModes")
    void everyProfileExcludesInvalidAccountsForEveryAudienceMode(String profile, Mode mode) {
        clearSeedProfiles();
        UUID good = recipient("eligible_good", profile);
        Set<UUID> selected = new HashSet<>(Set.of(good));
        Map<String, UUID> rejected = new LinkedHashMap<>();
        for (String reason : List.of("inactive", "unverified", "erased", "profile_missing", "role_missing",
                "role_mismatch", "multiple_roles", "multiple_profiles")) {
            UUID id = recipient("eligible_" + reason, profile);
            rejected.put(reason, id);
            selected.add(id);
        }
        String other = profile.equals("LISTENER") ? "MUSICIAN" : "LISTENER";
        f.jdbc.update("update tbl_user set status='INACTIVE' where id=?", rejected.get("inactive"));
        f.jdbc.update("update tbl_user set email_verified=false where id=?", rejected.get("unverified"));
        f.jdbc.update("update tbl_user set erased_at=now() where id=?", rejected.get("erased"));
        f.jdbc.update("delete from " + PROFILE_TABLES.get(profile) + " where " +
                (profile.equals("VENUE") ? "owner_id" : "user_id") + "=?", rejected.get("profile_missing"));
        for (String reason : List.of("role_missing", "role_mismatch"))
            f.jdbc.update("delete from user_roles where user_id=?", rejected.get(reason));
        role(rejected.get("role_mismatch"), other);
        role(rejected.get("multiple_roles"), other);
        profileOnly(rejected.get("multiple_profiles"), other);
        for (String unsupported : List.of("ORGANIZER", "PRODUCER")) {
            UUID id = recipient("eligible_" + unsupported.toLowerCase(Locale.ROOT), unsupported);
            rejected.put(unsupported, id); selected.add(id);
        }
        UUID staffOnly = recipient("eligible_staff_only", null);
        role(staffOnly, "ADMIN"); rejected.put("staff_only", staffOnly); selected.add(staffOnly);
        device(good, CustomPushPresentation.CAPABILITY);
        var audience = new Audience(mode, mode == Mode.PROFILE_TYPES ? Set.of(profile) : Set.of(),
                mode == Mode.USERS ? selected : Set.of());
        var campaign = schedule(create(write(audience, Repeat.ONCE)));
        for (var rejectedAccount : rejected.entrySet()) {
            assertThat(access.eligible(rejectedAccount.getValue(), audience)).as(rejectedAccount.getKey()).isFalse();
        }
        assertThat(access.search(f.user, "eligible_")).extracting(UserOption::id).containsExactly(good);
        work(campaign.id());
        assertThat(f.jdbc.queryForList("select recipient_id from tbl_notification", UUID.class)).containsExactly(good);
        assertThat(f.jdbc.queryForList("select recipient_id from tbl_push_delivery", UUID.class)).containsExactly(good);
        assertThat(f.jdbc.queryForList("select recipient_id from tbl_notification_campaign_recipient", UUID.class))
                .containsExactly(good);
    }

    @ParameterizedTest(name = "push preferences preserve inbox for {0}")
    @ValueSource(strings = {"MUSICIAN", "LISTENER", "VENUE", "STUDIO"})
    void pushPreferencesAndDevicePermissionsDoNotRemoveTheOwnedInbox(String profile) {
        clearSeedProfiles();
        Map<String, UUID> recipients = new LinkedHashMap<>();
        for (String reason : List.of("allowed", "disabled", "category", "denied", "old_client", "no_device")) {
            UUID id = recipient("preference_" + reason, profile);
            recipients.put(reason, id);
            if (!reason.equals("no_device")) device(id,
                    reason.equals("old_client") ? OverthinkingPushPresentation.CAPABILITY : CustomPushPresentation.CAPABILITY);
        }
        f.tx.executeWithoutResult(s -> {
            f.devices.updatePreferences(recipients.get("disabled"), new PushDeviceService.Preferences(false, Set.of()));
            f.devices.updatePreferences(recipients.get("category"), new PushDeviceService.Preferences(true, Set.of("CUSTOM")));
        });
        f.jdbc.update("update tbl_push_device set permission='DENIED' where user_id=?", recipients.get("denied"));
        var campaign = schedule(create(write(new Audience(Mode.USERS, Set.of(), Set.copyOf(recipients.values())), Repeat.ONCE)));
        work(campaign.id());
        assertThat(f.jdbc.queryForList("select recipient_id from tbl_notification", UUID.class))
                .containsExactlyInAnyOrderElementsOf(recipients.values());
        assertThat(f.jdbc.queryForList("select recipient_id from tbl_push_delivery", UUID.class))
                .containsExactly(recipients.get("allowed"));
    }

    @Test void usernameSearchTreatsWildcardCharactersLiterallyAndLimitsResults() {
        clearSeedProfiles();
        UUID underscore = recipient("name_literal", "MUSICIAN");
        recipient("nameXliteral", "LISTENER");
        UUID percent = recipient("name%literal", "VENUE");
        assertThat(access.search(f.user, "NAME_")).extracting(UserOption::id).containsExactly(underscore);
        assertThat(access.search(f.user, "name%")).extracting(UserOption::id).containsExactly(percent);
        for (int i = 0; i < 25; i++) recipient("limited_" + String.format("%02d", i), "STUDIO");
        assertThat(access.search(f.user, "limited_")).hasSize(20)
                .extracting(UserOption::username).isSorted();
    }

    @ParameterizedTest(name = "durable schedule {0}")
    @EnumSource(Repeat.class)
    void eachRepeatUsesItsExactCalendarAndStopsAtTheOccurrenceLimit(Repeat repeat) {
        Instant first = Instant.parse("2026-10-12T18:05:00Z"); // Monday, 21:05 Istanbul.
        Instant second = switch (repeat) {
            case ONCE -> null;
            case DAILY -> first.plus(Duration.ofDays(1));
            case WEEKLY -> first.plus(Duration.ofDays(7));
            case INTERVAL -> first.plus(Duration.ofDays(3));
        };
        var schedule = new Schedule(null, "Europe/Istanbul", repeat, repeat == Repeat.INTERVAL ? 3 : null,
                repeat == Repeat.WEEKLY ? Set.of(1) : Set.of(), null, 2,
                LocalDateTime.parse("2026-10-12T21:05:00"), null);
        var campaign = schedule(create(new Write(UUID.randomUUID(), null, "Takvim", "21:05",
                new Audience(Mode.USERS, Set.of(), Set.of(f.other)), new Target(Kind.HOME, null), schedule)));
        f.tx.executeWithoutResult(s -> worker.process(campaign.id(), first.minusNanos(1)));
        assertThat(count("tbl_notification")).isZero();
        f.tx.executeWithoutResult(s -> worker.process(campaign.id(), first));
        assertThat(count("tbl_notification")).isEqualTo(1);
        assertThat(service.get(f.user, campaign.id()).nextRunAt()).isEqualTo(second);
        if (second != null) {
            f.tx.executeWithoutResult(s -> worker.process(campaign.id(), second.minusNanos(1)));
            assertThat(count("tbl_notification")).isEqualTo(1);
            f.tx.executeWithoutResult(s -> worker.process(campaign.id(), second));
        }
        f.tx.executeWithoutResult(s -> worker.process(campaign.id(), first.plus(Duration.ofDays(30))));
        assertThat(count("tbl_notification")).isEqualTo(repeat == Repeat.ONCE ? 1 : 2);
        assertThat(service.get(f.user, campaign.id()).status()).isEqualTo(Status.COMPLETED);
    }

    @Test void remainingFanoutRechecksChangedAccountsAndExcludesNewRegistrations() {
        clearSeedProfiles();
        Set<UUID> original = new HashSet<>();
        for (int i = 0; i < 65; i++) original.add(recipient("live_" + i, "STUDIO"));
        var campaign = schedule(create(write(new Audience(Mode.PROFILE_TYPES, Set.of("STUDIO"), Set.of()), Repeat.ONCE)));
        work(campaign.id());
        var sent = f.jdbc.queryForList("select recipient_id from tbl_notification", UUID.class);
        assertThat(sent).hasSizeBetween(48, 50); // The two ineligible original seed accounts also consume scan slots.
        var remaining = original.stream().filter(id -> !sent.contains(id)).toList();
        assertThat(remaining).hasSizeGreaterThanOrEqualTo(15);
        UUID inactive = remaining.get(0), missingProfile = remaining.get(1);
        f.jdbc.update("update tbl_user set status='INACTIVE' where id=?", inactive);
        f.jdbc.update("delete from tbl_studio_profile where user_id=?", missingProfile);
        UUID later = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        f.jdbc.update("insert into tbl_user(id,status,email_verified,user_name,created_at) values(?,'ACTIVE',true,'live_new',?)",
                later, Timestamp.from(now.plusSeconds(1)));
        profile(later, "STUDIO");
        work(campaign.id()); work(campaign.id());
        original.remove(inactive); original.remove(missingProfile);
        assertThat(f.jdbc.queryForList("select recipient_id from tbl_notification", UUID.class))
                .containsExactlyInAnyOrderElementsOf(original).doesNotContain(later, inactive, missingProfile);
        assertThat(service.get(f.user, campaign.id()).status()).isEqualTo(Status.COMPLETED);
        assertThat(count("tbl_notification_campaign_occurrence")).isEqualTo(1);
    }
}

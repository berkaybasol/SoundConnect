package com.berkayb.soundconnect.modules.user.controller.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.service.AdminAccountDirectory;
import com.berkayb.soundconnect.modules.user.service.AdminAccountAccessService;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@ContextConfiguration(classes = {AdminAccountDirectoryController.class, AdminAccountDirectory.class,
        AdminPanelReadAccess.class, AdminAccountAccessService.class, GlobalExceptionHandler.class, AdminAccountDirectoryPostgresTest.Config.class})
@Testcontainers
@Transactional
class AdminAccountDirectoryPostgresTest {
    private static final String API = "/api/v1/admin/users";
    private static final String PANEL = "ADMIN_PANEL_ACCESS";
    private static final String READ = "READ_USERS";
    private static final String MANAGE = "MANAGE_USERS";
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Configuration
    @EnableMethodSecurity
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource database() {
            var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            new ResourceDatabasePopulator(new ClassPathResource("admin-account-directory.sql")).execute(ds);
            var accessMigration = new ResourceDatabasePopulator(new FileSystemResource("scripts/db/2026-11-23-account-access-decisions.sql"));
            accessMigration.execute(ds);
            accessMigration.execute(ds);
            return ds;
        }
        @Bean NamedParameterJdbcTemplate queries(DataSource db) { return new NamedParameterJdbcTemplate(db); }
        @Bean DataSourceTransactionManager transactionManager(DataSource db) { return new DataSourceTransactionManager(db); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .build();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired AdminAccountAccessService decisions;
    @Autowired NamedParameterJdbcTemplate queries;
    @MockitoBean PublicProfileResolverService profiles;
    JdbcTemplate db;
    UUID actor;

    @BeforeEach void setup() {
        db = queries.getJdbcTemplate();
        actor = account("reviewer", "ACTIVE", 1);
        db.update("insert into user_permissions values (?,2)", actor);
    }

    private UUID account(String username, String status, Integer role) {
        UUID id = UUID.randomUUID();
        db.update("""
                insert into tbl_user(id,user_name,public_code,status,email_verified,session_version,created_at)
                values (?,?,?,?,true,7,timestamp '2026-11-20 12:10:00')
                """, id, username, code(id), status);
        if (role != null) db.update("insert into user_roles values (?,?)", id, role);
        return id;
    }

    private static String code(UUID id) {
        return "SC-" + id.toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private RequestPostProcessor as(String... permissions) {
        var principal = UserDetailsImpl.fromUser(User.builder().id(actor).sessionVersion(7).build());
        var authorities = Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList();
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    @Test void allFourTypesHaveStablePagesAndOnlyReturnListFields() throws Exception {
        var listener = account("a-listener", "ACTIVE", 2);
        db.update("insert into \"tbl_listener-profile\" values (?)", listener);
        account("b-musician", "ACTIVE", 3);
        account("c-venue", "PENDING_VENUE_REQUEST", null);
        account("d-studio", "REJECTED_STUDIO_REQUEST", null);
        account("unsupported", "ACTIVE", 6);
        var erased = account("deleted-user", "INACTIVE", 4);
        db.update("update tbl_user set erased_at=now() where id=?", erased);
        var conflict = account("conflicting", "ACTIVE", 3);
        db.update("insert into tbl_studio_profile values (?)", conflict);
        mvc.perform(get(API).param("size","2").with(as(PANEL, READ))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.data.content[*].profileType",contains("LISTENER","MUSICIAN")))
                .andExpect(jsonPath("$.data.content[0].*",hasSize(5)))
                .andExpect(jsonPath("$.data.content[0].suspended").value(false)).andExpect(jsonPath("$.data.totalElements").value(4));
        mvc.perform(get(API).param("page","1").param("size","2").with(as(PANEL, READ)))
                .andExpect(jsonPath("$.data.content[*].profileType",contains("VENUE","STUDIO")))
                .andExpect(jsonPath("$.data.last").value(true));
        mvc.perform(get(API).param("page","2").param("size","2").with(as(PANEL, READ)))
                .andExpect(jsonPath("$.data.content",hasSize(0))).andExpect(jsonPath("$.data.totalElements").value(4));
        for (UUID hidden : List.of(erased, conflict, actor)) {
            mvc.perform(get(API + "/" + hidden).with(as(PANEL, READ))).andExpect(status().isNotFound());
        }
        verifyNoInteractions(profiles);
    }

    @Test void usernameAndIdentifiersSupportExactIdentityAndCombinedFilters() throws Exception {
        var studioId = account("izmir_studio", "INACTIVE", 5);
        account("izmir2studio", "ACTIVE", 3);
        for (String term : List.of("@İZMiR_", studioId.toString(), code(studioId).toLowerCase(Locale.ROOT))) {
            mvc.perform(get(API).param("query",term).param("profileType","STUDIO").param("status","INACTIVE")
                            .with(as(PANEL, READ)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[*].userId",contains(studioId.toString())));
        }
        mvc.perform(get(API).param("query","_").with(as(PANEL, READ)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        for (String noMatch : List.of("%", code(studioId).substring(0,18), "@")) {
            mvc.perform(get(API).param("query",noMatch).with(as(PANEL, READ)))
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }
        mvc.perform(get(API).param("query",code(studioId)).param("profileType","VENUE").with(as(PANEL, READ)))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        verifyNoInteractions(profiles);
    }

    @Test void invalidFiltersAndPaginationAreRejected() throws Exception {
        for (String[] invalid : List.of(new String[]{"size","0"}, new String[]{"size","101"},
                new String[]{"page","-1"}, new String[]{"page","1001"}, new String[]{"profileType","BAND"},
                new String[]{"profileType","FAKE"}, new String[]{"status","PENDING"}, new String[]{"query","x".repeat(81)})) {
            mvc.perform(get(API).param(invalid[0],invalid[1]).with(as(PANEL, READ))).andExpect(status().isBadRequest());
        }
    }

    @Test void candidateStatusAndLatestDecisionAreIndependentAndNoProfileIsOpened() throws Exception {
        UUID venue = account("venue-candidate", "PENDING_VENUE_REQUEST", null);
        db.update("""
                insert into tbl_venue_applications values
                (?,?,'PENDING',timestamp '2026-11-10',timestamp '2026-11-10'),
                (?,?,'REJECTED',timestamp '2026-11-12',timestamp '2026-11-12')
                """, UUID.randomUUID(), venue, UUID.randomUUID(), venue);
        mvc.perform(get(API + "/" + venue).with(as(PANEL, READ))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.data.status").value("PENDING_VENUE_REQUEST"))
                .andExpect(jsonPath("$.data.lastApplicationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.publicCode").value(code(venue)))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-20T12:10:00Z"))
                .andExpect(jsonPath("$.data.emailVerified").value(true)).andExpect(jsonPath("$.data.profileTarget").doesNotExist());
        UUID studio = account("studio-candidate", "PENDING_STUDIO_REQUEST", null);
        db.update("insert into tbl_studio_applications values (?,?,'PENDING',now(),now())", UUID.randomUUID(), studio);
        mvc.perform(get(API + "/" + studio).with(as(PANEL, READ)))
                .andExpect(jsonPath("$.data.profileType").value("STUDIO")).andExpect(jsonPath("$.data.lastApplicationStatus").value("PENDING"));
        verifyNoInteractions(profiles);
    }

    @Test void detailReturnsOnlyTheAccountsOwnPublicTargetAndHandlesRestrictedOrMissingProfiles() throws Exception {
        var owner = account("legacy-musician", "ACTIVE", null);
        db.update("insert into tbl_musician_profile values (?)", owner);
        UUID id = UUID.randomUUID();
        var band = new UserProfileTargetDto("BAND", UUID.randomUUID(), "Other target", null);
        var musician = new UserProfileTargetDto("MUSICIAN", id, "Display name", "unused-url");
        when(profiles.resolveByUserId(owner)).thenReturn(new UserProfilesResolveResponseDto(owner,List.of(band,musician)));
        mvc.perform(get(API + "/" + owner).with(as(PANEL, READ))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("legacy-musician"))
                .andExpect(jsonPath("$.data.profileTarget.*",hasSize(2)))
                .andExpect(jsonPath("$.data.profileTarget.type").value("MUSICIAN"))
                .andExpect(jsonPath("$.data.profileTarget.profileId").value(id.toString()))
                .andExpect(jsonPath("$.data.email").doesNotExist()).andExpect(jsonPath("$.data.phone").doesNotExist());
        UUID listener = account("restricted-listener", "ACTIVE", 2);
        when(profiles.resolveByUserId(listener)).thenReturn(new UserProfilesResolveResponseDto(listener, List.of()));
        mvc.perform(get(API + "/" + listener).with(as(PANEL, READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profileTarget").doesNotExist());
        UUID inactive = account("inactive", "INACTIVE", 4);
        mvc.perform(get(API + "/" + inactive).with(as(PANEL, READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profileTarget").doesNotExist());
    }

    @Test void bothEndpointsEnforceCurrentPanelReadAuthorityAndActiveSession() throws Exception {
        var target = account("pending-store", "PENDING_VENUE_REQUEST", null);
        for (String path : List.of(API, API + "/" + target)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(as(PANEL))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(as(READ))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(as(PANEL, READ, "ROLE_LISTENER"))).andExpect(status().isForbidden());
        }
        db.update("delete from user_permissions where user_id=?", actor);
        mvc.perform(get(API + "/" + target).with(as(PANEL, READ))).andExpect(status().isForbidden());
        db.update("insert into user_permissions values (?,2)", actor);
        db.update("insert into \"tbl_listener-profile\" values (?)", actor);
        mvc.perform(get(API).with(as(PANEL, READ))).andExpect(status().isForbidden());
        db.update("delete from \"tbl_listener-profile\" where user_id=?", actor);
        db.update("update tbl_user set session_version=8 where id=?", actor);
        mvc.perform(get(API + "/" + target).with(as(PANEL, READ))).andExpect(status().isForbidden());
        verifyNoInteractions(profiles);
    }

    private MockHttpServletRequestBuilder decision(UUID target, UUID request, boolean suspended, long revision, String reason) {
        return post(API + "/" + target + "/access").with(csrf()).contentType("application/json")
                .content("""
                        {"requestId":"%s","suspended":%s,"expectedRevision":%d,"reason":"%s"}
                        """.formatted(request, suspended, revision, reason));
    }

    @Test void suspendAndRestorePreserveApplicationAndInvalidatePreviouslyRegisteredSessionsAndDevices() throws Exception {
        db.update("insert into user_permissions values (?,3)", actor);
        UUID target = account("studio-test", "PENDING_STUDIO_REQUEST", null);
        db.update("insert into tbl_push_device values (?,?,null,'hash','ciphertext',3)", UUID.randomUUID(), target);
        UUID first = UUID.randomUUID();
        mvc.perform(get(API + "/" + target).with(as(PANEL, READ)))
                .andExpect(jsonPath("$.data.canManageAccess").value(true)).andExpect(jsonPath("$.data.accessRevision").value(0));
        mvc.perform(decision(target,first,true,0,"  Örnek gerekçe  ").with(as(PANEL, MANAGE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1)).andExpect(jsonPath("$.data.suspended").value(true));
        mvc.perform(decision(target,first,true,0,"Örnek gerekçe").with(as(PANEL, MANAGE))).andExpect(status().isOk());
        mvc.perform(get(API).param("suspended","true").with(as(PANEL, READ)))
                .andExpect(jsonPath("$.data.content[*].userId", contains(target.toString())));
        mvc.perform(get(API + "/" + target).with(as(PANEL, READ)))
                .andExpect(jsonPath("$.data.status").value("PENDING_STUDIO_REQUEST"))
                .andExpect(jsonPath("$.data.lastAccessDecision.actorUsername").value("reviewer"))
                .andExpect(jsonPath("$.data.lastAccessDecision.reason").value("Örnek gerekçe"))
                .andExpect(jsonPath("$.data.lastAccessDecision.decidedAt").isString());
        mvc.perform(decision(target,UUID.randomUUID(),false,0,"Eski sayfa").with(as(PANEL, MANAGE))).andExpect(status().isConflict());
        mvc.perform(decision(target,first,true,0,"Başka gerekçe").with(as(PANEL, MANAGE))).andExpect(status().isConflict());
        mvc.perform(decision(target,UUID.randomUUID(),false,1,"İnceleme tamamlandı").with(as(PANEL, MANAGE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        // Replaying a previously acknowledged request returns its receipt and cannot reinstate the ban.
        mvc.perform(decision(target,first,true,0,"Örnek gerekçe").with(as(PANEL, MANAGE))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1));
        mvc.perform(get(API + "/" + target).with(as(PANEL, READ)))
                .andExpect(jsonPath("$.data.suspended").value(false)).andExpect(jsonPath("$.data.accessRevision").value(2))
                .andExpect(jsonPath("$.data.status").value("PENDING_STUDIO_REQUEST"))
                .andExpect(jsonPath("$.data.lastAccessDecision.suspended").value(false));
        assertEquals(8L, db.queryForObject("select session_version from tbl_user where id=?",Long.class,target));
        assertEquals(2, db.queryForObject("select count(*) from tbl_account_access_decision where target_id=?",Integer.class,target));
        assertEquals(4L, db.queryForObject("select generation from tbl_push_device where user_id=? and token_ciphertext is null and revoked_at is not null",Long.class,target));
    }

    @Test void decisionsRequireCurrentManagePermissionAndCannotLockPrivilegedOrOutOfScopeAccounts() throws Exception {
        UUID target = account("personal", "ACTIVE", 3);
        mvc.perform(decision(target,UUID.randomUUID(),true,0,"Gerekçe").with(as(PANEL,READ))).andExpect(status().isForbidden());
        mvc.perform(decision(target,UUID.randomUUID(),true,0,"Gerekçe").with(as(PANEL,MANAGE))).andExpect(status().isForbidden());
        db.update("insert into user_permissions values (?,3)", actor);
        mvc.perform(decision(target,UUID.randomUUID(),true,0,"    ").with(as(PANEL,MANAGE))).andExpect(status().isBadRequest());
        UUID privileged = account("protected", "ACTIVE", 3);
        db.update("insert into user_permissions values (?,1)", privileged);
        for (UUID deny : List.of(actor,privileged,account("unsupported-role", "ACTIVE", 6))) {
            mvc.perform(decision(deny,UUID.randomUUID(),true,0,"Gerekçe").with(as(PANEL,MANAGE))).andExpect(status().isForbidden());
        }
        db.update("update tbl_user set access_suspended=true where id=?", actor);
        mvc.perform(decision(target,UUID.randomUUID(),true,0,"Gerekçe").with(as(PANEL,MANAGE))).andExpect(status().isForbidden());
        assertEquals(0, db.queryForObject("select count(*) from tbl_account_access_decision", Integer.class));
    }

    @Test void concurrentDecisionsHaveOneWinnerAndDeviceFailureRollsBackTheWholeDecision() throws Exception {
        UUID target = account("race-target", "ACTIVE", 3);
        UUID secondActor = account("reviewer-two", "ACTIVE", 1);
        db.update("insert into user_permissions values (?,3),(?,3)", actor, secondActor);
        UUID installation = UUID.randomUUID();
        db.update("insert into tbl_push_device values (?,?,null,'hash','cipher',?)", installation, target, Long.MAX_VALUE);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        try {
            var first = new AdminAccountAccessService.DecisionRequest(true, 0L, UUID.randomUUID(), "İlk karar");
            var other = new AdminAccountAccessService.DecisionRequest(true, 0L, UUID.randomUUID(), "İkinci karar");
            // Force the last database write to fail, after the decision and credential revision were written.
            assertThrows(DataAccessException.class, () -> decisions.decide(principal(actor), target, first));
            assertEquals(false, db.queryForObject("select access_suspended from tbl_user where id=?",Boolean.class,target));
            assertEquals(7L, db.queryForObject("select session_version from tbl_user where id=?",Long.class,target));
            assertEquals(0, db.queryForObject("select count(*) from tbl_account_access_decision where target_id=?",Integer.class,target));
            db.update("update tbl_push_device set generation=1 where installation_id=?",installation);
            var start = new CountDownLatch(1);
            try (var threads = Executors.newFixedThreadPool(2)) {
                var one = threads.submit(() -> race(start, actor, target, first));
                var two = threads.submit(() -> race(start, secondActor, target, other));
                start.countDown();
                assertEquals(List.of("conflict","saved"), List.of(one.get(15,TimeUnit.SECONDS),
                        two.get(15,TimeUnit.SECONDS)).stream().sorted().toList());
            }
            assertEquals(8L, db.queryForObject("select session_version from tbl_user where id=?",Long.class,target));
            assertEquals(1, db.queryForObject("select count(*) from tbl_account_access_decision where target_id=?",Integer.class,target));
        } finally {
            db.update("delete from tbl_push_device where installation_id=?",installation);
            db.update("delete from tbl_account_access_decision where target_id=?",target);
            for (UUID id : List.of(target, actor, secondActor)) {
                db.update("delete from user_roles where user_id=?", id);
                db.update("delete from user_permissions where user_id=?", id);
                db.update("delete from tbl_user where id=?", id);
            }
        }
    }

    private static UserDetailsImpl principal(UUID actorId) {
        return UserDetailsImpl.fromUser(User.builder().id(actorId).sessionVersion(7).build());
    }

    private String race(CountDownLatch start, UUID actorId, UUID target, AdminAccountAccessService.DecisionRequest request)
            throws InterruptedException {
        start.await();
        try {
            decisions.decide(principal(actorId), target, request);
            return "saved";
        } catch (SoundConnectException e) {
            assertEquals(ErrorType.ACCOUNT_ACCESS_CONFLICT, e.getErrorType());
            return "conflict";
        }
    }
}

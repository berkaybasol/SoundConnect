package com.berkayb.soundconnect.auth.passwordreset.service;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.otp.service.OtpMailService;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.passwordreset.dto.request.ResetPasswordRequestDto;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.profile.shared.factory.ProfileFactory;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real production reset service/JPA and disposable PostgreSQL; OTP is a controlled admission double. */
@DataJpaTest(properties = {"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"}, showSql = false)
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = PasswordResetSessionPostgresTest.TestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetSessionPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("session_revocation_fixture").withUsername("fixture").withPassword("fixture")
            .withLabel("soundconnect.task", "ready-for-prod-01-session-revocation");
    @Autowired DataSource dataSource;
    @Autowired EntityManager em;
    @Autowired UserRepository users;
    @Autowired PasswordResetService resets;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean OtpService otp;
    @MockitoBean PasswordResetMailService mail;
    @MockitoBean AuthAccountRateLimitGuard guard;
    @MockitoBean PublicProfileResolverService profiles;
    @MockitoBean PasswordEncoder encoder;
    final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
    final JwtTokenProvider tokens = tokens();
    UUID id;
    String username;
    String email;

    @BeforeEach void prepare() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
        }
        username = "fixture-" + UUID.randomUUID().toString().substring(0, 10);
        email = username + "@example.invalid";
        id = transaction().execute(status -> {
            Role role = em.createQuery("select r from Role r where r.name = 'ROLE_MUSICIAN'", Role.class)
                    .getResultStream().findFirst().orElseGet(() -> {
                        Role created = Role.builder().name("ROLE_MUSICIAN").build();
                        em.persist(created); return created;
                    });
            return users.saveAndFlush(User.builder().username(username).email(email)
                    .password(bcrypt.encode("old-password")).status(UserStatus.ACTIVE).emailVerified(true)
                    .roles(Set.of(role)).build()).getId();
        });
        when(otp.verifyPasswordResetOtp(eq(email), anyString())).thenReturn(true);
        when(encoder.encode(any())).thenAnswer(invocation -> bcrypt.encode(invocation.getArgument(0)));
        when(encoder.matches(any(), anyString())).thenAnswer(invocation ->
                bcrypt.matches(invocation.getArgument(0), invocation.getArgument(1)));
    }

    @Test void resetCommitsPasswordAndVersionTogetherAndNormalNewLoginWorks() {
        String oldToken = tokens.generateToken(new UserDetailsImpl(current()));
        resets.resetPassword(request("new-password", "111111"));
        User after = current();
        assertThat(after.getSessionVersion()).isEqualTo(1L);
        assertThat(bcrypt.matches("new-password", after.getPassword())).isTrue();
        assertThat(bcrypt.matches("old-password", after.getPassword())).isFalse();
        assertThat(tokens.getSessionVersionFromToken(oldToken)).isZero();
        String newToken = auth().login(new LoginRequestDto(username, "new-password")).getData().token();
        assertThat(tokens.getSessionVersionFromToken(newToken)).isEqualTo(after.getSessionVersion());
    }

    @Test void outerTransactionRollbackDoesNotRevokeSessionsOrChangePassword() {
        transaction().executeWithoutResult(status -> {
            resets.resetPassword(request("rolled-back", "111111"));
            assertThat(current().getSessionVersion()).isEqualTo(1L);
            status.setRollbackOnly();
        });
        assertThat(current().getSessionVersion()).isZero();
        assertThat(bcrypt.matches("old-password", current().getPassword())).isTrue();
    }

    @Test void oldRevisionRemainsVisibleBeforeCommitAndNewRevisionOnlyAfterCommit() throws Exception {
        CountDownLatch changed = new CountDownLatch(1), commit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> transaction().executeWithoutResult(status -> {
                resets.resetPassword(request("new-password", "111111"));
                changed.countDown();
                await(commit);
            }));
            try {
                await(changed);
                assertThat(current().getSessionVersion()).isZero();
                assertThat(bcrypt.matches("old-password", current().getPassword())).isTrue();
            } finally { commit.countDown(); }
            writer.get(15, TimeUnit.SECONDS);
        }
        assertThat(current().getSessionVersion()).isEqualTo(1L);
        assertThat(bcrypt.matches("new-password", current().getPassword())).isTrue();
    }

    @Test void simultaneousResetsSerializeWithoutLosingARevisionOrUsingCachedIdentity() throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1), secondLookedUp = new CountDownLatch(1), release = new CountDownLatch(1);
        when(encoder.encode("first-password")).thenAnswer(invocation -> {
            firstLocked.countDown(); await(release); return bcrypt.encode("first-password");
        });
        when(otp.verifyPasswordResetOtp(email, "222222")).thenAnswer(invocation -> {
            secondLookedUp.countDown(); return true;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> resets.resetPassword(request("first-password", "111111")));
            await(firstLocked);
            var second = executor.submit(() -> resets.resetPassword(request("second-password", "222222")));
            try {
                await(secondLookedUp);
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        assertThat(current().getSessionVersion()).isEqualTo(2L);
        assertThat(bcrypt.matches("second-password", current().getPassword())).isTrue();
    }

    @Test void loginThatVerifiedOldPasswordBeforeResetCannotMintCurrentRevision() throws Exception {
        CountDownLatch matched = new CountDownLatch(1), issue = new CountDownLatch(1);
        doAnswer(invocation -> {
            boolean valid = bcrypt.matches(invocation.getArgument(0), invocation.getArgument(1));
            matched.countDown(); await(issue); return valid;
        }).when(encoder).matches(eq("old-password"), anyString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var login = executor.submit(() -> auth().login(new LoginRequestDto(username, "old-password")));
            try {
                await(matched);
                resets.resetPassword(request("new-password", "111111"));
            } finally { issue.countDown(); }
            String lateToken = login.get(15, TimeUnit.SECONDS).getData().token();
            assertThat(tokens.getSessionVersionFromToken(lateToken)).isZero();
            assertThat(current().getSessionVersion()).isEqualTo(1L);
        }
    }

    @Test void staleManagedUserProfileWriteCannotRestorePasswordOrSessionRevision() throws Exception {
        CountDownLatch loaded = new CountDownLatch(1), resume = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> transaction().executeWithoutResult(status -> {
                User staleUser = users.findById(id).orElseThrow();
                loaded.countDown(); await(resume);
                staleUser.setDescription("unrelated profile update");
                em.flush();
            }));
            try { await(loaded); resets.resetPassword(request("new-password", "111111")); }
            finally { resume.countDown(); }
            stale.get(15, TimeUnit.SECONDS);
        }
        User after = current();
        assertThat(after.getSessionVersion()).isEqualTo(1L);
        assertThat(bcrypt.matches("new-password", after.getPassword())).isTrue();
        assertThat(after.getDescription()).isEqualTo("unrelated profile update");
    }

    @Test void invalidCodeDoesNotChangeEitherCredentialField() {
        when(otp.verifyPasswordResetOtp(email, "bad")).thenReturn(false);
        assertThatThrownBy(() -> resets.resetPassword(request("new-password", "bad"))).isInstanceOf(RuntimeException.class);
        assertThat(current().getSessionVersion()).isZero();
        assertThat(bcrypt.matches("old-password", current().getPassword())).isTrue();
    }

    @Test void identityChangedAfterLookupIsRefreshedUnderLockAndCannotResetOldAccountIdentity() throws Exception {
        CountDownLatch lookedUp = new CountDownLatch(1), continueReset = new CountDownLatch(1);
        doAnswer(invocation -> { lookedUp.countDown(); await(continueReset); return true; })
                .when(otp).verifyPasswordResetOtp(email, "111111");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reset = executor.submit(() -> resets.resetPassword(request("new-password", "111111")));
            try {
                await(lookedUp);
                transaction().executeWithoutResult(status -> {
                    User user = users.findByIdForUpdate(id).orElseThrow();
                    user.setEmail("renamed-" + email);
                });
            } finally { continueReset.countDown(); }
            assertThatThrownBy(() -> reset.get(15, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
        }
        assertThat(current().getSessionVersion()).isZero();
        assertThat(bcrypt.matches("old-password", current().getPassword())).isTrue();
    }

    @Test void migrationPreservesLegacyUsersAndNeverResetsAnExistingRevision() throws Exception {
        String sql = Files.readString(Path.of("scripts/db/2026-10-08-account-session-version.sql"));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema session_migration_fixture");
            statement.execute("set search_path to session_migration_fixture");
            statement.execute("create table tbl_user (id integer primary key, password text not null)");
            statement.execute("insert into tbl_user values (1, 'synthetic-hash')");
            statement.execute(sql);
            try (var result = statement.executeQuery("select session_version, password from tbl_user where id=1")) {
                assertThat(result.next()).isTrue(); assertThat(result.getLong(1)).isZero();
                assertThat(result.getString(2)).isEqualTo("synthetic-hash");
            }
            statement.execute("update tbl_user set session_version=7 where id=1");
            statement.execute(sql);
            try (var result = statement.executeQuery("select session_version from tbl_user where id=1")) {
                assertThat(result.next()).isTrue(); assertThat(result.getLong(1)).isEqualTo(7L);
            }
        }
    }

    User current() { return users.findById(id).orElseThrow(); }
    TransactionTemplate transaction() { return new TransactionTemplate(transactions); }
    ResetPasswordRequestDto request(String password, String code) { return new ResetPasswordRequestDto(username, code, password, password); }
    AuthService auth() { return new AuthService(tokens, users, encoder, mock(RoleRepository.class), mock(ProfileFactory.class),
            mock(ListenerProfileChoiceStatusReader.class), otp, mock(OtpMailService.class), mock(VenueApplicationService.class),
            mock(VenueApplicationSessionAccess.class), mock(StudioApplicationService.class), guard); }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("fixture coordination timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }
    static JwtTokenProvider tokens() {
        var tokens = new JwtTokenProvider();
        ReflectionTestUtils.setField(tokens, "jwtSecret", "session-postgres-synthetic-signing-secret-32-bytes");
        ReflectionTestUtils.setField(tokens, "jwtIssuer", "session-fixture");
        ReflectionTestUtils.setField(tokens, "jwtExpiration", 600000L); return tokens;
    }
    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @EntityScan(basePackages = "com.berkayb.soundconnect")
    @Import(PasswordResetService.class)
    static class TestConfiguration {
        @Bean DataSource dataSource() {
            if (!postgres.isRunning()) throw new IllegalStateException("Disposable PostgreSQL required");
            return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }
}

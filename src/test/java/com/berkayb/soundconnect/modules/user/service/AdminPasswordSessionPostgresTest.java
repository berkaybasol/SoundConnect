package com.berkayb.soundconnect.modules.user.service;

import com.berkayb.soundconnect.auth.security.*;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileProvisioner;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.mapper.UserMapper;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.user.support.UsernameChangeTimeProvider;
import com.berkayb.soundconnect.shared.realtime.WebSocketSecurityInterceptor;
import com.berkayb.soundconnect.shared.realtime.WebSocketSessionRegistry;
import com.berkayb.soundconnect.shared.realtime.WebSocketSubscriptionAuthorizer;
import com.berkayb.soundconnect.shared.security.SecurityErrorResponseWriter;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"}, showSql = false)
@Testcontainers @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AdminPasswordSessionPostgresTest.Fixture.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED) @DirtiesContext
class AdminPasswordSessionPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("stage01_admin_password").withLabel("soundconnect.fixture", "stage01-admin-password").withReuse(false);
    @Autowired UserServiceImpl service;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PasswordEncoder encoder;
    @MockitoBean UserMapper mapper;
    @MockitoBean UserEntityFinder finder;
    @MockitoBean UsernameChangeTimeProvider time;
    @MockitoBean PersonalProfileTypePolicy profilePolicy;
    @MockitoBean ListenerProfileProvisioner listenerProfiles;
    @MockitoBean com.berkayb.soundconnect.modules.user.deletion.ListenerAccountDeletionService deletion;
    @MockitoBean com.berkayb.soundconnect.modules.marketplace.media.MarketplaceMediaLifecycle media;
    UUID owner, target;
    JwtTokenProvider tokens;

    @BeforeEach void prepare() {
        assertThat(postgres.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", "stage01-admin-password");
        var tx = new TransactionTemplate(transactions);
        owner = tx.execute(ignored -> create("ROLE_OWNER"));
        target = tx.execute(ignored -> create("ROLE_MUSICIAN"));
        tokens = new JwtTokenProvider();
        ReflectionTestUtils.setField(tokens, "jwtSecret", "stage01-admin-fixture-only-32-byte-signing-secret");
        ReflectionTestUtils.setField(tokens, "jwtIssuer", "stage01-admin-fixture");
        ReflectionTestUtils.setField(tokens, "jwtExpiration", 600000L);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void administratorPasswordChangeRevokesOldHttpAndConnectedWebSocketAndPreservesOtherFields() throws Exception {
        User before = current();
        String oldToken = tokens.generateToken(new UserDetailsImpl(before));
        assertThat(httpAccepted(oldToken)).isTrue();
        var sessions = new WebSocketSessionRegistry();
        sessions.register("old-device", target, System.currentTimeMillis() + 60000, before.getSessionVersion());
        var userDetails = mock(CustomUserDetailsService.class);
        when(userDetails.loadUserById(target)).thenAnswer(ignored -> new UserDetailsImpl(current()));
        var ws = new WebSocketSecurityInterceptor(tokens, userDetails, mock(WebSocketSubscriptionAuthorizer.class),
                sessions, mock(ListenerProfileChoiceStatusReader.class));
        var message = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        message.setSessionId("old-device"); message.setDestination("/topic/notifications." + target);
        var delivery = MessageBuilder.createMessage(new byte[0], message.getMessageHeaders());
        assertThat(ws.preSend(delivery, null)).isNotNull();

        String username = "changed-" + target.toString().substring(0, 8), email = username + "@example.invalid";
        service.updateUser(owner, target, new UserUpdateRequestDto(username, "new-password", email, null));

        assertThat(httpAccepted(oldToken)).as("old JWT must not authenticate after admin password reset").isFalse();
        assertThat(ws.preSend(delivery, null)).as("already connected old socket must not receive the next message").isNull();
        User after = current();
        assertThat(after.getSessionVersion()).isEqualTo(before.getSessionVersion() + 1);
        assertThat(after.getUsername()).isEqualTo(username); assertThat(after.getEmail()).isEqualTo(email);
        assertThat(encoder.matches("new-password", after.getPassword())).isTrue();
        assertThat(httpAccepted(tokens.generateToken(new UserDetailsImpl(after)))).isTrue();
    }

    @Test void rollbackKeepsPasswordRevisionAndOtherIdentityFields() {
        User before = current();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.updateUser(owner, target, new UserUpdateRequestDto("rolled-" + target.toString().substring(0, 8), "new-password", null, null));
            tx.setRollbackOnly();
        });
        User after = current();
        assertThat(after.getSessionVersion()).isEqualTo(before.getSessionVersion());
        assertThat(after.getPassword()).isEqualTo(before.getPassword());
        assertThat(after.getUsername()).isEqualTo(before.getUsername());
    }

    @Test void identityOnlyUpdateDoesNotRevokeCurrentSession() {
        long version = current().getSessionVersion();
        service.updateUser(owner, target, new UserUpdateRequestDto("rename-" + target.toString().substring(0, 8), null, null, null));
        assertThat(current().getSessionVersion()).isEqualTo(version);
        assertThat(encoder.matches("old-password", current().getPassword())).isTrue();
    }

    private UUID create(String roleName) {
        Role role = em.createQuery("select r from Role r where r.name=:name", Role.class).setParameter("name", roleName)
                .getResultStream().findFirst().orElseGet(() -> {
                    Role created = Role.builder().name(roleName).build(); em.persist(created); return created;
                });
        String username = "fixture-" + UUID.randomUUID().toString().substring(0, 8);
        return users.saveAndFlush(User.builder().username(username).email(username + "@example.invalid")
                .password(encoder.encode("old-password")).emailVerified(true).status(UserStatus.ACTIVE).roles(Set.of(role)).build()).getId();
    }
    private User current() { return users.findById(target).orElseThrow(); }
    private boolean httpAccepted(String token) throws Exception {
        SecurityContextHolder.clearContext();
        var details = mock(CustomUserDetailsService.class); when(details.loadUserById(target)).thenReturn(new UserDetailsImpl(current()));
        var filter = new JwtAuthenticationFilter(tokens, details, new JwtUtil(tokens), mock(ListenerProfileChoiceGate.class),
                new SecurityErrorResponseWriter(new ObjectMapper().findAndRegisterModules()), mock(VenueApplicationSessionAccess.class));
        var request = new MockHttpServletRequest("GET", "/api/v1/users/me"); request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse(); var passed = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> passed.set(SecurityContextHolder.getContext().getAuthentication() != null));
        SecurityContextHolder.clearContext();
        return passed.get();
    }
    @Configuration(proxyBeanMethods = false) @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class, RoleRepository.class})
    @EntityScan(basePackages = "com.berkayb.soundconnect") @Import(UserServiceImpl.class)
    static class Fixture {
        @Bean DataSource dataSource() {
            if (!postgres.isRunning()) throw new IllegalStateException("Disposable PostgreSQL required");
            return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(4); }
    }
}

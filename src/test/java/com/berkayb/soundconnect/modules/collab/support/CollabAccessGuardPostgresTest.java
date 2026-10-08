package com.berkayb.soundconnect.modules.collab.support;

import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes new permission SQL and the existing native profile projection on a disposable database. */
@Testcontainers
class CollabAccessGuardPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("collab_access_guard_test").withUsername("collab_test").withPassword("collab_test");
    JdbcTemplate jdbc;
    CollabAccessGuard guard;
    UUID viewer, role, permission;

    @BeforeEach void setup() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("collab_access_guard_test");
        }
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table if not exists tbl_user(id uuid primary key,status text,email_verified boolean,erased_at timestamp)");
        jdbc.execute("create table if not exists tbl_role(id uuid primary key,name text)");
        jdbc.execute("create table if not exists user_roles(user_id uuid,role_id uuid)");
        jdbc.execute("create table if not exists tbl_permissions(id uuid primary key,name text)");
        jdbc.execute("create table if not exists user_permissions(user_id uuid,permission_id uuid)");
        jdbc.execute("create table if not exists role_permissions(role_id uuid,permission_id uuid)");
        for (String table : Set.of("\"tbl_listener-profile\"", "tbl_musician_profile", "tbl_studio_profile",
                "tbl_organizer_profile", "tbl_producer_profile")) {
            jdbc.execute("create table if not exists " + table + "(id uuid primary key,user_id uuid)");
            jdbc.execute("truncate " + table);
        }
        jdbc.execute("create table if not exists tbl_venues(id uuid primary key,owner_id uuid)");
        jdbc.execute("truncate tbl_venues,tbl_user,tbl_role,user_roles,tbl_permissions,user_permissions,role_permissions");
        viewer = UUID.randomUUID(); role = UUID.randomUUID(); permission = UUID.randomUUID();
        jdbc.update("insert into tbl_user values (?,'ACTIVE',true,null)", viewer);
        jdbc.update("insert into tbl_role values (?,'ROLE_ADMIN')", role);
        jdbc.update("insert into user_roles values (?,?)", viewer, role);
        jdbc.update("insert into tbl_permissions values (?,'MANAGE_COLLAB_REPORTS')", permission);
        var named = new NamedParameterJdbcTemplate(dataSource);
        var users = mock(UserRepository.class);
        when(users.findRoleNamesByUserId(any())).thenAnswer(call -> Set.copyOf(jdbc.queryForList(
                "select r.name from user_roles ur join tbl_role r on r.id=ur.role_id where ur.user_id=?",
                String.class, call.getArgument(0, UUID.class))));
        String profiles = UserRepository.class.getMethod("findExistingPersonalProfileRoleNames", UUID.class)
                .getAnnotation(Query.class).value();
        when(users.findExistingPersonalProfileRoleNames(any())).thenAnswer(call -> Set.copyOf(named.queryForList(
                profiles, Map.of("userId", call.getArgument(0, UUID.class)), String.class)));
        guard = new CollabAccessGuard(users, named);
    }

    @Test void directAndRoleModerationGrantsAreCurrentAccountScopedAndListenerAlwaysVetoes() {
        forbidden(() -> guard.requireModerator(viewer));
        jdbc.update("insert into user_permissions values (?,?)", UUID.randomUUID(), permission);
        forbidden(() -> guard.requireModerator(viewer));
        jdbc.update("insert into user_permissions values (?,?)", viewer, permission);
        assertThatCode(() -> guard.requireModerator(viewer)).doesNotThrowAnyException();
        jdbc.update("delete from user_permissions where user_id=?", viewer);
        forbidden(() -> guard.requireModerator(viewer));
        jdbc.update("insert into role_permissions values (?,?)", role, permission);
        assertThatCode(() -> guard.requireModerator(viewer)).doesNotThrowAnyException();
        jdbc.update("update tbl_user set status='INACTIVE' where id=?", viewer);
        forbidden(() -> guard.requireModerator(viewer));
        jdbc.update("update tbl_user set status='ACTIVE' where id=?", viewer);
        jdbc.update("insert into \"tbl_listener-profile\" values (?,?)", UUID.randomUUID(), viewer);
        forbidden(() -> guard.requireModerator(viewer));
        jdbc.update("delete from \"tbl_listener-profile\"");
        jdbc.update("update tbl_role set name='ROLE_LISTENER' where id=?", role);
        forbidden(() -> guard.requireModerator(viewer));
    }

    @Test void backstageRequiresTheMatchingSinglePersonalProfileAndFreshActiveAccount() {
        jdbc.update("update tbl_role set name='ROLE_MUSICIAN' where id=?", role);
        forbidden(() -> guard.requireBackstage(viewer));
        jdbc.update("insert into tbl_musician_profile values (?,?)", UUID.randomUUID(), viewer);
        assertThatCode(() -> guard.requireBackstage(viewer)).doesNotThrowAnyException();
        jdbc.update("insert into \"tbl_listener-profile\" values (?,?)", UUID.randomUUID(), viewer);
        forbidden(() -> guard.requireBackstage(viewer));
        jdbc.update("delete from \"tbl_listener-profile\"");
        jdbc.update("update tbl_user set erased_at=now() where id=?", viewer);
        forbidden(() -> guard.requireBackstage(viewer));
    }
    private void forbidden(Runnable action) {
        assertThat(catchThrowableOfType(action::run, SoundConnectException.class).getErrorType()).isEqualTo(ErrorType.COLLAB_FORBIDDEN);
    }
}

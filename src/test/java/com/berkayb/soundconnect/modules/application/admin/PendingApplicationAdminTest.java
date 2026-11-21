package com.berkayb.soundconnect.modules.application.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Arrays;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@ContextConfiguration(classes = {PendingApplicationAdminController.class, PendingApplicationQuery.class,
        GlobalExceptionHandler.class, PendingApplicationAdminTest.Config.class})
class PendingApplicationAdminTest {
    private static final String VENUES = "/api/v1/admin/venue-applications/pending";
    private static final String STUDIOS = "/api/v1/admin/studio-applications/pending";
    private static final String ID_PREFIX = "/00000000-0000-0000-0000-00000000000";
    private static final String PANEL = "ADMIN_PANEL_ACCESS";
    private static final String VENUE_PERMISSION = "MANAGE_VENUE_APPLICATIONS";
    private static final String STUDIO_PERMISSION = "MANAGE_STUDIO_APPLICATIONS";

    @Configuration
    @EnableMethodSecurity
    @EnableTransactionManagement
    static class Config {
        @Bean(destroyMethod = "shutdown") EmbeddedDatabase database() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                    .addScript("classpath:pending-application-admin.sql").build();
        }
        @Bean NamedParameterJdbcTemplate queries(EmbeddedDatabase db) { return new NamedParameterJdbcTemplate(db); }
        @Bean DataSourceTransactionManager transactionManager(EmbeddedDatabase db) { return new DataSourceTransactionManager(db); }
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .build();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private final UUID actor = UUID.randomUUID();

    @BeforeEach void createReviewer() {
        jdbc = new JdbcTemplate(db);
        jdbc.update("insert into tbl_user values (?,'reviewer','ACTIVE',true,null,7)", actor);
        jdbc.update("insert into user_roles values (?,1)", actor);
        jdbc.update("insert into user_permissions values (?,2),(?,3)", actor, actor);
    }

    private RequestPostProcessor reviewer(String... authorities) {
        var principal = UserDetailsImpl.fromUser(User.builder().id(actor).sessionVersion(7).build());
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test void listsUseStablePagesAndOnlyReturnTheRequestedPendingType() throws Exception {
        var admin = reviewer(PANEL, VENUE_PERMISSION, STUDIO_PERMISSION);
        mvc.perform(get(VENUES).param("size","1").with(admin))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.content[0].businessName").value("Venue 2"))
                .andExpect(jsonPath("$.data.content[0].applicantUsername").value("applicant"))
                .andExpect(jsonPath("$.data.content[0].cityName").value("İstanbul"))
                .andExpect(jsonPath("$.data.content[0].districtName").value("Kadıköy"))
                .andExpect(jsonPath("$.data.content[0].neighborhoodName").value("Moda"))
                .andExpect(jsonPath("$.data.content[0].applicationDate").value("2026-11-21T12:00:00Z"))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.content[0].address").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.content[0].phone").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.content[0].email").doesNotHaveJsonPath());
        mvc.perform(get(VENUES).param("size","1").param("page","1").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].businessName").value("Venue 1"));
        mvc.perform(get(STUDIOS).with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].businessName").value("Studio 1"))
                .andExpect(jsonPath("$.data.content[0].address").doesNotHaveJsonPath());
        mvc.perform(get(STUDIOS).param("page","1").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test void onlySameTypePendingDetailsExposeTheAddress() throws Exception {
        var admin = reviewer(PANEL, VENUE_PERMISSION, STUDIO_PERMISSION);
        mvc.perform(get(VENUES + ID_PREFIX + "1").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address").value("Adres")).andExpect(jsonPath("$.data.phone").doesNotHaveJsonPath());
        mvc.perform(get(STUDIOS + ID_PREFIX + "4").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address").value("Stüdyo adresi")).andExpect(jsonPath("$.data.email").doesNotHaveJsonPath());
        mvc.perform(get(VENUES + ID_PREFIX + "3").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(STUDIOS + ID_PREFIX + "5").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(VENUES + ID_PREFIX + "4").with(admin)).andExpect(status().isNotFound());
    }

    @Test void boundsAreValidated() throws Exception {
        var admin = reviewer(PANEL, VENUE_PERMISSION, STUDIO_PERMISSION);
        for (var path : new String[]{VENUES, STUDIOS}) {
            mvc.perform(get(path).param("size","0").with(admin)).andExpect(status().isBadRequest());
            mvc.perform(get(path).param("size","101").with(admin)).andExpect(status().isBadRequest());
            mvc.perform(get(path).param("page","-1").with(admin)).andExpect(status().isBadRequest());
            mvc.perform(get(path).param("page","1001").with(admin)).andExpect(status().isBadRequest());
        }
    }

    @Test void currentPanelAndAreaPermissionsRequiredForBothReads() throws Exception {
        var admin = reviewer(PANEL, VENUE_PERMISSION, STUDIO_PERMISSION);
        mvc.perform(get(VENUES)).andExpect(status().isUnauthorized());
        mvc.perform(get(VENUES).with(reviewer(VENUE_PERMISSION))).andExpect(status().isForbidden());
        mvc.perform(get(VENUES).with(reviewer(PANEL, STUDIO_PERMISSION))).andExpect(status().isForbidden());
        mvc.perform(get(STUDIOS + ID_PREFIX + "4").with(reviewer(PANEL, VENUE_PERMISSION))).andExpect(status().isForbidden());
        jdbc.update("delete from user_permissions where user_id=? and permission_id=2", actor);
        mvc.perform(get(VENUES).with(admin)).andExpect(status().isForbidden());
        mvc.perform(get(VENUES + ID_PREFIX + "1").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get(STUDIOS).with(admin)).andExpect(status().isOk());
        jdbc.update("delete from user_roles where user_id=?", actor);
        mvc.perform(get(STUDIOS).with(admin)).andExpect(status().isForbidden());
        mvc.perform(get(STUDIOS + ID_PREFIX + "4").with(admin)).andExpect(status().isForbidden());
    }

    @Test void listenerAndUnusableOrRevokedReviewerCannotReadApplications() throws Exception {
        var admin = reviewer(PANEL, VENUE_PERMISSION, STUDIO_PERMISSION);
        mvc.perform(get(VENUES).with(reviewer(PANEL, VENUE_PERMISSION,"ROLE_LISTENER"))).andExpect(status().isForbidden());
        jdbc.update("insert into user_roles values (?,2)", actor);
        mvc.perform(get(STUDIOS).with(admin)).andExpect(status().isForbidden());
        jdbc.update("delete from user_roles where user_id=? and role_id=2", actor);
        jdbc.update("insert into \"tbl_listener-profile\" values (?)", actor);
        mvc.perform(get(VENUES).with(admin)).andExpect(status().isForbidden());
        jdbc.update("delete from \"tbl_listener-profile\" where user_id=?", actor);
        jdbc.update("update tbl_user set status='PENDING_VENUE_REQUEST' where id=?", actor);
        mvc.perform(get(STUDIOS + ID_PREFIX + "4").with(admin)).andExpect(status().isForbidden());
        jdbc.update("update tbl_user set status='ACTIVE', session_version=8 where id=?", actor);
        mvc.perform(get(VENUES).with(admin)).andExpect(status().isForbidden());
    }
}

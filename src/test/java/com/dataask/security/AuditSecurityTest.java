package com.dataask.security;

import com.dataask.audit.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private String adminToken, userToken;

    @Configuration @EnableWebMvc @EnableTransactionManagement
    @Import({SecurityConfig.class,JwtTokens.class,JwtFilter.class,AuthController.class,AuditService.class,AuditController.class,ClientAuditController.class,com.dataask.common.RecordListController.class,com.dataask.admin.DataSourceController.class,com.dataask.config.RuntimeConfigService.class})
    static class Config {
        @Bean DataSource dataSource() { return AuditTestDatabase.create(); }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean com.dataask.data.StarRocksGateway gateway() { return org.mockito.Mockito.mock(com.dataask.data.StarRocksGateway.class); }
        @Bean CityPermissionService cityPermissionService() {
            // This focused audit/security slice has no StarRocks; auth still requires the dependency.
            return org.mockito.Mockito.mock(CityPermissionService.class);
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    }

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("dataask.jwt-secret","test-secret-at-least-thirty-two-characters-long")));
        context.register(Config.class); context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        PasswordEncoder passwords = context.getBean(PasswordEncoder.class);
        String hash = passwords.encode("test-password-123");
        jdbc.update("INSERT INTO da_users(username,display_name,password_hash,role) VALUES('admin','管理员',?,'ADMIN'),('alice','Alice',?,'USER')",hash,hash);
        JwtTokens tokens = context.getBean(JwtTokens.class);
        adminToken = tokens.issue("admin","ADMIN",hash); userToken = tokens.issue("alice","USER",hash);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }

    @Test void internalSourceMigrationWorksWithoutLoginButHttpManagementRequiresAdmin() throws Exception {
        context.getBean(com.dataask.admin.DataSourceController.class).migrate();
        String input="{\"name\":\"业务数据\",\"jdbcUrl\":\"jdbc:mysql://warehouse:9030/ads\",\"schema\":\"ads\",\"username\":\"reader\",\"password\":\"super-secret-password\"}";
        mvc.perform(post("/api/admin/datasources").header("Authorization","Bearer "+adminToken).contentType("application/json").content(input)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/datasources").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("super-secret-password"))));
        mvc.perform(post("/api/admin/datasources").header("Authorization","Bearer "+userToken).contentType("application/json").content(input)).andExpect(status().isForbidden());
    }

    @Test void ordinaryUsersCannotReadAdminListsOrMutateDataSources() throws Exception {
        for(String path:List.of("/api/admin/users/page","/api/admin/metrics/page","/api/admin/metrics/batches/page","/api/admin/datasources")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization","Bearer "+userToken)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/admin/datasources/anything/activate").header("Authorization","Bearer "+userToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users/page").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
    }

    @Test void anonymousAndOrdinaryUsersCannotReadOrDeleteAnyAuditSqlRecords() throws Exception {
        for (String path : List.of("/api/admin/audit/logs","/api/admin/audit/queries","/api/admin/audit/options","/api/admin/audit/queries/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization","Bearer "+userToken)).andExpect(status().isForbidden());
        }
        for (String kind : List.of("logs","queries"))
            mvc.perform(post("/api/admin/audit/"+kind+"/batch-delete").header("Authorization","Bearer "+userToken)
                .contentType("application/json").content("{\"ids\":[\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"]}")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM da_operation_audit WHERE outcome='DENIED'",Integer.class)).isEqualTo(10);
    }

    @Test void realAdminCanPageInspectSqlAndDeleteAndDeletionIsItselfAudited() throws Exception {
        AuditService audit = context.getBean(AuditService.class);
        audit.query("alice","CHAT",null,"订单量","SELECT 42 FROM ads.orders",()->new com.dataask.data.StarRocksGateway.QueryResult(List.of(),List.of()));
        String id = jdbc.queryForObject("SELECT id FROM da_query_audit",String.class);
        mvc.perform(get("/api/admin/audit/queries").header("Authorization","Bearer "+adminToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].sql").doesNotExist());
        mvc.perform(get("/api/admin/audit/queries/"+id).header("Authorization","Bearer "+adminToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sql").value("SELECT 42 FROM ads.orders"));
        mvc.perform(post("/api/admin/audit/queries/batch-delete").header("Authorization","Bearer "+adminToken)
                .contentType("application/json").content("{\"ids\":[\""+id+"\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM da_query_audit",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT detail FROM da_operation_audit WHERE action='批量删除 SQL 查询记录'",String.class)).contains(id,"实际删除 1 条");
        assertThat(jdbc.queryForObject("SELECT username FROM da_operation_audit WHERE action='批量删除 SQL 查询记录'",String.class)).isEqualTo("admin");
    }

    @Test void loginLogoutProfileAndUiEventsUseVerifiedIdentityAndNeverPersistPasswordsOrForgedActors() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"alice\",\"password\":\"test-password-123\"}"))
            .andExpect(status().isOk());
        mvc.perform(put("/api/auth/profile").header("Authorization","Bearer "+userToken).contentType("application/json").content("{\"displayName\":\"Alice New\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/audit/events").header("Authorization","Bearer "+userToken).contentType("application/json")
                .content("{\"action\":\"THEME_CHANGE\",\"target\":\"dark\",\"username\":\"admin\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").header("Authorization","Bearer "+userToken)).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT username FROM da_operation_audit",String.class)).containsOnly("alice");
        assertThat(jdbc.queryForList("SELECT action FROM da_operation_audit",String.class)).contains("登录","修改个人资料","切换界面主题","退出登录");
        assertThat(jdbc.queryForList("SELECT * FROM da_operation_audit").toString()).doesNotContain("test-password-123",userToken,"password_hash");
    }
}

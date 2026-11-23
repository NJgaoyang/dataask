package com.dataask.admin;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.StarRocksGateway;
import com.dataask.semantic.EmbeddingSemanticService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(AdminAccessTest.MethodSecurityConfig.class)
class AdminAccessTest {
    @Autowired private AdminController controller;

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
        @Bean
        AdminController adminController() {
            return new AdminController(
                mock(StarRocksGateway.class), mock(RuntimeConfigService.class),
                mock(JdbcTemplate.class), mock(PasswordEncoder.class), mock(ObjectMapper.class),
                mock(EmbeddingSemanticService.class));
        }
    }

    private void authenticate(String role) {
        var authorities = "ADMIN".equals(role)
            ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),new SimpleGrantedAuthority("PERM_SYSTEM_ADMIN"))
            : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("test-user", null, authorities));
    }

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void ordinaryUserCannotReadOrModifyModelsAndDataSources() {
        authenticate("USER");
        assertThrows(AccessDeniedException.class, () -> controller.settings());
        assertThrows(AccessDeniedException.class, () -> controller.saveStarRocks(null));
        assertThrows(AccessDeniedException.class, () -> controller.testStarRocks(null));
        assertThrows(AccessDeniedException.class, () -> controller.saveAi(null));
        assertThrows(AccessDeniedException.class, () -> controller.testAi(null));
        assertThrows(AccessDeniedException.class, () -> controller.saveEmbedding(null));
        assertThrows(AccessDeniedException.class, () -> controller.testEmbedding(null));
        assertThrows(AccessDeniedException.class, () -> controller.rebuildSemantics());
        assertThrows(AccessDeniedException.class, () ->
            controller.updateUser("someone",
                new AdminController.UserEditRequest("Someone", "USER"),
                SecurityContextHolder.getContext().getAuthentication()));
        assertThrows(AccessDeniedException.class, () ->
            controller.resetPassword("someone",
                new AdminController.ResetPasswordRequest("new-long-password-123")));
    }

    @Test
    void customSystemAdminCannotEscalateIdentityToAdministrator() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("ops-user",null,List.of(
                new SimpleGrantedAuthority("ROLE_OPERATIONS"),
                new SimpleGrantedAuthority("PERM_SYSTEM_ADMIN"),
                new SimpleGrantedAuthority("SCOPE_CITY"))));

        // A delegated system operator may enter non-IAM system settings.
        assertDoesNotThrow(() -> controller.settings());

        // But identity, role and StarRocks authority boundaries remain true-admin only.
        assertThrows(AccessDeniedException.class, () ->
            controller.createUser(new AdminController.UserRequest(
                "backdoor-admin","Backdoor","long-password-123","ADMIN")));
        assertThrows(AccessDeniedException.class, () ->
            controller.resetPassword("admin",
                new AdminController.ResetPasswordRequest("new-admin-password-123")));
        assertThrows(AccessDeniedException.class, () ->
            controller.createRole(new AdminController.RoleRequest(
                "OPS2","Ops2",java.util.Set.of("SYSTEM_ADMIN"))));
        assertThrows(AccessDeniedException.class, () ->
            controller.saveStarRocks(null));
    }

    @Test
    void administratorCanEnterConfigurationMethods() {
        authenticate("ADMIN");
        assertDoesNotThrow(() -> controller.saveAi(null));
        assertDoesNotThrow(() -> controller.saveEmbedding(null));
    }
}

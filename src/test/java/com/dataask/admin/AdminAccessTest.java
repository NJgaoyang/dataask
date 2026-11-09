package com.dataask.admin;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.StarRocksGateway;
import com.dataask.semantic.EmbeddingSemanticService;
import com.dataask.semantic.JoinRuleService;
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
                mock(JoinRuleService.class), mock(EmbeddingSemanticService.class));
        }
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("test-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
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
    void administratorCanEnterConfigurationMethods() {
        authenticate("ADMIN");
        assertDoesNotThrow(() -> controller.saveAi(null));
        assertDoesNotThrow(() -> controller.saveEmbedding(null));
    }
}

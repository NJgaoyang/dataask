package com.dataask.admin;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.StarRocksGateway;
import com.dataask.semantic.EmbeddingSemanticService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminUserManagementTest {
    private JdbcTemplate jdbc;
    private PasswordEncoder encoder;
    private AdminController controller;
    private final UsernamePasswordAuthenticationToken operator =
        new UsernamePasswordAuthenticationToken("admin", null, List.of());

    @BeforeEach
    void prepare() {
        jdbc=mock(JdbcTemplate.class);
        encoder=mock(PasswordEncoder.class);
        controller=new AdminController(
            mock(StarRocksGateway.class),mock(RuntimeConfigService.class),
            jdbc,encoder,new ObjectMapper(),
            mock(EmbeddingSemanticService.class));
    }

    @Test
    void updatesExistingUserDisplayNameAndRole() {
        when(jdbc.queryForList(
            eq("SELECT role FROM da_users WHERE username=? FOR UPDATE"),eq("alice")))
            .thenReturn(List.of(Map.of("role","USER")));
        when(jdbc.update(anyString(),eq("Alice Zhang"),eq("ADMIN"),eq("alice"))).thenReturn(1);
        Map<String,Object> result=controller.updateUser("alice",
            new AdminController.UserEditRequest(" Alice Zhang ", "ADMIN"),operator);
        assertEquals(true,result.get("success"));
        verify(jdbc).update("UPDATE da_users SET display_name=?,role=? WHERE username=?",
            "Alice Zhang","ADMIN","alice");
    }

    @Test
    void refusesDemotingLastAdministrator() {
        when(jdbc.queryForList(
            eq("SELECT role FROM da_users WHERE username=? FOR UPDATE"),eq("bob")))
            .thenReturn(List.of(Map.of("role","ADMIN")));
        when(jdbc.queryForList(
            eq("SELECT username FROM da_users WHERE role='ADMIN' ORDER BY username FOR UPDATE")))
            .thenReturn(List.of(Map.of("username","bob")));
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            () -> controller.updateUser("bob",
                new AdminController.UserEditRequest("Bob","USER"),operator));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void refusesSelfRoleDemotion() {
        when(jdbc.queryForList(
            eq("SELECT role FROM da_users WHERE username=? FOR UPDATE"),eq("admin")))
            .thenReturn(List.of(Map.of("role","ADMIN")));
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            () -> controller.updateUser("admin",
                new AdminController.UserEditRequest("Admin","USER"),operator));
        assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
    }

    @Test
    void rejectsInvalidRole() {
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            () -> controller.updateUser("alice",
                new AdminController.UserEditRequest("Alice","SUPERUSER"),operator));
        assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void securelyResetsOnlyTargetPassword() {
        when(encoder.encode("another-long-secret-123")).thenReturn("encoded");
        when(jdbc.update(
            eq("UPDATE da_users SET password_hash=? WHERE username=?"),
            eq("encoded"),eq("alice"))).thenReturn(1);
        Map<String,Object> result=controller.resetPassword("alice",
            new AdminController.ResetPasswordRequest("another-long-secret-123"));
        assertEquals(true,result.get("success"));
        verify(jdbc).update(
            "UPDATE da_users SET password_hash=? WHERE username=?","encoded","alice");
    }

    @Test
    void rejectsShortResetPasswords() {
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            () -> controller.resetPassword("alice",
                new AdminController.ResetPasswordRequest("short")));
        assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
        verifyNoInteractions(encoder,jdbc);
    }
}

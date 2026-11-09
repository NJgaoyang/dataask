package com.dataask.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SelfPasswordChangeTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final JwtTokens jwt = mock(JwtTokens.class);
    private final AuthController controller = new AuthController(jdbc, passwords, jwt);
    private final UsernamePasswordAuthenticationToken alice =
        new UsernamePasswordAuthenticationToken("alice", null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

    @AfterEach void resetContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsBadOldPasswordAndDoesNotUpdate() {
        when(jdbc.query(eq("SELECT password_hash FROM da_users WHERE username=?"),
            any(RowMapper.class), eq("alice"))).thenReturn(List.of("stored-hash"));
        when(passwords.matches("wrong-password", "stored-hash")).thenReturn(false);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> controller.changePassword(
                new AuthController.ChangePasswordRequest("wrong-password", "new-password-123"),
                alice));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void changesOnlyTheAuthenticatedUserPassword() {
        when(jdbc.query(eq("SELECT password_hash FROM da_users WHERE username=?"),
            any(RowMapper.class), eq("alice"))).thenReturn(List.of("old-hash"));
        when(passwords.matches("previous-pass-123", "old-hash")).thenReturn(true);
        when(passwords.encode("updated-pass-123")).thenReturn("new-hash");
        when(jdbc.update(anyString(), eq("new-hash"), eq("alice"), eq("old-hash")))
            .thenReturn(1);

        Map<String,Object> answer = controller.changePassword(
            new AuthController.ChangePasswordRequest("previous-pass-123", "updated-pass-123"),
            alice);

        assertEquals(true,answer.get("success"));
        verify(jdbc).update(contains("WHERE username=? AND password_hash=?"),
            eq("new-hash"), eq("alice"), eq("old-hash"));
    }

    @Test
    void passwordPolicyAndConcurrentChangeAreEnforced() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () ->
                controller.changePassword(new AuthController.ChangePasswordRequest("old", "short"), alice))
                .getStatusCode());
        when(jdbc.query(eq("SELECT password_hash FROM da_users WHERE username=?"),
            any(RowMapper.class), eq("alice"))).thenReturn(List.of("old-hash"));
        when(passwords.matches("old-pass-123", "old-hash")).thenReturn(true);
        when(passwords.encode("new-pass-123")).thenReturn("new-hash");
        when(jdbc.update(anyString(), eq("new-hash"), eq("alice"), eq("old-hash")))
            .thenReturn(0);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class, () ->
                controller.changePassword(
                    new AuthController.ChangePasswordRequest("old-pass-123", "new-pass-123"),
                    alice)).getStatusCode());
    }

    @Test
    void passwordChangeInvalidatesPreviouslyIssuedTokens() throws Exception {
        String oldStamp=JwtTokens.passwordStamp("old-hash");
        assertNotEquals(oldStamp,JwtTokens.passwordStamp("new-hash"));
        when(jwt.read("old.jwt.token")).thenReturn(
            new JwtTokens.Identity("alice", "USER", oldStamp));
        when(jdbc.queryForList(
            eq("SELECT role,password_hash FROM da_users WHERE username=?"), eq("alice")))
            .thenReturn(List.of(Map.of("role", "USER", "password_hash", "new-hash")));
        MockHttpServletRequest request=new MockHttpServletRequest();
        request.addHeader("Authorization","Bearer old.jwt.token");
        MockHttpServletResponse response=new MockHttpServletResponse();
        FilterChain chain=mock(FilterChain.class);

        new JwtFilter(jwt, jdbc).doFilterInternal(request,response,chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request,response);
    }
}

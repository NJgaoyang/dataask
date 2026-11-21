package com.dataask.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SelfProfileTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuthController controller = new AuthController(jdbc, mock(PasswordEncoder.class), mock(JwtTokens.class));
    private final UsernamePasswordAuthenticationToken alice = new UsernamePasswordAuthenticationToken("alice", null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));

    @Test void profileReadsOnlyTheAuthenticatedUserAndNeverReturnsCredentials() {
        when(jdbc.queryForList(anyString(),eq("alice"))).thenReturn(List.of(Map.of(
            "display_name","Alice", "created_at","2026-11-10 09:00:00", "role","USER", "password_hash","never-return-this")));
        var profile = controller.profile(alice);
        assertThat(profile).containsEntry("username","alice").containsEntry("displayName","Alice")
            .containsEntry("role","USER").containsEntry("roleName","USER").containsEntry("dataScope","ALL");
        assertThat(profile).containsKeys("username","displayName","createdAt","role","roleName","permissions","dataScope");
        verify(jdbc).queryForList("SELECT display_name,created_at,role FROM da_users WHERE username=?","alice");
    }

    @Test void displayNameUpdatesOnlyTheAuthenticatedAccountWithoutChangingRoleOrPassword() {
        when(jdbc.queryForList(anyString(),eq("alice"))).thenReturn(List.of(Map.of("display_name","Alice","created_at","2026-11-10","role","USER")));
        assertThat(controller.updateProfile(new AuthController.UpdateProfileRequest("  Alice Zhang  "),alice))
            .containsEntry("displayName","Alice Zhang").containsEntry("username","alice");
        verify(jdbc).update("UPDATE da_users SET display_name=? WHERE username=?","Alice Zhang","alice");
        verify(jdbc,never()).update(contains("role="),any(),any());
        verify(jdbc,never()).update(contains("password_hash="),any(),any());
    }

    @Test void rejectsBlankOversizedAndControlCharacterNamesWithoutWriting() {
        for(String name : List.of("   ","x".repeat(101),"Alice\nAdmin")) {
            assertThatThrownBy(() -> controller.updateProfile(new AuthController.UpdateProfileRequest(name),alice))
                .isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(jdbc);
    }

    @Test void missingAccountsCannotBeReadOrUpdated() {
        when(jdbc.queryForList(anyString(),eq("alice"))).thenReturn(List.of());
        assertThatThrownBy(() -> controller.profile(alice)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.updateProfile(new AuthController.UpdateProfileRequest("Alice"),alice))
            .isInstanceOf(ResponseStatusException.class);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}

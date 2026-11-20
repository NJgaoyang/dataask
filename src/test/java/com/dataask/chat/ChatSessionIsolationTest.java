package com.dataask.chat;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.SqlGuard;
import com.dataask.data.StarRocksGateway;
import com.dataask.metrics.MetricDictionaryService;
import com.dataask.semantic.EmbeddingSemanticService;
import com.dataask.semantic.EntityValueResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatSessionIsolationTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private ChatController controller;
    private final Authentication alice = new UsernamePasswordAuthenticationToken("alice", null, List.of());
    private final Authentication bob = new UsernamePasswordAuthenticationToken("bob", null, List.of());

    @BeforeEach
    void setUp() {
        controller = new ChatController(jdbc, mock(StarRocksGateway.class), mock(SqlGuard.class),
            new ObjectMapper(), mock(RuntimeConfigService.class), mock(EntityValueResolver.class),
            mock(MetricDictionaryService.class), mock(ColumnLabelService.class),
            mock(QuestionPresentationService.class), mock(EmbeddingSemanticService.class),
            new ChatCancellationService());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsOnlySessionsOwnedByTheAuthenticatedUser() {
        when(jdbc.query(contains("FROM da_sessions WHERE username=?"), any(RowMapper.class), eq("alice")))
            .thenReturn(List.of(Map.of("id", "alice-session", "title", "Alice 的会话")));

        List<Map<String,Object>> sessions = controller.sessions(alice);

        assertEquals("alice-session", sessions.getFirst().get("id"));
        verify(jdbc).query(contains("FROM da_sessions WHERE username=?"), any(RowMapper.class), eq("alice"));
        verify(jdbc, never()).query(contains("FROM da_sessions WHERE username=?"), any(RowMapper.class), eq("bob"));
    }

    @Test
    void rejectsReadingAnotherUsersSessionBeforeLoadingAnyMessages() {
        when(jdbc.queryForObject(
            "SELECT COUNT(*) FROM da_sessions WHERE id=? AND username=?",
            Integer.class, "alice-session", "bob")).thenReturn(0);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> controller.messages("alice-session", bob));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(jdbc, never()).query(startsWith("SELECT payload_json FROM da_messages"),
            any(RowMapper.class), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadsMessagesOnlyAfterVerifyingSessionOwnership() {
        when(jdbc.queryForObject(
            "SELECT COUNT(*) FROM da_sessions WHERE id=? AND username=?",
            Integer.class, "alice-session", "alice")).thenReturn(1);
        when(jdbc.query(startsWith("SELECT payload_json FROM da_messages"),
            any(RowMapper.class), eq("alice-session")))
            .thenReturn(List.of("{\"id\":\"message-1\",\"question\":\"昨日营业额\"}"));

        var messages = controller.messages("alice-session", alice);

        assertEquals("message-1", messages.getFirst().path("id").asText());
        verify(jdbc).queryForObject(
            "SELECT COUNT(*) FROM da_sessions WHERE id=? AND username=?",
            Integer.class, "alice-session", "alice");
    }

    @Test
    void rejectsDeletingAnotherUsersSessionBeforeAnyDelete() {
        when(jdbc.queryForObject(
            "SELECT COUNT(*) FROM da_sessions WHERE id=? AND username=?",
            Integer.class, "alice-session", "bob")).thenReturn(0);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> controller.deleteSession("alice-session", bob));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(jdbc, never()).update(startsWith("DELETE FROM"), any(Object[].class));
    }

    @Test
    void deletesOwnSessionTogetherWithItsMessages() {
        when(jdbc.queryForObject(
            "SELECT COUNT(*) FROM da_sessions WHERE id=? AND username=?",
            Integer.class, "alice-session", "alice")).thenReturn(1);
        when(jdbc.update("DELETE FROM da_messages WHERE session_id=?", "alice-session")).thenReturn(3);
        when(jdbc.update("DELETE FROM da_sessions WHERE id=? AND username=?", "alice-session", "alice")).thenReturn(1);

        assertEquals(1, controller.deleteSession("alice-session", alice).get("deleted"));

        verify(jdbc).update("DELETE FROM da_messages WHERE session_id=?", "alice-session");
        verify(jdbc).update("DELETE FROM da_sessions WHERE id=? AND username=?", "alice-session", "alice");
    }

    @Test
    void batchDeleteOnlyRemovesSessionsOwnedByTheCaller() {
        var request = new ChatController.SessionDeleteRequest(List.of("alice-session", "bob-session"));
        when(jdbc.queryForList(contains("SELECT id FROM da_sessions WHERE username=? AND id IN"),
            eq(String.class), any(Object[].class))).thenReturn(List.of("alice-session"));
        when(jdbc.update(contains("DELETE FROM da_messages WHERE session_id IN"), any(Object[].class))).thenReturn(2);
        when(jdbc.update(contains("DELETE FROM da_sessions WHERE username=? AND id IN"), any(Object[].class))).thenReturn(1);

        assertEquals(1, controller.deleteSessions(request, alice).get("deleted"));

        verify(jdbc).queryForList(contains("SELECT id FROM da_sessions WHERE username=? AND id IN"),
            eq(String.class), eq(new Object[]{"alice", "alice-session", "bob-session"}));
        verify(jdbc).update("DELETE FROM da_messages WHERE session_id IN (?)", new Object[]{"alice-session"});
        verify(jdbc).update("DELETE FROM da_sessions WHERE username=? AND id IN (?)",
            new Object[]{"alice", "alice-session"});
    }

    @Test
    void batchDeleteWithoutOwnedSessionsIssuesNoDelete() {
        var request = new ChatController.SessionDeleteRequest(List.of("bob-session"));
        when(jdbc.queryForList(contains("SELECT id FROM da_sessions WHERE username=? AND id IN"),
            eq(String.class), any(Object[].class))).thenReturn(List.of());

        assertEquals(0, controller.deleteSessions(request, alice).get("deleted"));

        verify(jdbc, never()).update(startsWith("DELETE FROM"), any(Object[].class));
    }
}

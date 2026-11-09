package com.dataask.chat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatCancellationServiceTest {
    @Test
    void onlyRequestOwnerCanStopAndActiveJdbcStatementIsCancelled() throws Exception {
        ChatCancellationService service = new ChatCancellationService();
        ChatCancellationService.Active active = service.begin("alice", "12345678-aaaa");
        Statement statement = mock(Statement.class);
        try {
            active.registerStatement(statement);
            assertFalse(service.cancel("bob","12345678-aaaa"));
            verifyNoInteractions(statement);
            assertTrue(service.cancel("alice","12345678-aaaa"));
            verify(statement).cancel();
            ResponseStatusException error = assertThrows(ResponseStatusException.class, active::checkCancelled);
            assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        } finally {
            service.end(active);
        }
        assertFalse(Thread.currentThread().isInterrupted());
        assertFalse(service.cancel("alice","12345678-aaaa"));
    }

    @Test
    void duplicateActiveIdsAreRejectedAndReleasedForReuse() {
        ChatCancellationService service = new ChatCancellationService();
        ChatCancellationService.Active active = service.begin("alice", "12345678-bbbb");
        try {
            assertEquals(HttpStatus.CONFLICT,
                assertThrows(ResponseStatusException.class, () ->
                    service.begin("alice","12345678-bbbb")).getStatusCode());
        } finally {
            service.end(active);
        }
        ChatCancellationService.Active next = service.begin("alice", "12345678-bbbb");
        service.end(next);
    }

    @Test
    void legacyRequestsWithoutRequestIdStaySupported() {
        ChatCancellationService service = new ChatCancellationService();
        ChatCancellationService.Active active = service.begin("mobile-user",null);
        assertDoesNotThrow(active::checkCancelled);
        service.end(active);
    }

    @Test
    void malformedRequestIdIsRejected() {
        ChatCancellationService service = new ChatCancellationService();
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () ->
                service.begin("alice","bad")).getStatusCode());
    }
}

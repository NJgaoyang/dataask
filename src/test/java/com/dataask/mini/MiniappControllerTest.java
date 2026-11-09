package com.dataask.mini;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MiniappControllerTest {
    private JdbcTemplate jdbc;
    private MiniappController controller;
    private Authentication alice;
    private Authentication admin;

    @BeforeEach
    void setup() {
        jdbc=mock(JdbcTemplate.class);
        controller=new MiniappController(jdbc);
        alice=new UsernamePasswordAuthenticationToken("alice",null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
        admin=new UsernamePasswordAuthenticationToken("root",null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void adminOnlyListIsProtected() {
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            ()->controller.charts("all",alice));
        assertEquals(HttpStatus.FORBIDDEN,error.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void invalidViewCannotCreateSqlInjection() {
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            ()->controller.charts("all' OR 1=1",alice));
        assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void privateChartCannotBeReadByOtherUser() {
        when(jdbc.queryForList(anyString(),eq("chart-1")))
            .thenReturn(List.of(Map.of("id","chart-1","ownerUsername","bob","shareScope","PRIVATE")));
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            ()->controller.chart("chart-1",alice));
        assertEquals(HttpStatus.NOT_FOUND,error.getStatusCode());
    }

    @Test
    void allSharedChartCanBeReadByMember() {
        when(jdbc.queryForList(anyString(),eq("chart-2")))
            .thenReturn(List.of(Map.of("id","chart-2","ownerUsername","bob","shareScope","ALL")));
        assertEquals("chart-2",controller.chart("chart-2",alice).get("id"));
    }

    @Test
    void administratorCanReadPrivateChart() {
        when(jdbc.queryForList(anyString(),eq("chart-3")))
            .thenReturn(List.of(Map.of("id","chart-3","ownerUsername","alice","shareScope","PRIVATE")));
        assertEquals("chart-3",controller.chart("chart-3",admin).get("id"));
    }

    @Test
    void saveRejectsForeignSessionMessages() {
        when(jdbc.queryForList(anyString(),eq("msg-bob"),eq("alice"))).thenReturn(List.of());
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            ()->controller.save(new MiniappController.SaveChart("msg-bob","test"),alice));
        assertEquals(HttpStatus.NOT_FOUND,error.getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}

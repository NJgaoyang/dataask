package com.dataask.mini;

import com.dataask.chat.ChatController;
import com.dataask.data.SqlGuard;
import com.dataask.data.StarRocksGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
    private ChatController chat;
    private SqlGuard guard;
    private StarRocksGateway starrocks;

    @BeforeEach
    void setup() {
        jdbc=mock(JdbcTemplate.class);
        chat=mock(ChatController.class);
        guard=mock(SqlGuard.class);
        starrocks=mock(StarRocksGateway.class);
        controller=new MiniappController(jdbc,chat,new ObjectMapper(),guard,starrocks);
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
    @Test
    void titlesAreShortVersionsOfQuestionsInsteadOfModelLabels() {
        assertEquals("昨日各城市订单量排行",
            ChartTitleFormatter.fromQuestion("请帮我查看昨天各城市订单量排行？"));
        assertEquals("近7天的营业额趋势",
            ChartTitleFormatter.fromQuestion("查看近7天的营业额趋势。"));
        assertEquals("我的图表",ChartTitleFormatter.fromQuestion(""));
    }

    @Test
    void chartPreviewRespectsSharingAndCurrentTablePermissions() throws Exception {
        String payload="{\"id\":\"msg1\",\"columns\":[\"city\",\"count\"],\"rows\":[{\"city\":\"西安市\",\"count\":18}],\"unsupported\":false}";
        when(jdbc.queryForList(anyString(),eq("chart1"))).thenReturn(List.of(Map.of(
            "id","chart1","ownerUsername","alice","shareScope","PRIVATE",
            "sourceMessageId","msg1","question","昨日各城市订单量排行")));
        when(jdbc.queryForList(eq("SELECT generated_sql,payload_json FROM da_messages WHERE id=?"),eq("msg1")))
            .thenReturn(List.of(Map.of("generated_sql","SELECT city,count FROM ads.orders",
                "payload_json",payload)));
        when(jdbc.query(eq("SELECT table_name FROM da_allowed_tables WHERE enabled=1"),
            any(org.springframework.jdbc.core.RowMapper.class)))
            .thenReturn(List.of("ads.orders"));
        when(starrocks.schema()).thenReturn("ads");
        when(guard.validate(anyString(),anySet(),eq("ads"))).thenReturn("SELECT city,count FROM ads.orders");
        Map<String,Object> view=controller.view("chart1",alice);
        assertEquals("昨日各城市订单量排行",((Map<?,?>)view.get("chart")).get("title"));
        assertEquals("西安市",((JsonNode)view.get("answer")).path("rows").get(0).path("city").asText());
        verify(guard).validate("SELECT city,count FROM ads.orders",Set.of("ads.orders"),"ads");
        verifyNoInteractions(chat);
    }

    @Test
    void chartRefreshDoesNotOpenANewConversation() {
        when(jdbc.queryForList(anyString(),eq("chart2"))).thenReturn(List.of(Map.of(
            "id","chart2","ownerUsername","alice","shareScope","PRIVATE",
            "question","昨日订单量","dataset","ads.orders")));
        ChatController.Answer fresh=new ChatController.Answer(
            "temp","transient","昨日订单量","订单量","真实数据","bar",
            List.of("city","total_orders"),Map.of("total_orders","订单量"),
            List.of(Map.of("city","西安市","total_orders",12)),List.of(),
            "订单量","昨日",false,"ads.orders");
        when(chat.previewChart(eq("昨日订单量"),eq("ads.orders"),eq(alice)))
            .thenReturn(fresh);
        Map<String,Object> output=controller.refresh("chart2",alice);
        assertEquals(false,output.get("cached"));
        assertSame(fresh,output.get("answer"));
        verify(chat).previewChart("昨日订单量","ads.orders",alice);
    }

    @Test
    void chartPreviewRejectsUnauthorizedCachedResult() {
        when(jdbc.queryForList(anyString(),eq("chart3"))).thenReturn(List.of(Map.of(
            "id","chart3","ownerUsername","alice","shareScope","PRIVATE",
            "sourceMessageId","msg3","question","私有订单")));
        when(jdbc.queryForList(anyString(),eq("msg3"))).thenReturn(List.of(Map.of(
            "generated_sql","SELECT * FROM ads.secret_orders","payload_json","{\"rows\":[]}")));
        when(jdbc.query(eq("SELECT table_name FROM da_allowed_tables WHERE enabled=1"),
            any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(List.of("ads.public_orders"));
        when(starrocks.schema()).thenReturn("ads");
        when(guard.validate(anyString(),anySet(),eq("ads")))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"表未授权"));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->controller.view("chart3",alice)).getStatusCode());
        verifyNoInteractions(chat);
    }

}

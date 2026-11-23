package com.dataask.chat;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.SqlGuard;
import com.dataask.data.StarRocksGateway;
import com.dataask.metrics.MetricDictionaryService;
import com.dataask.semantic.EmbeddingSemanticService;
import com.dataask.semantic.EntityValueResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChatDashboardFlowTest {
    @Test
    void dashboardIntentOnlyActivatesForPageStyleAnalysisRequests() {
        assertThat(ChatController.dashboardIntent("帮我生成本月经营驾驶舱")).isTrue();
        assertThat(ChatController.dashboardIntent("做一个经营看板")).isTrue();
        assertThat(ChatController.dashboardIntent("整体经营情况概览")).isTrue();
        assertThat(ChatController.dashboardIntent("昨天订单量是多少")).isFalse();
        assertThat(ChatController.dashboardIntent("按城市看订单量")).isFalse();
    }

    @Test @SuppressWarnings("unchecked")
    void dashboardRequestExecutesMultipleGuardedQueriesAndPersistsCards() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        StarRocksGateway gateway=mock(StarRocksGateway.class);
        RuntimeConfigService runtime=mock(RuntimeConfigService.class);
        EntityValueResolver entities=mock(EntityValueResolver.class);
        MetricDictionaryService dictionary=mock(MetricDictionaryService.class);
        EmbeddingSemanticService embeddings=mock(EmbeddingSemanticService.class);
        ObjectMapper json=new ObjectMapper();

        when(runtime.ai()).thenReturn(new RuntimeConfigService.AiConfig(
            "https://provider.example/v1","private-test-key","test-model"));
        when(jdbc.query(eq("SELECT table_name FROM da_allowed_tables WHERE enabled=1"),any(RowMapper.class)))
            .thenReturn(List.of("ads.orders"));
        when(gateway.schema()).thenReturn("ads");
        when(gateway.discover()).thenReturn(List.of(new StarRocksGateway.TableMeta("ads.orders",List.of(
            new StarRocksGateway.ColumnMeta("city","varchar","城市"),
            new StarRocksGateway.ColumnMeta("order_cnt","bigint","订单量"),
            new StarRocksGateway.ColumnMeta("dt","date","日期")))));
        when(entities.resolve(anyString(),anySet(),anyList())).thenReturn(List.of());
        when(entities.normalizeCityEquality(anyString(),anyList())).thenAnswer(inv->inv.getArgument(0));

        String monthStart=LocalDate.now(ZoneId.of("Asia/Shanghai")).withDayOfMonth(1).toString();
        String monthEnd=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusMonths(1).withDayOfMonth(1).toString();
        String kpiSql="SELECT SUM(order_cnt) AS total_orders FROM ads.orders WHERE dt >= '"+monthStart+"' AND dt < '"+monthEnd+"'";
        String trendSql="SELECT dt, SUM(order_cnt) AS total_orders FROM ads.orders WHERE dt >= '"+monthStart+"' AND dt < '"+monthEnd+"' GROUP BY dt ORDER BY dt";
        String citySql="SELECT city, SUM(order_cnt) AS total_orders FROM ads.orders WHERE dt >= '"+monthStart+"' AND dt < '"+monthEnd+"' GROUP BY city ORDER BY total_orders DESC LIMIT 10";

        when(gateway.query(anyString(),any())).thenAnswer(invocation->{
            String sql=invocation.getArgument(0);
            if(sql.contains("GROUP BY dt")) return new StarRocksGateway.QueryResult(
                List.of("dt","total_orders"),List.of(
                    Map.of("dt",monthStart,"total_orders",120),
                    Map.of("dt",LocalDate.parse(monthStart).plusDays(1).toString(),"total_orders",150)));
            if(sql.contains("GROUP BY city")) return new StarRocksGateway.QueryResult(
                List.of("city","total_orders"),List.of(
                    Map.of("city","南京市","total_orders",188),
                    Map.of("city","杭州市","total_orders",156)));
            return new StarRocksGateway.QueryResult(
                List.of("total_orders"),List.of(Map.of("total_orders",344)));
        });

        ChatController controller=new ChatController(jdbc,gateway,new SqlGuard(),json,runtime,entities,
            dictionary,new ColumnLabelService(),new QuestionPresentationService(),embeddings,
            new ChatCancellationService());
        RestClient.Builder builder=RestClient.builder();
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(controller,"http",builder.build());

        Map<String,Object> plan=Map.of(
            "title","本月经营驾驶舱","viewType","dashboard",
            "cards",List.of(
                Map.of("title","本月订单量","sql",kpiSql,"chartType","metric",
                    "metricLabel","订单量","measureColumn","total_orders",
                    "columnLabels",Map.of("total_orders","订单量")),
                Map.of("title","订单趋势","sql",trendSql,"chartType","line",
                    "metricLabel","订单量","measureColumn","total_orders",
                    "columnLabels",Map.of("dt","日期","total_orders","订单量")),
                Map.of("title","城市订单排行","sql",citySql,"chartType","bar",
                    "metricLabel","订单量","measureColumn","total_orders",
                    "columnLabels",Map.of("city","城市","total_orders","订单量"))
            ));
        server.expect(requestTo("https://provider.example/v1/chat/completions")).andRespond(withSuccess(
            json.writeValueAsString(Map.of("choices",List.of(Map.of(
                "message",Map.of("content",json.writeValueAsString(plan)))))),
            MediaType.APPLICATION_JSON));

        ChatController.Answer answer=controller.ask(
            new ChatController.AskRequest(null,"生成本月经营驾驶舱",null,"dash-req-1",null),
            new UsernamePasswordAuthenticationToken("alice",null,List.of()));

        server.verify();
        assertThat(answer.unsupported()).isFalse();
        assertThat(answer.viewType()).isEqualTo("dashboard");
        assertThat(answer.chartType()).isEqualTo("dashboard");
        assertThat(answer.dashboardCards()).hasSize(3);
        assertThat(answer.dashboardCards()).extracting(ChatController.DashboardCard::chartType)
            .containsExactly("metric","line","bar");
        assertThat(answer.dashboardCards()).extracting(ChatController.DashboardCard::title)
            .containsExactly("本月订单量","订单趋势","城市订单排行");
        assertThat(answer.dashboardCards()).extracting(ChatController.DashboardCard::metricLabel)
            .containsExactly("订单量","订单量","订单量");
        assertThat(answer.dashboardCards().get(1).columnLabels())
            .containsEntry("total_orders","订单量");
        assertThat(answer.dashboardCards().getFirst().rows().getFirst().get("total_orders"))
            .isEqualTo(344);
        assertThat(answer.rows()).isEmpty();
        verify(gateway,times(3)).query(anyString(),any());

        var payload=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(startsWith("INSERT INTO da_messages"),eq(answer.id()),eq(answer.sessionId()),
            eq(answer.question()),eq(kpiSql),eq(answer.title()),eq(answer.summary()),eq("dashboard"),payload.capture());
        ChatController.Answer cached=json.readValue(payload.getValue(),ChatController.Answer.class);
        assertThat(cached.viewType()).isEqualTo("dashboard");
        assertThat(cached.dashboardCards()).hasSize(3);
        assertThat(cached.dashboardCards().get(1).title()).contains("订单趋势");
    }
}

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

class ChatExplanationFlowTest {
    @Test @SuppressWarnings("unchecked")
    void queryFactsTimingsAndSummaryArePersistedAndSurviveHistorySerialization() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StarRocksGateway gateway = mock(StarRocksGateway.class);
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        EntityValueResolver entities = mock(EntityValueResolver.class);
        MetricDictionaryService dictionary = mock(MetricDictionaryService.class);
        EmbeddingSemanticService embeddings = mock(EmbeddingSemanticService.class);
        ObjectMapper json = new ObjectMapper();
        when(runtime.ai()).thenReturn(new RuntimeConfigService.AiConfig("https://provider.example/v1", "private-test-key", "test-model"));
        when(jdbc.query(eq("SELECT table_name FROM da_allowed_tables WHERE enabled=1"),any(RowMapper.class)))
            .thenReturn(List.of("ads.orders"));
        when(gateway.schema()).thenReturn("ads");
        when(gateway.discover()).thenReturn(List.of(new StarRocksGateway.TableMeta("ads.orders",List.of(
            new StarRocksGateway.ColumnMeta("city","varchar","城市"),
            new StarRocksGateway.ColumnMeta("order_cnt","bigint","订单量"),
            new StarRocksGateway.ColumnMeta("dt","date","日期")))));
        when(entities.resolve(anyString(),anySet(),anyList())).thenReturn(List.of());
        when(entities.normalizeCityEquality(anyString(),anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.query(anyString(),any())).thenReturn(new StarRocksGateway.QueryResult(
            List.of("city","total"),List.of(Map.of("city","西安市","total",188),Map.of("city","成都市","total",156))));
        ChatController controller = new ChatController(jdbc,gateway,new SqlGuard(),json,runtime,entities,
            dictionary,new ColumnLabelService(),new QuestionPresentationService(),embeddings,new ChatCancellationService());
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(controller,"http",builder.build());
        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
        String sql = "SELECT city, SUM(order_cnt) AS total FROM ads.orders WHERE dt = '" + day + "' GROUP BY city";
        String plan = json.writeValueAsString(Map.of("title","城市订单量","sql",sql,"chartType","bar",
            "metricLabel","订单量","measureColumn","total","columnLabels",Map.of("city","城市","total","订单量")));
        server.expect(requestTo("https://provider.example/v1/chat/completions")).andRespond(withSuccess(
            json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",plan))))),MediaType.APPLICATION_JSON));

        var answer = controller.ask(new ChatController.AskRequest(null,"昨天各城市的订单量排行",null,"query-test",null),
            new UsernamePasswordAuthenticationToken("alice",null,List.of()));
        server.verify();
        assertThat(answer.unsupported()).isFalse();
        assertThat(answer.semantic().dimensions()).extracting(QueryExplanation.Field::label).containsExactly("城市");
        assertThat(answer.semantic().metrics()).extracting(QueryExplanation.Field::label).containsExactly("订单量");
        assertThat(answer.semantic().filters()).containsExactly("dt = '" + day + "'");
        assertThat(answer.semantic().tables()).containsExactly("ads.orders");
        assertThat(answer.semantic().metrics()).allMatch(field -> field.expression().contains("SUM"));
        assertThat(answer.summary()).contains("西安市的订单量最高，为 188");
        assertThat(answer.execution().steps()).extracting(QueryExplanation.Step::key)
            .containsExactly("metadata","semantic","planning","validation","query","result");
        assertThat(answer.execution().steps()).allMatch(step -> step.status().equals("completed"));
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(startsWith("INSERT INTO da_messages"),eq(answer.id()),eq(answer.sessionId()),
            eq(answer.question()),eq(sql),eq(answer.title()),eq(answer.summary()),eq(answer.chartType()),payload.capture());
        var cached = json.readValue(payload.getValue(),ChatController.Answer.class);
        assertThat(cached.semantic().filters()).contains("dt = '" + day + "'");
        assertThat(cached.semantic().metrics().getFirst().expression()).contains("SUM");
        assertThat(cached.execution()).isEqualTo(answer.execution());
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM da_sessions WHERE id=? AND username=?"),eq(Integer.class),eq(answer.sessionId()),eq("alice")))
            .thenReturn(1);
        when(jdbc.query(eq("SELECT payload_json FROM da_messages WHERE session_id=? ORDER BY created_at,id"),any(RowMapper.class),eq(answer.sessionId())))
            .thenReturn(List.of(payload.getValue()));
        var historical = controller.messages(answer.sessionId(),new UsernamePasswordAuthenticationToken("alice",null,List.of()));
        assertThat(historical.getFirst().path("semantic").path("filters")).hasSize(1);
        assertThat(historical.getFirst().path("semantic").path("filters").get(0).asText()).isEqualTo("dt = '" + day + "'");
        assertThat(historical.getFirst().path("semantic").path("metrics").get(0).path("expression").asText()).contains("SUM");
        assertThat(payload.getValue()).doesNotContain("private-test-key","provider.example","messages\":", "上一轮使用SQL");
        // Previous payloads without the two new attributes remain readable.
        var old = json.valueToTree(answer).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)old).remove(List.of("semantic","execution"));
        assertThat(json.treeToValue(old,ChatController.Answer.class).semantic()).isNull();
    }
}

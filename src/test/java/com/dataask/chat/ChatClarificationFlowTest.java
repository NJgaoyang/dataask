package com.dataask.chat;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.SqlGuard;
import com.dataask.data.StarRocksGateway;
import com.dataask.metrics.MetricDictionaryService;
import com.dataask.semantic.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatClarificationFlowTest {
    @Test
    void vagueTrendReturnsClickableGroundedOptionsWithoutExecutingAnySql() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        StarRocksGateway starrocks=mock(StarRocksGateway.class);
        SqlGuard guard=mock(SqlGuard.class);
        RuntimeConfigService runtime=mock(RuntimeConfigService.class);
        EntityValueResolver entities=mock(EntityValueResolver.class);
        MetricDictionaryService dictionary=mock(MetricDictionaryService.class);
        ColumnLabelService labels=mock(ColumnLabelService.class);
        QuestionPresentationService presentation=mock(QuestionPresentationService.class);
        EmbeddingSemanticService embedding=mock(EmbeddingSemanticService.class);
        when(runtime.ai()).thenReturn(
            new RuntimeConfigService.AiConfig("https://example.com/v1","test-api-key","model"));
        when(jdbc.query(eq("SELECT table_name FROM da_allowed_tables WHERE enabled=1"),
            any(RowMapper.class))).thenReturn(
                List.of("ads.city_orders","ads.city_users"));
        when(starrocks.discover()).thenReturn(List.of(
            new StarRocksGateway.TableMeta("ads.city_orders",List.of(
                new StarRocksGateway.ColumnMeta("city","varchar","城市"),
                new StarRocksGateway.ColumnMeta("order_cnt","bigint","订单数"))),
            new StarRocksGateway.TableMeta("ads.city_users",List.of(
                new StarRocksGateway.ColumnMeta("city","varchar","城市"),
                new StarRocksGateway.ColumnMeta("user_cnt","bigint","用户数")))));
        when(entities.resolve(anyString(),anySet(),anyList())).thenReturn(List.of());
        when(entities.hints(anyList())).thenReturn("");
        when(dictionary.promptForQuestion(anyString(),anySet())).thenReturn("");
        when(embedding.hints(anyString(),anySet())).thenReturn("");
        when(starrocks.schema()).thenReturn("ads");
        when(presentation.resolve(anyString(),anyString(),any()))
            .thenReturn(new QuestionPresentationService.Presentation("","近7天"));
        when(presentation.heading(any(),anyString())).thenReturn("数据查询");

        ChatController controller=new ChatController(
            jdbc,starrocks,guard,new ObjectMapper(),runtime,entities,dictionary,
            labels,presentation,embedding,new ChatCancellationService());
        ChatController.Answer answer=controller.ask(
            new ChatController.AskRequest(
                null,"查看西安市近7天趋势",null,"clarify-request-0001",null),
            new UsernamePasswordAuthenticationToken("tester",null,List.of()));

        assertThat(answer.unsupported()).isTrue();
        assertThat(answer.title()).isEqualTo("请确认查询指标");
        assertThat(answer.summary()).contains("请选择一个指标");
        assertThat(answer.followUps()).containsExactly(
            "查看西安市近7天订单量趋势","查看西安市近7天用户数趋势");
        assertThat(answer.rows()).isEmpty();
        verifyNoInteractions(guard);
        verify(starrocks,never()).query(anyString(),any());
        verify(jdbc).update(contains("INSERT INTO da_messages"),
            any(),any(),any(),any(),any(),any(),any(),any());
    }
}

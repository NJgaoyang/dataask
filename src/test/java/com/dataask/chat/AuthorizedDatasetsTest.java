package com.dataask.chat;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.SqlGuard;
import com.dataask.data.StarRocksGateway;
import com.dataask.metrics.MetricDictionaryService;
import com.dataask.semantic.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthorizedDatasetsTest {
    private JdbcTemplate jdbc;
    private StarRocksGateway starrocks;
    private ChatController controller;

    @BeforeEach
    void prepare() {
        jdbc=mock(JdbcTemplate.class);
        starrocks=mock(StarRocksGateway.class);
        controller=new ChatController(
            jdbc,starrocks,mock(SqlGuard.class),new ObjectMapper(),
            mock(RuntimeConfigService.class),mock(EntityValueResolver.class),
            mock(MetricDictionaryService.class),mock(ColumnLabelService.class),
            mock(QuestionPresentationService.class),mock(EmbeddingSemanticService.class),
            new ChatCancellationService());
    }

    @Test
    void datasetListOnlyUsesApprovedTables() {
        when(jdbc.query(anyString(), any(RowMapper.class)))
            .thenReturn(List.of("ads.approved_orders", "ads.approved_sales"));
        assertEquals(List.of("ads.approved_orders", "ads.approved_sales"),controller.datasets());
        verify(jdbc).query(contains("enabled=1"),any(RowMapper.class));
    }

    @Test
    void previewRejectsUnapprovedDatasetBeforeTouchingStarRocks() {
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("ads.private"))).thenReturn(0);
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            ()->controller.datasetPreview("ads.private"));
        assertEquals(HttpStatus.FORBIDDEN,error.getStatusCode());
        verifyNoInteractions(starrocks);
    }

    @Test
    void previewShowsOnlyActualMetadataForApprovedDataset() {
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("ads.orders"))).thenReturn(1);
        when(starrocks.discover()).thenReturn(List.of(
            new StarRocksGateway.TableMeta("ads.orders", List.of(
                new StarRocksGateway.ColumnMeta("order_cnt","BIGINT","订单数")))));
        Map<String,Object> preview=controller.datasetPreview("ads.orders");
        assertEquals("ads.orders",preview.get("name"));
        assertEquals(1,((List<?>)preview.get("columns")).size());
        verify(starrocks).discover();
    }

    @Test
    void blankDatasetCannotBePreviewed() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->controller.datasetPreview("  "))
                .getStatusCode());
    }
}

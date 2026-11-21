package com.dataask.semantic;

import com.dataask.audit.AuditTestDatabase;
import com.dataask.config.RuntimeConfigService;
import com.dataask.data.StarRocksGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EmbeddingSourceIsolationTest {
    @Test
    void cachedMetricHintsCannotCrossSourceOrUnauthorizedTables() {
        var datasource=AuditTestDatabase.create();
        var jdbc=new JdbcTemplate(datasource);
        var settings=mock(RuntimeConfigService.class);
        String first="00000000-0000-0000-0000-000000000001";
        String second="00000000-0000-0000-0000-000000000002";
        var active=new java.util.concurrent.atomic.AtomicReference<>(first);
        when(settings.activeSourceId()).thenAnswer(invocation->active.get());
        var service=new EmbeddingSemanticService(settings,jdbc,mock(StarRocksGateway.class),
            new ObjectMapper(),new DataSourceTransactionManager(datasource));

        long firstSales=metric(jdbc,"ads.sales","amount",first);
        long secondSales=metric(jdbc,"ads.sales","amount",second);
        long hidden=metric(jdbc,"ads.secret_sales","amount",first);

        assertThat(service.allowedMetricIds(Set.of("ads.sales")))
            .containsExactly(Long.toString(firstSales));
        active.set(second);
        assertThat(service.allowedMetricIds(Set.of("ads.sales")))
            .containsExactly(Long.toString(secondSales));
        active.set(first);
        assertThat(service.allowedMetricIds(Set.of("ads.sales")))
            .containsExactly(Long.toString(firstSales))
            .doesNotContain(Long.toString(hidden));
        assertThat(service.allowedMetricIds(Set.of("ads.secret_sales")))
            .containsExactly(Long.toString(hidden));
    }

    @Test
    void complexMetricRequiresEveryReferencedTableInSemanticIndex() {
        var dataSource=AuditTestDatabase.create();
        var jdbc=new JdbcTemplate(dataSource);
        var settings=mock(RuntimeConfigService.class);
        when(settings.activeSourceId()).thenReturn("00000000-0000-0000-0000-000000000001");
        var service=new EmbeddingSemanticService(settings,jdbc,mock(StarRocksGateway.class),
            new ObjectMapper(),new DataSourceTransactionManager(dataSource));
        long id=metric(jdbc,"","", "00000000-0000-0000-0000-000000000001");
        jdbc.update("INSERT INTO da_metric_calculations " +
            "(metric_id,source_tables,source_fields,calculation_expression,join_rules) VALUES(?,?,?,?,?)",
            id,"ads.orders_di,ads.refunds_di","paid_amount,refund_amount",
            "SUM(paid_amount)-SUM(refund_amount)","order_id");
        assertThat(service.allowedMetricIds(Set.of("ads.orders_di"))).isEmpty();
        assertThat(service.allowedMetricIds(Set.of("ads.orders_di","ads.refunds_di")))
            .containsExactly(Long.toString(id));
    }

    private long metric(JdbcTemplate jdbc,String table,String field,String source) {
        String batch=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO da_metric_import_batches(id,source_file,uploaded_by,row_count) VALUES(?,?,?,1)",
            batch,"test","admin");
        jdbc.update("INSERT INTO da_metric_definitions " +
            "(batch_id,metric_name,aliases,definition,ads_table,ads_field,time_field,dimensions,notes,sheet_name,excel_row) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            batch,"销售额","","测试指标",table,field,"","city_id","","test",1);
        Long id=jdbc.queryForObject("SELECT MAX(id) FROM da_metric_definitions WHERE batch_id=?",Long.class,batch);
        jdbc.update("INSERT INTO da_metric_definition_sources(metric_id,data_source_id) VALUES(?,?)",
            id,source);
        return id;
    }
}

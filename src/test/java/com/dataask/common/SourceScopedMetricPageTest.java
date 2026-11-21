package com.dataask.common;

import com.dataask.audit.AuditTestDatabase;
import com.dataask.metrics.MetricDictionaryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceScopedMetricPageTest {
    @Test
    void metricPageIdentifiesSourcesForIdenticalTableNames() {
        JdbcTemplate jdbc=new JdbcTemplate(AuditTestDatabase.create());
        String first="00000000-0000-0000-0000-000000000001";
        String second="00000000-0000-0000-0000-000000000002";
        for(String[] source:List.of(new String[]{first,"经营数据源"},new String[]{second,"财务数据源"}))
            jdbc.update("INSERT INTO da_datasources(id,name,jdbc_url,username,password_enc,schema_name,description) " +
                "VALUES(?,?,?,?,?,?,?)",source[0],source[1],"jdbc:mysql://localhost:9030/ads",
                "reader","dummy","ads","");
        var service=new MetricDictionaryService(jdbc);
        var entry=new MetricDictionaryService.MetricEdit("营业额","","自动识别",
            "ads.sales","amount","","city_id","");
        service.importAutoSuggestions(List.of(entry),"admin",first);
        service.importAutoSuggestions(List.of(entry),"admin",second);

        @SuppressWarnings("unchecked")
        List<Map<String,Object>> items=(List<Map<String,Object>>)new RecordListController(jdbc)
            .metrics(1,10,"营业额").get("items");
        assertThat(items).hasSize(2);
        assertThat(items).extracting(x->x.get("sourceName"))
            .containsExactlyInAnyOrder("经营数据源","财务数据源");
    }
}

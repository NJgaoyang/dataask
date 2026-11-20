package com.dataask.data;

import com.dataask.config.RuntimeConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogPolicyFilterTest {
    @Test void filtersToSelectedFieldsAndPreservesTheirChineseComments() {
        var service=new CatalogPolicyService(mock(JdbcTemplate.class),mock(RuntimeConfigService.class),new ObjectMapper());
        var original=new StarRocksGateway.TableMeta("ads.revenue",List.of(
            new StarRocksGateway.ColumnMeta("amount","decimal","当日金额"),
            new StarRocksGateway.ColumnMeta("secret","decimal","内部字段")));
        var policy=new CatalogPolicyService.Policy("source","rev",Map.of("ads.revenue",List.of("amount")));
        var result=service.filter(List.of(original),policy);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().columns()).extracting(StarRocksGateway.ColumnMeta::name).containsExactly("amount");
        assertThat(result.getFirst().columns().getFirst().comment()).isEqualTo("当日金额");
        assertThat(service.filter(List.of(original),
            new CatalogPolicyService.Policy("source","rev",Map.of("ads.revenue",List.of())))).isEmpty();
    }
}

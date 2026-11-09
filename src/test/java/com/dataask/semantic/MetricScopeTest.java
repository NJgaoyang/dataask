package com.dataask.semantic;
import com.dataask.data.StarRocksGateway.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class MetricScopeTest {
    private final List<TableMeta> metadata=List.of(new TableMeta("ads.revenue",List.of(
        new ColumnMeta("amount","decimal","当日金额"),new ColumnMeta("dt","date","业务日期"),
        new ColumnMeta("city","varchar","城市名称"))));
    @Test void allowsSelectedMetricAndChineseDimensionLabel() {
        assertThat(MetricScope.permits("ads.revenue","SUM(amount)","dt","城市名称",metadata)).isTrue();
    }
    @Test void rejectsUnselectedMetricDateAndDimensionBindings() {
        assertThat(MetricScope.permits("ads.revenue","SUM(secret_amount)","dt","city",metadata)).isFalse();
        assertThat(MetricScope.permits("ads.revenue","amount","hidden_date","city",metadata)).isFalse();
        assertThat(MetricScope.permits("ads.revenue","amount","dt","private_city",metadata)).isFalse();
    }
    @Test void doesNotGuessAmbiguousUnqualifiedTables() {
        var duplicate=List.of(metadata.getFirst(),new TableMeta("other.revenue",metadata.getFirst().columns()));
        assertThat(MetricScope.permits("revenue","amount","dt","city",duplicate)).isFalse();
    }
    @Test void allowsGeneralBusinessDefinitionsButNotUnboundPhysicalFields() {
        assertThat(MetricScope.permits("","","","",metadata)).isTrue();
        assertThat(MetricScope.permits("","amount","","",metadata)).isFalse();
    }
}

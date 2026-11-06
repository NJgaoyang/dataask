package com.dataask.semantic;

import com.dataask.data.StarRocksGateway;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityValueResolverTest {
    private final StarRocksGateway gateway = mock(StarRocksGateway.class);
    private final EntityValueResolver resolver = new EntityValueResolver(gateway);

    private List<StarRocksGateway.TableMeta> metadata() {
        return List.of(new StarRocksGateway.TableMeta("ads.revenue_city_di",
            List.of(new StarRocksGateway.ColumnMeta("city_name", "varchar",
                "城市名称"), new StarRocksGateway.ColumnMeta("pay_amount", "decimal", "收入金额"))));
    }

    @Test void resolvesXianToRealValue() {
        when(gateway.schema()).thenReturn("ads");
        when(gateway.distinctDimensionValues("ads.revenue_city_di","city_name"))
            .thenReturn(List.of("西安市","北京市","杭州市"));
        var matches=resolver.resolve("昨天西安的收入是多少", Set.of("ads.revenue_city_di"), metadata());
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().alias()).isEqualTo("西安");
        assertThat(matches.getFirst().canonical()).isEqualTo("西安市");
        assertThat(resolver.normalizeCityEquality(
            "SELECT SUM(pay_amount) FROM ads.revenue_city_di WHERE city_name = '西安'",matches))
            .contains("city_name = '西安市'");
    }

    @Test void exactNameHasPriorityOverShortAlias() {
        when(gateway.schema()).thenReturn("ads");
        when(gateway.distinctDimensionValues("ads.revenue_city_di","city_name"))
            .thenReturn(List.of("西安市"));
        var matches=resolver.resolve("昨天西安市的收入",Set.of("ads.revenue_city_di"),metadata());
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().alias()).isEqualTo("西安市");
    }

    @Test void unapprovedTablesDoNotFeedEntityAliases() {
        var matches=resolver.resolve("西安收入", Set.of(),metadata());
        assertThat(matches).isEmpty();
        verify(gateway,never()).distinctDimensionValues(anyString(),anyString());
    }

    @Test void doesNotRewriteUnrelatedColumns() {
        when(gateway.schema()).thenReturn("ads");
        when(gateway.distinctDimensionValues("ads.revenue_city_di","city_name"))
            .thenReturn(List.of("西安市"));
        var matches=resolver.resolve("西安收入",Set.of("ads.revenue_city_di"),metadata());
        String query="SELECT SUM(pay_amount) FROM ads.revenue_city_di WHERE channel_name = '西安'";
        assertThat(resolver.normalizeCityEquality(query,matches)).isEqualTo(query);
    }
}

package com.dataask.data;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CityRowGuardTest {
    private final CityRowGuard guard=new CityRowGuard();
    private final List<StarRocksGateway.TableMeta> metadata=List.of(
        new StarRocksGateway.TableMeta("ads.daily_revenue",List.of(
            new StarRocksGateway.ColumnMeta("city_name","varchar","城市"),
            new StarRocksGateway.ColumnMeta("amount","decimal","营业额"))),
        new StarRocksGateway.TableMeta("ads.city_orders",List.of(
            new StarRocksGateway.ColumnMeta("city_name_std","varchar","城市"),
            new StarRocksGateway.ColumnMeta("orders","bigint","订单量"))),
        new StarRocksGateway.TableMeta("ads.global_total",List.of(
            new StarRocksGateway.ColumnMeta("amount","decimal","金额"))));

    @Test void filtersSingleTableAndPreservesExistingWhere() {
        String sql=guard.scope("SELECT city_name, SUM(amount) FROM ads.daily_revenue WHERE amount>0 GROUP BY city_name",
            metadata,List.of("杭州市","武汉市"),"ads");
        assertThat(sql).contains("杭州市","武汉市"," IN ","GROUP BY");
    }
    @Test void filtersBothJoinedTables() {
        String sql=guard.scope("SELECT t.city_name, SUM(t.amount) FROM ads.daily_revenue t " +
            "JOIN ads.city_orders o ON t.city_name=o.city_name_std GROUP BY t.city_name",
            metadata,List.of("北京市"),"ads");
        assertThat(sql).contains("city_name_std","北京市");
        assertThat(sql.split(" IN ",-1).length).isGreaterThanOrEqualTo(3);
    }
    @Test void deniesUnknownCityLineageAndNestedSelects() {
        assertThatThrownBy(()->guard.scope("SELECT amount FROM ads.global_total",
            metadata,List.of("北京市"),"ads")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->guard.scope("SELECT city_name FROM ads.daily_revenue WHERE amount IN " +
            "(SELECT amount FROM ads.city_orders)",metadata,List.of("北京市"),"ads"))
            .isInstanceOf(ResponseStatusException.class);
    }
    @Test void deniesEmptyGrantsOrDirectPermissionTableReads() {
        assertThatThrownBy(()->guard.scope("SELECT * FROM ads.daily_revenue",metadata,List.of(),"ads"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex->assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->guard.scope("SELECT city_name FROM ads.ads_user_city_permission",
            metadata,List.of("北京市"),"ads")).isInstanceOf(ResponseStatusException.class);
    }
}

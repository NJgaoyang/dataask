package com.dataask.data;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SqlGuardTest {
    private final SqlGuard guard = new SqlGuard();
    private final Set<String> allowed = Set.of("ads.revenue_city_di");

    @Test void permitsAggregate() {
        String sql = "SELECT city_name, SUM(pay_amount) FROM ads.revenue_city_di GROUP BY city_name";
        assertThat(guard.validate(sql, allowed, "ads")).isEqualTo(sql);
    }
    @Test void rejectsWrite() {
        assertThatThrownBy(() -> guard.validate("DELETE FROM ads.revenue_city_di", allowed, "ads"))
            .isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsUnapproved() {
        assertThatThrownBy(() -> guard.validate("SELECT * FROM ads.secret_users", allowed, "ads"))
            .isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsMultiStatement() {
        assertThatThrownBy(() -> guard.validate("SELECT * FROM ads.revenue_city_di; DROP TABLE x", allowed, "ads"))
            .isInstanceOf(ResponseStatusException.class);
    }
    @Test void allowsJoinBetweenAnyApprovedTablesWithoutRules() {
        Set<String> readable = Set.of("ads.revenue_city_di", "ads.orders_city_di");
        String sql = "SELECT r.city_name, o.order_cnt " +
            "FROM ads.revenue_city_di r INNER JOIN ads.orders_city_di o " +
            "ON r.city_name=o.city";
        assertThat(guard.validate(sql, readable, "ads")).isEqualTo(sql);
    }
    @Test void allowsArbitraryValidJoinConditionsBetweenGrantedTables() {
        Set<String> readable = Set.of("ads.revenue_city_di", "ads.orders_city_di");
        String sql = "SELECT r.city_name, o.order_cnt " +
            "FROM ads.revenue_city_di r LEFT JOIN ads.orders_city_di o " +
            "ON r.city_name=o.city AND r.dt>=o.dt";
        assertThat(guard.validate(sql, readable, "ads")).isEqualTo(sql);
    }
    @Test void allowsThreeReadableTablesWithoutAnyAdminJoinRule() {
        Set<String> readable=Set.of("ads.revenue_city_di","ads.orders_city_di","ads.region_dim");
        String sql = "SELECT r.city_name, SUM(o.order_cnt) " +
            "FROM ads.revenue_city_di r " +
            "JOIN ads.orders_city_di o ON r.city_name=o.city " +
            "LEFT JOIN ads.region_dim d ON o.city=d.city_name " +
            "GROUP BY r.city_name";
        assertThat(guard.validate(sql,readable,"ads")).isEqualTo(sql);
    }
    @Test void rejectsUnauthorizedTableEvenWhenJoinedFromApprovedTable() {
        Set<String> readable=Set.of("ads.revenue_city_di","ads.orders_city_di");
        String sql = "SELECT r.city_name FROM ads.revenue_city_di r " +
            "JOIN ads.private_customers p ON r.city_name=p.city";
        assertThatThrownBy(() -> guard.validate(sql,readable,"ads"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("未授权");
    }
    @Test void rejectsUnauthorizedNestedJoinTable() {
        Set<String> readable=Set.of("ads.revenue_city_di","ads.orders_city_di");
        String sql = "SELECT r.city_name FROM ads.revenue_city_di r " +
            "JOIN (SELECT city FROM ads.private_customers) p ON r.city_name=p.city";
        assertThatThrownBy(() -> guard.validate(sql,readable,"ads"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("未授权");
    }

}

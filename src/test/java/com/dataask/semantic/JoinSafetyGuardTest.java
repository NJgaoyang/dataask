package com.dataask.semantic;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class JoinSafetyGuardTest {
    private final JoinSafetyGuard guard = new JoinSafetyGuard();

    private List<JoinRuleService.Rule> approved() {
        return List.of(new JoinRuleService.Rule(1L,"ads.revenue_di","ads.orders_di",
            List.of("city_name","dt"),List.of("city","dt"),"ONE_TO_ONE",
            "两张ADS汇总表在每个城市每个日期上分别唯一"));
    }

    @Test void acceptsApprovedCompositeJoin() {
        String sql = "SELECT r.city_name, SUM(r.amount) AS total_amount " +
            "FROM ads.revenue_di r JOIN ads.orders_di o ON r.city_name = o.city " +
            "AND r.dt = o.dt GROUP BY r.city_name";
        assertThatCode(() -> guard.validate(sql,approved(),"ads")).doesNotThrowAnyException();
    }

    @Test void rejectsJoinMissingPartOfGrain() {
        String sql = "SELECT r.city_name FROM ads.revenue_di r " +
            "JOIN ads.orders_di o ON r.city_name = o.city";
        assertThatThrownBy(() -> guard.validate(sql,approved(),"ads"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test void rejectsUnreviewedJoin() {
        String sql = "SELECT r.city_name FROM ads.revenue_di r " +
            "JOIN ads.orders_di o ON r.city_name = o.city AND r.dt = o.dt";
        assertThatThrownBy(() -> guard.validate(sql,List.of(),"ads"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test void keepsSingleTableQueriesWorking() {
        assertThatCode(() -> guard.validate("SELECT COUNT(*) FROM ads.revenue_di", List.of(), "ads"))
            .doesNotThrowAnyException();
    }
}

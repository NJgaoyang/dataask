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
        assertThatThrownBy(() -> guard.validate("DELETE FROM ads.revenue_city_di", allowed))
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
}

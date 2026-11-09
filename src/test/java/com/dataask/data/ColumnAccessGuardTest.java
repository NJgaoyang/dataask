package com.dataask.data;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ColumnAccessGuardTest {
    private final ColumnAccessGuard guard = new ColumnAccessGuard();
    private final Map<String,List<String>> selected = Map.of(
        "ads.revenue", List.of("dt", "city", "amount"),
        "lookup.cities", List.of("city", "region"));
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT SUM(amount) AS 总金额 FROM ads.revenue WHERE dt='2026-11-08'",
        "SELECT city,SUM(amount) AS total FROM ads.revenue WHERE amount>0 GROUP BY city ORDER BY total DESC LIMIT 10",
        "SELECT COUNT(*) AS n FROM ads.revenue",
        "SELECT ROUND(COALESCE(SUM(amount),0),2) AS total FROM ads.revenue",
        "SELECT SUM(CASE WHEN city='西安市' THEN amount ELSE 0 END) AS total FROM ads.revenue",
        "SELECT DATE_TRUNC('day',dt),SUM(amount) FROM ads.revenue GROUP BY DATE_TRUNC('day',dt) HAVING SUM(amount)>0",
        "SELECT r.city,c.region,SUM(r.amount) AS total FROM ads.revenue r JOIN lookup.cities c ON r.city=c.city GROUP BY r.city,c.region",
        "SELECT city FROM ads.revenue WHERE city IN ('西安市','杭州市') AND amount BETWEEN 1 AND 100",
        "SELECT amount AS secret_amount FROM ads.revenue ORDER BY secret_amount"
    })
    void permitsResolvedSelectedFields(String sql) {
        assertThatCode(() -> guard.validate(sql, selected, "ads")).doesNotThrowAnyException();
    }
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT secret_amount FROM ads.revenue",
        "SELECT amount FROM ads.revenue WHERE secret_amount>0",
        "SELECT city,SUM(amount) FROM ads.revenue GROUP BY secret_amount",
        "SELECT amount FROM ads.revenue ORDER BY secret_amount",
        "SELECT SUM(amount) AS secret_amount FROM ads.revenue GROUP BY secret_amount",
        "SELECT SUM(amount) AS secret_amount FROM ads.revenue HAVING secret_amount>1",
        "SELECT COUNT(secret_amount) FROM ads.revenue",
        "SELECT SUM(CASE WHEN secret_amount>0 THEN amount ELSE 0 END) FROM ads.revenue",
        "SELECT r.amount FROM ads.revenue r JOIN lookup.cities c ON r.secret_amount=c.city",
        "SELECT * FROM ads.revenue",
        "SELECT r.* FROM ads.revenue r",
        "SELECT amount FROM revenue",
        "SELECT amount FROM ads.revenue WHERE amount IN (SELECT secret_amount FROM ads.revenue)",
        "SELECT ROW_NUMBER() OVER (ORDER BY secret_amount) FROM ads.revenue",
        "SELECT amount FROM ads.revenue UNION SELECT secret_amount FROM ads.revenue",
        "WITH x AS (SELECT secret_amount FROM ads.revenue) SELECT * FROM x",
        "SELECT LOAD_FILE('/etc/passwd') FROM ads.revenue"
    })
    void rejectsUnselectedOrUnresolvableFields(String sql) {
        assertThatThrownBy(() -> guard.validate(sql, selected, "ads"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}

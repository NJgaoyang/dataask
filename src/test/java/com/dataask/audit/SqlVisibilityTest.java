package com.dataask.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SqlVisibilityTest {
    @Test void allUsersCanSeeTheAuthorizedAnswerIncludingExecutionSql() throws Exception {
        var payload = new ObjectMapper().readTree("""
            {"sql":"SELECT secret FROM ads.orders", "semantic":{"dimensions":[{"column":"city","label":"城市","expression":"o.city"}],
            "metrics":[{"column":"total","label":"订单量","expression":"SUM(orders)"}],"filters":["dt='2026-11-09'"],"tables":["ads.orders"]},"execution":{"steps":[{"label":"数据查询"}]}}
            """);
        var user = new UsernamePasswordAuthenticationToken("alice",null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var admin = new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var visible = SqlVisibility.visible(payload,user);
        assertThat(visible).isEqualTo(payload);
        assertThat(SqlVisibility.visible(payload,admin)).isEqualTo(payload);
    }
}

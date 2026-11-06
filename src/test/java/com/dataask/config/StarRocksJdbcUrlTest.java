package com.dataask.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StarRocksJdbcUrlTest {
    @Test
    void adaptsStarRocksShorthandToBundledMysqlDriver() {
        assertThat(RuntimeConfigService.normalizeStarRocksUrl(
            "jdbc:starrocks://192.168.113.135:9030","ads"))
            .isEqualTo("jdbc:mysql://192.168.113.135:9030/ads");
    }

    @Test
    void leavesNormalMysqlUrlUnchanged() {
        String value="jdbc:mysql://127.0.0.1:9030/ads";
        assertThat(RuntimeConfigService.normalizeStarRocksUrl(value,"ads"))
            .isEqualTo(value);
    }
}

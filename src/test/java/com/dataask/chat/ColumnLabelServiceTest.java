package com.dataask.chat;

import com.dataask.data.StarRocksGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnLabelServiceTest {
    private final ColumnLabelService service = new ColumnLabelService();
    private final ObjectMapper json = new ObjectMapper();

    @Test void translatesResultAliasesToChinese() {
        Map<String,String> labels = service.labels(
            List.of("city","total_orders"),
            json.createObjectNode(),
            Set.of("ads.orders_di"),
            List.of(new StarRocksGateway.TableMeta("ads.orders_di", List.of(
                new StarRocksGateway.ColumnMeta("city","varchar","城市名称"),
                new StarRocksGateway.ColumnMeta("orders","bigint","支付订单数量")
            )))
        );
        assertThat(labels).containsEntry("city","城市名称")
                          .containsEntry("total_orders","订单总数");
    }

    @Test void aiChineseAliasMayBeUsedWithoutChangingDataKeys() throws Exception {
        Map<String,String> labels = service.labels(
            List.of("city","total_orders"),
            json.readTree("{\"city\":\"城市\",\"total_orders\":\"订单数量\"}"),
            Set.of(),List.of()
        );
        assertThat(labels).containsEntry("city","城市")
                          .containsEntry("total_orders","订单数量");
    }

    @Test void unknownAliasIsStillShownAsChinesePlaceholder() {
        Map<String,String> labels = service.labels(
            List.of("unknown_rare_field"),json.createObjectNode(),Set.of(),List.of());
        assertThat(labels).containsEntry("unknown_rare_field","字段1");
    }
}

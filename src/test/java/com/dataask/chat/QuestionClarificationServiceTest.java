package com.dataask.chat;

import com.dataask.data.StarRocksGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class QuestionClarificationServiceTest {
    private final QuestionClarificationService service = new QuestionClarificationService();

    private final List<StarRocksGateway.TableMeta> metadata = List.of(
        new StarRocksGateway.TableMeta("ads.orders_city_di",List.of(
            new StarRocksGateway.ColumnMeta("city_name","varchar","城市"),
            new StarRocksGateway.ColumnMeta("order_cnt","bigint","订单数"),
            new StarRocksGateway.ColumnMeta("order_id","bigint","订单标识"))),
        new StarRocksGateway.TableMeta("ads.users_city_di",List.of(
            new StarRocksGateway.ColumnMeta("city_name","varchar","城市"),
            new StarRocksGateway.ColumnMeta("user_cnt","bigint","用户数"))),
        new StarRocksGateway.TableMeta("ads.sales_city_di",List.of(
            new StarRocksGateway.ColumnMeta("city_name","varchar","城市"),
            new StarRocksGateway.ColumnMeta("sales_amount","decimal(18,2)","营业额"))),
        new StarRocksGateway.TableMeta("ads.private_sales",List.of(
            new StarRocksGateway.ColumnMeta("payment_amount","decimal","支付金额"))));

    private final Set<String> readable=Set.of(
        "ads.orders_city_di","ads.users_city_di","ads.sales_city_di");

    @Test
    void vagueSevenDayTrendOffersOnlyTrueApprovedMetrics() {
        assertThat(service.metricMissing("查看近7天趋势","")).isTrue();
        assertThat(service.choices("查看近7天趋势",readable,metadata))
            .containsExactly(
                "查看近7天订单量趋势",
                "查看近7天用户数趋势",
                "查看近7天营业额趋势");
    }

    @Test
    void retainsLocationAndPeriodInClickThroughQuestions() {
        assertThat(service.metricMissing("查看西安市近7天趋势","")).isTrue();
        assertThat(service.choices("查看西安市近7天趋势",readable,metadata))
            .containsExactly(
                "查看西安市近7天订单量趋势",
                "查看西安市近7天用户数趋势",
                "查看西安市近7天营业额趋势");
    }

    @Test
    void doesNotInterruptExplicitMetricOrFollowupContext() {
        assertThat(service.metricMissing("查看近7天订单量趋势","")).isFalse();
        assertThat(service.metricMissing("查看近7天趋势","昨天各城市订单量")).isFalse();
        assertThat(service.metricMissing("查询库存周转天数趋势","")).isFalse();
        assertThat(service.metricMissing("2026年销售数据如何","")).isFalse();
    }

    @Test
    void filtersUnauthorizedTableAndNeverPromotesIdentifiersIntoMeasures() {
        assertThat(service.choices("查看近7天趋势",
            Set.of("ads.orders_city_di"),metadata))
            .containsExactly("查看近7天订单量趋势");
        assertThat(service.choices("查看近7天趋势",
            Set.of("ads.private_sales"),metadata))
            .containsExactly("查看近7天支付金额趋势");
        assertThat(service.choices("查看近7天趋势",
            Set.of("ads.no_such_table"),metadata)).isEmpty();
        assertThat(service.choices("查看近7天趋势",readable,metadata))
            .noneMatch(item -> item.contains("支付金额") || item.contains("订单标识"));
    }

    @Test
    void ignoresNonNumericTextFieldsEvenWithMisleadingMetricName() {
        var columns=List.of(new StarRocksGateway.TableMeta("ads.orders",List.of(
            new StarRocksGateway.ColumnMeta("total_orders","varchar","订单数描述"),
            new StarRocksGateway.ColumnMeta("order_id","bigint","订单标识"))));
        assertThat(service.choices("查看近7天趋势",Set.of("ads.orders"),columns))
            .isEmpty();
    }

    @Test
    void asksForMetricsOnlyWhenTheClarificationRefersToThem() {
        assertThat(service.isMetricClarification("你想查询哪个业务指标？")).isTrue();
        assertThat(service.isMetricClarification("订单数还是用户数？")).isTrue();
        assertThat(service.isMetricClarification("请选择城市还是门店")).isFalse();
    }
}

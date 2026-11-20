package com.dataask.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryExplanationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void extractsGroupedDimensionsMultipleMetricsAndActualDateFilter() throws Exception {
        var semantic = QueryExplanation.semantic(
            "SELECT city, SUM(orders) AS total, AVG(amount) AS avg_amount FROM ads.orders " +
                "WHERE dt = '2026-11-09' GROUP BY city HAVING SUM(orders) > 0",
            List.of("city", "total", "avg_amount"), Map.of("city", "城市", "total", "订单量", "avg_amount", "平均金额"),
            json.readTree("{\"measureColumn\":\"total\"}"));
        assertThat(semantic.dimensions()).extracting(QueryExplanation.Field::label).containsExactly("城市");
        assertThat(semantic.metrics()).extracting(QueryExplanation.Field::label).containsExactly("订单量", "平均金额");
        assertThat(semantic.filters()).contains("dt = '2026-11-09'", "聚合后筛选：SUM(orders) > 0");
        assertThat(semantic.tables()).containsExactly("ads.orders");
        assertThat(semantic.partial()).isFalse();
    }

    @Test void numericIdentifiersGroupedByPositionAreDimensionsEvenIfModelCallsThemMeasures() throws Exception {
        var semantic = QueryExplanation.semantic("SELECT city_id, COUNT(*) AS n FROM ads.orders GROUP BY 1",
            List.of("city_id", "n"), Map.of(), json.readTree("{\"measureColumn\":\"city_id\"}"));
        assertThat(semantic.dimensions()).extracting(QueryExplanation.Field::column).containsExactly("city_id");
        assertThat(semantic.metrics()).extracting(QueryExplanation.Field::column).containsExactly("n");
    }

    @Test void recognizesRatiosWithoutSummingThemInNarrative() throws Exception {
        var semantic = QueryExplanation.semantic("SELECT city, SUM(paid) / NULLIF(SUM(total), 0) AS rate " +
            "FROM ads.orders GROUP BY city", List.of("city", "rate"), Map.of("city", "城市", "rate", "支付率"), json.readTree("{}"));
        assertThat(semantic.metrics()).extracting(QueryExplanation.Field::column).containsExactly("rate");
        String summary = QueryExplanation.summarize(List.of(Map.of("city", "甲市", "rate", new BigDecimal("0.8")),
            Map.of("city", "乙市", "rate", new BigDecimal("0.6"))), List.of("city", "rate"), Map.of(), semantic);
        assertThat(summary).contains("甲市的支付率最高，为 0.8", "不代表全部数据").doesNotContain("合计", "1.4", "导致");
    }

    @Test void quotesInsideStringLiteralsAreNotAggregateFunctions() throws Exception {
        var semantic = QueryExplanation.semantic("SELECT 'SUM(fake)' AS description FROM ads.orders",
            List.of("description"), Map.of(), json.readTree("{}"));
        assertThat(semantic.metrics()).isEmpty();
    }

    @Test void complexQueriesAndWildcardExpansionReportPartialRatherThanInventMappings() throws Exception {
        var union = QueryExplanation.semantic("SELECT city FROM ads.a UNION ALL SELECT city FROM ads.b",
            List.of("city"), Map.of(), json.readTree("{}"));
        assertThat(union.partial()).isTrue();
        assertThat(union.tables()).containsExactly("ads.a", "ads.b");
        var wildcard = QueryExplanation.semantic("SELECT * FROM ads.a", List.of("city", "amount"), Map.of(), json.readTree("{}"));
        assertThat(wildcard.partial()).isTrue();
        assertThat(wildcard.dimensions()).isEmpty();
        assertThat(wildcard.metrics()).isEmpty();
    }

    @Test void emptyResultsRetainVerifiedDimensionsAndMetrics() throws Exception {
        var semantic = QueryExplanation.semantic("SELECT city, SUM(amount) AS amount FROM ads.a GROUP BY city",
            List.of("city", "amount"), Map.of("city", "城市", "amount", "营业额"), json.readTree("{}"));
        assertThat(semantic.metrics()).extracting(QueryExplanation.Field::label).containsExactly("营业额");
        assertThat(QueryExplanation.summarize(List.of(), List.of("city", "amount"), Map.of(), semantic))
            .contains("没有返回数据").doesNotContain("最高");
    }

    @Test void summaryHandlesNullsNegativeValuesAndTiesWithoutInventingATotal() throws Exception {
        var semantic = QueryExplanation.semantic("SELECT city, SUM(amount) AS amount FROM ads.a GROUP BY city",
            List.of("city", "amount"), Map.of("city", "城市", "amount", "净收入"), json.readTree("{}"));
        String summary = QueryExplanation.summarize(List.of(Map.of("city", "甲市", "amount", -5),
            Map.of("city", "乙市", "amount", -5), Map.of("city", "丙市", "amount", -8)),
            List.of("city", "amount"), Map.of(), semantic);
        assertThat(summary).contains("最高，为 -5", "2 行并列").doesNotContain("合计");
    }

    @Test void traceOnlyContainsRecordedStagesAndNonnegativeMeasuredDurations() {
        var trace = new QueryExplanation.Trace();
        trace.mark("metadata", "数据准备", "completed", "已读取授权字段");
        trace.mark("planning", "查询规划", "needs_input", "等待确认指标");
        var execution = trace.finish();
        assertThat(execution.durationMs()).isNotNegative();
        assertThat(execution.steps()).extracting(QueryExplanation.Step::key).containsExactly("metadata", "planning");
        assertThat(execution.steps()).allMatch(step -> step.durationMs() >= 0);
    }
}

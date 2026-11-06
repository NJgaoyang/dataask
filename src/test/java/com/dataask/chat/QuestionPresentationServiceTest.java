package com.dataask.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class QuestionPresentationServiceTest {
    private final QuestionPresentationService service = new QuestionPresentationService();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode plan(String metric, String measure) {
        return mapper.createObjectNode().put("metricLabel",metric).put("measureColumn",measure);
    }

    @Test void yesterdayChangesTimeNotMetricName() {
        var result=service.resolve("前一天的总金额是多少？", "", plan("总金额","当日金额"));
        assertThat(result.metricLabel()).isEqualTo("总金额");
        assertThat(result.periodLabel()).isEqualTo("前一天");
        assertThat(service.heading(result,"当日金额")).isEqualTo("总金额");
        var labels=service.alignLabels(result,List.of("当日金额"),
            List.of(Map.of("当日金额",new BigDecimal("18320.50"))),
            Map.of("当日金额","当天金额"),plan("总金额","当日金额"));
        assertThat(labels).containsEntry("当日金额","总金额");
    }

    @Test void standaloneMetricRetainsItsName() {
        var result=service.resolve("总金额", "",plan("当日金额","amount"));
        assertThat(result.metricLabel()).isEqualTo("总金额");
        var prefix=service.resolve("查询总金额", "",plan("当日金额","amount"));
        assertThat(prefix.metricLabel()).isEqualTo("总金额");
    }

    @Test void directMetricQuestionKeepsUserExpression() {
        var result=service.resolve("西安的收入是多少", "", plan("收入","amount"));
        assertThat(result.metricLabel()).isEqualTo("收入");
    }

    @Test void inventedPhysicalLabelIsNotShownAsMetric() {
        var result=service.resolve("前一天的总金额", "",plan("当日金额","amount"));
        assertThat(result.metricLabel()).isEqualTo("总金额");
    }

    @Test void relativeDayMustMatchSqlAbsoluteDate() {
        LocalDate today=LocalDate.of(2026,11,6);
        assertThat(QuestionPresentationService.supportsRequestedDay(
            "前一天的总金额","SELECT SUM(amount) FROM ads.t WHERE dt='2026-11-05'",today))
            .isTrue();
        assertThat(QuestionPresentationService.supportsRequestedDay(
            "前一天的总金额","SELECT SUM(amount) FROM ads.t WHERE dt='2026-11-06'",today))
            .isFalse();
    }

    @Test void unsupportedPlanCannotMasqueradeAsAnswer() throws Exception {
        assertThat(QuestionPresentationService.hasValidPlan(
            mapper.readTree("{\"unsupported\":true}"))).isFalse();
        assertThat(QuestionPresentationService.hasValidPlan(
            mapper.readTree("{\"clarification\":\"口径未确认\"}"))).isFalse();
        assertThat(QuestionPresentationService.hasValidPlan(
            mapper.readTree("{\"sql\":\"SELECT 1\"}"))).isTrue();
        assertThat(QuestionPresentationService.UNSUPPORTED)
            .contains("当前问题不支持").contains("许愿");
    }
}

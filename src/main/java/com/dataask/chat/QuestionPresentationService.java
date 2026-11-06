package com.dataask.chat;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presentation is derived from the question's business meaning, never from a
 * physical measure such as "当日金额". Time is a separate attribute.
 */
@Service
public class QuestionPresentationService {
    public static final String UNSUPPORTED = "对不起，当前问题不支持，请找管理员“许愿”。";

    private static final Pattern PERIOD = Pattern.compile(
        "前一天|上一天|昨天|昨日|前天|今天|今日|本日|上周|本周|上个月|上月|本月|本季度|上季度|今年|去年|" +
        "近\\d+天|最近\\d+天|过去\\d+天|\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{4}-\\d{1,2}-\\d{1,2}");

    private static final Pattern QUESTION_TAIL = Pattern.compile(
        "(?:分别)?(?:是多少|有多少|多少钱|多少|怎么样|如何|趋势|排行|排名|情况|数据|明细|统计|呢|吗|[？?。！!])+$");
    private static final Pattern PREFIX = Pattern.compile(
        "^(?:请帮我|帮我|请|帮忙|查询一下|查询|查一下|查|统计一下|统计|看一下|查看|看看|看|分析一下|分析|给我|告诉我)+");

    public record Presentation(String metricLabel, String periodLabel) {}

    public Presentation resolve(String question, String previousQuestion, JsonNode plan) {
        String normalized = clean(question);
        String metric = eligibleMetric(normalized, plan.path("metricLabel").asText(""));
        if (metric.isEmpty()) metric = phraseAfterDe(normalized);
        if (metric.isEmpty() && previousQuestion != null && !previousQuestion.isBlank()) {
            String old = clean(previousQuestion);
            metric = eligibleMetric(old, plan.path("metricLabel").asText(""));
            if (metric.isEmpty()) metric = phraseAfterDe(old);
        }
        String period = periodLabel(normalized);
        if (period.isEmpty() && previousQuestion != null)
            period = periodLabel(clean(previousQuestion));
        return new Presentation(metric, period);
    }

    static String eligibleMetric(String question, String proposed) {
        String candidate = clean(proposed);
        candidate = QUESTION_TAIL.matcher(candidate).replaceAll("");
        candidate = PREFIX.matcher(candidate).replaceFirst("");
        candidate = PERIOD.matcher(candidate).replaceAll("");
        candidate = candidate.replaceAll("^(?:的|在|于|各|按)", "").trim();
        if (candidate.length() < 2 || candidate.length() > 20) return "";
        if (!question.contains(candidate)) return "";
        if (candidate.matches(".*(?:日期|时间|城市|省份|地区|门店|当日|当天|昨日|昨天)$")) return "";
        return candidate;
    }

    static String phraseAfterDe(String question) {
        String text = QUESTION_TAIL.matcher(question).replaceAll("");
        text = PREFIX.matcher(text).replaceFirst("");
        // Pick the right-most "的" if present: "昨天西安市的总金额" -> "总金额".
        int pos = text.lastIndexOf('的');
        if (pos >= 0 && pos < text.length() - 2) {
            String candidate = text.substring(pos + 1).trim();
            candidate = QUESTION_TAIL.matcher(candidate).replaceAll("");
            candidate = PERIOD.matcher(candidate).replaceAll("");
            if (candidate.length() >= 2 && candidate.length() <= 16 &&
                    !candidate.matches(".*(?:城市|门店|日期|月份)$")) return candidate;
        }
        // A bare request like “总金额” or “查询总金额” is itself the metric,
        // even if the model proposes a different physical field label.
        String bare = PERIOD.matcher(text).replaceAll("");
        bare = bare.replaceAll("^(?:的|关于|请问|想知道|我想了解)", "").trim();
        if (bare.length() >= 2 && bare.length() <= 12 &&
            !bare.contains("的") && !bare.matches(".*(?:城市|门店|日期|月份|排行榜)$") &&
            !bare.matches(".*(?:有哪些|怎么|如何).*")) return bare;
        return "";
    }

    public static String periodLabel(String question) {
        Matcher match = PERIOD.matcher(question == null ? "" : question);
        return match.find() ? match.group() : "";
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", "");
    }

    /**
     * Only replace an actual result measure when there is one unambiguous
     * numeric output. Dimension columns keep their true label.
     */
    public Map<String,String> alignLabels(Presentation presentation, List<String> columns,
                 List<Map<String,Object>> rows, Map<String,String> existing, JsonNode plan) {
        Map<String,String> out = new LinkedHashMap<>(existing);
        if (presentation.metricLabel().isEmpty() || columns.isEmpty() || rows.isEmpty()) return out;
        String chosen = plan.path("measureColumn").asText("");
        if (!columns.contains(chosen)) {
            List<String> numeric = new ArrayList<>();
            for (String col : columns) {
                if (rows.stream().anyMatch(row -> isNumeric(row.get(col)))) numeric.add(col);
            }
            if (numeric.size() == 1) chosen = numeric.getFirst();
            else return out;
        }
        String finalColumn = chosen;
        if (rows.stream().anyMatch(row -> isNumeric(row.get(finalColumn)))) {
            out.put(finalColumn, presentation.metricLabel());
        }
        return out;
    }

    private boolean isNumeric(Object val) {
        if (val instanceof Number) return true;
        if (val == null || val instanceof Boolean) return false;
        // Do not mistake dates, numeric-looking IDs or descriptions for values.
        if (!(val instanceof String string)) return false;
        return string.matches("[-+]?\\d+(\\.\\d+)?");
    }

    public String heading(Presentation presentation, String modelTitle) {
        String metric = presentation.metricLabel();
        if (metric.isEmpty()) return modelTitle == null || modelTitle.isBlank() ? "数据查询" : modelTitle;
        return metric; // Never prepend "昨日"/"当日" to the metric itself.
    }

    public static boolean supportsRequestedDay(String question, String sql, java.time.LocalDate today) {
        if (question == null || sql == null) return false;
        String text = question.replaceAll("\\s+", "");
        java.time.LocalDate target = null;
        if (text.contains("前天")) target = today.minusDays(2);
        else if (text.contains("昨天") || text.contains("昨日") ||
                 text.contains("前一天") || text.contains("上一天")) target = today.minusDays(1);
        if (target == null) return true;
        // The generator is instructed to use absolute dates. If the actual
        // target day is absent, never label an unrelated query as yesterday.
        return sql.contains(target.toString());
    }

    public static boolean hasValidPlan(JsonNode plan) {
        return plan != null && plan.isObject() &&
            !plan.path("unsupported").asBoolean(false) &&
            plan.path("clarification").asText("").isBlank() &&
            !plan.path("sql").asText("").isBlank();
    }
}

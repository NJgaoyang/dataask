package com.dataask.chat;

import com.fasterxml.jackson.databind.JsonNode;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.math.BigDecimal;
import java.util.*;

/** Public query facts only: never provider prompts, credentials or model reasoning. */
public final class QueryExplanation {
    private QueryExplanation() {}

    public record Field(String column, String label, String expression) {}
    public record Semantic(List<Field> dimensions, List<Field> metrics, List<String> filters,
                           List<String> tables, boolean partial) {}
    public record Step(String key, String label, String status, long durationMs, String detail) {}
    public record Execution(long durationMs, List<Step> steps) {}

    static final class Trace {
        private final long started = System.nanoTime();
        private long previous = started;
        private final List<Step> steps = new ArrayList<>();

        void mark(String key, String label, String status, String detail) {
            long now = System.nanoTime();
            steps.add(new Step(key, label, status, Math.max(0, (now - previous) / 1_000_000), detail));
            previous = now;
        }

        Execution finish() {
            return new Execution(Math.max(0, (System.nanoTime() - started) / 1_000_000), List.copyOf(steps));
        }
    }

    /** Parse the validated SQL that actually ran, not an unverified model explanation. */
    static Semantic semantic(String sql, List<String> columns, Map<String, String> labels, JsonNode plan) {
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            List<String> tables = new TablesNamesFinder().getTables((net.sf.jsqlparser.statement.Statement) select)
                .stream().sorted().toList();
            if (!(select instanceof PlainSelect plain))
                return new Semantic(List.of(), List.of(), List.of(), tables, true);
            List<Field> dimensions = new ArrayList<>(), metrics = new ArrayList<>();
            List<String> filters = new ArrayList<>();
            if (plain.getWhere() != null) filters.add(plain.getWhere().toString());
            if (plain.getHaving() != null) filters.add("聚合后筛选：" + plain.getHaving());
            if (plain.getSelectItems().size() != columns.size())
                return new Semantic(List.of(), List.of(), List.copyOf(filters), tables, true);
            boolean partial = false;
            String measure = plan.path("measureColumn").asText("");
            for (int i = 0; i < Math.min(plain.getSelectItems().size(), columns.size()); i++) {
                var item = plain.getSelectItems().get(i);
                Expression expression = item.getExpression();
                if (expression.toString().equals("*") || expression.toString().endsWith(".*")) {
                    partial = true;
                    continue;
                }
                String col = columns.get(i);
                Field field = new Field(col, labels.getOrDefault(col, col), expression.toString());
                boolean grouped = false;
                if (plain.getGroupBy() != null && plain.getGroupBy().getGroupByExpressionList() != null) {
                    for (Object group : plain.getGroupBy().getGroupByExpressionList()) {
                        String value = clean(group.toString());
                        if (value.equals(clean(expression.toString())) || value.equals(clean(col)) ||
                            value.equals(Integer.toString(i + 1))) grouped = true;
                    }
                }
                if (!grouped && (aggregate(expression) || col.equalsIgnoreCase(measure))) metrics.add(field);
                else dimensions.add(field);
            }
            // Derived CTE/subquery columns may not expose their aggregation at the outer SELECT.
            partial |= metrics.isEmpty() || select.getWithItemsList() != null ||
                !(plain.getFromItem() instanceof net.sf.jsqlparser.schema.Table);
            return new Semantic(List.copyOf(dimensions), List.copyOf(metrics), List.copyOf(filters), tables, partial);
        } catch (Exception ex) {
            // Explanation is best effort and must never turn a successful query into a failure.
            return new Semantic(List.of(), List.of(), List.of(), List.of(), true);
        }
    }

    private static String clean(String text) {
        return text.replace("`", "").replace("\"", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean aggregate(Expression expression) {
        boolean[] found = {false};
        expression.accept(new ExpressionVisitorAdapter() {
            @Override public void visit(Function function) {
                if (Set.of("SUM", "COUNT", "AVG", "MIN", "MAX", "STDDEV", "STDDEV_POP", "STDDEV_SAMP",
                        "VARIANCE", "VAR_POP", "VAR_SAMP", "GROUP_CONCAT", "APPROX_COUNT_DISTINCT",
                        "BITMAP_UNION_COUNT", "HLL_UNION_AGG").contains(function.getName().toUpperCase(Locale.ROOT)))
                    found[0] = true;
                super.visit(function);
            }
        });
        return found[0];
    }

    /** Deterministic observations; never sum ratios or infer causes from a limited result set. */
    static String summarize(List<Map<String, Object>> rows, List<String> columns,
                            Map<String, String> labels, Semantic semantic) {
        if (rows.isEmpty()) return "当前查询范围没有返回数据，请检查日期、数据刷新状态或筛选条件。";
        if (rows.size() == 1 && columns.size() == 1) {
            String col = columns.getFirst();
            return "本次查询的" + labels.getOrDefault(col, col) + "为 " + Objects.toString(rows.getFirst().get(col), "空值") + "。";
        }
        StringBuilder out = new StringBuilder("本次查询返回 " + rows.size() + " 行数据");
        if (!semantic.dimensions().isEmpty())
            out.append("，按").append(String.join("、", semantic.dimensions().stream().map(Field::label).toList())).append("展示");
        out.append("。");
        for (Field metric : semantic.metrics().stream().limit(3).toList()) {
            Map<String, Object> highest = null;
            BigDecimal max = null;
            int ties = 0;
            for (Map<String, Object> row : rows) {
                BigDecimal value = number(row.get(metric.column()));
                if (value == null) continue;
                if (max == null || value.compareTo(max) > 0) { max = value; highest = row; ties = 1; }
                else if (value.compareTo(max) == 0) ties++;
            }
            if (highest == null) continue;
            out.append("\n在返回数据中，");
            if (!semantic.dimensions().isEmpty()) {
                Map<String, Object> top = highest;
                out.append(String.join(" / ", semantic.dimensions().stream()
                    .map(f -> Objects.toString(top.get(f.column()), "空值")).toList())).append("的");
            }
            out.append(metric.label()).append("最高，为 ").append(max.stripTrailingZeros().toPlainString());
            if (ties > 1) out.append("（共 ").append(ties).append(" 行并列）");
            out.append("。");
        }
        out.append("\n以上仅描述本次返回结果（最多 200 行），不代表全部数据，也不推断业务原因。");
        return out.toString();
    }

    private static BigDecimal number(Object value) {
        if (value == null || value instanceof Boolean) return null;
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException ex) { return null; }
    }
}

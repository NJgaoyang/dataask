package com.dataask.semantic;

import com.dataask.data.ColumnAccessGuard;
import com.dataask.data.SqlGuard;
import com.dataask.data.StarRocksGateway;
import java.util.*;

/** Structured metric bindings must resolve against the currently selected physical fields. */
public final class MetricScope {
    private MetricScope() {}
    public static boolean permits(String table, String field, String time, String dimensions,
                                  List<StarRocksGateway.TableMeta> metadata) {
        table = Objects.toString(table, "").trim();
        field = Objects.toString(field, "").trim();
        time = Objects.toString(time, "").trim();
        dimensions = Objects.toString(dimensions, "").trim();
        if (table.isEmpty()) return field.isEmpty() && time.isEmpty() && dimensions.isEmpty();
        final String name = table;
        var matches = metadata.stream().filter(t -> t.name().equalsIgnoreCase(name) ||
            (!name.contains(".") && t.name().toLowerCase(Locale.ROOT).endsWith("." + name.toLowerCase(Locale.ROOT)))).toList();
        if (matches.size() != 1) return false;
        var target = matches.getFirst();
        Map<String,List<String>> grants = Map.of(target.name(), target.columns().stream().map(StarRocksGateway.ColumnMeta::name).toList());
        List<String> expressions = new ArrayList<>();
        if (!field.isEmpty()) expressions.add(field);
        if (!time.isEmpty()) expressions.add(time);
        if (!dimensions.isEmpty()) expressions.addAll(Arrays.asList(dimensions.split("[,，、]")));
        try {
            for (String expression : expressions) {
                expression = expression.trim();
                final String label = expression;
                var labels = target.columns().stream().filter(c -> c.comment().equals(label)).toList();
                if (labels.size() == 1) expression = labels.getFirst().name();
                String sql = "SELECT " + expression + " FROM " + target.name();
                new SqlGuard().validate(sql, grants.keySet(), target.name().split("\\.")[0]);
                new ColumnAccessGuard().validate(sql, grants, target.name().split("\\.")[0]);
            }
            return true;
        } catch (RuntimeException ignored) { return false; }
    }
}

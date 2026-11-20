package com.dataask.data;

import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Fail closed for SQL shapes that cannot be resolved to the current selected fields. */
@Component
public class ColumnAccessGuard {
    public void validate(String sql, Map<String,List<String>> grants, String schema) {
        try {
            if (!(CCJSqlParserUtil.parse(sql) instanceof Select select) ||
                !(select.getSelectBody() instanceof PlainSelect p) ||
                select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) throw denied();
            Map<String,String> aliases = new HashMap<>();
            Map<String,Set<String>> columns = new LinkedHashMap<>();
            register(p.getFromItem(), grants, schema, aliases, columns);
            if (p.getJoins() != null) for (Join join : p.getJoins()) {
                register(join.getRightItem(), grants, schema, aliases, columns);
                if (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()) throw denied();
            }
            Set<String> outputAliases = new HashSet<>();
            for (SelectItem<?> item : p.getSelectItems()) {
                expression(item.getExpression(), aliases, columns, Set.of());
                if (item.getAlias() != null) outputAliases.add(clean(item.getAlias().getName()));
            }
            expression(p.getWhere(), aliases, columns, Set.of());
            expression(p.getHaving(), aliases, columns, Set.of());
            if (p.getJoins() != null) for (Join join : p.getJoins())
                if (join.getOnExpressions() != null) for (Expression on : join.getOnExpressions())
                    expression(on, aliases, columns, Set.of());
            if (p.getGroupBy() != null) {
                if (p.getGroupBy().getGroupingSets() != null && !p.getGroupBy().getGroupingSets().isEmpty()) throw denied();
                for (Object value : p.getGroupBy().getGroupByExpressions())
                    expression((Expression) value, aliases, columns, Set.of());
            }
            if (p.getOrderByElements() != null) for (OrderByElement order : p.getOrderByElements())
                expression(order.getExpression(), aliases, columns, outputAliases);
            if (p.getLimit() != null) {
                if (p.getLimit().getRowCount() != null && !(p.getLimit().getRowCount() instanceof LongValue)) throw denied();
                if (p.getLimit().getOffset() != null && !(p.getLimit().getOffset() instanceof LongValue)) throw denied();
            }
            // Copy only inspected clauses. Any extra SQL feature must receive an explicit implementation first.
            PlainSelect checked = new PlainSelect();
            checked.setSelectItems(p.getSelectItems()); checked.setFromItem(p.getFromItem());
            checked.setJoins(p.getJoins()); checked.setWhere(p.getWhere());
            checked.setGroupByElement(p.getGroupBy()); checked.setHaving(p.getHaving());
            checked.setOrderByElements(p.getOrderByElements()); checked.setLimit(p.getLimit());
            checked.setDistinct(p.getDistinct());
            if (p.getDistinct() != null && p.getDistinct().getOnSelectItems() != null &&
                    !p.getDistinct().getOnSelectItems().isEmpty()) throw denied();
            if (!p.toString().equals(checked.toString())) throw denied();
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw denied(); }
    }
    private static void register(FromItem item, Map<String,List<String>> grants, String schema,
            Map<String,String> aliases, Map<String,Set<String>> columns) {
        if (!(item instanceof Table table)) throw denied();
        String name = clean(table.getFullyQualifiedName());
        if (!name.contains(".")) throw denied(); // Explicit databases prevent JDBC-default/schema mismatches.
        String selected = null;
        for (String key : grants.keySet()) if (clean(key).equals(name)) {
            if (selected != null) throw denied(); selected = key;
        }
        if (selected == null || grants.get(selected).isEmpty()) throw denied();
        if (table.getPivot() != null || table.getUnPivot() != null) throw denied();
        String alias = table.getAlias() == null ? clean(table.getName()) : clean(table.getAlias().getName());
        if (aliases.putIfAbsent(alias, name) != null) throw denied();
        aliases.putIfAbsent(name, name);
        Set<String> values = new HashSet<>();
        grants.get(selected).forEach(c -> values.add(clean(c)));
        columns.put(name, values);
    }
    private static final Set<String> FUNCTIONS = Set.of("sum", "count", "avg", "min", "max", "round", "floor", "ceil",
        "ceiling", "abs", "coalesce", "ifnull", "nullif", "if", "date_trunc", "date_format", "date", "year",
        "month", "day", "dayofmonth", "datediff", "lower", "upper", "trim", "concat", "substring", "substr");
    private static void expression(Expression value, Map<String,String> aliases,
            Map<String,Set<String>> grants, Set<String> outputAliases) {
        if (value == null) return;
        if (value instanceof Column c) {
            String field = clean(c.getColumnName());
            String qualifier = c.getTable() == null ? "" : clean(c.getTable().getFullyQualifiedName());
            if (qualifier.isEmpty() && outputAliases.contains(field)) return;
            if (!qualifier.isEmpty()) {
                String table = aliases.get(qualifier);
                if (table == null || !grants.getOrDefault(table, Set.of()).contains(field)) throw denied();
            } else {
                long matches = grants.values().stream().filter(fields -> fields.contains(field)).count();
                if (matches != 1) throw denied();
            }
        } else if (value instanceof LongValue || value instanceof DoubleValue || value instanceof StringValue ||
                   value instanceof NullValue || value instanceof DateValue || value instanceof TimeValue || value instanceof TimestampValue) {
            return;
        } else if (value instanceof BinaryExpression b) {
            expression(b.getLeftExpression(), aliases, grants, outputAliases);
            expression(b.getRightExpression(), aliases, grants, outputAliases);
        } else if (value instanceof Parenthesis p) expression(p.getExpression(), aliases, grants, outputAliases);
        else if (value instanceof SignedExpression p) expression(p.getExpression(), aliases, grants, outputAliases);
        else if (value instanceof NotExpression p) expression(p.getExpression(), aliases, grants, outputAliases);
        else if (value instanceof CastExpression p) expression(p.getLeftExpression(), aliases, grants, outputAliases);
        else if (value instanceof IsNullExpression p) expression(p.getLeftExpression(), aliases, grants, outputAliases);
        else if (value instanceof Between p) {
            expression(p.getLeftExpression(), aliases, grants, outputAliases);
            expression(p.getBetweenExpressionStart(), aliases, grants, outputAliases);
            expression(p.getBetweenExpressionEnd(), aliases, grants, outputAliases);
        } else if (value instanceof InExpression p) {
            if (!(p.getRightExpression() instanceof ExpressionList<?>)) throw denied();
            expression(p.getLeftExpression(), aliases, grants, outputAliases);
            expression(p.getRightExpression(), aliases, grants, outputAliases);
        } else if (value instanceof ExpressionList<?> list) {
            for (Object element : list) expression((Expression) element, aliases, grants, outputAliases);
        } else if (value instanceof CaseExpression p) {
            expression(p.getSwitchExpression(), aliases, grants, outputAliases);
            expression(p.getElseExpression(), aliases, grants, outputAliases);
            if (p.getWhenClauses() != null) for (WhenClause c : p.getWhenClauses()) {
                expression(c.getWhenExpression(), aliases, grants, outputAliases);
                expression(c.getThenExpression(), aliases, grants, outputAliases);
            }
        } else if (value instanceof Function f) {
            String name = clean(f.getName());
            if (!FUNCTIONS.contains(name) || f.getNamedParameters() != null ||
                f.getOrderByElements() != null && !f.getOrderByElements().isEmpty()) throw denied();
            if (f.isAllColumns() && !name.equals("count")) throw denied();
            if (f.getParameters() != null) for (Expression e : f.getParameters()) {
                if (e instanceof AllColumns && name.equals("count")) continue;
                expression(e, aliases, grants, outputAliases);
            }
        } else throw denied(); // SELECT *, subqueries, window clauses, user variables: not implicitly trusted.
    }
    private static String clean(String name) {
        return name == null ? "" : name.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }
    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "查询使用了未选择的字段，或包含尚不能校验字段权限的SQL结构；请管理员确认表字段范围");
    }
}

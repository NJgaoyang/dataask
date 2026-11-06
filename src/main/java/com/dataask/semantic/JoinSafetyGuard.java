package com.dataask.semantic;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class JoinSafetyGuard {
    public void validate(String sql, List<JoinRuleService.Rule> rules, String defaultSchema) {
        try {
            Statement statement=CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select)) throw invalid();
            Set<String> tables=new HashSet<>();
            for (String name : new TablesNamesFinder().getTables(statement)) {
                tables.add(normalizeTable(name, defaultSchema));
            }
            if (tables.size() <= 1) {
                if (select.getSelectBody() instanceof PlainSelect single &&
                    single.getJoins()!=null && !single.getJoins().isEmpty()) throw invalid();
                return;
            }

            // Multi-table SQL is permitted only for SELECT...JOIN with exact approved ON keys.
            if (!(select.getSelectBody() instanceof PlainSelect plain) || plain.getJoins()==null ||
                !(plain.getFromItem() instanceof Table first)) throw invalid();

            Map<String,String> aliases=new HashMap<>();
            String firstTable=normalizeTable(first.getFullyQualifiedName(),defaultSchema);
            register(aliases,first,firstTable);
            Set<String> visited=new HashSet<>();
            visited.add(firstTable);

            for (Join join : plain.getJoins()) {
                if (!(join.getRightItem() instanceof Table right) ||
                    join.getOnExpression()==null || join.isSimple() || join.isCross() || join.isNatural())
                    throw invalid();

                String rightTable=normalizeTable(right.getFullyQualifiedName(),defaultSchema);
                register(aliases,right,rightTable);
                if (visited.contains(rightTable)) throw invalid();

                List<Set<String>> onPairs=new ArrayList<>();
                collect(join.getOnExpression(), aliases, onPairs);
                if (onPairs.isEmpty()) throw invalid();

                boolean matched=false;
                for (JoinRuleService.Rule rule : rules) {
                    boolean forward=rule.rightTable().equalsIgnoreCase(rightTable) &&
                        visited.contains(rule.leftTable().toLowerCase(Locale.ROOT));
                    boolean reverse=rule.leftTable().equalsIgnoreCase(rightTable) &&
                        visited.contains(rule.rightTable().toLowerCase(Locale.ROOT));
                    if (!forward && !reverse) continue;
                    Set<Set<String>> expected=new HashSet<>();
                    for (int i=0;i<rule.leftKeys().size();i++) {
                        expected.add(Set.of(
                            rule.leftTable().toLowerCase(Locale.ROOT)+"."+rule.leftKeys().get(i).toLowerCase(Locale.ROOT),
                            rule.rightTable().toLowerCase(Locale.ROOT)+"."+rule.rightKeys().get(i).toLowerCase(Locale.ROOT)));
                    }
                    if (new HashSet<>(onPairs).equals(expected)) {
                        matched=true;
                        break;
                    }
                }
                if (!matched) throw invalid();
                visited.add(rightTable);
            }
            if (!visited.containsAll(tables) || visited.size()!=tables.size()) throw invalid();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    private static void register(Map<String,String> aliases, Table table, String normalized) {
        aliases.put(table.getName().toLowerCase(Locale.ROOT),normalized);
        if (table.getAlias()!=null) aliases.put(
            table.getAlias().getName().toLowerCase(Locale.ROOT),normalized);
    }

    private static void collect(Expression expr, Map<String,String> aliases, List<Set<String>> result) {
        if (expr instanceof AndExpression both) {
            collect(both.getLeftExpression(),aliases,result);
            collect(both.getRightExpression(),aliases,result);
        } else if (expr instanceof EqualsTo eq &&
                eq.getLeftExpression() instanceof Column left &&
                eq.getRightExpression() instanceof Column right) {
            String a=resolveColumn(aliases,left);
            String b=resolveColumn(aliases,right);
            if(a.equals(b)) throw invalid();
            result.add(Set.of(a,b));
        } else {
            throw invalid(); // no OR, implicit joins, non-equality, or function-based ON
        }
    }

    private static String resolveColumn(Map<String,String> aliases, Column column) {
        String table=column.getTable()==null ? "" : column.getTable().getName();
        if (table==null || table.isBlank()) throw invalid();
        String resolved=aliases.get(table.toLowerCase(Locale.ROOT));
        if (resolved==null) throw invalid();
        return resolved+"."+column.getColumnName().replace(Character.toString(96),"").toLowerCase(Locale.ROOT);
    }

    private static String normalizeTable(String name, String schema) {
        String cleaned=name.replace(Character.toString(96),"").replace("\"","").toLowerCase(Locale.ROOT);
        if (!cleaned.contains(".")) cleaned=schema.toLowerCase(Locale.ROOT)+"."+cleaned;
        return cleaned;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "多表关联未经确认或 JOIN 条件不符合管理员配置的关联键。请先配置表粒度及关联关系，再进行跨表查询。");
    }
}

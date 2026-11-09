package com.dataask.semantic;

import com.dataask.data.StarRocksGateway;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EntityValueResolver {
    private static final int MAX_COLUMNS = 12;
    private static final Pattern CHINESE_CITY = Pattern.compile("^[\\p{IsHan}]{2,12}市$");
    private final StarRocksGateway gateway;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public EntityValueResolver(StarRocksGateway gateway) {
        this.gateway = gateway;
    }

    record ColumnRef(String table, String column) {}
    private record CacheEntry(Instant expiresAt, List<String> values) {}

    public record Match(String alias, String canonical, List<String> columnNames,
                        List<String> sourceColumns) {}

    /**
     * Match user expressions to actual allowed ADS city values.
     * Never infer a city from embeddings or issue a filter on an unverified value.
     */
    public List<Match> resolve(String question, Set<String> allowedTables,
                               List<StarRocksGateway.TableMeta> metadata) {
        Map<String, Set<String>> candidates = new HashMap<>();
        Map<String, Set<String>> fields = new HashMap<>();
        Map<String, Set<String>> sources = new HashMap<>();
        int examined = 0;
        for (StarRocksGateway.TableMeta table : metadata) {
            if (!allowedTables.contains(table.name())) continue;
            for (StarRocksGateway.ColumnMeta col : table.columns()) {
                if (!isCityNameColumn(col)) continue;
                if (++examined > MAX_COLUMNS) break;
                ColumnRef ref = new ColumnRef(table.name(), col.name());
                for (String canonical : values(ref)) {
                    if (!CHINESE_CITY.matcher(canonical).matches()) continue;
                    String alias = canonical.substring(0, canonical.length() - 1);
                    for (String key : List.of(alias, canonical)) {
                        candidates.computeIfAbsent(key,k->new HashSet<>()).add(canonical);
                        fields.computeIfAbsent(key,k->new HashSet<>()).add(col.name());
                        sources.computeIfAbsent(key,k->new HashSet<>()).add(table.name()+"."+col.name());
                    }
                }
            }
            if (examined > MAX_COLUMNS) break;
        }
        List<String> keys = new ArrayList<>(candidates.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        List<Match> found = new ArrayList<>();
        List<int[]> occupied = new ArrayList<>();
        for (String key : keys) {
            if (candidates.get(key).size() != 1) continue; // ambiguous canonical values
            int offset = question.indexOf(key);
            if (offset < 0) continue;
            int end = offset + key.length();
            boolean overlap = occupied.stream().anyMatch(r -> offset < r[1] && end > r[0]);
            if (overlap) continue;
            String canonical = candidates.get(key).iterator().next();
            found.add(new Match(key, canonical, sorted(fields.get(key)), sorted(sources.get(key))));
            occupied.add(new int[]{offset,end});
            if (found.size() == 4) break;
        }
        return found;
    }

    public static boolean isCityNameColumn(StarRocksGateway.ColumnMeta col) {
        String type = col.type().toLowerCase(Locale.ROOT);
        if (!type.contains("char") && !type.equals("string")) return false;
        String name = col.name().toLowerCase(Locale.ROOT);
        String comment = col.comment();
        if (name.endsWith("_id") || name.endsWith("_code") || name.endsWith("_no")) return false;
        return name.contains("city_name") || name.equals("city") || name.endsWith("_city") ||
               comment.contains("城市名称") || comment.contains("所属城市") ||
               comment.contains("城市标准") || comment.contains("地市名称");
    }

    private List<String> values(ColumnRef ref) {
        String key = gateway.selectionRevision() + ":" + ref.table() + "." + ref.column();
        if (cache.size() > 500) cache.clear();
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.values();
        List<String> fetched = gateway.distinctDimensionValues(ref.table(), ref.column());
        List<String> copy = List.copyOf(fetched);
        cache.put(key, new CacheEntry(Instant.now().plus(15, ChronoUnit.MINUTES), copy));
        return copy;
    }

    static List<String> sorted(Set<String> values) {
        List<String> list=new ArrayList<>(values);
        Collections.sort(list);
        return List.copyOf(list);
    }

    public String hints(List<Match> matches) {
        if (matches.isEmpty()) return "";
        StringBuilder out = new StringBuilder("\n已从授权 ADS 表真实维度值确认的城市实体（不是推测）：\n");
        for (Match m : matches) {
            out.append("- 用户用语“").append(m.alias()).append("”");
            out.append("，标准城市值“").append(m.canonical()).append("”，仅适用于字段：");
            out.append(String.join("、", m.sourceColumns())).append("\n");
        }
        out.append("SQL 对应城市字段必须使用标准城市值进行精确匹配，不要把用户简称直接写入 WHERE。");
        return out.toString();
    }

    /**
     * Deterministic safety net when the LLM still writes an exact alias literal in
     * a WHERE equality comparison on a confirmed city column.
     */
    public String normalizeCityEquality(String sql, List<Match> matches) {
        String normalized = sql;
        for (Match m : matches) {
            if (m.alias().equals(m.canonical())) continue;
            for (String column : m.columnNames()) {
                String expression = "((?:(?:[a-zA-Z_][a-zA-Z_0-9]*|\\x60[a-zA-Z_][a-zA-Z_0-9]*\\x60)\\.)?" +
                    "\\x60?" + Pattern.quote(column) + "\\x60?\\s*=\\s*)'" +
                    Pattern.quote(m.alias().replace("'", "''")) + "'";
                Matcher matcher = Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(normalized);
                StringBuffer result = new StringBuffer();
                while (matcher.find()) {
                    matcher.appendReplacement(result, Matcher.quoteReplacement(
                        matcher.group(1) + "'" + m.canonical().replace("'", "''") + "'"));
                }
                matcher.appendTail(result);
                normalized = result.toString();
            }
        }
        return normalized;
    }
}

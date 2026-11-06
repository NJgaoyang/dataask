package com.dataask.semantic;

import com.dataask.data.StarRocksGateway;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class JoinRuleService {
    private final JdbcTemplate jdbc;
    private final StarRocksGateway gateway;

    public JoinRuleService(JdbcTemplate jdbc, StarRocksGateway gateway) {
        this.jdbc=jdbc;
        this.gateway=gateway;
    }

    public record JoinInput(String leftTable, String rightTable, String leftKeys,
                            String rightKeys, String cardinality, String grainNote) {}
    public record Rule(long id, String leftTable, String rightTable, List<String> leftKeys,
                       List<String> rightKeys, String cardinality, String grainNote) {}

    public List<Rule> all() {
        return jdbc.query(
            "SELECT id,left_table,right_table,left_keys,right_keys,cardinality,grain_note " +
            "FROM da_ads_join_rules ORDER BY id DESC LIMIT 100",
            (rs,n)->new Rule(rs.getLong(1),rs.getString(2),rs.getString(3),
                parseKeys(rs.getString(4)),parseKeys(rs.getString(5)),
                rs.getString(6),rs.getString(7)));
    }

    public List<Rule> allowed(Set<String> allowedTables) {
        return all().stream()
            .filter(r -> allowedTables.contains(r.leftTable()) && allowedTables.contains(r.rightTable()))
            .toList();
    }

    public String hints(Set<String> allowedTables) {
        List<Rule> rules=allowed(allowedTables);
        if (rules.isEmpty()) {
            return "\n当前没有经过管理员确认的 ADS 跨表关联规则。" +
                   "如果必须关联多张表，禁止猜测 JOIN 条件，请以 clarification 说明需要配置关联键、粒度。";
        }
        StringBuilder out=new StringBuilder("\n经过管理员确认的 ADS 表关系（只能使用这些关系）：\n");
        int included=0;
        for (Rule rule : rules) {
            out.append(rule.leftTable()).append(" 与 ").append(rule.rightTable())
               .append("，关联键：");
            for (int i=0;i<rule.leftKeys().size();i++) {
                if(i>0) out.append(" AND ");
                out.append(rule.leftTable()).append(".").append(rule.leftKeys().get(i))
                   .append(" = ")
                   .append(rule.rightTable()).append(".").append(rule.rightKeys().get(i));
            }
            out.append("；关系：").append(rule.cardinality())
               .append("；粒度：").append(rule.grainNote()).append("\n");
            if (++included>=30 || out.length()>9000) break;
        }
        out.append("关联之前应检查两个汇总表是否在关联键上唯一；" +
                   "尽量先按各自表的业务粒度聚合后关联，避免重复求和。" +
                   "未明确保证粒度时必须澄清，不得自行编造联接键。\n");
        return out.toString();
    }

    private static List<String> parseKeys(String csv) {
        List<String> keys=new ArrayList<>();
        if (csv==null || csv.isBlank()) return keys;
        for (String item : csv.split("[,，]+")) {
            String key=item.trim();
            if(!key.isEmpty()) keys.add(key);
        }
        return keys;
    }

    public long create(JoinInput input, Set<String> allowedTables) {
        if (input==null) throw bad("请填写关联规则");
        String left=trim(input.leftTable()), right=trim(input.rightTable());
        if (!allowedTables.contains(left) || !allowedTables.contains(right) || left.equals(right))
            throw bad("仅允许对两张不同且已授权的 ADS 表建立关联");
        String card=trim(input.cardinality());
        if (!Set.of("ONE_TO_ONE","MANY_TO_ONE").contains(card))
            throw bad("只允许一对一或多对一关系；多对多汇总表不能直接关联");
        String note=trim(input.grainNote());
        if (note.length()<8 || note.length()>800)
            throw bad("请填写至少8字的业务粒度和唯一性说明");
        List<String> leftKeys=parseKeys(input.leftKeys()),rightKeys=parseKeys(input.rightKeys());
        if (leftKeys.isEmpty() || leftKeys.size()>5 || leftKeys.size()!=rightKeys.size())
            throw bad("请填写1至5组对应的关联键，左右字段数必须相同");
        List<StarRocksGateway.TableMeta> discovered=gateway.discover();
        Map<String,Set<String>> meta=new HashMap<>();
        for (StarRocksGateway.TableMeta table : discovered) {
            Set<String> names=new HashSet<>();
            for(StarRocksGateway.ColumnMeta c : table.columns()) names.add(c.name().toLowerCase(Locale.ROOT));
            meta.put(table.name(),names);
        }
        if(!meta.containsKey(left)||!meta.containsKey(right))
            throw bad("关联表不存在于 ADS 元数据");
        for(int i=0;i<leftKeys.size();i++){
            String lk=leftKeys.get(i),rk=rightKeys.get(i);
            if(!lk.matches("[a-zA-Z_][a-zA-Z_0-9]*") ||
               !rk.matches("[a-zA-Z_][a-zA-Z_0-9]*"))
                throw bad("关联字段仅允许真实 ADS 字段名");
            if(!meta.get(left).contains(lk.toLowerCase(Locale.ROOT)) ||
               !meta.get(right).contains(rk.toLowerCase(Locale.ROOT)))
                throw bad("关联字段在对应 ADS 表中不存在：" + lk + " / " + rk);
        }
        if (new HashSet<>(leftKeys).size()!=leftKeys.size() ||
            new HashSet<>(rightKeys).size()!=rightKeys.size())
            throw bad("同一关联键不能重复");
        org.springframework.jdbc.support.KeyHolder holder =
            new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement(
                "INSERT INTO da_ads_join_rules(left_table,right_table,left_keys,right_keys,cardinality,grain_note) " +
                "VALUES(?,?,?,?,?,?)", new String[]{"id"});
            statement.setString(1,left);
            statement.setString(2,right);
            statement.setString(3,String.join(",",leftKeys));
            statement.setString(4,String.join(",",rightKeys));
            statement.setString(5,card);
            statement.setString(6,note);
            return statement;
        },holder);
        return holder.getKey()==null ? 0L : holder.getKey().longValue();
    }

    public void delete(long id) {
        if (id<=0 || jdbc.update("DELETE FROM da_ads_join_rules WHERE id=?",id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"关联规则不存在");
    }

    private static String trim(String value) { return value==null?"":value.trim(); }
    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
}

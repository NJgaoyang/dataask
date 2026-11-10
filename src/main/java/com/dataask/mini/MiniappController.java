package com.dataask.mini;

import com.dataask.chat.ChatController;
import com.dataask.data.SqlGuard;
import com.dataask.data.StarRocksGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mobile/H5 miniapp facade. Authentication is inherited from the existing JWT security filter.
 * Saved charts support a permission-checked cached view and a live refresh without
 * creating conversations. All results originate from an authorized StarRocks query.
 */
@RestController
@RequestMapping("/api/mini")
public class MiniappController {
    private final JdbcTemplate jdbc;
    private final ChatController chat;
    private final ObjectMapper json;
    private final SqlGuard guard;
    private final StarRocksGateway starrocks;

    public MiniappController(JdbcTemplate jdbc, ChatController chat,
                             ObjectMapper json, SqlGuard guard, StarRocksGateway starrocks) {
        this.jdbc=jdbc;
        this.chat=chat;
        this.json=json;
        this.guard=guard;
        this.starrocks=starrocks;
    }

    private Map<String,Object> prettyChart(Map<String,Object> chart) {
        Map<String,Object> result=new LinkedHashMap<>(chart);
        result.put("title",ChartTitleFormatter.fromQuestion(
            Objects.toString(chart.get("question"),"")));
        return result;
    }

    record SaveChart(@NotBlank String sourceMessageId, @Size(max=180) String title) {}
    record ShareChart(@NotBlank String scope, List<String> users) {}

    private boolean admin(Authentication user) {
        return user.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "图表不存在或无权访问");
    }

    @GetMapping("/bootstrap")
    public Map<String,Object> bootstrap(Authentication user) {
        Map<String,Object> account = jdbc.queryForMap(
            "SELECT username,display_name,role FROM da_users WHERE username=?", user.getName());
        return Map.of("user", account, "suggestions", List.of(
            "昨日营业额是多少？", "近7天的订单量趋势如何？", "按城市看营业额TOP10",
            "本月新增用户数及环比？", "本周营业额趋势怎么样？", "昨天订单总数是多少？"));
    }

    @GetMapping("/charts")
    public List<Map<String,Object>> charts(@RequestParam(defaultValue="mine") String view, Authentication user) {
        String name = user.getName();
        String fields = "SELECT c.id,c.owner_username AS ownerUsername,c.source_message_id AS sourceMessageId," +
            "c.question,c.title,c.chart_type AS chartType,c.share_scope AS shareScope," +
            "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(m.payload_json,'$.dataset')),'') AS dataset," +
            "c.created_at AS createdAt,c.updated_at AS updatedAt FROM da_saved_charts c " +
            "LEFT JOIN da_messages m ON m.id=c.source_message_id ";
        List<Map<String,Object>> found = switch(view) {
            case "mine" -> jdbc.queryForList(fields +
                "WHERE c.owner_username=? ORDER BY c.updated_at DESC LIMIT 200", name);
            case "shared" -> jdbc.queryForList(fields +
                "WHERE c.owner_username<>? AND (c.share_scope='ALL' OR " +
                "(c.share_scope='SELECTED' AND EXISTS (SELECT 1 FROM da_chart_acl a " +
                "WHERE a.chart_id=c.id AND a.username=?))) ORDER BY c.updated_at DESC LIMIT 200", name, name);
            case "all" -> {
                if (!admin(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可查看所有图表");
                yield jdbc.queryForList(fields + "ORDER BY c.updated_at DESC LIMIT 200");
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "view 只能为 mine/shared/all");
        };
        return found.stream().map(this::prettyChart).toList();
    }

    @GetMapping("/charts/{id}")
    public Map<String,Object> chart(@PathVariable String id, Authentication user) {
        Map<String,Object> row = rawChart(id);
        String owner = Objects.toString(row.get("ownerUsername"));
        if (admin(user) || user.getName().equals(owner)) return row;
        if ("ALL".equals(row.get("shareScope"))) return row;
        if ("SELECTED".equals(row.get("shareScope"))) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM da_chart_acl WHERE chart_id=? AND username=?",
                Integer.class,id,user.getName());
            if (count!=null && count>0) return row;
        }
        throw notFound();
    }

    /**
     * Direct chart detail: show its original result immediately, without redirecting
     * into the historic conversation. Re-check every referenced table against the
     * CURRENT ADS allowlist before releasing cached rows to anyone, even owners.
     */
    @GetMapping("/charts/{id}/view")
    public Map<String,Object> view(@PathVariable String id, Authentication user) {
        Map<String,Object> info = chart(id,user);
        List<Map<String,Object>> source=jdbc.queryForList(
            "SELECT generated_sql,payload_json FROM da_messages WHERE id=?",
            Objects.toString(info.get("sourceMessageId"),""));
        if(source.isEmpty()) throw notFound();
        String sql=Objects.toString(source.getFirst().get("generated_sql"),"");
        if(sql.isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该收藏没有可展示的查询结果");
        Set<String> readable=new LinkedHashSet<>(jdbc.query(
            "SELECT table_name FROM da_allowed_tables WHERE enabled=1",
            (rs,n) -> rs.getString(1)));
        // SqlGuard also rejects DDL/DML and tables whose authorization was revoked.
        guard.validate(sql,readable,starrocks.schema());
        try {
            JsonNode payload=json.readTree(Objects.toString(source.getFirst().get("payload_json"),""));
            if(payload==null || payload.path("unsupported").asBoolean(false) ||
               !payload.path("rows").isArray())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"收藏的数据已失效");
            return Map.of("chart",info,"answer",payload,"cached",true);
        } catch(ResponseStatusException e) {
            throw e;
        } catch(Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"保存的图表结果已损坏");
        }
    }

    /**
     * Recalculate from the original business question using the current AI model,
     * authorized metadata and live StarRocks data. No chat transcript is created.
     */
    @PostMapping("/charts/{id}/refresh")
    public Map<String,Object> refresh(@PathVariable String id, Authentication user) {
        Map<String,Object> info=chart(id,user);
        ChatController.Answer answer=chat.previewChart(
            Objects.toString(info.get("question"),""),
            Objects.toString(info.get("dataset"),""),user);
        if(answer.unsupported() || answer.rows().isEmpty())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "当前无法刷新该图表，请检查指标口径、ADS 表授权与模型配置");
        return Map.of("chart",info,"answer",answer,"cached",false);
    }

    @PostMapping("/charts")
    @Transactional
    public Map<String,Object> save(@Valid @RequestBody SaveChart request, Authentication user) {
        List<Map<String,Object>> source = jdbc.queryForList(
            "SELECT m.id,m.question,m.title,m.chart_type FROM da_messages m " +
            "JOIN da_sessions s ON s.id=m.session_id WHERE m.id=? AND s.username=?",
            request.sourceMessageId(), user.getName());
        if (source.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"问数消息不存在或无权收藏");
        List<String> existing = jdbc.query("SELECT id FROM da_saved_charts WHERE owner_username=? AND source_message_id=?",
            (rs,n)->rs.getString(1),user.getName(),request.sourceMessageId());
        if (!existing.isEmpty()) return rawChart(existing.getFirst());
        Map<String,Object> m = source.getFirst();
        String title = ChartTitleFormatter.fromQuestion(
            Objects.toString(m.get("question"),""));
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO da_saved_charts(id,owner_username,source_message_id,question,title,chart_type,share_scope) " +
            "VALUES(?,?,?,?,?,?,'PRIVATE')", id,user.getName(),request.sourceMessageId(),
            m.get("question"), title, m.get("chart_type"));
        return rawChart(id);
    }

    @PutMapping("/charts/{id}/share")
    @Transactional
    public Map<String,Object> share(@PathVariable String id, @Valid @RequestBody ShareChart request,
                                     Authentication user) {
        requireManage(id,user);
        String scope = request.scope().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PRIVATE","ALL","SELECTED").contains(scope))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"共享范围必须为 PRIVATE、ALL 或 SELECTED");
        List<String> users = request.users()==null ? List.of() :
            request.users().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if ("SELECTED".equals(scope)) {
            if (users.isEmpty() || users.size()>20)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择1-20个成员账号");
            for (String u : users) {
                if (!u.matches("[a-zA-Z0-9_.-]{1,64}"))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"成员账号格式不正确");
                Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM da_users WHERE username=?",Integer.class,u);
                if (found==null || found==0)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"指定成员不存在: "+u);
            }
        }
        jdbc.update("DELETE FROM da_chart_acl WHERE chart_id=?",id);
        if ("SELECTED".equals(scope)) {
            for (String u : users) jdbc.update("INSERT INTO da_chart_acl(chart_id,username) VALUES(?,?)",id,u);
        }
        jdbc.update("UPDATE da_saved_charts SET share_scope=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",scope,id);
        return rawChart(id);
    }

    @DeleteMapping("/charts/{id}")
    @Transactional
    public Map<String,Boolean> delete(@PathVariable String id, Authentication user) {
        requireManage(id,user);
        jdbc.update("DELETE FROM da_saved_charts WHERE id=?",id);
        return Map.of("deleted",true);
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String,Boolean> deleteSession(@PathVariable String id, Authentication user) {
        int deleted=jdbc.update("DELETE FROM da_sessions WHERE id=? AND username=?",id,user.getName());
        if (deleted==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在或无权删除");
        return Map.of("deleted",true);
    }

    private Map<String,Object> rawChart(String id) {
        List<Map<String,Object>> rows = jdbc.queryForList(
            "SELECT c.id,c.owner_username AS ownerUsername,c.source_message_id AS sourceMessageId," +
            "c.question,c.title,c.chart_type AS chartType,c.share_scope AS shareScope," +
            "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(m.payload_json,'$.dataset')),'') AS dataset," +
            "c.created_at AS createdAt,c.updated_at AS updatedAt FROM da_saved_charts c " +
            "LEFT JOIN da_messages m ON m.id=c.source_message_id WHERE c.id=?",id);
        if (rows.isEmpty()) throw notFound();
        return prettyChart(rows.getFirst());
    }

    private void requireManage(String id, Authentication user) {
        Map<String,Object> row=rawChart(id);
        if (!admin(user) && !user.getName().equals(row.get("ownerUsername")))
            throw notFound();
    }
}

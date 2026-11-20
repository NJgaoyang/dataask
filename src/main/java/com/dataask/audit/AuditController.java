package com.dataask.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    public AuditController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/options")
    public Map<String,Object> options() {
        return Map.of("today",LocalDate.now(ZONE).toString(),"timezone",ZONE.getId(),
            "usernames",jdbc.query("SELECT DISTINCT username FROM da_operation_audit ORDER BY username LIMIT 500",(rs,n)->rs.getString(1)),
            "modules",List.of("AUTH","CHAT","CHART","ADMIN","AUDIT","CLIENT","SYSTEM"));
    }

    @GetMapping("/logs")
    public Map<String,Object> logs(@RequestParam Map<String,String> params) { return page(params,false); }

    @GetMapping("/queries")
    public Map<String,Object> queries(@RequestParam Map<String,String> params) { return page(params,true); }

    private Map<String,Object> page(Map<String,String> params, boolean queries) {
        int page = number(params.get("page"),1,1,1_000_000);
        int size = number(params.get("pageSize"),20,1,100);
        Search search = search(params,queries,LocalDate.now(ZONE));
        String table = queries ? "da_query_audit" : "da_operation_audit";
        String time = queries ? "started_at" : "occurred_at";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM "+table+search.where(),Long.class,search.args().toArray());
        List<Object> args = new ArrayList<>(search.args());
        args.add(size); args.add((page-1)*size);
        // SQL text is returned only by the separate, administrator-protected detail endpoint.
        String fields = queries
            ? "id,operation_id AS operationId,request_id AS requestId,username,source,session_id AS sessionId,question,status,row_count AS rowCount,duration_ms AS durationMs,error_code AS errorCode,started_at AS occurredAt,finished_at AS finishedAt"
            : "id,request_id AS requestId,username,actor_role AS actorRole,http_method AS method,path,module,action,target,detail,outcome AS status,http_status AS httpStatus,duration_ms AS durationMs,occurred_at AS occurredAt,finished_at AS finishedAt";
        List<Map<String,Object>> items = jdbc.query("SELECT "+fields+" FROM "+table+search.where()+
            " ORDER BY "+time+" DESC,id DESC LIMIT ? OFFSET ?",ROW,args.toArray());
        return Map.of("items",items,"total",total == null ? 0 : total,"page",page,"pageSize",size,"timezone",ZONE.getId());
    }

    @GetMapping("/queries/{id}")
    public Map<String,Object> queryDetail(@PathVariable String id) {
        validateId(id);
        AuditContext.set("target","查询记录："+id);
        List<Map<String,Object>> rows = jdbc.query("SELECT id,operation_id AS operationId,request_id AS requestId,username,source,session_id AS sessionId,question,sql_text AS sql,status,row_count AS rowCount,duration_ms AS durationMs,error_code AS errorCode,started_at AS occurredAt,finished_at AS finishedAt FROM da_query_audit WHERE id=?",ROW,id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"查询记录不存在或已被删除");
        return rows.getFirst();
    }

    public record DeleteRequest(@NotEmpty @Size(max=200) List<@Pattern(regexp="[0-9a-fA-F-]{36}") String> ids) {}

    @PostMapping("/logs/batch-delete")
    @Transactional
    public Map<String,Object> deleteLogs(@Valid @RequestBody DeleteRequest input) { return delete(input,false); }

    @PostMapping("/queries/batch-delete")
    @Transactional
    public Map<String,Object> deleteQueries(@Valid @RequestBody DeleteRequest input) { return delete(input,true); }

    private Map<String,Object> delete(DeleteRequest input, boolean queries) {
        if (input.ids() == null || input.ids().isEmpty() || input.ids().size()>200) throw bad("一次请选择1到200条记录");
        LinkedHashSet<String> ids = new LinkedHashSet<>(input.ids());
        ids.forEach(AuditController::validateId);
        // The request's own audit record must survive its deletion operation.
        ids.remove(AuditContext.get("id"));
        String placeholders = String.join(",",Collections.nCopies(ids.size(),"?"));
        int deleted = ids.isEmpty() ? 0 : jdbc.update("DELETE FROM "+(queries ? "da_query_audit" : "da_operation_audit")+
            " WHERE id IN ("+placeholders+")",ids.toArray());
        AuditContext.set("action",queries ? "批量删除 SQL 查询记录" : "批量删除操作审计记录");
        AuditContext.set("target",queries ? "SQL 查询记录" : "操作审计记录");
        AuditContext.set("detail","请求删除 "+ids.size()+" 条，实际删除 "+deleted+" 条；记录ID："+String.join(",",ids));
        return Map.of("deleted",deleted);
    }

    record Search(String where, List<Object> args) {}

    static Search search(Map<String,String> params, boolean queries, LocalDate today) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        String time = queries ? "started_at" : "occurred_at";
        boolean allDates = "true".equals(params.get("allDates"));
        String from = text(params,"dateFrom",10), to = text(params,"dateTo",10);
        if (!allDates || !from.isEmpty() || !to.isEmpty()) {
            LocalDate start = date(from.isEmpty() ? (to.isEmpty() ? today.toString() : to) : from);
            LocalDate end = date(to.isEmpty() ? start.toString() : to);
            if (end.isBefore(start)) throw bad("结束日期不能早于开始日期");
            clauses.add(time+">=? AND "+time+"<?");
            args.add(utc(start)); args.add(utc(end.plusDays(1)));
        }
        equal(clauses,args,"username",text(params,"username",64));
        equal(clauses,args,"request_id",text(params,"requestId",36));
        if (queries) {
            String status = text(params,"status",16);
            if (!status.isEmpty() && !Set.of("RUNNING","SUCCESS","FAILED","CANCELLED").contains(status)) throw bad("查询状态无效");
            equal(clauses,args,"status",status);
            String source = text(params,"source",24);
            if (!source.isEmpty() && !Set.of("CHAT","CHART_REFRESH").contains(source)) throw bad("查询来源无效");
            equal(clauses,args,"source",source);
        } else {
            String status = text(params,"status",16);
            if (!status.isEmpty() && !Set.of("RUNNING","SUCCESS","FAILED","DENIED").contains(status)) throw bad("操作状态无效");
            equal(clauses,args,"outcome",status);
            equal(clauses,args,"module",text(params,"module",24));
            equal(clauses,args,"action",text(params,"action",80));
        }
        String keyword = text(params,"keyword",200);
        if (!keyword.isEmpty()) {
            String pattern = "%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            String[] fields = queries ? new String[]{"question","sql_text"} : new String[]{"action","target","detail","path"};
            clauses.add("("+String.join(" OR ",Arrays.stream(fields).map(f->f+" LIKE ? ESCAPE '!'" ).toList())+")");
            for (String ignored : fields) args.add(pattern);
        }
        return new Search(clauses.isEmpty() ? "" : " WHERE "+String.join(" AND ",clauses),args);
    }

    private static void equal(List<String> clauses,List<Object> args,String column,String value) {
        if (!value.isEmpty()) { clauses.add(column+"=?"); args.add(value); }
    }
    private static LocalDateTime utc(LocalDate day) { return day.atStartOfDay(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
    private static LocalDate date(String value) {
        try {
            LocalDate day = LocalDate.parse(value);
            if (day.getYear()<1970 || day.getYear()>9998) throw bad("日期超出支持范围");
            return day;
        } catch (DateTimeParseException ex) { throw bad("日期格式需为 yyyy-MM-dd"); }
    }
    private static String text(Map<String,String> params,String key,int max) {
        String value = params.getOrDefault(key,"").trim();
        if (value.length()>max) throw bad("搜索条件过长："+key);
        return value;
    }
    private static int number(String value,int fallback,int min,int max) {
        if (value == null || value.isBlank()) return fallback;
        try { int n = Integer.parseInt(value); if(n>=min && n<=max) return n; }
        catch (NumberFormatException ignored) {}
        throw bad("分页参数无效");
    }
    private static void validateId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F-]{36}")) throw bad("记录ID格式无效");
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private static final RowMapper<Map<String,Object>> ROW = (rs,n) -> {
        Map<String,Object> row = new LinkedHashMap<>();
        for (int i=1;i<=rs.getMetaData().getColumnCount();i++) {
            Object value = rs.getObject(i);
            if (value instanceof Timestamp t) value = t.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
            else if (value instanceof LocalDateTime t) value = t.toInstant(ZoneOffset.UTC).toString();
            row.put(rs.getMetaData().getColumnLabel(i),value);
        }
        return row;
    };
}

package com.dataask.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api")
public class RecordListController {
    private final JdbcTemplate jdbc;
    public RecordListController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private Map<String,Object> query(String fields,String from,String order,List<Object> args,int page,int size) {
        var p=new ListPage(page,size);
        Long total=jdbc.queryForObject("SELECT COUNT(*) "+from,Long.class,args.toArray());
        var values=new ArrayList<>(args); values.add(p.size()); values.add(p.offset());
        return p.result(jdbc.queryForList("SELECT "+fields+" "+from+" ORDER BY "+order+" LIMIT ? OFFSET ?",values.toArray()),total);
    }
    @GetMapping("/admin/users/page")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_ADMIN')")
    public Map<String,Object> users(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword) {
        String pattern=ListPage.pattern(keyword);
        return query("username,display_name AS displayName,role,created_at AS createdAt","FROM da_users WHERE username LIKE ? ESCAPE '!' OR display_name LIKE ? ESCAPE '!'","id",List.of(pattern,pattern),page,pageSize);
    }
    public Map<String,Object> sessions(Authentication user,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword) {
        return sessions(user,page,pageSize,keyword,"");
    }
    @GetMapping("/chat/sessions/page")
    public Map<String,Object> sessions(Authentication user,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String date) {
        var args=new ArrayList<Object>();
        args.add(user.getName());
        args.add(ListPage.pattern(keyword));
        String from="FROM da_sessions WHERE username=? AND title LIKE ? ESCAPE '!'";
        if (date != null && !date.isBlank()) {
            try {
                var day=LocalDate.parse(date);
                args.add(java.sql.Date.valueOf(day));
                args.add(java.sql.Date.valueOf(day.plusDays(1)));
                from += " AND updated_at >= ? AND updated_at < ?";
            } catch (DateTimeParseException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"日期格式不正确");
            }
        }
        return query("id,title,updated_at AS updatedAt",from,"updated_at DESC,id",args,page,pageSize);
    }
    @GetMapping("/mini/charts/page")
    public Map<String,Object> charts(Authentication user,@RequestParam(defaultValue="mine") String view,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="ALL") String type,@RequestParam(defaultValue="recent") String sort) {
        if (!Set.of("mine","shared").contains(view) || !Set.of("recent","oldest").contains(sort) || !Set.of("ALL","bar","line","pie","table","metric").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"筛选条件不正确");
        var args=new ArrayList<Object>(); args.add(user.getName());
        String where=view.equals("mine") ? "c.owner_username=?" : "c.owner_username<>? AND (c.share_scope='ALL' OR (c.share_scope='SELECTED' AND EXISTS (SELECT 1 FROM da_chart_acl a WHERE a.chart_id=c.id AND a.username=?)))";
        if (view.equals("shared")) args.add(user.getName());
        where="("+where+") AND (c.title LIKE ? ESCAPE '!' OR c.question LIKE ? ESCAPE '!')";
        args.add(ListPage.pattern(keyword)); args.add(ListPage.pattern(keyword));
        if (!type.equals("ALL")) { where+=" AND c.chart_type=?"; args.add(type); }
        return query("c.id,c.owner_username AS ownerUsername,c.question,c.title,c.chart_type AS chartType,c.updated_at AS updatedAt,c.created_at AS createdAt","FROM da_saved_charts c WHERE "+where,"c.updated_at "+(sort.equals("oldest") ? "ASC" : "DESC")+",c.id",args,page,pageSize);
    }
    @GetMapping("/admin/metrics/page")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_ADMIN')")
    public Map<String,Object> metrics(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword) {
        String pattern=ListPage.pattern(keyword);
        return query("d.id,d.metric_name AS metricName,d.aliases,d.definition," +
            "d.ads_table AS adsTable,d.ads_field AS adsField,d.time_field AS timeField,d.dimensions,d.notes," +
            "COALESCE(src.name,'通用口径') AS sourceName",
            "FROM da_metric_definitions d " +
            "LEFT JOIN da_metric_definition_sources assoc ON assoc.metric_id=d.id " +
            "LEFT JOIN da_datasources src ON src.id=assoc.data_source_id " +
            "WHERE d.metric_name LIKE ? ESCAPE '!' OR d.definition LIKE ? ESCAPE '!'",
            "d.id DESC",List.of(pattern,pattern),page,pageSize);
    }
    @GetMapping("/admin/metrics/batches/page")
    @PreAuthorize("hasAuthority('PERM_SYSTEM_ADMIN')")
    public Map<String,Object> batches(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String keyword) {
        return query("id,source_file AS filename,uploaded_by AS uploadedBy,row_count AS count,created_at AS createdAt","FROM da_metric_import_batches WHERE source_file LIKE ? ESCAPE '!'","created_at DESC,id DESC",List.of(ListPage.pattern(keyword)),page,pageSize);
    }
}

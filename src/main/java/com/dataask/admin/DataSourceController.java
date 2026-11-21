package com.dataask.admin;

import com.dataask.audit.AuditContext;
import com.dataask.config.RuntimeConfigService;
import com.dataask.data.StarRocksGateway;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/admin/datasources")
@PreAuthorize("hasAuthority('PERM_SYSTEM_ADMIN')")
public class DataSourceController {
    private final JdbcTemplate jdbc;
    private final RuntimeConfigService settings;
    private final StarRocksGateway gateway;
    public DataSourceController(JdbcTemplate jdbc, RuntimeConfigService settings, StarRocksGateway gateway) {
        this.jdbc=jdbc; this.settings=settings; this.gateway=gateway;
    }
    public record Input(String name,String jdbcUrl,String username,String password,String schema,String description) {}
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private Map<String,Object> existing(String id) {
        return jdbc.queryForList("SELECT * FROM da_datasources WHERE id=?",id).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"数据源不存在"));
    }
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    @PreAuthorize("permitAll()") // Internal startup event, not an HTTP endpoint.
    public void migrate() {
        lock();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM da_datasources",Long.class)>0) return;
        // Preserve the existing connection and grants on the first upgrade.
        try {
            var config=settings.starRocks();
            if (!config.configured()) return;
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO da_datasources(id,name,jdbc_url,username,password_enc,schema_name,description) VALUES(?,?,?,?,?,?,?)",
                id,"默认数据源",config.jdbcUrl(),config.username(),settings.sealCredential(config.password()),config.schema(),"");
            settings.activateSource(id,config);
        } catch (IllegalStateException ignored) { /* A damaged legacy credential can be repaired by adding a source. */ }
    }
    @GetMapping
    public Map<String,Object> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,
                                   @RequestParam(defaultValue="") String keyword) {
        var p=new com.dataask.common.ListPage(page,pageSize);
        String pattern=com.dataask.common.ListPage.pattern(keyword);
        String where=" WHERE name LIKE ? ESCAPE '!' OR jdbc_url LIKE ? ESCAPE '!'";
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM da_datasources"+where,Long.class,pattern,pattern);
        String active=settings.activeSourceId();
        var rows=jdbc.queryForList("SELECT id,name,jdbc_url AS jdbcUrl,username,schema_name AS `schema`,description,updated_at AS updatedAt FROM da_datasources"+where+" ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",pattern,pattern,p.size(),p.offset());
        rows.forEach(row->row.put("active",active.equals(row.get("id"))));
        return p.result(rows,total);
    }
    private RuntimeConfigService.StarRocksConfig candidate(String id,Input input) {
        Map<String,Object> previous=id==null ? null : existing(id);
        if (input==null || input.name()==null || input.name().isBlank() || input.name().trim().length()>100) throw bad("名称需为1到100个字符");
        if (input.description()!=null && input.description().length()>500) throw bad("描述不能超过500字");
        String password=Objects.toString(input.password(),"");
        if (previous!=null && password.isEmpty()) password=settings.openCredential((String)previous.get("password_enc"));
        return settings.validateConnection(Objects.toString(input.jdbcUrl(),"").trim(),Objects.toString(input.username(),"").trim(),password,Objects.toString(input.schema(),"").trim());
    }
    @PostMapping("/test")
    public Map<String,Object> testNew(@RequestBody Input input) { return gateway.test(candidate(null,input)); }
    @PostMapping("/{id}/test")
    public Map<String,Object> test(@PathVariable String id,@RequestBody(required=false) Input input) {
        if (input!=null) return gateway.test(candidate(id,input));
        return gateway.test(connection(existing(id)));
    }
    private RuntimeConfigService.StarRocksConfig connection(Map<String,Object> row) {
        return settings.validateConnection((String)row.get("jdbc_url"),(String)row.get("username"),settings.openCredential((String)row.get("password_enc")),(String)row.get("schema_name"));
    }
    @PostMapping
    @Transactional
    public Map<String,Object> create(@RequestBody Input input) { return save(null,input); }
    @PutMapping("/{id}")
    @Transactional
    public Map<String,Object> update(@PathVariable String id,@RequestBody Input input) { return save(id,input); }
    private Map<String,Object> save(String id,Input input) {
        lock();
        var config=candidate(id,input);
        boolean active=id!=null && id.equals(settings.activeSourceId());
        if (active) {
            var old=existing(id);
            if (!old.get("jdbc_url").equals(config.jdbcUrl()) || !old.get("schema_name").equals(config.schema()) || !old.get("username").equals(config.username()))
                throw bad("启用中的数据源不可更换连接地址、数据库或账号，请新增数据源后切换");
            gateway.test(config);
        }
        String sourceId=id==null ? UUID.randomUUID().toString() : id;
        AuditContext.set("target","数据源："+sourceId);
        AuditContext.set("detail",id==null ? "新增数据源" : "编辑数据源");
        try {
            if (id==null) jdbc.update("INSERT INTO da_datasources(id,name,jdbc_url,username,password_enc,schema_name,description) VALUES(?,?,?,?,?,?,?)",sourceId,input.name().trim(),config.jdbcUrl(),config.username(),settings.sealCredential(config.password()),config.schema(),Objects.toString(input.description(),""));
            else jdbc.update("UPDATE da_datasources SET name=?,jdbc_url=?,username=?,password_enc=?,schema_name=?,description=? WHERE id=?",input.name().trim(),config.jdbcUrl(),config.username(),settings.sealCredential(config.password()),config.schema(),Objects.toString(input.description(),""),id);
        } catch (org.springframework.dao.DuplicateKeyException ex) { throw bad("数据源名称已存在"); }
        if (active) settings.activateSource(sourceId,config);
        return Map.of("id",sourceId,"success",true);
    }
    @PostMapping("/{id}/activate")
    @Transactional
    public Map<String,Object> activate(@PathVariable String id) {
        // Serialize switches on the settings row, including concurrent administrators.
        lock();
        var config=connection(existing(id));
        gateway.test(config);
        if (!id.equals(settings.activeSourceId())) {
            String previous=settings.activeSourceId();
            // Preserve legacy permissions before clearing the active-runtime cache.
            if(previous!=null && !previous.isBlank()){
                jdbc.update("INSERT IGNORE INTO da_source_table_permissions(data_source_id,table_name,enabled) " +
                    "SELECT ?,table_name,enabled FROM da_allowed_tables WHERE enabled=1",previous);
            }
            jdbc.update("UPDATE da_allowed_tables SET enabled=0");
            jdbc.update("INSERT INTO da_allowed_tables(table_name,enabled) " +
                "SELECT table_name,1 FROM da_source_table_permissions WHERE data_source_id=? AND enabled=1 " +
                "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)",id);
            jdbc.update("DELETE FROM da_semantic_vectors");
            settings.activateSource(id,config);
        }
        AuditContext.set("target","数据源："+id);
        AuditContext.set("detail","切换启用数据源，恢复该源已保存的表授权并重置语义索引");
        return Map.of("success",true);
    }
    @DeleteMapping("/{id}")
    @Transactional
    public Map<String,Object> delete(@PathVariable String id) {
        lock();
        existing(id);
        if (id.equals(settings.activeSourceId())) throw bad("当前启用的数据源不能删除，请先切换数据源");
        jdbc.update("DELETE FROM da_source_table_permissions WHERE data_source_id=?",id);
        jdbc.update("DELETE FROM da_datasources WHERE id=?",id);
        AuditContext.set("target","数据源："+id);
        return Map.of("success",true);
    }

    private void lock() {
        jdbc.update("INSERT IGNORE INTO da_app_settings(setting_key,setting_value) VALUES('sr.activeSource','')");
        jdbc.queryForList("SELECT setting_key FROM da_app_settings WHERE setting_key='sr.activeSource' FOR UPDATE");
    }
}

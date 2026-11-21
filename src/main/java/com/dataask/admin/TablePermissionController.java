package com.dataask.admin;

import com.dataask.audit.AuditContext;
import com.dataask.config.RuntimeConfigService;
import com.dataask.metrics.MetricDictionaryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.*;
import java.util.*;

/**
 * Table-level grants can be maintained for every configured StarRocks data source.
 * Only grants from the active source are copied into da_allowed_tables, which is
 * still the fail-closed allowlist used by the query and semantic engines.
 */
@RestController
@RequestMapping("/api/admin/table-permissions")
@PreAuthorize("hasAuthority('PERM_SYSTEM_ADMIN')")
public class TablePermissionController {
    private static final int MAX_SUGGESTIONS=1000;
    private static final Set<String> NUMERIC_TYPES=Set.of(
        "tinyint","smallint","int","integer","bigint","largeint",
        "float","double","decimal","decimal32","decimal64","decimal128","decimal256","numeric","real");

    private final JdbcTemplate jdbc;
    private final RuntimeConfigService settings;
    private final MetricDictionaryService metrics;

    public TablePermissionController(JdbcTemplate jdbc,RuntimeConfigService settings,MetricDictionaryService metrics){
        this.jdbc=jdbc;this.settings=settings;this.metrics=metrics;
    }

    public record Source(String id,String name,String schema,boolean active){}
    public record TableItem(String name,boolean enabled){}
    public record GrantRequest(@NotBlank String sourceId,@NotBlank String tableName,boolean enabled){}
    public record Suggestion(String metricName,String definition,String adsTable,String adsField,
                             String dimensions,String timeField,String notes){}
    public record Preview(String sourceName,String schema,String sourceId,int count,int withoutPrimaryKey,
                          List<Suggestion> metrics,List<String> notices){}

    private record StoredSource(String id,String name,String jdbcUrl,String username,String passwordEnc,String schema){}
    private record Column(String name,String type,String comment,boolean primaryKey){}
    private record TableDetail(String qualifiedName,List<Column> columns){}

    @GetMapping("/sources")
    public List<Source> sources(){
        String active=settings.activeSourceId();
        return jdbc.query("SELECT id,name,schema_name FROM da_datasources ORDER BY name,id",
            (rs,n)->new Source(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(1).equals(active)));
    }

    private StoredSource source(String sourceId){
        if(sourceId==null || !sourceId.matches("[0-9a-fA-F-]{36}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择有效的数据源");
        List<StoredSource> rows=jdbc.query(
            "SELECT id,name,jdbc_url,username,password_enc,schema_name FROM da_datasources WHERE id=?",
            (rs,n)->new StoredSource(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6)),sourceId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"数据源不存在");
        return rows.getFirst();
    }

    private List<TableDetail> discover(StoredSource source){
        var config=settings.validateConnection(source.jdbcUrl(),source.username(),
            settings.openCredential(source.passwordEnc()),source.schema());
        Properties props=new Properties();
        props.setProperty("user",config.username());
        props.setProperty("password",config.password());
        props.setProperty("connectTimeout","5000");
        props.setProperty("socketTimeout","15000");
        Map<String,List<Column>> columns=new LinkedHashMap<>();
        Map<String,Set<String>> keys=new HashMap<>();
        try(Connection connection=DriverManager.getConnection(config.jdbcUrl(),props)){
            String query="SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,COLUMN_COMMENT,COLUMN_KEY " +
                "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME,ORDINAL_POSITION";
            try(PreparedStatement statement=connection.prepareStatement(query)){
                statement.setQueryTimeout(20);
                statement.setString(1,config.schema());
                try(ResultSet rs=statement.executeQuery()){
                    while(rs.next()){
                        String table=rs.getString(1);
                        if("ads".equalsIgnoreCase(config.schema()) &&
                            "ads_user_city_permission".equalsIgnoreCase(table)) continue;
                        String type=Objects.toString(rs.getString(3),"");
                        columns.computeIfAbsent(table,ignored->new ArrayList<>()).add(
                            new Column(rs.getString(2),type,Objects.toString(rs.getString(4),""),
                                "PRI".equalsIgnoreCase(rs.getString(5))));
                    }
                }
            }
            // StarRocks 3.3 exposes a composite Primary Key list in tables_config.
            // It is optional for non-OLAP tables, so never fail metadata discovery for it.
            try(PreparedStatement statement=connection.prepareStatement(
                "SELECT TABLE_NAME,PRIMARY_KEY FROM information_schema.tables_config WHERE TABLE_SCHEMA=?")){
                statement.setQueryTimeout(8);
                statement.setString(1,config.schema());
                try(ResultSet rs=statement.executeQuery()){
                    while(rs.next()){
                        Set<String> pk=new HashSet<>();
                        for(String key:Objects.toString(rs.getString(2),"").split(",")){
                            String normalized=key.strip().replace("`","").replace("\"","");
                            if(!normalized.isEmpty())pk.add(normalized.toLowerCase(Locale.ROOT));
                        }
                        keys.put(rs.getString(1),pk);
                    }
                }
            }catch(SQLException ignored){
                // COLUMN_KEY from information_schema.COLUMNS remains the fallback.
            }
        }catch(SQLException ex){
            org.slf4j.LoggerFactory.getLogger(TablePermissionController.class)
                .warn("Table metadata lookup failed for configured source {}: state={}",source.id(),ex.getSQLState());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "读取数据源表结构失败，请检查连接、Schema 及元数据读取权限");
        }
        List<TableDetail> result=new ArrayList<>();
        columns.forEach((table,fields)->{
            Set<String> pk=keys.getOrDefault(table,Set.of());
            result.add(new TableDetail(config.schema()+"."+table,
                fields.stream().map(f->new Column(f.name(),f.type(),f.comment(),
                    f.primaryKey()||pk.contains(f.name().toLowerCase(Locale.ROOT)))).toList()));
        });
        return result;
    }

    private Set<String> enabled(String sourceId){
        return new HashSet<>(jdbc.query(
            "SELECT table_name FROM da_source_table_permissions WHERE data_source_id=? AND enabled=1",
            (rs,n)->rs.getString(1),sourceId));
    }

    /** A one-time, non-destructive migration of existing grants for the active source. */
    private void ensureActiveLegacyGrants(String sourceId){
        if(!sourceId.equals(settings.activeSourceId()))return;
        Integer count=jdbc.queryForObject(
            "SELECT COUNT(*) FROM da_source_table_permissions WHERE data_source_id=?",
            Integer.class,sourceId);
        if(count!=null && count==0){
            jdbc.update("INSERT IGNORE INTO da_source_table_permissions(data_source_id,table_name,enabled) " +
                "SELECT ?,table_name,enabled FROM da_allowed_tables WHERE enabled=1",sourceId);
        }
    }

    @GetMapping
    public List<TableItem> tables(@RequestParam String sourceId){
        StoredSource selected=source(sourceId);
        ensureActiveLegacyGrants(sourceId);
        Set<String> enabled=enabled(sourceId);
        return discover(selected).stream().map(t->new TableItem(t.qualifiedName(),
            enabled.contains(t.qualifiedName()))).toList();
    }

    @PostMapping
    @Transactional
    public Map<String,Object> change(@Valid @RequestBody GrantRequest request){
        StoredSource selected=source(request.sourceId());
        boolean exists=discover(selected).stream().anyMatch(x->x.qualifiedName().equals(request.tableName()));
        if(!exists)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"所选数据源中不存在该表");
        ensureActiveLegacyGrants(selected.id());
        jdbc.update("INSERT INTO da_source_table_permissions(data_source_id,table_name,enabled) VALUES(?,?,?) " +
            "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)",
            selected.id(),request.tableName(),request.enabled()?1:0);
        if(selected.id().equals(settings.activeSourceId())){
            jdbc.update("INSERT INTO da_allowed_tables(table_name,enabled) VALUES(?,?) " +
                "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)",request.tableName(),request.enabled()?1:0);
        }
        AuditContext.set("target","数据源："+selected.name()+"；表："+request.tableName());
        AuditContext.set("detail",request.enabled()?"授权查询":"取消授权");
        return Map.of("success",true);
    }

    private Preview parseSuggestions(String sourceId){
        StoredSource selected=source(sourceId);
        ensureActiveLegacyGrants(sourceId);
        Set<String> grants=enabled(sourceId);
        List<Suggestion> items=new ArrayList<>();
        List<String> notices=new ArrayList<>();
        int withoutPrimaryKey=0;
        for(TableDetail table:discover(selected)){
            if(!grants.contains(table.qualifiedName()))continue;
            List<String> primary=table.columns().stream().filter(Column::primaryKey)
                .map(Column::name).toList();
            if(primary.isEmpty()){
                withoutPrimaryKey++;
                notices.add(table.qualifiedName()+"：未发现主键，需手工补充分析维度");
            }
            String dimensions=String.join(",",primary);
            if(dimensions.length()>500) {
                notices.add(table.qualifiedName()+"：联合主键过长，已跳过");
                continue;
            }
            for(Column field:table.columns()){
                String type=field.type().toLowerCase(Locale.ROOT).replaceAll("\\(.*","").strip();
                if(field.primaryKey() || !NUMERIC_TYPES.contains(type))continue;
                if(items.size()>=MAX_SUGGESTIONS)
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "待解析的数值字段超过 1000 个，请减少授权表后再试");
                String caption=field.comment().strip().isEmpty()?field.name():field.comment().strip();
                if(caption.length()>100)caption=caption.substring(0,100);
                String title=caption+"（"+field.name()+"）";
                if(title.length()>150)title=title.substring(0,150);
                String definition="自动识别草稿：建议对 "+field.name()+" 按 "+
                    (primary.isEmpty()?"人工指定的分析维度":String.join("、",primary))+
                    " 聚合；默认建议 SUM，需确认口径、单位和是否可累加";
                String notes="字段类型："+field.type()+"；来源数据源："+selected.name()+
                    "；自动解析建议，需人工确认";
                items.add(new Suggestion(title,definition,table.qualifiedName(),field.name(),
                    dimensions,"",notes));
            }
        }
        if(items.isEmpty())notices.add("没有可解析的数值字段，请先授权至少一张含数值列的表");
        return new Preview(selected.name(),selected.schema(),sourceId,items.size(),
            withoutPrimaryKey,List.copyOf(items),List.copyOf(notices));
    }

    @GetMapping("/auto-parse")
    public Preview preview(@RequestParam String sourceId){return parseSuggestions(sourceId);}

    public record ConfirmRequest(@NotBlank String sourceId){}

    @PostMapping("/auto-parse/confirm")
    public Map<String,Object> confirm(@Valid @RequestBody ConfirmRequest request,
                                      org.springframework.security.core.Authentication authentication){
        // The semantic and SQL engines currently execute against a single active source.
        // Do not pollute their metric dictionary with definitions from inactive sources.
        if(!request.sourceId().equals(settings.activeSourceId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "请先在数据源配置中启用该数据源，再确认生成指标");
        Preview preview=parseSuggestions(request.sourceId());
        if(preview.metrics().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未发现可生成的指标字段");
        List<MetricDictionaryService.MetricEdit> entries=preview.metrics().stream()
            .map(s->new MetricDictionaryService.MetricEdit(s.metricName(),"",s.definition(),
                s.adsTable(),s.adsField(),s.timeField(),s.dimensions(),s.notes())).toList();
        Map<String,Object> result=metrics.importAutoSuggestions(entries,authentication.getName());
        AuditContext.set("target","指标自动解析："+preview.sourceName());
        AuditContext.set("detail","已解析候选 "+preview.count()+" 条");
        return result;
    }
}

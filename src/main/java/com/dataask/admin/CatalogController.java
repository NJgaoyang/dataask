package com.dataask.admin;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.CatalogPolicyService;
import com.dataask.data.StarRocksGateway;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/admin/catalog")
@PreAuthorize("hasRole('ADMIN')")
public class CatalogController {
    private final RuntimeConfigService settings;
    private final StarRocksGateway gateway;
    private final CatalogPolicyService policies;
    public CatalogController(RuntimeConfigService settings, StarRocksGateway gateway, CatalogPolicyService policies) {
        this.settings=settings; this.gateway=gateway; this.policies=policies;
    }
    @GetMapping("/databases")
    public Map<String,Object> databases() {
        var config=settings.starRocks();
        var current=policies.current();
        return Map.of("configured",config.configured(),"defaultDatabase",config.schema(),
            "databases",config.configured() ? List.of(config.schema()) : List.of(),
            "sourceKey",current.sourceKey(),"revision",current.revision(),"selection",current.tables());
    }
    @GetMapping("/tables")
    public Map<String,Object> tables(@RequestParam String database) {
        CatalogPolicyService.identifier(database);
        var config=settings.starRocks();
        if (!database.equals(config.schema()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"只能选择当前 ADS 数据库");
        var policy=policies.current();
        Set<String> legacy=policies.enabledTables();
        List<Map<String,Object>> result=new ArrayList<>();
        for (var table : gateway.catalog()) {
            List<String> selected=policy.configured() ?
                policy.tables().getOrDefault(table.name(),List.of()) :
                legacy.contains(table.name()) ? table.columns().stream().map(StarRocksGateway.ColumnMeta::name).toList() : List.of();
            result.add(Map.of("name",table.name(),"columns",table.columns(),
                "selectedColumns",selected));
        }
        return Map.of("database",database,"sourceKey",policy.sourceKey(),
            "revision",policy.revision(),"tables",result);
    }
    public record Selection(String database,String sourceKey,String revision,Map<String,List<String>> tables) {}
    @PostMapping("/selection")
    public Map<String,Object> save(@RequestBody Selection input) {
        if(input==null || input.sourceKey()==null || input.revision()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先加载数据库和表字段");
        var saved=policies.saveDatabase(input.sourceKey(),input.revision(),input.database(),
            input.tables(),gateway.catalog());
        int fields=saved.tables().values().stream().mapToInt(List::size).sum();
        return Map.of("success",true,"revision",saved.revision(),
            "tableCount",saved.tables().size(),"fieldCount",fields,
            "message","字段权限已保存；Embedding 索引已失效，按需重建");
    }
}

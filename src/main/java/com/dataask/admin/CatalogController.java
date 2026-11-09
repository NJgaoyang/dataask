package com.dataask.admin;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.CatalogPolicyService;
import com.dataask.data.StarRocksGateway;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/admin/catalog")
public class CatalogController {
    private final RuntimeConfigService settings;
    private final StarRocksGateway gateway;
    private final CatalogPolicyService policies;
    public CatalogController(RuntimeConfigService settings, StarRocksGateway gateway, CatalogPolicyService policies) {
        this.settings = settings; this.gateway = gateway; this.policies = policies;
    }
    @GetMapping("/databases")
    public Map<String,Object> databases() {
        var config = settings.starRocks();
        var policy = policies.current();
        return Map.of("configured", config.configured(), "defaultDatabase", config.schema(),
            "databases", config.configured() ? gateway.databases() : List.of(),
            "sourceKey", policy.sourceKey(), "revision", policy.revision(),
            "selection", policy.tables());
    }
    @GetMapping("/tables")
    public Map<String,Object> tables(@RequestParam String database) {
        CatalogPolicyService.identifier(database);
        var policy = policies.current();
        List<Map<String,Object>> result = new ArrayList<>();
        for (var table : gateway.catalog(database)) {
            result.add(Map.of("name", table.name(), "comment", table.comment(), "columns", table.columns(),
                "selectedColumns", policy.tables().getOrDefault(table.name(), List.of())));
        }
        if (!RuntimeConfigService.sourceKey(settings.starRocks()).equals(policy.sourceKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "数据源已变更，请刷新");
        return Map.of("database", database, "sourceKey", policy.sourceKey(), "revision", policy.revision(), "tables", result);
    }
    public record Selection(String database, String sourceKey, String revision, Map<String,List<String>> tables) {}
    @PostMapping("/selection")
    public Map<String,Object> save(@RequestBody Selection input) {
        if (input == null || input.sourceKey() == null || input.revision() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先加载数据源和表字段");
        CatalogPolicyService.identifier(input.database());
        var result = policies.saveDatabase(input.sourceKey(), input.revision(), input.database(), input.tables(),
            gateway.catalog(input.database()));
        int fields = result.tables().values().stream().mapToInt(List::size).sum();
        return Map.of("success", true, "revision", result.revision(), "tableCount", result.tables().size(),
            "fieldCount", fields, "message", "已保存到 MySQL，表字段范围立即生效；使用 Embedding 时请重建索引");
    }
}

package com.dataask.data;

import com.dataask.config.RuntimeConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class CatalogPolicyService {
    public record Policy(String sourceKey, String revision, Map<String,List<String>> tables) {}
    private final JdbcTemplate jdbc;
    private final RuntimeConfigService settings;
    private final ObjectMapper json;

    public CatalogPolicyService(JdbcTemplate jdbc, RuntimeConfigService settings, ObjectMapper json) {
        this.jdbc = jdbc; this.settings = settings; this.json = json;
    }
    public Policy current() {
        return read(RuntimeConfigService.sourceKey(settings.starRocks()), false);
    }
    private Policy read(String source, boolean lock) {
        List<String> values = jdbc.query("SELECT setting_value FROM da_app_settings WHERE setting_key='catalog.policy'" +
            (lock ? " FOR UPDATE" : ""), (rs, n) -> rs.getString(1));
        if (values.isEmpty() || values.getFirst().isBlank()) return new Policy(source, "none", Map.of());
        try {
            Policy policy = json.readValue(values.getFirst(), Policy.class);
            if (!source.equals(policy.sourceKey())) return new Policy(source, "none", Map.of());
            return policy;
        } catch (Exception e) { throw new IllegalStateException("表字段授权配置损坏，请管理员重新保存", e); }
    }
    public static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据库、表、字段名格式不正确");
    }
    @Transactional
    public Policy saveDatabase(String sourceKey, String revision, String schema,
            Map<String,List<String>> selected, List<StarRocksGateway.TableMeta> actual) {
        identifier(schema);
        jdbc.queryForObject("SELECT setting_value FROM da_app_settings WHERE setting_key='system.bootstrap.lock' FOR UPDATE", String.class);
        if (selected == null || selected.size() > 300) throw bad("一次最多选择300张表");
        jdbc.update("INSERT INTO da_app_settings(setting_key,setting_value) VALUES('catalog.policy','') " +
            "ON DUPLICATE KEY UPDATE setting_key=setting_key");
        // Serialize with other policy writes before checking the effective connection identity.
        jdbc.queryForObject("SELECT setting_value FROM da_app_settings WHERE setting_key='catalog.policy' FOR UPDATE", String.class);
        String source = RuntimeConfigService.sourceKey(settings.starRocks());
        if (!source.equals(sourceKey)) throw conflict("数据源已更换，请刷新后重新选择表和字段");
        Policy old = read(source, true);
        if (!old.revision().equals(revision)) throw conflict("配置已被修改，请刷新后重新保存");
        Map<String,Set<String>> available = new LinkedHashMap<>();
        for (StarRocksGateway.TableMeta table : actual) {
            Set<String> cols = new HashSet<>();
            table.columns().forEach(c -> cols.add(c.name()));
            available.put(table.name(), cols);
        }
        Map<String,List<String>> combined = new LinkedHashMap<>(old.tables());
        combined.keySet().removeIf(t -> t.startsWith(schema + "."));
        for (var item : selected.entrySet()) {
            String table = item.getKey();
            if (!table.startsWith(schema + ".") || !available.containsKey(table)) throw bad("所选表不在当前数据库中");
            identifier(table.substring(schema.length() + 1));
            List<String> columns = item.getValue();
            if (columns == null || columns.isEmpty() || columns.size() > 500) throw bad("每张已选表必须选择1～500个字段");
            if (new HashSet<>(columns).size() != columns.size()) throw bad("不能重复选择字段");
            for (String col : columns) {
                identifier(col);
                if (!available.get(table).contains(col)) throw bad("字段不存在或已变更：" + table + "." + col);
            }
            combined.put(table, List.copyOf(columns));
        }
        if (combined.size() > 1000) throw bad("最多开放1000张表");
        Policy policy = new Policy(source, UUID.randomUUID().toString(), combined);
        try { jdbc.update("UPDATE da_app_settings SET setting_value=? WHERE setting_key='catalog.policy'", json.writeValueAsString(policy)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        jdbc.update("UPDATE da_allowed_tables SET enabled=0");
        for (String table : combined.keySet()) jdbc.update("INSERT INTO da_allowed_tables(table_name,enabled) VALUES(?,1) " +
            "ON DUPLICATE KEY UPDATE enabled=1", table);
        // A revoked field must disappear from the next semantic index/search, not just the UI.
        jdbc.update("DELETE FROM da_semantic_vectors");
        return policy;
    }
    public List<StarRocksGateway.TableMeta> filter(List<StarRocksGateway.TableMeta> metadata, Policy policy) {
        List<StarRocksGateway.TableMeta> result = new ArrayList<>();
        for (var table : metadata) {
            List<String> columns = policy.tables().get(table.name());
            if (columns == null) continue;
            var filtered = table.columns().stream().filter(c -> columns.contains(c.name())).toList();
            if (!filtered.isEmpty()) result.add(new StarRocksGateway.TableMeta(table.name(), filtered, table.comment()));
        }
        return result;
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}

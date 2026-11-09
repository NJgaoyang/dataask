package com.dataask.data;

import com.dataask.config.RuntimeConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.*;
import java.util.*;

@Service
public class StarRocksGateway {
    private final RuntimeConfigService settings;
    private final CatalogPolicyService policy;
    private final ColumnAccessGuard columns;
    private final SqlGuard sqlGuard;
    public StarRocksGateway(RuntimeConfigService settings, CatalogPolicyService policy,
            ColumnAccessGuard columns, SqlGuard sqlGuard) {
        this.settings = settings; this.policy = policy; this.columns = columns; this.sqlGuard = sqlGuard;
    }
    public boolean configured() { return settings.starRocks().configured(); }
    public String schema() { return settings.starRocks().schema(); }
    public String selectionRevision() {
        var p = policy.current(); return p.sourceKey() + ":" + p.revision();
    }
    private Connection connect(RuntimeConfigService.StarRocksConfig config) throws SQLException {
        if (!config.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "尚未配置分析数据源，请管理员登录系统管理设置 StarRocks 连接");
        Properties p = new Properties();
        p.setProperty("user", config.username()); p.setProperty("password", config.password());
        p.setProperty("connectTimeout", "5000"); p.setProperty("socketTimeout", "30000");
        p.setProperty("allowMultiQueries", "false"); p.setProperty("allowLoadLocalInfile", "false");
        p.setProperty("allowUrlInLocalInfile", "false");
        return DriverManager.getConnection(config.jdbcUrl(), p);
    }
    public record ColumnMeta(String name, String type, String comment) {}
    public record TableMeta(String name, List<ColumnMeta> columns, String comment) {
        public TableMeta(String name, List<ColumnMeta> columns) { this(name, columns, ""); }
    }
    public record QueryResult(List<String> columns, List<Map<String,Object>> rows) {}

    public Map<String,Object> test(RuntimeConfigService.StarRocksConfig config) {
        try (Connection c = connect(config); Statement s = c.createStatement()) {
            s.setQueryTimeout(8);
            try (ResultSet result = s.executeQuery("SELECT 1")) {
                if (!result.next()) throw new SQLException("empty test result");
            }
            return Map.of("success", true, "message", "连接成功，可继续选择数据库、表及字段");
        } catch (SQLException ex) { throw connectionError("连接测试失败，请检查 FE 地址、端口和账号", ex); }
    }
    public List<String> databases() {
        List<String> result = new ArrayList<>();
        Set<String> system = Set.of("information_schema", "mysql", "sys", "performance_schema", "_statistics_");
        try (Connection c = connect(settings.starRocks()); Statement s = c.createStatement()) {
            s.setQueryTimeout(10);
            try (ResultSet rs = s.executeQuery("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null && !system.contains(name.toLowerCase(Locale.ROOT))) result.add(name);
                }
            }
            return result;
        } catch (SQLException ex) { throw connectionError("无法读取数据库列表，请检查连接和权限", ex); }
    }
    /** Admin-only metadata discovery. NL2SQL must use discover(), not this method. */
    public List<TableMeta> catalog(String schema) {
        CatalogPolicyService.identifier(schema);
        return catalog(settings.starRocks(), schema);
    }
    private List<TableMeta> catalog(RuntimeConfigService.StarRocksConfig config, String schema) {
        String sql = "SELECT c.TABLE_NAME,c.COLUMN_NAME,c.DATA_TYPE,c.COLUMN_COMMENT,t.TABLE_COMMENT " +
            "FROM information_schema.COLUMNS c LEFT JOIN information_schema.TABLES t " +
            "ON c.TABLE_SCHEMA=t.TABLE_SCHEMA AND c.TABLE_NAME=t.TABLE_NAME " +
            "WHERE c.TABLE_SCHEMA=? ORDER BY c.TABLE_NAME,c.ORDINAL_POSITION";
        Map<String,List<ColumnMeta>> tables = new LinkedHashMap<>();
        Map<String,String> comments = new HashMap<>();
        try (Connection c = connect(config); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, schema); s.setQueryTimeout(15);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    tables.computeIfAbsent(table, k -> new ArrayList<>()).add(new ColumnMeta(
                        rs.getString(2), rs.getString(3), Objects.toString(rs.getString(4), "")));
                    comments.put(table, Objects.toString(rs.getString(5), ""));
                }
            }
        } catch (SQLException ex) { throw connectionError("无法读取表和字段，请检查数据库授权", ex); }
        return tables.entrySet().stream().map(e -> new TableMeta(schema + "." + e.getKey(), e.getValue(), comments.get(e.getKey()))).toList();
    }
    /** Only selected physical fields are sent to the model/embedding layer. */
    public List<TableMeta> discover() {
        var config = settings.starRocks();
        var selected = policy.current();
        if (!RuntimeConfigService.sourceKey(config).equals(selected.sourceKey())) return List.of();
        Set<String> databases = new LinkedHashSet<>();
        for (String table : selected.tables().keySet()) {
            int dot = table.indexOf('.');
            if (dot < 1) continue;
            String db = table.substring(0, dot); CatalogPolicyService.identifier(db); databases.add(db);
        }
        List<TableMeta> metadata = new ArrayList<>();
        for (String db : databases) metadata.addAll(catalog(config, db));
        return policy.filter(metadata, selected);
    }
    public List<String> distinctDimensionValues(String qualifiedTable, String column) {
        var config = settings.starRocks(); var selected = policy.current();
        if (!RuntimeConfigService.sourceKey(config).equals(selected.sourceKey()) ||
            !selected.tables().getOrDefault(qualifiedTable, List.of()).contains(column))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该表或字段未被选择");
        String[] name = qualifiedTable.split("\\.", -1);
        if (name.length != 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "表名格式不正确");
        CatalogPolicyService.identifier(name[0]); CatalogPolicyService.identifier(name[1]); CatalogPolicyService.identifier(column);
        String field = "`" + column + "`";
        String sql = "SELECT DISTINCT " + field + " FROM `" + name[0] + "`.`" + name[1] + "` WHERE " +
            field + " IS NOT NULL AND " + field + " <> '' LIMIT 500";
        List<String> result = new ArrayList<>();
        try (Connection c = connect(config); Statement s = c.createStatement()) {
            s.setQueryTimeout(8); s.setMaxRows(500);
            try (ResultSet rs = s.executeQuery(sql)) {
                while (rs.next()) {
                    String value = rs.getString(1);
                    if (value != null && value.strip().length() >= 2 && value.strip().length() <= 60) result.add(value.strip());
                }
            }
        } catch (SQLException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("维度读取失败 SQLState={}", ex.getSQLState());
        }
        return result;
    }
    public QueryResult query(String sql) {
        var config = settings.starRocks(); var selected = policy.current();
        if (!RuntimeConfigService.sourceKey(config).equals(selected.sourceKey()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据源已变更，请重新提问");
        sqlGuard.validate(sql, selected.tables().keySet(), config.schema());
        columns.validate(sql, selected.tables(), config.schema());
        try (Connection c = connect(config); Statement s = c.createStatement()) {
            s.setQueryTimeout(20); s.setMaxRows(200);
            try (ResultSet rs = s.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> headers = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) headers.add(meta.getColumnLabel(i));
                if (new HashSet<>(headers).size() != headers.size())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询结果存在重名列，请生成独立列别名");
                List<Map<String,Object>> rows = new ArrayList<>();
                while (rs.next() && rows.size() < 200) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= headers.size(); i++) {
                        Object value = rs.getObject(i);
                        if (value instanceof byte[]) value = "[二进制数据]";
                        else if (value instanceof java.sql.Date || value instanceof java.sql.Timestamp || value instanceof java.sql.Time)
                            value = value.toString();
                        row.put(headers.get(i - 1), value);
                    }
                    rows.add(row);
                }
                return new QueryResult(headers, rows);
            }
        } catch (SQLException ex) { throw connectionError("StarRocks 查询失败，请检查字段和业务口径", ex); }
    }
    private ResponseStatusException connectionError(String message, SQLException ex) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("StarRocks SQLState={} code={}", ex.getSQLState(), ex.getErrorCode());
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
}

package com.dataask.data;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.*;
import java.util.*;

@Service
public class StarRocksGateway {
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String schema;

    public StarRocksGateway(@Value("${dataask.starrocks.jdbc-url:}") String jdbcUrl,
            @Value("${dataask.starrocks.username:}") String user,
            @Value("${dataask.starrocks.password:}") String password,
            @Value("${dataask.starrocks.schema:ads}") String schema) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("StarRocks Schema 配置无效");
        this.schema = schema;
    }

    public boolean configured() { return jdbcUrl != null && !jdbcUrl.isBlank(); }
    public String schema() { return schema; }

    private Connection connect() throws SQLException {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "尚未配置 StarRocks 连接，请先设置 DATAASK_STARROCKS_JDBC_URL");
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    public record ColumnMeta(String name, String type, String comment) {}
    public record TableMeta(String name, List<ColumnMeta> columns) {}
    public record QueryResult(List<String> columns, List<Map<String, Object>> rows) {}

    public List<TableMeta> discover() {
        String sql = "SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,COLUMN_COMMENT FROM information_schema.COLUMNS " +
                     "WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME,ORDINAL_POSITION";
        Map<String, List<ColumnMeta>> tables = new LinkedHashMap<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setQueryTimeout(15);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    tables.computeIfAbsent(table, ignored -> new ArrayList<>()).add(
                        new ColumnMeta(rs.getString(2), rs.getString(3),
                            rs.getString(4) == null ? "" : rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "读取 StarRocks ADS 元数据失败，请检查连接和权限");
        }
        List<TableMeta> result = new ArrayList<>();
        tables.forEach((name, columns) -> result.add(new TableMeta(schema + "." + name, columns)));
        return result;
    }

    public QueryResult query(String sql) {
        try (Connection c = connect(); Statement statement = c.createStatement()) {
            statement.setQueryTimeout(20);
            statement.setMaxRows(200);
            try (ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> headers = new ArrayList<>();
                for (int i=1; i<=meta.getColumnCount(); i++) headers.add(meta.getColumnLabel(i));
                List<Map<String,Object>> rows = new ArrayList<>();
                while (rs.next() && rows.size() < 200) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for (int i=1; i<=headers.size(); i++) {
                        Object val = rs.getObject(i);
                        if (val instanceof byte[] bytes) val = "[binary " + bytes.length + " bytes]";
                        else if (val instanceof java.sql.Date || val instanceof java.sql.Timestamp
                                || val instanceof java.sql.Time) val = val.toString();
                        row.put(headers.get(i-1), val);
                    }
                    rows.add(row);
                }
                return new QueryResult(headers, rows);
            }
        } catch (SQLException e) {
            // Avoid disclosing JDBC credentials and internal connection details to the caller.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "StarRocks 查询执行失败，请检查所选 ADS 表与字段口径");
        }
    }
}

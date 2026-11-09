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

    public StarRocksGateway(RuntimeConfigService settings) {
        this.settings=settings;
    }

    public boolean configured() { return settings.starRocks().configured(); }
    public String schema() { return settings.starRocks().schema(); }

    private Connection connect(RuntimeConfigService.StarRocksConfig config) throws SQLException {
        if (!config.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "StarRocks 未配置，请在系统管理中设置 FE JDBC 地址、用户及 ADS Schema");
        Properties properties=new Properties();
        properties.setProperty("user",config.username());
        properties.setProperty("password",config.password());
        properties.setProperty("connectTimeout","5000");
        properties.setProperty("socketTimeout","30000");
        properties.setProperty("allowLoadLocalInfile","false");
        properties.setProperty("allowUrlInLocalInfile","false");
        return DriverManager.getConnection(config.jdbcUrl(),properties);
    }

    public record ColumnMeta(String name, String type, String comment) {}
    public record TableMeta(String name, List<ColumnMeta> columns) {}
    public record QueryResult(List<String> columns, List<Map<String, Object>> rows) {}

    public Map<String,Object> test(RuntimeConfigService.StarRocksConfig config) {
        try (Connection conn=connect(config);
             PreparedStatement ps=conn.prepareStatement(
                 "SELECT COUNT(DISTINCT TABLE_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=?")) {
            ps.setQueryTimeout(10);
            ps.setString(1,config.schema());
            try (ResultSet rs=ps.executeQuery()) {
                int count=rs.next()?rs.getInt(1):0;
                return Map.of("success",true,"tables",count,
                    "message",count>0?"连接成功，已识别 "+count+" 张 ADS 表":"连接成功，但指定数据库没有可访问的表");
            }
        } catch (SQLException ex) {
            org.slf4j.LoggerFactory.getLogger(StarRocksGateway.class)
                .warn("StarRocks connection test failed: SQLState={} vendorCode={}",ex.getSQLState(),ex.getErrorCode());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "连接测试失败，请检查 FE 地址、端口、用户密码、ADS 库名及权限");
        }
    }

    public List<TableMeta> discover() {
        RuntimeConfigService.StarRocksConfig config=settings.starRocks();
        String sql="SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,COLUMN_COMMENT FROM information_schema.COLUMNS " +
                   "WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME,ORDINAL_POSITION";
        Map<String,List<ColumnMeta>> tables=new LinkedHashMap<>();
        try (Connection c=connect(config);PreparedStatement ps=c.prepareStatement(sql)) {
            ps.setString(1,config.schema());
            ps.setQueryTimeout(15);
            try (ResultSet rs=ps.executeQuery()) {
                while(rs.next()) {
                    String table=rs.getString(1);
                    tables.computeIfAbsent(table,ignored -> new ArrayList<>()).add(
                        new ColumnMeta(rs.getString(2),rs.getString(3),
                            rs.getString(4)==null?"":rs.getString(4)));
                }
            }
        } catch (SQLException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "读取 StarRocks ADS 元数据失败，请检查数据源连接和权限");
        }
        List<TableMeta> result=new ArrayList<>();
        tables.forEach((name,columns) -> result.add(new TableMeta(config.schema()+"."+name,columns)));
        return result;
    }

    /**
     * Read bounded canonical geographic dimension values from a vetted ADS table.
     * Caller must verify table and column against approved metadata before calling.
     */
    public List<String> distinctDimensionValues(String qualifiedTable, String column) {
        RuntimeConfigService.StarRocksConfig config = settings.starRocks();
        String prefix = config.schema() + ".";
        if (!qualifiedTable.startsWith(prefix) ||
            !qualifiedTable.substring(prefix.length()).matches("[A-Za-z_][A-Za-z0-9_]*") ||
            !column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "维度字段不在当前 ADS 数据库内");
        }
        String table = qualifiedTable.substring(prefix.length());
        String quote = Character.toString(96);
        String field = quote + column + quote;
        String sql = "SELECT DISTINCT " + field + " FROM " +
                     quote + config.schema() + quote + "." + quote + table + quote +
                     " WHERE " + field + " IS NOT NULL AND " + field + " <> '' LIMIT 500";
        List<String> values = new ArrayList<>();
        try (Connection c = connect(config); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(8);
            ps.setMaxRows(500);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && values.size() < 500) {
                    String value = rs.getString(1);
                    if (value != null) {
                        value = value.trim();
                        if (value.length() >= 2 && value.length() <= 60) values.add(value);
                    }
                }
            }
        } catch (SQLException e) {
            // Best effort: semantic lookup must not interrupt normal analytics.
            org.slf4j.LoggerFactory.getLogger(StarRocksGateway.class)
                .warn("Dimension lookup unavailable for {}.{}: SQLState={}",
                    qualifiedTable, column, e.getSQLState());
        }
        return values;
    }

    public interface QueryCancellation {
        void checkCancelled();
        void registerStatement(Statement statement);
        void clearStatement();
    }

    public QueryResult query(String sql) {
        return query(sql, null);
    }

    public QueryResult query(String sql, QueryCancellation cancellation) {
        RuntimeConfigService.StarRocksConfig config=settings.starRocks();
        if (cancellation != null) cancellation.checkCancelled();
        try (Connection c=connect(config);Statement statement=c.createStatement()) {
            if (cancellation != null) {
                cancellation.registerStatement(statement);
                cancellation.checkCancelled();
            }
            statement.setQueryTimeout(20);
            statement.setMaxRows(200);
            try (ResultSet rs=statement.executeQuery(sql)) {
                if (cancellation != null) cancellation.checkCancelled();
                ResultSetMetaData meta=rs.getMetaData();
                List<String> headers=new ArrayList<>();
                for(int i=1;i<=meta.getColumnCount();i++) headers.add(meta.getColumnLabel(i));
                List<Map<String,Object>> rows=new ArrayList<>();
                while(rs.next() && rows.size()<200) {
                    if (cancellation != null) cancellation.checkCancelled();
                    Map<String,Object> row=new LinkedHashMap<>();
                    for(int i=1;i<=headers.size();i++) {
                        Object val=rs.getObject(i);
                        if(val instanceof byte[] bytes) val="[binary "+bytes.length+" bytes]";
                        else if(val instanceof java.sql.Date || val instanceof java.sql.Timestamp
                                || val instanceof java.sql.Time) val=val.toString();
                        row.put(headers.get(i-1),val);
                    }
                    rows.add(row);
                }
                if (cancellation != null) cancellation.checkCancelled();
                return new QueryResult(headers,rows);
            }
        } catch (SQLException ex) {
            if (cancellation != null) cancellation.checkCancelled();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "StarRocks 查询执行失败，请检查所选 ADS 表和字段口径");
        } finally {
            if (cancellation != null) cancellation.clearStatement();
        }
    }
}

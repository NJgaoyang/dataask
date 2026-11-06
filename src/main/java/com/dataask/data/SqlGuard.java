package com.dataask.data;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class SqlGuard {
    private static final Pattern BLOCKED = Pattern.compile(
        "(?i)(--|/\\*|\\*/|#|\\binto\\s+outfile\\b|\\binto\\s+dumpfile\\b|" +
        "\\bload_file\\s*\\(|\\bsleep\\s*\\(|\\bbenchmark\\s*\\(|\\binformation_schema\\b|" +
        "\\bmysql\\.|\\bsys\\.)");

    public String validate(String sql, Set<String> grantedTables, String defaultSchema) {
        if (sql == null || sql.isBlank() || sql.length() > 12000
                || sql.indexOf(';') >= 0 || BLOCKED.matcher(sql).find()) {
            throw invalid();
        }
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) throw invalid();
            Set<String> referenced = new TablesNamesFinder().getTables(stmt);
            if (referenced.isEmpty()) throw invalid();
            Set<String> granted = new HashSet<>();
            for (String item : grantedTables) granted.add(item.toLowerCase(Locale.ROOT));
            for (String name : referenced) {
                String normalized = name.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
                if (!normalized.contains(".")) normalized = defaultSchema.toLowerCase(Locale.ROOT) + "." + normalized;
                if (!granted.contains(normalized)) throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "生成的 SQL 使用了未授权的表：" + normalized);
            }
            return sql;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "AI 生成的 SQL 无法通过只读安全校验，请换一种提问方式");
        }
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "仅允许单条 SELECT 查询，且必须使用已授权的 ADS 表");
    }
}

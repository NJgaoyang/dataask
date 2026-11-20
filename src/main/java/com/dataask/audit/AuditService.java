package com.dataask.audit;

import com.dataask.data.StarRocksGateway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    public static String bounded(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ");
        return clean.substring(0, Math.min(clean.length(), max));
    }

    public void beginOperation(String id, String username, String role, String method, String path,
            String module, String action, String target) {
        jdbc.update("INSERT INTO da_operation_audit(id,request_id,username,actor_role,http_method,path,module,action,target,outcome,occurred_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,'RUNNING',?)", id,id,bounded(username,64),bounded(role,16),method,
            bounded(path,512),module,action,bounded(target,500),now());
    }

    public void finishOperation(String id, String username, String role, String action, String target,
            String detail, int status, long duration) {
        jdbc.update("UPDATE da_operation_audit SET username=?,actor_role=?,action=?,target=?,detail=?," +
                "outcome=?,http_status=?,duration_ms=?,finished_at=? WHERE id=?",
            bounded(username,64),bounded(role,16),bounded(action,80),bounded(target,500),bounded(detail,12000),
            status < 400 ? "SUCCESS" : status == 401 || status == 403 ? "DENIED" : "FAILED", status,duration,now(),id);
    }

    /** Save the exact validated SQL BEFORE invoking the business database, including refreshes. */
    public StarRocksGateway.QueryResult query(String username, String source, String sessionId,
            String question, String sql, Supplier<StarRocksGateway.QueryResult> execute) {
        String id = UUID.randomUUID().toString();
        long started = System.nanoTime();
        jdbc.update("INSERT INTO da_query_audit(id,operation_id,request_id,username,source,session_id,question,sql_text,status,started_at) " +
                "VALUES(?,?,?,?,?,?,?,?,'RUNNING',?)", id,AuditContext.get("id"),AuditContext.get("id"),username,source,
            sessionId,bounded(question,800),sql,now());
        StarRocksGateway.QueryResult result;
        try {
            result = execute.get();
        } catch (RuntimeException | Error ex) {
            // An interrupted query thread must still be able to borrow an audit connection.
            boolean interrupted = Thread.interrupted();
            boolean cancelled = interrupted ||
                ex instanceof ResponseStatusException status && status.getStatusCode().value() == 409;
            try {
                finishQuery(id,cancelled ? "CANCELLED" : "FAILED",null,started,
                    cancelled ? "QUERY_CANCELLED" : "QUERY_FAILED");
            } catch (RuntimeException auditError) { ex.addSuppressed(auditError); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw ex;
        }
        finishQuery(id,"SUCCESS",result.rows().size(),started,"");
        return result;
    }

    private void finishQuery(String id, String status, Integer rows, long started, String errorCode) {
        jdbc.update("UPDATE da_query_audit SET status=?,row_count=?,duration_ms=?,error_code=?,finished_at=? WHERE id=?",
            status,rows,Math.max(0,(System.nanoTime()-started)/1_000_000),errorCode,now(),id);
    }
}

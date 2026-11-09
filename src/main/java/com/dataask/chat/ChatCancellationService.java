package com.dataask.chat;

import com.dataask.data.StarRocksGateway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Statement;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-user, per-request cancellation of PC chat requests. JDBC cancellation is
 * best-effort (depends on driver and StarRocks FE); cancelled answers are never persisted.
 */
@Service
public class ChatCancellationService {
    private final ConcurrentHashMap<String, Active> running = new ConcurrentHashMap<>();

    public static final class Active implements StarRocksGateway.QueryCancellation {
        private final String owner;
        private final String id;
        private final Thread worker;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final AtomicReference<Statement> statement = new AtomicReference<>();

        private Active(String owner, String id) {
            this.owner = owner;
            this.id = id;
            this.worker = Thread.currentThread();
        }
        @Override public void checkCancelled() {
            if (stopped.get()) throw new ResponseStatusException(HttpStatus.CONFLICT, "本次查询已停止");
        }
        @Override public void registerStatement(Statement s) {
            statement.set(s);
            if (stopped.get()) cancelStatement(s);
        }
        @Override public void clearStatement() { statement.set(null); }
        private static void cancelStatement(Statement s) {
            try { s.cancel(); } catch (SQLException | RuntimeException ignored) {
                // Closing the JDBC statement and stopping result persistence is the fallback.
            }
        }
        private void cancel() {
            stopped.set(true);
            Statement s = statement.get();
            if (s != null) cancelStatement(s);
            worker.interrupt(); // also interrupts supported AI HTTP client operations.
        }
    }

    public Active begin(String username, String requestId) {
        if (requestId == null || requestId.isBlank()) return new Active(username, "");
        if (!requestId.matches("[A-Za-z0-9-]{8,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId 格式不正确");
        Active candidate = new Active(username, requestId);
        if (running.putIfAbsent(requestId, candidate) != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "重复的 requestId");
        return candidate;
    }

    public boolean cancel(String username, String requestId) {
        if (requestId == null || requestId.isBlank()) return false;
        Active task = running.get(requestId);
        if (task == null || !task.owner.equals(username)) return false;
        task.cancel();
        return true;
    }

    public void end(Active active) {
        if (!active.id.isBlank()) running.remove(active.id, active);
        active.clearStatement();
        // A servlet thread is reused by Tomcat; don't carry a cancellation interrupt onward.
        if (active.stopped.get() && Thread.currentThread() == active.worker) Thread.interrupted();
    }
}

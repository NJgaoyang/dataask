package com.dataask.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Records API reads and writes without copying request bodies, credentials or query strings. */
public class AuditFilter extends OncePerRequestFilter {
    private final AuditService audit;
    public AuditFilter(AuditService audit) { this.audit = audit; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || request.getRequestURI().equals("/api/health") ||
            "OPTIONS".equals(request.getMethod());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth == null ? "anonymous" : auth.getName();
        String role = auth == null ? "ANONYMOUS" : auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())) ? "ADMIN" : "USER";
        String id = UUID.randomUUID().toString();
        String path = request.getRequestURI();
        String module = module(path);
        String action = action(request.getMethod(),path);
        long started = System.nanoTime();
        request.setAttribute("dataask.audit.id",id);
        response.setHeader("X-Request-Id",id);
        try {
            audit.beginOperation(id,username,role,request.getMethod(),path,module,action,path);
        } catch (RuntimeException ex) {
            // Never allow an unaudited mutation when the audit database is unavailable.
            LoggerFactory.getLogger(AuditFilter.class).error("Cannot persist audit operation {}",id,ex);
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"审计服务暂不可用，请稍后重试\"}");
            return;
        }
        boolean failed = false;
        try {
            chain.doFilter(request,response);
        } catch (IOException | ServletException | RuntimeException ex) {
            failed = true;
            throw ex;
        } finally {
            try {
                audit.finishOperation(id,attribute(request,"username",username),attribute(request,"role",role),
                    attribute(request,"action",action),attribute(request,"target",path),attribute(request,"detail",""),
                    failed ? 500 : response.getStatus(),Math.max(0,(System.nanoTime()-started)/1_000_000));
            } catch (RuntimeException ex) {
                // The durable RUNNING entry survives, rather than inventing a successful completion.
                LoggerFactory.getLogger(AuditFilter.class).error("Cannot finalize audit operation {}",id,ex);
            }
        }
    }

    private String attribute(HttpServletRequest request, String key, String fallback) {
        Object value = request.getAttribute("dataask.audit."+key);
        return value instanceof String s ? s : fallback;
    }
    static String module(String path) {
        if (path.startsWith("/api/auth/")) return "AUTH";
        if (path.startsWith("/api/admin/audit")) return "AUDIT";
        if (path.startsWith("/api/admin/")) return "ADMIN";
        if (path.startsWith("/api/chat/")) return "CHAT";
        if (path.contains("charts")) return "CHART";
        if (path.equals("/api/audit/events")) return "CLIENT";
        return "SYSTEM";
    }
    static String action(String method, String path) {
        if (path.equals("/api/auth/login")) return "登录";
        if (path.equals("/api/auth/logout")) return "退出登录";
        if (path.endsWith("change-password")) return "修改密码";
        if (path.endsWith("reset-password")) return "重置用户密码";
        if (path.equals("/api/auth/profile") && method.equals("PUT")) return "修改个人资料";
        if (path.equals("/api/chat/ask")) return "智能问数";
        if (path.equals("/api/chat/cancel")) return "停止查询";
        if (path.endsWith("/refresh")) return "刷新图表";
        if (path.endsWith("/share")) return "设置图表分享";
        if (path.startsWith("/api/chat/sessions") && path.endsWith("/batch-delete")) return "批量删除会话";
        if (path.endsWith("/batch-delete")) return "批量删除审计记录";
        if (path.contains("/audit/queries")) return "查看查询 SQL 审计";
        if (path.contains("/audit/")) return "查看操作审计";
        if (path.endsWith("/reset-password")) return "重置用户密码";
        if (path.contains("/users")) return method.equals("GET") ? "查看用户" : method.equals("POST") ? "新增用户" : "修改用户";
        if (path.contains("/datasources")) {
            if (path.endsWith("/activate")) return "启用数据源";
            if (path.endsWith("/test")) return "测试数据源";
            return switch (method) { case "GET" -> "查看数据源"; case "POST" -> "新增数据源"; case "PUT" -> "编辑数据源"; case "DELETE" -> "删除数据源"; default -> "管理数据源"; };
        }
        if (path.contains("/settings/")) return path.endsWith("/test") ? "测试服务连接" : "修改系统配置";
        if (path.endsWith("/table-allow")) return "设置数据表授权";
        if (path.contains("/metrics")) return method.equals("GET") ? "查看指标字典" : method.equals("DELETE") ? "删除指标批次" : "导入或预览指标字典";
        if (path.endsWith("/rebuild")) return "重建语义索引";
        String resource = path.contains("charts") ? "图表" : path.contains("sessions") ? "会话" : "系统资源";
        return switch (method) {
            case "GET" -> "查看"+resource;
            case "DELETE" -> "删除"+resource;
            case "PUT", "PATCH" -> "修改"+resource;
            default -> "提交"+resource;
        };
    }
}

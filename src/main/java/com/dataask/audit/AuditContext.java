package com.dataask.audit;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Trusted server-side context; never populated from arbitrary client identity fields. */
public final class AuditContext {
    private AuditContext() {}
    public static void set(String key, String value) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)
            attrs.getRequest().setAttribute("dataask.audit." + key, value);
    }
    public static String get(String key) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)
            return (String) attrs.getRequest().getAttribute("dataask.audit." + key);
        return null;
    }
}

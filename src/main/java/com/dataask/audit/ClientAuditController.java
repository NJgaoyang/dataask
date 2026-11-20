package com.dataask.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/audit")
public class ClientAuditController {
    public record Event(@NotBlank @Size(max=32) String action,@NotBlank @Size(max=100) String target) {}
    @PostMapping("/events")
    public Map<String,Boolean> event(@Valid @RequestBody Event event) {
        String label = switch (event.action()) {
            case "PAGE_VIEW" -> "切换页面";
            case "THEME_CHANGE" -> "切换界面主题";
            case "COPY_RESULT" -> "复制查询结果";
            case "VIEW_PROCESS" -> "展开执行过程";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不支持的操作类型");
        };
        if (event.action().equals("THEME_CHANGE") && !Set.of("light","dark").contains(event.target()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"主题无效");
        AuditContext.set("action",label);
        AuditContext.set("target",AuditService.bounded(event.target(),100));
        AuditContext.set("detail","客户端上报；身份由已验证登录凭证确定");
        return Map.of("success",true);
    }
}

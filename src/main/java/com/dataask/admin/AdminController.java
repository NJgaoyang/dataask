package com.dataask.admin;

import com.dataask.data.StarRocksGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final StarRocksGateway starrocks;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String aiUrl;
    private final String aiModel;
    private final String aiKey;

    public AdminController(StarRocksGateway starrocks, JdbcTemplate jdbc, PasswordEncoder encoder,
            @Value("${dataask.ai.base-url:}") String aiUrl,
            @Value("${dataask.ai.model:}") String aiModel,
            @Value("${dataask.ai.api-key:}") String aiKey) {
        this.starrocks = starrocks; this.jdbc = jdbc; this.encoder = encoder;
        this.aiUrl = aiUrl; this.aiModel = aiModel; this.aiKey = aiKey;
    }

    @GetMapping("/status")
    Map<String,Object> status() {
        return Map.of("starrocksConfigured", starrocks.configured(), "schema", starrocks.schema(),
            "aiConfigured", !aiUrl.isBlank() && !aiModel.isBlank() && !aiKey.isBlank(),
            "model", aiModel);
    }

    @GetMapping("/tables")
    List<Map<String,Object>> tables() {
        Set<String> enabled = new HashSet<>(jdbc.query(
            "SELECT table_name FROM da_allowed_tables WHERE enabled=1", (rs,n) -> rs.getString(1)));
        List<Map<String,Object>> result = new ArrayList<>();
        for (StarRocksGateway.TableMeta table : starrocks.discover()) {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("name", table.name());
            item.put("enabled", enabled.contains(table.name()));
            item.put("columns", table.columns());
            result.add(item);
        }
        return result;
    }

    record AllowTableRequest(@NotBlank String tableName, boolean enabled) {}

    @PostMapping("/table-allow")
    Map<String,Object> allow(@Valid @RequestBody AllowTableRequest request) {
        boolean exists = starrocks.discover().stream().anyMatch(x -> x.name().equals(request.tableName()));
        if (!exists) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指定的 ADS 表不存在");
        jdbc.update("INSERT INTO da_allowed_tables(table_name,enabled) VALUES(?,?) " +
                    "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)",
            request.tableName(), request.enabled() ? 1 : 0);
        return Map.of("success", true);
    }

    @GetMapping("/users")
    List<Map<String,Object>> users() {
        return jdbc.query("SELECT username,display_name,role,created_at FROM da_users ORDER BY id",
            (rs,n) -> Map.of("username",rs.getString(1), "displayName",rs.getString(2),
                "role",rs.getString(3), "createdAt",rs.getTimestamp(4).toString()));
    }

    record UserRequest(@NotBlank String username, @NotBlank String displayName,
                       @Size(min=10) String password, @NotBlank String role) {}

    @PostMapping("/users")
    Map<String,Object> createUser(@Valid @RequestBody UserRequest input) {
        if (!input.username().matches("[A-Za-z0-9_-]{3,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号仅允许3~64位字母、数字、下划线和横线");
        if (!Set.of("ADMIN","USER").contains(input.role()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色只能是 ADMIN 或 USER");
        try {
            jdbc.update("INSERT INTO da_users(username,display_name,password_hash,role) VALUES(?,?,?,?)",
                input.username(), input.displayName(), encoder.encode(input.password()), input.role());
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号已存在");
        }
        return Map.of("success",true);
    }
}

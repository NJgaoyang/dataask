package com.dataask.admin;

import com.dataask.audit.AuditContext;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.StarRocksGateway;
import com.dataask.semantic.EmbeddingSemanticService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('PERM_SYSTEM_ADMIN')")
public class AdminController {
    private final StarRocksGateway starrocks;
    private final RuntimeConfigService settings;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final ObjectMapper json;
    private final EmbeddingSemanticService embeddings;

    public AdminController(StarRocksGateway starrocks, RuntimeConfigService settings,
            JdbcTemplate jdbc, PasswordEncoder encoder, ObjectMapper json,
            EmbeddingSemanticService embeddings) {
        this.starrocks=starrocks; this.settings=settings;
        this.jdbc=jdbc; this.encoder=encoder; this.json=json;
        this.embeddings=embeddings;
    }

    @GetMapping("/status")
    Map<String,Object> status() { return settings.status(); }

    @GetMapping("/settings")
    Map<String,Object> settings() { return settings.adminView(); }

    @PostMapping("/settings/starrocks/test")
    Map<String,Object> testStarRocks(@RequestBody RuntimeConfigService.StarRocksInput input) {
        return starrocks.test(settings.validateStarRocks(input));
    }

    @PostMapping("/settings/starrocks")
    Map<String,Object> saveStarRocks(@RequestBody RuntimeConfigService.StarRocksInput input) {
        if (!Objects.toString(settings.activeSourceId(),"").isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"请在数据源管理中编辑或启用数据源");
        RuntimeConfigService.StarRocksConfig validated=settings.validateStarRocks(input);
        starrocks.test(validated); // Reject invalid connection before saving.
        settings.saveStarRocks(input);
        return Map.of("success",true,"message","StarRocks 数据源已保存并生效");
    }

    @PostMapping("/settings/ai")
    Map<String,Object> saveAi(@RequestBody RuntimeConfigService.ModelInput input) {
        settings.saveAi(input);
        return Map.of("success",true,"message","对话模型已保存，立即生效");
    }

    @PostMapping("/settings/ai/test")
    Map<String,Object> testAi(@RequestBody(required=false) RuntimeConfigService.ModelInput input) {
        if (input!=null && input.clearKey())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "已开启清除原有 API Key；请先关闭开关再测试现有模型连接，或保存无密钥配置后再测试");
        RuntimeConfigService.AiConfig config=input==null?settings.ai():settings.candidateAi(input);
        if (config.baseUrl().isBlank() || config.model().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先填写 Base URL 和模型名称");
        try {
            String target=config.baseUrl().replaceAll("/+$","")+"/chat/completions";
            String body=json.writeValueAsString(Map.of(
                "model",config.model(),
                "messages",List.of(Map.of("role","user","content","仅回答 OK"))));
            HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/json");
            if (!config.apiKey().isBlank())
                builder.header("Authorization","Bearer "+config.apiKey());
            HttpRequest request=builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            HttpResponse<String> reply=client.send(request,HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode()<200 || reply.statusCode()>=300) {
                String reason = reply.statusCode()==401 ?
                    "模型接口返回 HTTP 401：认证失败。请检查当前模型的 API Key，并确保未启用清除原有 API Key；无需在日志中输出密钥" :
                    "模型测试失败，HTTP "+reply.statusCode()+"；请检查接口、模型 ID、模型权限及网络";
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,reason);
            }
            com.fasterxml.jackson.databind.JsonNode data=json.readTree(reply.body());
            if (!data.path("choices").isArray() || data.path("choices").isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "接口已响应，但不是 Chat Completions 格式");
            return Map.of("success",true,"message","对话模型测试成功");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(AdminController.class)
                .warn("Chat model connection test failed: {}",ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "对话模型连接失败，请检查 Base URL、API Key、模型名称及网络");
        }
    }

    @PostMapping("/settings/embedding")
    Map<String,Object> saveEmbedding(@RequestBody RuntimeConfigService.EmbeddingInput input) {
        settings.saveEmbedding(input);
        return Map.of("success",true,"message","Embedding 模型配置已保存；请重建语义索引");
    }

    @PostMapping("/settings/embedding/test")
    Map<String,Object> testEmbedding(@RequestBody(required=false) RuntimeConfigService.EmbeddingInput input) {
        RuntimeConfigService.EmbeddingConfig config=
            input==null?settings.embedding():settings.candidateEmbedding(input);
        return embeddings.test(config);
    }

    @GetMapping("/semantic/status")
    Map<String,Object> semanticStatus() { return embeddings.status(); }

    @PostMapping("/semantic/rebuild")
    Map<String,Object> rebuildSemantics() { return embeddings.rebuild(); }

    @GetMapping("/tables")
    List<Map<String,Object>> tables() {
        Set<String> enabled=new HashSet<>(jdbc.query(
            "SELECT table_name FROM da_allowed_tables WHERE enabled=1",(rs,n)->rs.getString(1)));
        List<Map<String,Object>> result=new ArrayList<>();
        for(StarRocksGateway.TableMeta table:starrocks.discover()){
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("name",table.name());
            item.put("enabled",enabled.contains(table.name()));
            item.put("columns",table.columns());
            result.add(item);
        }
        return result;
    }

    record AllowTableRequest(@NotBlank String tableName,boolean enabled) {}

    @PostMapping("/table-allow")
    Map<String,Object> allow(@Valid @RequestBody AllowTableRequest request) {
        AuditContext.set("target",request.tableName());
        AuditContext.set("detail",request.enabled() ? "开放数据表查询权限" : "停用数据表查询权限");
        boolean exists=starrocks.discover().stream().anyMatch(x->x.name().equals(request.tableName()));
        if(!exists) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"指定 ADS 表不存在");
        jdbc.update("INSERT INTO da_allowed_tables(table_name,enabled) VALUES(?,?) "+
            "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)",
            request.tableName(),request.enabled()?1:0);
        String sourceId=settings.activeSourceId();
        if(sourceId!=null && !sourceId.isBlank())
            jdbc.update("INSERT INTO da_source_table_permissions(data_source_id,table_name,enabled) " +
                "VALUES(?,?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)",
                sourceId,request.tableName(),request.enabled()?1:0);
        return Map.of("success",true);
    }


    private boolean validRole(String role) {
        if(role==null || !role.matches("[A-Z][A-Z0-9_]{1,15}")) return false;
        if(Set.of("ADMIN","USER","CITY_MANAGER").contains(role)) return true;
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM da_roles WHERE code=? AND enabled=1",Integer.class,role);
        return count!=null && count>0;
    }

    private static Set<String> normalizedPermissions(Collection<String> input) {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        if(input!=null) for(String value:input) {
            String code=value==null?"":value.strip().toUpperCase(Locale.ROOT);
            if(!com.dataask.security.RolePermissionService.ALL_PERMISSIONS.contains(code))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"包含不支持的权限："+code);
            result.add(code);
        }
        return result;
    }

    @GetMapping("/roles")
    List<Map<String,Object>> roles() {
        // Avoid a role/user aggregation join: older deployments can have a different
        // da_users index/collation, and the role list must be resilient to orphaned users.
        Map<String,Long> userCounts=new HashMap<>();
        jdbc.query("SELECT role,COUNT(*) FROM da_users GROUP BY role",rs -> {
            userCounts.put(rs.getString(1),rs.getLong(2));
        });
        Map<String,Set<String>> grants=new HashMap<>();
        jdbc.query("SELECT role_code,permission_code FROM da_role_permissions ORDER BY role_code,permission_code",
            rs -> {
                grants.computeIfAbsent(rs.getString(1),unused -> new LinkedHashSet<>())
                    .add(rs.getString(2));
            });
        List<Map<String,Object>> result=new ArrayList<>();
        jdbc.query("SELECT code,name,built_in,enabled FROM da_roles ORDER BY built_in DESC,code",rs -> {
            String code=rs.getString(1);
            Set<String> permissions="ADMIN".equals(code)
                ? com.dataask.security.RolePermissionService.ALL_PERMISSIONS
                : grants.getOrDefault(code,Set.of());
            result.add(Map.of("code",code,"name",Objects.toString(rs.getString(2),code),
                "builtIn",rs.getBoolean(3),"enabled",rs.getBoolean(4),
                "cityScoped",!"ADMIN".equals(code),
                "dataScope","ADMIN".equals(code)?"ALL":"CITY",
                "permissions",permissions,"userCount",userCounts.getOrDefault(code,0L)));
        });
        return result;
    }

    record RoleRequest(@NotBlank @Size(max=16) String code,@NotBlank @Size(max=80) String name,
                       Set<String> permissions) {}
    record RoleEditRequest(@NotBlank @Size(max=80) String name,Set<String> permissions) {}

    @PostMapping("/roles")
    @Transactional
    Map<String,Object> createRole(@Valid @RequestBody RoleRequest input) {
        String code=input.code().strip().toUpperCase(Locale.ROOT);
        String name=input.name().strip();
        if(!code.matches("[A-Z][A-Z0-9_]{2,15}") || Set.of("ADMIN","USER","CITY_MANAGER").contains(code))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"角色编码需为3至16位大写字母、数字或下划线，且不能使用系统角色编码");
        Set<String> permissions=normalizedPermissions(input.permissions());
        if(permissions.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"角色至少需要一个功能权限");
        try {
            jdbc.update("INSERT INTO da_roles(code,name,built_in,enabled) VALUES(?,?,0,1)",code,name);
        } catch(DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"角色编码已存在");
        }
        for(String permission:permissions)
            jdbc.update("INSERT INTO da_role_permissions(role_code,permission_code) VALUES(?,?)",code,permission);
        AuditContext.set("target","角色："+code);
        AuditContext.set("detail","新建角色："+name+"；权限："+String.join(",",permissions));
        return Map.of("success",true,"code",code);
    }

    @PutMapping("/roles/{code}")
    @Transactional
    Map<String,Object> updateRole(@PathVariable String code,@Valid @RequestBody RoleEditRequest input) {
        code=code.strip().toUpperCase(Locale.ROOT);
        if("ADMIN".equals(code))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"管理员为系统内置角色，权限固定为全部");
        List<Map<String,Object>> roleRows=jdbc.queryForList("SELECT name,built_in FROM da_roles WHERE code=?",code);
        if(roleRows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"角色不存在");
        if(((Number)roleRows.getFirst().get("built_in")).intValue()!=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"系统内置角色不能修改");
        String name=input.name().strip();
        Set<String> permissions=normalizedPermissions(input.permissions());
        if(permissions.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"角色至少需要一个功能权限");
        jdbc.update("UPDATE da_roles SET name=? WHERE code=?",name,code);
        jdbc.update("DELETE FROM da_role_permissions WHERE role_code=?",code);
        for(String permission:permissions)
            jdbc.update("INSERT INTO da_role_permissions(role_code,permission_code) VALUES(?,?)",code,permission);
        AuditContext.set("target","角色："+code);
        AuditContext.set("detail","角色名称："+name+"；权限："+String.join(",",permissions));
        return Map.of("success",true);
    }

    @DeleteMapping("/roles/{code}")
    @Transactional
    Map<String,Object> deleteRole(@PathVariable String code) {
        code=code.strip().toUpperCase(Locale.ROOT);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT built_in FROM da_roles WHERE code=? FOR UPDATE",code);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"角色不存在");
        if(((Number)rows.getFirst().get("built_in")).intValue()!=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"系统内置角色不能删除");
        Integer users=jdbc.queryForObject("SELECT COUNT(*) FROM da_users WHERE role=?",Integer.class,code);
        if(users!=null && users>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该角色仍有用户，不能删除");
        jdbc.update("DELETE FROM da_role_permissions WHERE role_code=?",code);
        jdbc.update("DELETE FROM da_roles WHERE code=?",code);
        AuditContext.set("target","角色："+code);
        AuditContext.set("detail","删除自定义角色");
        return Map.of("success",true);
    }

    @GetMapping("/users")
    List<Map<String,Object>> users() {
        return jdbc.query("SELECT u.username,u.display_name,u.role,COALESCE(r.name,u.role),u.created_at " +
                "FROM da_users u LEFT JOIN da_roles r ON r.code=u.role ORDER BY u.id",
            (rs,n)->Map.of("username",rs.getString(1),"displayName",rs.getString(2),
                "role",rs.getString(3),"roleName",rs.getString(4),"createdAt",rs.getTimestamp(5).toString()));
    }

    record UserRequest(@NotBlank String username,@NotBlank @Size(max=80) String displayName,
                       @Size(min=10,max=128) String password,@NotBlank String role) {}

    @PostMapping("/users")
    Map<String,Object> createUser(@Valid @RequestBody UserRequest input) {
        AuditContext.set("target","用户："+input.username());
        AuditContext.set("detail","显示名称："+input.displayName()+"；角色："+input.role());
        if(!input.username().matches("[A-Za-z0-9_-]{3,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"账号仅允许3~64位字母、数字、下划线和横线");
        if(!validRole(input.role()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"角色不存在或已停用");
        if(input.password()==null || input.password().length()<10)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"密码至少10位");
        try{
            jdbc.update("INSERT INTO da_users(username,display_name,password_hash,role) VALUES(?,?,?,?)",
                input.username(),input.displayName(),encoder.encode(input.password()),input.role());
        }catch(DuplicateKeyException ex){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"账号已存在");
        }
        return Map.of("success",true);
    }
    record UserEditRequest(@NotBlank String displayName, @NotBlank String role) {}
    record ResetPasswordRequest(@NotBlank String password) {}

    /**
     * An administrator can edit a user's profile and role, but cannot demote
     * their own active account or the last administrator.
     */
    @PutMapping("/users/{username}")
    @Transactional
    Map<String,Object> updateUser(@PathVariable String username,
                                  @Valid @RequestBody UserEditRequest input,
                                  Authentication operator) {
        AuditContext.set("target","用户："+username);
        AuditContext.set("detail","显示名称："+input.displayName()+"；角色："+input.role());
        if (!username.matches("[A-Za-z0-9_-]{3,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号格式不正确");
        if (!validRole(input.role()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色不存在或已停用");
        String displayName = input.displayName().trim();
        if (displayName.isBlank() || displayName.length()>80)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "姓名需要1至80个字符");

        List<Map<String,Object>> row = jdbc.queryForList(
            "SELECT role FROM da_users WHERE username=? FOR UPDATE", username);
        if (row.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        String previousRole=(String) row.getFirst().get("role");
        if (operator.getName().equals(username) && !previousRole.equals(input.role()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能修改自己的角色");
        if ("ADMIN".equals(previousRole) && !"ADMIN".equals(input.role())) {
            // Lock all admin rows to serialize simultaneous demotion attempts.
            List<Map<String,Object>> administrators = jdbc.queryForList(
                "SELECT username FROM da_users WHERE role='ADMIN' ORDER BY username FOR UPDATE");
            if (administrators.size() <= 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "不能取消最后一位管理员的权限");
        }
        jdbc.update("UPDATE da_users SET display_name=?,role=? WHERE username=?",
            displayName, input.role(), username);
        return Map.of("success",true,"message","用户信息已更新");
    }

    /**
     * Reset a password without revealing stored hashes or touching other users.
     * The JWT password stamp rejects existing sessions after the update.
     */
    @PostMapping("/users/{username}/reset-password")
    Map<String,Object> resetPassword(@PathVariable String username,
                                     @Valid @RequestBody ResetPasswordRequest input) {
        if (!username.matches("[A-Za-z0-9_-]{3,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号格式不正确");
        if (input.password().length()<10 || input.password().length()>128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码长度需要10至128位");
        int changed=jdbc.update("UPDATE da_users SET password_hash=? WHERE username=?",
            encoder.encode(input.password()),username);
        if(changed!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"用户不存在");
        return Map.of("success",true,"message","密码已重置，该用户需要重新登录");
    }

}

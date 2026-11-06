package com.dataask.admin;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.StarRocksGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
public class AdminController {
    private final StarRocksGateway starrocks;
    private final RuntimeConfigService settings;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final ObjectMapper json;

    public AdminController(StarRocksGateway starrocks, RuntimeConfigService settings,
            JdbcTemplate jdbc, PasswordEncoder encoder, ObjectMapper json) {
        this.starrocks=starrocks; this.settings=settings;
        this.jdbc=jdbc; this.encoder=encoder; this.json=json;
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
        RuntimeConfigService.StarRocksConfig validated=settings.validateStarRocks(input);
        starrocks.test(validated); // Reject invalid connection before saving.
        settings.saveStarRocks(input);
        return Map.of("success",true,"message","StarRocks 数据源已保存并生效");
    }

    @PostMapping("/settings/ai/test")
    Map<String,Object> testAi(@RequestBody RuntimeConfigService.AiInput input) {
        RuntimeConfigService.AiConfig config=settings.validateAi(input);
        if (config.apiKey().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "尚未读取到 AI API Key，请检查实际运行的 conf/application.yml 中 dataask.ai.api-key");
        try {
            String target=config.baseUrl()+"/chat/completions";
            String body=json.writeValueAsString(Map.of(
                "model",config.model(),
                "messages",List.of(Map.of("role","user","content","仅回答 OK"))));
            HttpRequest request=HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer "+config.apiKey())
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            HttpResponse<String> reply=client.send(request,HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode()<200 || reply.statusCode()>=300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI 模型测试失败，HTTP "+reply.statusCode()+"；请检查 Base URL、模型名及密钥权限");
            }
            if (!reply.body().contains("choices"))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "接口已响应，但不是兼容的 Chat Completions 响应");
            return Map.of("success",true,"message","AI 模型连接成功");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(AdminController.class)
                .warn("AI connection test failed: {}",ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "AI 模型连接失败，请检查地址、网络和模型配置");
        }
    }

    @PostMapping("/settings/ai")
    Map<String,Object> saveAi(@RequestBody RuntimeConfigService.AiInput input) {
        if (settings.ai().apiKey().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "AI API Key 未加载，请先配置 application.yml");
        settings.saveAi(input);
        return Map.of("success",true,
            "message","AI 模型配置已保存，API Key 继续从 application.yml 加载");
    }

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
        boolean exists=starrocks.discover().stream().anyMatch(x->x.name().equals(request.tableName()));
        if(!exists) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"指定 ADS 表不存在");
        jdbc.update("INSERT INTO da_allowed_tables(table_name,enabled) VALUES(?,?) "+
            "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)",
            request.tableName(),request.enabled()?1:0);
        return Map.of("success",true);
    }

    @GetMapping("/users")
    List<Map<String,Object>> users() {
        return jdbc.query("SELECT username,display_name,role,created_at FROM da_users ORDER BY id",
            (rs,n)->Map.of("username",rs.getString(1),"displayName",rs.getString(2),
                "role",rs.getString(3),"createdAt",rs.getTimestamp(4).toString()));
    }

    record UserRequest(@NotBlank String username,@NotBlank String displayName,
                       @Size(min=10) String password,@NotBlank String role) {}

    @PostMapping("/users")
    Map<String,Object> createUser(@Valid @RequestBody UserRequest input) {
        if(!input.username().matches("[A-Za-z0-9_-]{3,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"账号仅允许3~64位字母、数字、下划线和横线");
        if(!Set.of("ADMIN","USER").contains(input.role()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"角色只能是 ADMIN 或 USER");
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
}

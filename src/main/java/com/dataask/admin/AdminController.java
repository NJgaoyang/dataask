package com.dataask.admin;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.StarRocksGateway;
import com.dataask.semantic.JoinRuleService;
import com.dataask.semantic.EmbeddingSemanticService;
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
    private final JoinRuleService joinRules;
    private final EmbeddingSemanticService embeddings;

    public AdminController(StarRocksGateway starrocks, RuntimeConfigService settings,
            JdbcTemplate jdbc, PasswordEncoder encoder, ObjectMapper json,
            JoinRuleService joinRules, EmbeddingSemanticService embeddings) {
        this.starrocks=starrocks; this.settings=settings;
        this.jdbc=jdbc; this.encoder=encoder; this.json=json;
        this.joinRules=joinRules;
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
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "模型测试失败，HTTP "+reply.statusCode()+"；请检查接口、Key 与模型权限");
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

    @GetMapping("/join-rules")
    List<JoinRuleService.Rule> listJoinRules() {
        return joinRules.all();
    }

    @PostMapping("/join-rules")
    Map<String,Object> createJoinRule(@RequestBody JoinRuleService.JoinInput input) {
        Set<String> allowed = new HashSet<>(jdbc.query(
            "SELECT table_name FROM da_allowed_tables WHERE enabled=1",
            (rs,n)->rs.getString(1)));
        long id = joinRules.create(input, allowed);
        return Map.of("success", true, "id", id);
    }

    @DeleteMapping("/join-rules/{id}")
    Map<String,Object> deleteJoinRule(@PathVariable long id) {
        joinRules.delete(id);
        return Map.of("success",true);
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
        throw new ResponseStatusException(HttpStatus.GONE,
            "整表授权入口已停用，请在数据库 / 表 / 字段页面明确选择字段后保存");
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

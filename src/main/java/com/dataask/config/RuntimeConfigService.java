package com.dataask.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

@Service
public class RuntimeConfigService {
    public record StarRocksConfig(String jdbcUrl, String username, String password, String schema) {
        public boolean configured() {
            return !jdbcUrl.isBlank() && !username.isBlank() && !schema.isBlank();
        }
    }
    public record AiConfig(String baseUrl, String apiKey, String model) {
        public boolean configured() {
            return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
        }
    }
    public record StarRocksInput(String jdbcUrl, String username, String password, String schema) {}
    public record ModelInput(String baseUrl, String model, String apiKey, boolean clearKey) {}
    public record EmbeddingInput(String baseUrl, String model, String apiKey, boolean clearKey,
                                 boolean enabled, int dimensions) {}
    public record EmbeddingConfig(String baseUrl, String apiKey, String model,
                                  boolean enabled, int dimensions) {
        public boolean configured() { return enabled && !baseUrl.isBlank() && !model.isBlank(); }
    }

    private final JdbcTemplate jdbc;
    private final StarRocksConfig yamlStarRocks;
    private final AiConfig yamlAi;
    private final String jwtSecret;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec encryptionKey;

    public RuntimeConfigService(JdbcTemplate jdbc,
            @Value("${dataask.jwt-secret:}") String jwtSecret,
            @Value("${dataask.starrocks.jdbc-url:}") String srUrl,
            @Value("${dataask.starrocks.username:}") String srUser,
            @Value("${dataask.starrocks.password:}") String srPassword,
            @Value("${dataask.starrocks.schema:ads}") String srSchema,
            @Value("${dataask.ai.base-url:}") String aiUrl,
            @Value("${dataask.ai.api-key:}") String aiKey,
            @Value("${dataask.ai.model:}") String aiModel) {
        this.jdbc = jdbc;
        this.jwtSecret = jwtSecret;
        this.yamlStarRocks = new StarRocksConfig(safe(srUrl),safe(srUser),
                srPassword == null ? "" : srPassword,safe(srSchema));
        this.yamlAi = new AiConfig(safe(aiUrl), aiKey == null ? "" : aiKey, safe(aiModel));
    }

    @PostConstruct
    void initializeEncryption() {
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("必须配置至少32字节的 JWT 密钥");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("dataask.runtime.password.v1:".getBytes(StandardCharsets.UTF_8));
            encryptionKey = new SecretKeySpec(digest.digest(jwtSecret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception ex) {
            throw new IllegalStateException("初始化配置加密失败", ex);
        }
    }

    private static String safe(String text) { return text == null ? "" : text.trim(); }
    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }

    private Map<String,String> stored() {
        Map<String,String> values = new HashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM da_app_settings",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                values.put(rs.getString(1),rs.getString(2));
            });
        return values;
    }

    private String effective(Map<String,String> map, String name, String fallback) {
        return map.getOrDefault(name,fallback);
    }
    public StarRocksConfig starRocks() {
        Map<String,String> m=stored();
        return new StarRocksConfig(
            effective(m,"sr.jdbcUrl",yamlStarRocks.jdbcUrl()),
            effective(m,"sr.username",yamlStarRocks.username()),
            m.containsKey("sr.password") ? decrypt(m.get("sr.password")) : yamlStarRocks.password(),
            effective(m,"sr.schema",yamlStarRocks.schema()));
    }
    public AiConfig ai() {
        Map<String,String> m=stored();
        return new AiConfig(effective(m,"ai.baseUrl",yamlAi.baseUrl()),
            m.containsKey("ai.apiKey") ? decrypt(m.get("ai.apiKey")) : yamlAi.apiKey(),
            effective(m,"ai.model",yamlAi.model()));
    }

    public EmbeddingConfig embedding() {
        Map<String,String> m=stored();
        return new EmbeddingConfig(
            m.getOrDefault("embedding.baseUrl","https://dashscope.aliyuncs.com/api/v1"),
            m.containsKey("embedding.apiKey") ? decrypt(m.get("embedding.apiKey")) : "",
            m.getOrDefault("embedding.model","qwen3.7-text-embedding-flash"),
            Boolean.parseBoolean(m.getOrDefault("embedding.enabled","false")),
            parseDimensions(m.get("embedding.dimensions")));
    }

    private static int parseDimensions(String text) {
        try {
            int dimension=Integer.parseInt(text);
            return dimension >= 64 && dimension <= 4096 ? dimension : 1024;
        } catch (Exception ignored) { return 1024; }
    }

    private static String validateModelUrl(String raw) {
        String url=safe(raw).replaceAll("/+$","");
        if (url.length()>500) throw invalid("模型 Base URL 过长");
        try {
            java.net.URI parsed=java.net.URI.create(url);
            if (!(parsed.getScheme().equalsIgnoreCase("http") || parsed.getScheme().equalsIgnoreCase("https")) ||
                parsed.getHost()==null || parsed.getUserInfo()!=null || parsed.getFragment()!=null ||
                parsed.getRawQuery()!=null) throw invalid("模型地址必须是 HTTP(S) Base URL");
            return url;
        } catch (Exception ex) {
            throw invalid("模型 Base URL 格式不正确");
        }
    }

    private static String validateModelName(String text) {
        String name=safe(text);
        if (name.isBlank() || name.length()>180 || name.contains("\n") || name.contains("\r"))
            throw invalid("请输入有效的模型名称");
        return name;
    }

    public AiConfig candidateAi(ModelInput input) {
        if (input==null) throw invalid("模型配置不能为空");
        AiConfig current=ai();
        String key=input.clearKey() ? "" :
            (input.apiKey()==null || input.apiKey().isBlank() ? current.apiKey() : input.apiKey().trim());
        if (key.length()>4096) throw invalid("API Key 过长");
        return new AiConfig(validateModelUrl(input.baseUrl()),key,validateModelName(input.model()));
    }

    public EmbeddingConfig candidateEmbedding(EmbeddingInput input) {
        if (input==null) throw invalid("Embedding 配置不能为空");
        EmbeddingConfig current=embedding();
        String key=input.clearKey() ? "" :
            (input.apiKey()==null || input.apiKey().isBlank() ? current.apiKey() : input.apiKey().trim());
        if (key.length()>4096) throw invalid("API Key 过长");
        if (!input.enabled()) {
            return new EmbeddingConfig(safe(input.baseUrl()),key,safe(input.model()),false,
                input.dimensions()>0 ? input.dimensions() : 1024);
        }
        int dimension=input.dimensions();
        if (dimension<64 || dimension>4096) throw invalid("向量维度必须介于 64 和 4096");
        return new EmbeddingConfig(validateModelUrl(input.baseUrl()),key,
            validateModelName(input.model()),true,dimension);
    }

    @Transactional
    public void saveAi(ModelInput input) {
        AiConfig config=candidateAi(input);
        upsert("ai.baseUrl",config.baseUrl());
        upsert("ai.model",config.model());
        if (input.clearKey() || (input.apiKey()!=null && !input.apiKey().isBlank()))
            upsert("ai.apiKey",encrypt(config.apiKey()));
    }

    @Transactional
    public void saveEmbedding(EmbeddingInput input) {
        EmbeddingConfig config=candidateEmbedding(input);
        upsert("embedding.baseUrl",config.baseUrl());
        upsert("embedding.model",config.model());
        upsert("embedding.enabled",Boolean.toString(config.enabled()));
        upsert("embedding.dimensions",Integer.toString(config.dimensions()));
        if (input.clearKey() || (input.apiKey()!=null && !input.apiKey().isBlank()))
            upsert("embedding.apiKey",encrypt(config.apiKey()));
    }

    public StarRocksConfig validateStarRocks(StarRocksInput data) {
        if (data==null) throw invalid("缺少数据源配置");
        StarRocksConfig previous=starRocks();
        String url=safe(data.jdbcUrl());
        String username=safe(data.username());
        String schema=safe(data.schema());
        String pass=(data.password()==null || data.password().isBlank())
                ? previous.password() : data.password();
        // Use a simple JDBC URL; untrusted JDBC parameters must not be accepted.
        if (!url.matches("jdbc:mysql://[A-Za-z0-9.-]+:[0-9]{1,5}/[A-Za-z_][A-Za-z0-9_]*")) {
            throw invalid("JDBC 地址应为 jdbc:mysql://FE地址:9030/ads，不允许额外参数");
        }
        if (username.isBlank() || username.length()>120) throw invalid("请填写 StarRocks 用户名");
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw invalid("ADS 数据库名不正确");
        if (pass.length()>500) throw invalid("密码过长");
        return new StarRocksConfig(url,username,pass,schema);
    }

    @Transactional
    public void saveStarRocks(StarRocksInput data) {
        StarRocksConfig c=validateStarRocks(data);
        upsert("sr.jdbcUrl",c.jdbcUrl());
        upsert("sr.username",c.username());
        upsert("sr.schema",c.schema());
        if (data.password()!=null && !data.password().isBlank()) {
            upsert("sr.password",encrypt(c.password()));
        }
    }
    private void upsert(String name,String value) {
        jdbc.update("INSERT INTO da_app_settings (setting_key,setting_value) VALUES(?,?) " +
            "ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)",name,value);
    }

    public Map<String,Object> adminView() {
        StarRocksConfig sr=starRocks();
        AiConfig ai=ai();
        EmbeddingConfig embedding=embedding();
        return Map.of(
            "starrocks", Map.of("jdbcUrl",sr.jdbcUrl(),"username",sr.username(),
                "schema",sr.schema(),"passwordSet",!sr.password().isBlank()),
            "ai", Map.of("baseUrl",ai.baseUrl(),"model",ai.model(),
                "apiKeySet",!ai.apiKey().isBlank()),
            "embedding", Map.of("baseUrl",embedding.baseUrl(),"model",embedding.model(),
                "apiKeySet",!embedding.apiKey().isBlank(),
                "enabled",embedding.enabled(),"dimensions",embedding.dimensions()));
    }
    public Map<String,Object> status() {
        StarRocksConfig sr=starRocks();
        AiConfig ai=ai();
        EmbeddingConfig embedding=embedding();
        return Map.ofEntries(
            Map.entry("starrocksConfigured",sr.configured()),Map.entry("schema",sr.schema()),
            Map.entry("aiConfigured",ai.configured()),Map.entry("model",ai.model()),
            Map.entry("aiKeyConfigured",!ai.apiKey().isBlank()),
            Map.entry("aiUrlConfigured",!ai.baseUrl().isBlank()),
            Map.entry("aiModelConfigured",!ai.model().isBlank()),
            Map.entry("embeddingConfigured",embedding.configured()),
            Map.entry("embeddingEnabled",embedding.enabled()),
            Map.entry("embeddingModel",embedding.model()));
    }
    private String encrypt(String plain) {
        try {
            byte[] iv=new byte[12];random.nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,encryptionKey,new GCMParameterSpec(128,iv));
            byte[] payload=cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed=new byte[iv.length+payload.length];
            System.arraycopy(iv,0,packed,0,iv.length);
            System.arraycopy(payload,0,packed,iv.length,payload.length);
            return "v1:"+Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) { throw new IllegalStateException("数据源密码加密失败",e); }
    }
    private String decrypt(String value) {
        try {
            if (value==null || !value.startsWith("v1:")) throw new IllegalStateException("密码数据格式无效");
            byte[] packed=Base64.getDecoder().decode(value.substring(3));
            if (packed.length<29) throw new IllegalStateException("密码数据无效");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,encryptionKey,
                new GCMParameterSpec(128,Arrays.copyOfRange(packed,0,12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(packed,12,packed.length)),StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("无法解密已保存的数据源密码，请检查 JWT 密钥是否被更换",e);
        }
    }
}

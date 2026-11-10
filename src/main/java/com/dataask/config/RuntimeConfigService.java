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
        String schema=effective(m,"sr.schema",yamlStarRocks.schema());
        return new StarRocksConfig(
            normalizeStarRocksUrl(effective(m,"sr.jdbcUrl",yamlStarRocks.jdbcUrl()),schema),
            effective(m,"sr.username",yamlStarRocks.username()),
            m.containsKey("sr.password") ? decrypt(m.get("sr.password")) : yamlStarRocks.password(),
            schema);
    }
    public static String normalizeStarRocksUrl(String raw, String schema) {
        String url=safe(raw);
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
            throw invalid("StarRocks 数据库名不正确");
        if (url.startsWith("jdbc:starrocks://")) {
            // This project ships MySQL Connector/J, not a StarRocks-specific JDBC driver.
            // Convert the user's configured FE address to the MySQL wire protocol.
            String target=url.substring("jdbc:starrocks://".length());
            if (target.matches("[A-Za-z0-9.\\-]+:[0-9]{1,5}"))
                return "jdbc:mysql://"+target+"/"+schema;
            if (target.matches("[A-Za-z0-9.\\-]+:[0-9]{1,5}/[A-Za-z_][A-Za-z0-9_]*"))
                return "jdbc:mysql://"+target;
            throw invalid("StarRocks JDBC 地址格式不正确");
        }
        return url;
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
        // An explicit replacement/clear must be possible even when the prior key
        // cannot be decrypted (for example after an operator rotated the secret).
        String key=input.clearKey() ? "" :
            (input.apiKey()==null || input.apiKey().isBlank() ? ai().apiKey() : input.apiKey().trim());
        if (key.length()>4096) throw invalid("API Key 过长");
        return new AiConfig(validateModelUrl(input.baseUrl()),key,validateModelName(input.model()));
    }

    public EmbeddingConfig candidateEmbedding(EmbeddingInput input) {
        if (input==null) throw invalid("Embedding 配置不能为空");
        String key=input.clearKey() ? "" :
            (input.apiKey()==null || input.apiKey().isBlank() ? embedding().apiKey() : input.apiKey().trim());
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
        String username=safe(data.username());
        String schema=safe(data.schema());
        String url=normalizeStarRocksUrl(data.jdbcUrl(),schema);
        // If a replacement password is supplied, never try to decrypt a
        // corrupted existing credential before allowing the repair.
        String pass=(data.password()==null || data.password().isBlank())
                ? starRocks().password() : data.password();
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

    private record CredentialHealth(boolean set, boolean unreadable) {}
    /**
     * Admin endpoints must remain accessible if *one* encrypted credential is
     * broken. Never return or log secret values; report only a diagnostic flag.
     * Runtime use still strictly decrypts credentials and fails closed.
     */
    private CredentialHealth credentialHealth(Map<String,String> saved,
                                              String key, String yamlFallback) {
        if (!saved.containsKey(key)) return new CredentialHealth(!yamlFallback.isBlank(),false);
        try {
            return new CredentialHealth(!decrypt(saved.get(key)).isBlank(),false);
        } catch (IllegalStateException ex) {
            return new CredentialHealth(false,true);
        }
    }
    private static boolean configuredStarRocks(String url, String user, String schema,
                                               CredentialHealth password) {
        return !url.isBlank() && !user.isBlank() &&
               !schema.isBlank() && !password.unreadable();
    }

    public Map<String,Object> adminView() {
        // Read persisted settings once and assess each credential separately;
        // a damaged AI key must not prevent opening StarRocks and vice versa.
        Map<String,String> saved=stored();
        CredentialHealth sr=credentialHealth(saved,"sr.password",yamlStarRocks.password());
        CredentialHealth ai=credentialHealth(saved,"ai.apiKey",yamlAi.apiKey());
        CredentialHealth emb=credentialHealth(saved,"embedding.apiKey","");
        String rawDimensions=saved.get("embedding.dimensions");
        return Map.of(
            "starrocks", Map.of(
                "jdbcUrl",safe(effective(saved,"sr.jdbcUrl",yamlStarRocks.jdbcUrl())),
                "username",safe(effective(saved,"sr.username",yamlStarRocks.username())),
                "schema",safe(effective(saved,"sr.schema",yamlStarRocks.schema())),
                "passwordSet",sr.set(),"passwordUnreadable",sr.unreadable()),
            "ai", Map.of(
                "baseUrl",safe(effective(saved,"ai.baseUrl",yamlAi.baseUrl())),
                "model",safe(effective(saved,"ai.model",yamlAi.model())),
                "apiKeySet",ai.set(),"apiKeyUnreadable",ai.unreadable()),
            "embedding", Map.of(
                "baseUrl",safe(saved.getOrDefault("embedding.baseUrl","https://dashscope.aliyuncs.com/api/v1")),
                "model",safe(saved.getOrDefault("embedding.model","qwen3.7-text-embedding-flash")),
                "apiKeySet",emb.set(),"apiKeyUnreadable",emb.unreadable(),
                "enabled",Boolean.parseBoolean(saved.getOrDefault("embedding.enabled","false")),
                "dimensions",parseDimensions(rawDimensions)));
    }

    public Map<String,Object> status() {
        Map<String,String> saved=stored();
        CredentialHealth sr=credentialHealth(saved,"sr.password",yamlStarRocks.password());
        CredentialHealth ai=credentialHealth(saved,"ai.apiKey",yamlAi.apiKey());
        CredentialHealth emb=credentialHealth(saved,"embedding.apiKey","");
        String schema=safe(effective(saved,"sr.schema",yamlStarRocks.schema()));
        String srUrl=safe(effective(saved,"sr.jdbcUrl",yamlStarRocks.jdbcUrl()));
        String srUser=safe(effective(saved,"sr.username",yamlStarRocks.username()));
        String aiBase=safe(effective(saved,"ai.baseUrl",yamlAi.baseUrl()));
        String aiModel=safe(effective(saved,"ai.model",yamlAi.model()));
        String embBase=safe(saved.getOrDefault("embedding.baseUrl","https://dashscope.aliyuncs.com/api/v1"));
        String embModel=safe(saved.getOrDefault("embedding.model","qwen3.7-text-embedding-flash"));
        boolean embeddingEnabled=Boolean.parseBoolean(saved.getOrDefault("embedding.enabled","false"));
        return Map.ofEntries(
            Map.entry("starrocksConfigured",configuredStarRocks(srUrl,srUser,schema,sr)),
            Map.entry("schema",schema),
            Map.entry("starrocksCredentialUnreadable",sr.unreadable()),
            Map.entry("aiConfigured",!aiBase.isBlank() && !aiModel.isBlank() && ai.set()),
            Map.entry("model",aiModel),
            Map.entry("aiKeyConfigured",ai.set()),
            Map.entry("aiCredentialUnreadable",ai.unreadable()),
            Map.entry("aiUrlConfigured",!aiBase.isBlank()),
            Map.entry("aiModelConfigured",!aiModel.isBlank()),
            Map.entry("embeddingConfigured",embeddingEnabled && !embBase.isBlank() &&
                !embModel.isBlank() && !emb.unreadable()),
            Map.entry("embeddingEnabled",embeddingEnabled),
            Map.entry("embeddingModel",embModel),
            Map.entry("embeddingCredentialUnreadable",emb.unreadable()));
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
            // AES-GCM: 12-byte IV + 16-byte authentication tag is valid for empty plaintext.
            // clearKey saves an encrypted empty string: its packed length is exactly 28.
            if (packed.length<28) throw new IllegalStateException("密码数据无效");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,encryptionKey,
                new GCMParameterSpec(128,Arrays.copyOfRange(packed,0,12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(packed,12,packed.length)),StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("无法解密已保存的数据源密码，请检查 JWT 密钥是否被更换",e);
        }
    }
}

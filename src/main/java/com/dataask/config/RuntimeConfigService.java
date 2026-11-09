package com.dataask.config;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class RuntimeConfigService {
    public record StarRocksConfig(String jdbcUrl, String username, String password, String schema) {
        public boolean configured() { return !jdbcUrl.isBlank() && !username.isBlank(); }
    }
    public record AiConfig(String baseUrl, String apiKey, String model) {
        public boolean configured() { return !baseUrl.isBlank() && !model.isBlank() && (!requiresKey(baseUrl) || !apiKey.isBlank()); }
    }
    public record StarRocksInput(String jdbcUrl, String username, String password, String schema) {}
    public record ModelInput(String baseUrl, String model, String apiKey, boolean clearKey) {}
    public record EmbeddingInput(String baseUrl, String model, String apiKey, boolean clearKey,
                                 boolean enabled, int dimensions) {}
    public record EmbeddingConfig(String baseUrl, String apiKey, String model, boolean enabled, int dimensions) {
        public boolean configured() { return enabled && !baseUrl.isBlank() && !model.isBlank() && (!requiresKey(baseUrl) || !apiKey.isBlank()); }
    }

    private static boolean requiresKey(String baseUrl) {
        String host;
        try { host = Objects.toString(URI.create(baseUrl).getHost(), "").toLowerCase(Locale.ROOT); }
        catch (Exception ignored) { return true; }
        return host.equals("api.deepseek.com") || host.endsWith(".aliyuncs.com");
    }
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;

    public RuntimeConfigService(JdbcTemplate jdbc, InstallationSettings installation) {
        this.jdbc = jdbc;
        this.cipher = new CredentialCipher(installation.signingKey());
    }

    private static String safe(String text) { return text == null ? "" : text.trim(); }
    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private Map<String,String> stored() {
        Map<String,String> values = new HashMap<>();
        jdbc.query("SELECT setting_key,setting_value FROM da_app_settings",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> values.put(rs.getString(1), rs.getString(2)));
        return values;
    }
    private String secret(Map<String,String> values, String key) {
        return values.containsKey(key) ? cipher.decrypt(values.get(key)) : "";
    }
    public StarRocksConfig starRocks() {
        Map<String,String> m = stored();
        String schema = m.getOrDefault("sr.schema", "ads");
        return new StarRocksConfig(normalizeStarRocksUrl(m.getOrDefault("sr.jdbcUrl", ""), schema),
            m.getOrDefault("sr.username", ""), secret(m, "sr.password"), schema);
    }
    public static String normalizeStarRocksUrl(String raw, String schema) {
        String url = safe(raw);
        if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw invalid("数据库名不正确");
        if (url.startsWith("jdbc:starrocks://")) {
            String target = url.substring("jdbc:starrocks://".length());
            if (target.matches("[A-Za-z0-9.-]+:[0-9]{1,5}")) return "jdbc:mysql://" + target + "/" + schema;
            if (target.matches("[A-Za-z0-9.-]+:[0-9]{1,5}/[A-Za-z_][A-Za-z0-9_]*")) return "jdbc:mysql://" + target;
            throw invalid("StarRocks JDBC 地址格式不正确");
        }
        return url;
    }
    public static String sourceKey(StarRocksConfig config) {
        try {
            byte[] bytes = (config.jdbcUrl() + "\n" + config.username()).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public AiConfig ai() {
        Map<String,String> m = stored();
        return new AiConfig(m.getOrDefault("ai.baseUrl", ""), secret(m, "ai.apiKey"), m.getOrDefault("ai.model", ""));
    }
    public EmbeddingConfig embedding() {
        Map<String,String> m = stored();
        int size;
        try { size = Integer.parseInt(m.getOrDefault("embedding.dimensions", "1024")); }
        catch (NumberFormatException e) { size = 1024; }
        return new EmbeddingConfig(m.getOrDefault("embedding.baseUrl", ""), secret(m, "embedding.apiKey"),
            m.getOrDefault("embedding.model", ""), Boolean.parseBoolean(m.getOrDefault("embedding.enabled", "false")), size);
    }
    private static String validateModelUrl(String raw) {
        String url = safe(raw).replaceAll("/+$", "");
        try {
            URI u = URI.create(url);
            if (url.length() > 500 || !("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())) ||
                u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null || u.getRawQuery() != null)
                throw invalid("请输入 HTTP(S) Base URL，不能包含账号、查询参数或片段");
            return url;
        } catch (IllegalArgumentException e) { throw invalid("模型 Base URL 格式不正确"); }
    }
    private static String modelName(String name) {
        name = safe(name);
        if (name.isEmpty() || name.length() > 180 || name.contains("\n") || name.contains("\r"))
            throw invalid("请输入有效的模型名称");
        return name;
    }
    private static String key(String input, boolean clear, String old) {
        String value = clear ? "" : safe(input).isEmpty() ? old : input.trim();
        if (value.length() > 4096 || value.contains("\r") || value.contains("\n")) throw invalid("API Key 格式不正确");
        return value;
    }
    public AiConfig candidateAi(ModelInput input) {
        if (input == null) throw invalid("模型配置不能为空");
        return new AiConfig(validateModelUrl(input.baseUrl()), key(input.apiKey(), input.clearKey(), ai().apiKey()), modelName(input.model()));
    }
    public EmbeddingConfig candidateEmbedding(EmbeddingInput input) {
        if (input == null) throw invalid("Embedding 配置不能为空");
        String apiKey = key(input.apiKey(), input.clearKey(), embedding().apiKey());
        if (input.dimensions() < 64 || input.dimensions() > 4096) throw invalid("向量维度必须在64～4096之间");
        return new EmbeddingConfig(input.enabled() ? validateModelUrl(input.baseUrl()) : safe(input.baseUrl()),
            apiKey, input.enabled() ? modelName(input.model()) : safe(input.model()), input.enabled(), input.dimensions());
    }
    public StarRocksConfig validateStarRocks(StarRocksInput input) {
        if (input == null) throw invalid("缺少数据源配置");
        String schema = safe(input.schema()).isEmpty() ? "ads" : safe(input.schema());
        String url = normalizeStarRocksUrl(input.jdbcUrl(), schema);
        // FE connection may omit a default database: database choice is a subsequent UI step.
        if (!url.matches("jdbc:mysql://[A-Za-z0-9.-]+:[0-9]{1,5}/(?:[A-Za-z_][A-Za-z0-9_]*)?"))
            throw invalid("连接地址格式：jdbc:mysql://FE地址:9030/，也可附加默认数据库名");
        if (safe(input.username()).isEmpty() || safe(input.username()).length() > 120) throw invalid("请填写用户名");
        String password = input.password() == null || input.password().isBlank() ? starRocks().password() : input.password();
        if (password.length() > 500) throw invalid("密码过长");
        return new StarRocksConfig(url, safe(input.username()), password, schema);
    }
    @Transactional
    public void saveStarRocks(StarRocksInput input) {
        lockSettings();
        StarRocksConfig old = starRocks(), config = validateStarRocks(input);
        if (!sourceKey(old).equals(sourceKey(config))) {
            // Never silently grant a different server access to the former server's tables.
            jdbc.update("DELETE FROM da_app_settings WHERE setting_key='catalog.policy'");
            jdbc.update("UPDATE da_allowed_tables SET enabled=0");
            jdbc.update("DELETE FROM da_ads_join_rules");
            jdbc.update("DELETE FROM da_semantic_vectors");
        }
        upsert("sr.jdbcUrl", config.jdbcUrl());
        upsert("sr.username", config.username());
        upsert("sr.schema", config.schema());
        upsert("sr.password", cipher.encrypt(config.password()));
    }
    @Transactional
    public void saveAi(ModelInput input) {
        lockSettings();
        AiConfig config = candidateAi(input);
        upsert("ai.baseUrl", config.baseUrl());
        upsert("ai.model", config.model());
        upsert("ai.apiKey", cipher.encrypt(config.apiKey()));
    }
    @Transactional
    public void saveEmbedding(EmbeddingInput input) {
        lockSettings();
        EmbeddingConfig old = embedding(), config = candidateEmbedding(input);
        if (!old.baseUrl().equals(config.baseUrl()) || !old.model().equals(config.model()) || old.dimensions() != config.dimensions())
            jdbc.update("DELETE FROM da_semantic_vectors");
        upsert("embedding.baseUrl", config.baseUrl());
        upsert("embedding.model", config.model());
        upsert("embedding.apiKey", cipher.encrypt(config.apiKey()));
        upsert("embedding.enabled", Boolean.toString(config.enabled()));
        upsert("embedding.dimensions", Integer.toString(config.dimensions()));
    }
    private void lockSettings() {
        jdbc.queryForObject("SELECT setting_value FROM da_app_settings WHERE setting_key='system.bootstrap.lock' FOR UPDATE", String.class);
    }
    private void upsert(String name, String value) {
        jdbc.update("INSERT INTO da_app_settings(setting_key,setting_value) VALUES(?,?) " +
            "ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)", name, value);
    }
    public Map<String,Object> adminView() {
        StarRocksConfig sr = starRocks(); AiConfig ai = ai(); EmbeddingConfig embedding = embedding();
        return Map.of("storage", "MYSQL", "starrocks", Map.of("jdbcUrl", sr.jdbcUrl(), "username", sr.username(),
            "schema", sr.schema(), "passwordSet", !sr.password().isBlank()),
            "ai", Map.of("baseUrl", ai.baseUrl(), "model", ai.model(), "apiKeySet", !ai.apiKey().isBlank()),
            "embedding", Map.of("baseUrl", embedding.baseUrl(), "model", embedding.model(),
                "apiKeySet", !embedding.apiKey().isBlank(), "enabled", embedding.enabled(), "dimensions", embedding.dimensions()));
    }
    public Map<String,Object> status() {
        StarRocksConfig sr = starRocks(); AiConfig ai = ai(); EmbeddingConfig em = embedding();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("starrocksConfigured", sr.configured()); out.put("schema", sr.schema());
        out.put("aiConfigured", ai.configured()); out.put("model", ai.model());
        out.put("aiKeyConfigured", !ai.apiKey().isBlank()); out.put("aiUrlConfigured", !ai.baseUrl().isBlank());
        out.put("aiModelConfigured", !ai.model().isBlank()); out.put("embeddingConfigured", em.configured());
        out.put("embeddingEnabled", em.enabled()); out.put("embeddingModel", em.model()); out.put("storage", "MYSQL");
        return out;
    }
}

package com.dataask.semantic;

import com.dataask.config.RuntimeConfigService;
import com.dataask.data.StarRocksGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class EmbeddingSemanticService {
    private static final int MAX_DOCS = 120;
    private static final int BATCH_SIZE = 20;
    private static final int MAX_TEXT = 2500;
    private final RuntimeConfigService settings;
    private final JdbcTemplate jdbc;
    private final StarRocksGateway starrocks;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8)).build();

    public EmbeddingSemanticService(RuntimeConfigService settings, JdbcTemplate jdbc,
            StarRocksGateway starrocks, ObjectMapper mapper,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.settings=settings;
        this.jdbc=jdbc;
        this.starrocks=starrocks;
        this.mapper=mapper;
        this.tx=new TransactionTemplate(manager);
    }

    private record Document(String type, String key, String text) {}
    private record VectorDocument(Document document, double[] vector) {}
    public record Match(String type, String source, String text, double score) {}

    public Map<String,Object> status() {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM da_semantic_vectors",Integer.class);
        String model=jdbc.query("SELECT model_name FROM da_semantic_vectors ORDER BY id DESC LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : "");
        RuntimeConfigService.EmbeddingConfig config=settings.embedding();
        return Map.of("enabled",config.enabled(),"configured",config.configured(),
            "model",config.model(),"indexedModel",model,"count",count==null?0:count);
    }

    public Map<String,Object> test(RuntimeConfigService.EmbeddingConfig config) {
        if (!config.configured())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "请先开启 Embedding 并填写 Base URL 和模型名称");
        double[][] matrix=embed(config,List.of("测试中文经营数据语义检索"));
        return Map.of("success",true,"message","Embedding 模型连接成功",
            "dimensions",matrix[0].length);
    }

    static boolean isDashScopeNative(String baseUrl) {
        try {
            URI uri=URI.create(baseUrl);
            String hostname=uri.getHost();
            String path=uri.getPath();
            return hostname!=null && (hostname.equals("dashscope.aliyuncs.com")
                    || hostname.endsWith(".maas.aliyuncs.com"))
                && path!=null && path.replaceAll("/+$","").endsWith("/api/v1");
        } catch (Exception ignored) {
            return false;
        }
    }

    private double[][] embed(RuntimeConfigService.EmbeddingConfig cfg, List<String> input) {
        if (!cfg.configured()) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,"尚未配置 Embedding 模型");
        try {
            boolean nativeDashScope=isDashScopeNative(cfg.baseUrl());
            String base=cfg.baseUrl().replaceAll("/+$","");
            String endpoint=nativeDashScope
                ? base+"/services/embeddings/text-embedding/text-embedding" : base+"/embeddings";
            String requestBody=nativeDashScope
                ? mapper.writeValueAsString(Map.of(
                    "model",cfg.model(),
                    "input",Map.of("texts",input),
                    "parameters",Map.of("dimension",cfg.dimensions(),"output_type","dense")))
                : mapper.writeValueAsString(Map.of(
                    "model",cfg.model(),"input",input,"dimensions",cfg.dimensions()));
            HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/json");
            if (!cfg.apiKey().isBlank())
                request.header("Authorization","Bearer "+cfg.apiKey());
            HttpResponse<String> reply=http.send(
                request.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode()<200 || reply.statusCode()>=300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Embedding 服务返回 HTTP "+reply.statusCode()+"，请检查 URL、Key 或模型名称");
            }
            JsonNode result=mapper.readTree(reply.body());
            JsonNode response=nativeDashScope
                ? result.path("output").path("embeddings")
                : result.path("data");
            if (!response.isArray() || response.size()!=input.size())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Embedding 服务未返回对应数量的向量");
            double[][] output=new double[input.size()][];
            for (int n=0;n<response.size();n++) {
                JsonNode item=response.get(n);
                int idx=nativeDashScope
                    ? item.path("text_index").asInt(-1)
                    : item.has("index") ? item.path("index").asInt(-1) : n;
                if (idx<0 || idx>=input.size() || output[idx]!=null)
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Embedding 返回的索引无效");
                JsonNode vec=item.path("embedding");
                if (!vec.isArray() || vec.size()!=cfg.dimensions())
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "向量维度不一致：模型返回 "+vec.size()+"，配置为 "+cfg.dimensions());
                double[] values=new double[vec.size()];
                for(int i=0;i<values.length;i++) {
                    if (!vec.get(i).isNumber()) throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,"Embedding 向量包含非数字值");
                    double v=vec.get(i).asDouble();
                    if (!Double.isFinite(v)) throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,"Embedding 向量包含非法数值");
                    values[i]=v;
                }
                output[idx]=values;
            }
            return output;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                "Embedding call failed: {}", ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Embedding 请求失败，请检查服务网络连接或模型接口");
        }
    }

    public synchronized Map<String,Object> rebuild() {
        RuntimeConfigService.EmbeddingConfig cfg=settings.embedding();
        if (!cfg.configured())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "请先在 Web 模型管理中启用并保存 Embedding 配置");
        Set<String> allowed=new HashSet<>(jdbc.query(
            "SELECT table_name FROM da_allowed_tables WHERE enabled=1",
            (rs,n)->rs.getString(1)));
        if (allowed.isEmpty()) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,"请先在 ADS 表管理中授权至少一张表");
        List<Document> documents=new ArrayList<>();
        List<StarRocksGateway.TableMeta> tables=starrocks.discover();
        for (StarRocksGateway.TableMeta table : tables) {
            if (!allowed.contains(table.name())) continue;
            StringBuilder sb=new StringBuilder("ADS 表 ").append(table.name())
                .append("；表字段及中文注释：");
            for(StarRocksGateway.ColumnMeta column:table.columns()) {
                sb.append(" ").append(column.name()).append("（")
                  .append(column.comment()).append("；").append(column.type()).append("）");
                if(sb.length()>MAX_TEXT) break;
            }
            documents.add(new Document("ADS_TABLE",table.name(),truncate(sb.toString())));
            if(documents.size()>=MAX_DOCS/2) break;
        }

        List<Map<String,Object>> metricRows=jdbc.queryForList(
            "SELECT id,metric_name,aliases,definition,ads_table,ads_field,dimensions " +
            "FROM da_metric_definitions ORDER BY id DESC LIMIT 100");
        for (Map<String,Object> row : metricRows) {
            String table=Objects.toString(row.get("ads_table"),"").trim();
            if(!table.isEmpty() && allowed.stream().noneMatch(t ->
                    t.equalsIgnoreCase(table) || t.endsWith("."+table))) continue;
            String content="指标 "+row.get("metric_name")+"；别名 "+row.get("aliases")+
                "；口径 "+row.get("definition")+"；ADS 表 "+table+
                "；计算字段 "+row.get("ads_field")+"；维度 "+row.get("dimensions");
            documents.add(new Document("METRIC",Objects.toString(row.get("id")),truncate(content)));
            if (documents.size()>=MAX_DOCS) break;
        }
        if (documents.isEmpty()) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,"没有可索引的 ADS 表或指标定义");
        List<VectorDocument> output=new ArrayList<>();
        for(int start=0;start<documents.size();start+=BATCH_SIZE) {
            List<Document> slice=documents.subList(start,Math.min(start+BATCH_SIZE,documents.size()));
            double[][] vectors=embed(cfg,slice.stream().map(Document::text).toList());
            for(int i=0;i<slice.size();i++)
                output.add(new VectorDocument(slice.get(i),vectors[i]));
        }

        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM da_semantic_vectors");
            for (VectorDocument vector : output) {
                try {
                    jdbc.update("INSERT INTO da_semantic_vectors " +
                        "(source_type,source_key,content,vector_json,model_name) VALUES(?,?,?,?,?)",
                        vector.document().type(),vector.document().key(),vector.document().text(),
                        mapper.writeValueAsString(vector.vector()),cfg.model());
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                    throw new IllegalStateException("向量序列化失败",ex);
                }
            }
        });
        return Map.of("success",true,"count",output.size(),"model",cfg.model(),
            "message","已重建 "+output.size()+" 条 ADS / 指标向量索引");
    }

    public String hints(String question, Set<String> allowedTables) {
        RuntimeConfigService.EmbeddingConfig cfg=settings.embedding();
        if (!cfg.configured()) return "";
        Integer count=jdbc.queryForObject(
            "SELECT COUNT(*) FROM da_semantic_vectors WHERE model_name=?",
            Integer.class,cfg.model());
        if (count==null || count==0) return "";
        try {
            double[] query=embed(cfg,List.of(question))[0];
            List<Match> scored=new ArrayList<>();
            jdbc.query("SELECT source_type,source_key,content,vector_json " +
                "FROM da_semantic_vectors WHERE model_name=? LIMIT 150",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String type=rs.getString(1),source=rs.getString(2),content=rs.getString(3);
                    if ("ADS_TABLE".equals(type) && !allowedTables.contains(source)) return;
                    JsonNode values;
                    try {
                        values=mapper.readTree(rs.getString(4));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                        return;
                    }
                    if (!values.isArray() || values.size()!=query.length) return;
                    double dot=0, norm1=0, norm2=0;
                    for(int i=0;i<query.length;i++) {
                        double a=query[i],b=values.get(i).asDouble();
                        dot+=a*b;norm1+=a*a;norm2+=b*b;
                    }
                    double score=dot/(Math.sqrt(norm1)*Math.sqrt(norm2)+1e-12);
                    if(Double.isFinite(score))
                        scored.add(new Match(type,source,content,score));
                },cfg.model());
            scored.sort(Comparator.comparingDouble(Match::score).reversed());
            StringBuilder sb=new StringBuilder("\nEmbedding 语义检索命中的参考元数据（必须再经过 ADS 字段、指标口径和权限验证）：\n");
            int kept=0;
            for(Match match:scored) {
                if(kept>=5)break;
                sb.append(match.type()).append(" ").append(match.text()).append('\n');
                kept++;
            }
            return kept==0?"":sb.toString();
        } catch(Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                "Semantic retrieval unavailable: {}", e.getClass().getSimpleName());
            return ""; // Keep the existing deterministic NL2SQL fallback.
        }
    }

    private static String truncate(String source) {
        return source.length()>MAX_TEXT?source.substring(0,MAX_TEXT):source;
    }
}

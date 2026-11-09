from pathlib import Path
import json

ROOT = Path.cwd()
def edit(path, old, new, count=1):
    p = ROOT / path
    text = p.read_text()
    if text.count(old) != count:
        raise RuntimeError(f'{path}: expected {count} occurrences, got {text.count(old)}: {old[:90]!r}')
    p.write_text(text.replace(old, new))
def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)

app = 'web/src/App.vue'
edit(app, "import axios from 'axios'", "import api from './api'")
edit(app, "import AnswerChart from './components/AnswerChart.vue'", "import AnswerChart from './components/AnswerChart.vue'\nimport CatalogPicker from './components/CatalogPicker.vue'")
edit(app, """const api = axios.create({ baseURL: '/api', timeout: 65000 })
api.interceptors.request.use(config => {
  const token = sessionStorage.getItem('dataask_token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})
""", '')
edit(app, "const adminVisible = ref(false)", """const adminVisible = ref(false)
const adminSection = ref('setup')
const adminSections = [{key:'setup',label:'模型与连接'}, {key:'catalog',label:'数据库 / 表 / 字段'},
  {key:'joins',label:'表关联'}, {key:'metrics',label:'指标口径'}, {key:'users',label:'账号管理'}]
const catalogGeneration = ref(0)
const serverBuild = ref({})
async function onCatalogSaved() {
  await Promise.all([reloadTables(), reloadJoinRules(), reloadSemanticStatus()])
}
async function loadBuildInfo() {
  try { serverBuild.value = (await api.get('/build-info', { params: { t: Date.now() } })).data }
  catch { serverBuild.value = {} }
}
""")
edit(app, "  adminVisible.value = true\n  try { await refreshAdminStatus() }", "  adminVisible.value = true\n  await loadBuildInfo()\n  try { await refreshAdminStatus() }")
edit(app, "  configKind.value = kind\n  configTestMessage.value = ''", "  configKind.value = kind\n  configVisible.value = true\n  configTestMessage.value = ''")
edit(app, """    if (kind === 'starrocks') {
      await reloadTables()
      configVisible.value = false
    }
    await refreshAdminStatus()""", """    await refreshAdminStatus()
    if (kind === 'starrocks') {
      srForm.value.password = ''
      catalogGeneration.value++
      await onCatalogSaved()
      configVisible.value = false
      adminSection.value = 'catalog'
    }""")
start = ROOT / app
s = start.read_text()
a = s.index('async function toggleAllowed(table) {')
b = s.index('async function addUser()', a)
s = s[:a] + s[b:]
start.write_text(s)
edit(app, "const expandedTable = ref('')\n", '')
edit(app, "width: isDesktop ? 'min(920px, 92vw)'", "width: isDesktop ? 'min(1120px, 94vw)'")
edit(app, """        <div class="admin-panel">
          <div class="drawer-header"><b>系统管理</b><van-icon name="cross" @click="adminVisible=false"/></div>""", """        <div class="admin-panel config-center">
          <div class="drawer-header"><b>系统管理 · MySQL 配置中心</b><van-icon name="cross" @click="adminVisible=false"/></div>
          <nav class="admin-tabs" aria-label="管理模块">
            <button v-for="item in adminSections" :key="item.key" type="button"
              :class="{active:adminSection===item.key}" @click="adminSection=item.key">{{ item.label }}</button>
          </nav>
          <div class="admin-content">
          <section v-show="adminSection==='setup'">
          <p class="muted">模型、分析连接和表字段范围统一保存在 MySQL，保存后立即生效，无需重新打包。</p>""")
edit(app, """          <div class="admin-build-status">DataAsk 工作台 · 版本 {{ buildSha }} · PC宽屏使用三栏布局</div>""", """          <div class="admin-build-status">界面版本 {{ buildSha }} · 服务版本 {{ serverBuild.buildSha || '未读取' }} · 配置存储 MySQL</div>
          <p v-if="serverBuild.buildSha && serverBuild.buildSha !== buildSha && buildSha !== 'dev'" class="metric-error">
            页面与服务器版本不一致，请强制刷新浏览器，并检查实际部署的 JAR。</p>
          <p class="muted">部署验证：/api/build-info · PC 三栏工作台 / 手机自适应对话。</p>
          <van-button type="primary" plain block @click="adminSection='catalog'">选择数据库、表与字段</van-button>
          </section>""")
s = start.read_text()
a = s.index('          <h3>开放给 AI 的 ADS 表</h3>')
b = s.index('          <section class="metric-admin join-admin">', a)
s = s[:a] + """          <section v-if="adminVisible" v-show="adminSection==='catalog'">
            <CatalogPicker :generation="catalogGeneration" @configure-source="showConfig('starrocks')" @saved="onCatalogSaved"/>
          </section>
""" + s[b:]
start.write_text(s)
edit(app, '<section class="metric-admin join-admin">', '<section v-show="adminSection===\'joins\'" class="metric-admin join-admin">')
edit(app, '<section class="metric-admin">', '<section v-show="adminSection===\'metrics\'" class="metric-admin">')
edit(app, '          <h3>内部账号管理</h3>', '          <section v-show="adminSection===\'users\'">\n          <h3>内部账号管理</h3>')
edit(app, '          <p class="muted">AI 配置直接使用服务器 application.yml，不会在浏览器回显密钥；StarRocks 配置可通过上方入口测试和保存。</p>', '          <p class="muted">账号、模型、分析连接及表字段选择均保存在 MySQL。服务器配置文件仅负责元数据库连接与启动参数。</p>\n          </section>\n          </div>')
edit(app, '首次启动由管理员配置初始密码，系统不会使用公开默认密码。', '首次安装的管理员密码见启动日志或安装时指定的初始密码；已有账号继续使用原密码。')
edit(app, 'placeholder="jdbc:mysql://192.168.1.10:9030/ads"', 'placeholder="jdbc:mysql://192.168.1.10:9030/"')
edit(app, 'label="ADS 数据库"', 'label="默认数据库"')
edit(app, '配置 StarRocks FE 只读账号，测试连接成功后保存并立即读取 ADS 字段注释。', '填写 StarRocks FE 连接，连接地址可不包含数据库。保存后进入数据库、表和字段选择；更换服务器会清除旧表授权与关联规则。')
write('web/src/api.js', """import axios from 'axios'
const api = axios.create({ baseURL: '/api', timeout: 65000 })
api.interceptors.request.use(config => {
  const token = sessionStorage.getItem('dataask_token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})
export default api
""")
p = ROOT / 'web/src/style.css'
p.write_text(p.read_text() + """
/* Fixed-size management workspace; module bodies and field grids scroll independently. */
.config-center.admin-panel{display:flex;flex-direction:column;overflow:hidden;padding:20px;box-sizing:border-box}
.config-center>.drawer-header{flex:none;padding:6px 2px 18px;font-size:17px}
.admin-tabs{flex:none;display:flex;gap:8px;overflow-x:auto;border-bottom:1px solid #e6edf5;margin-bottom:14px}
.admin-tabs button{flex:none;background:none;border:0;border-bottom:2px solid transparent;padding:11px 14px;font-size:13px;color:#7c8da2;cursor:pointer}
.admin-tabs button.active{color:#286ccd;border-bottom-color:#286ccd;font-weight:600}
.admin-content{flex:1;min-height:0;overflow:auto;padding:0 4px 12px}
@media(max-width:700px){.config-center.admin-panel{padding:14px}.admin-tabs{gap:2px}.admin-tabs button{font-size:12px;padding:10px}.config-center>.drawer-header{font-size:15px}}
""")
edit('web/vite.config.js', 'http://127.0.0.1:8080', 'http://127.0.0.1:8087')
picker = 'web/src/components/CatalogPicker.vue'
edit(picker, "const loading = ref(false), saving = ref(false), error = ref(''), dirty = ref(false), page = ref(1)", "const loading = ref(false), saving = ref(false), error = ref(''), dirty = ref(false), page = ref(1)\nconst loaded = ref(false)\nlet requestId = 0")
edit(picker, "  if (!name) { tables.value = []; selected.value = {}; return }\n  loading.value = true; error.value = ''", "  const id = ++requestId\n  loaded.value = false\n  if (!name) { tables.value = []; selected.value = {}; return }\n  loading.value = true; error.value = ''")
edit(picker, "    tables.value = data.tables", "    if (id !== requestId) return\n    loaded.value = true\n    tables.value = data.tables")
edit(picker, "  } catch (e) { tables.value = []; selected.value = {}; error.value = message(e) }\n  finally { loading.value = false }", "  } catch (e) { if(id === requestId) { tables.value = []; selected.value = {}; error.value = message(e) } }\n  finally { if(id === requestId) loading.value = false }")
edit(picker, "  loading.value = true; error.value = ''\n  try {\n    const { data } = await api.get('/admin/catalog/databases')", "  loading.value = true; error.value = ''; loaded.value = false\n  try {\n    const { data } = await api.get('/admin/catalog/databases')")
edit(picker, "  if (saving.value) return", "  if (saving.value || !loaded.value) return")
edit(picker, ':disabled="!database || !configured"', ':disabled="!database || !configured || !loaded"')
edit(picker, ':disabled="saving" @click="save"', ':disabled="saving || !loaded" @click="save"')
runtime='src/main/java/com/dataask/config/RuntimeConfigService.java'
edit(runtime, 'return !baseUrl.isBlank() && !model.isBlank();', 'return !baseUrl.isBlank() && !model.isBlank() && (!requiresKey(baseUrl) || !apiKey.isBlank());')
edit(runtime, 'return enabled && !baseUrl.isBlank() && !model.isBlank();', 'return enabled && !baseUrl.isBlank() && !model.isBlank() && (!requiresKey(baseUrl) || !apiKey.isBlank());')
edit(runtime, '    private final JdbcTemplate jdbc;', '''    private static boolean requiresKey(String baseUrl) {
        String host;
        try { host = Objects.toString(URI.create(baseUrl).getHost(), "").toLowerCase(Locale.ROOT); }
        catch (Exception ignored) { return true; }
        return host.equals("api.deepseek.com") || host.endsWith(".aliyuncs.com");
    }
    private final JdbcTemplate jdbc;''')
for signature in ['public void saveStarRocks(StarRocksInput input) {', 'public void saveAi(ModelInput input) {', 'public void saveEmbedding(EmbeddingInput input) {']:
    edit(runtime, signature, signature+'\n        lockSettings();')
edit(runtime, '    private void upsert(String name, String value) {', '''    private void lockSettings() {
        jdbc.queryForObject("SELECT setting_value FROM da_app_settings WHERE setting_key='system.bootstrap.lock' FOR UPDATE", String.class);
    }
    private void upsert(String name, String value) {''')
edit(runtime, '            jdbc.update("UPDATE da_allowed_tables SET enabled=0");', '            jdbc.update("UPDATE da_allowed_tables SET enabled=0");\n            jdbc.update("DELETE FROM da_ads_join_rules");')
policy='src/main/java/com/dataask/data/CatalogPolicyService.java'
edit(policy, '        identifier(schema);\n        if (selected', '''        identifier(schema);
        jdbc.queryForObject("SELECT setting_value FROM da_app_settings WHERE setting_key='system.bootstrap.lock' FOR UPDATE", String.class);
        if (selected''')
edit(policy, 'new StarRocksGateway.TableMeta(table.name(), filtered)', 'new StarRocksGateway.TableMeta(table.name(), filtered, table.comment())')
admin='src/main/java/com/dataask/admin/AdminController.java'
p=ROOT/admin; s=p.read_text(); a=s.index('        boolean exists=starrocks.discover()',s.index('Map<String,Object> allow(')); b=s.index('\n    }',a)
s=s[:a]+'''        throw new ResponseStatusException(HttpStatus.GONE,
            "整表授权入口已停用，请在数据库 / 表 / 字段页面明确选择字段后保存");'''+s[b:]; p.write_text(s)
install='src/main/java/com/dataask/config/InstallationSettings.java'
edit(install, '        tx.executeWithoutResult(status -> {', '''        // Idempotent upgrade: a catalog of many selected columns can exceed TEXT's 64 KiB.
        String type = jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='da_app_settings' AND COLUMN_NAME='setting_value'", String.class);
        if (!"longtext".equalsIgnoreCase(type)) jdbc.execute("ALTER TABLE da_app_settings MODIFY setting_value LONGTEXT NOT NULL");
        tx.executeWithoutResult(status -> {''')
edit('src/main/resources/schema.sql', '  setting_value TEXT NOT NULL,', '  setting_value LONGTEXT NOT NULL,')
chat='src/main/java/com/dataask/chat/ChatController.java'
edit(chat, '尚未配置 AI 模型，请设置 DATAASK_AI_BASE_URL / API_KEY / MODEL', '尚未配置 AI 模型，请管理员进入系统管理 → 模型与连接进行配置')
edit(chat, '''        Set<String> allowed = new LinkedHashSet<>(jdbc.query(
            "SELECT table_name FROM da_allowed_tables WHERE enabled=1", (rs,n) -> rs.getString(1)));
        if (allowed.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请管理员先在 ADS 表管理中开放至少一张表");
        List<StarRocksGateway.TableMeta> discovered = starrocks.discover();''', '''        List<StarRocksGateway.TableMeta> discovered = starrocks.discover();
        Set<String> allowed = new LinkedHashSet<>();
        discovered.forEach(table -> allowed.add(table.name()));
        if (allowed.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请管理员在数据库 / 表 / 字段页面选择并保存问数范围");''')
edit(chat, '必须使用完整 adsSchema.tableName 表名，日期使用绝对值而不是 CURRENT_DATE/NOW。', '必须使用元数据中列出的完整 database.tableName 表名，允许的表可能来自不同数据库；不能统一替换成默认库。日期使用绝对值而不是 CURRENT_DATE/NOW。\n            仅生成直接 SELECT、聚合和已批准的 JOIN，禁止 SELECT *、CTE、子查询和窗口函数；GROUP BY、HAVING 请使用物理字段或完整表达式，不使用输出别名。')
edit(chat, 'metricDictionary.promptForQuestion(question, allowed)', 'metricDictionary.promptForQuestion(question, allowed, discovered)')
edit('src/main/java/com/dataask/semantic/EntityValueResolver.java', 'String key = gateway.schema() + ":" + ref.table() + "." + ref.column();', 'String key = gateway.selectionRevision() + ":" + ref.table() + "." + ref.column();\n        if (cache.size() > 500) cache.clear();')
guard='src/main/java/com/dataask/data/ColumnAccessGuard.java'
edit(guard, 'expression(p.getHaving(), aliases, columns, outputAliases);', 'expression(p.getHaving(), aliases, columns, Set.of());')
edit(guard, 'expression((Expression) value, aliases, columns, outputAliases);', 'expression((Expression) value, aliases, columns, Set.of());')
edit(guard, 'if (!name.contains(".")) name = clean(schema) + "." + name;', 'if (!name.contains(".")) throw denied(); // Explicit databases prevent JDBC-default/schema mismatches.')
join='src/main/java/com/dataask/semantic/JoinRuleService.java'
edit(join, '    public List<Rule> allowed(Set<String> allowedTables) {\n        return all().stream()', '''    public List<Rule> allowed(Set<String> allowedTables) {
        Map<String,Set<String>> selected = new HashMap<>();
        for (var table : gateway.discover()) {
            Set<String> names = new HashSet<>();
            table.columns().forEach(c -> names.add(c.name()));
            selected.put(table.name(), names);
        }
        return all().stream()''')
edit(join, '            .filter(r -> allowedTables.contains(r.leftTable()) && allowedTables.contains(r.rightTable()))', '''            .filter(r -> allowedTables.contains(r.leftTable()) && allowedTables.contains(r.rightTable()))
            .filter(r -> selected.getOrDefault(r.leftTable(), Set.of()).containsAll(r.leftKeys()) &&
                selected.getOrDefault(r.rightTable(), Set.of()).containsAll(r.rightKeys()))''')
metrics='src/main/java/com/dataask/metrics/MetricDictionaryService.java'
edit(metrics, '    public String promptForQuestion(String question, Set<String> allowedTables) {', '''    public String promptForQuestion(String question, Set<String> allowedTables) {
        return promptForQuestion(question, allowedTables, null);
    }
    public String promptForQuestion(String question, Set<String> allowedTables,
            List<com.dataask.data.StarRocksGateway.TableMeta> metadata) {''')
edit(metrics, '        for (String[] row : rows) {\n            String rawTable', '''        for (String[] row : rows) {
            if (metadata != null && !com.dataask.semantic.MetricScope.permits(row[3], row[4], row[5], row[6], metadata)) continue;
            String rawTable''')
emb='src/main/java/com/dataask/semantic/EmbeddingSemanticService.java'
edit(emb, '        List<Document> documents=new ArrayList<>();', '        String revision = starrocks.selectionRevision();\n        List<Document> documents=new ArrayList<>();')
edit(emb, '"SELECT id,metric_name,aliases,definition,ads_table,ads_field,dimensions "', '"SELECT id,metric_name,aliases,definition,ads_table,ads_field,time_field,dimensions "')
edit(emb, '        for (Map<String,Object> row : metricRows) {\n            String table', '''        for (Map<String,Object> row : metricRows) {
            if (!MetricScope.permits(Objects.toString(row.get("ads_table"),""),
                Objects.toString(row.get("ads_field"),""), Objects.toString(row.get("time_field"),""),
                Objects.toString(row.get("dimensions"),""), tables)) continue;
            String table''')
edit(emb, '        tx.executeWithoutResult(status -> {\n            jdbc.update("DELETE FROM da_semantic_vectors");', '''        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT setting_value FROM da_app_settings WHERE setting_key='system.bootstrap.lock' FOR UPDATE", String.class);
            if (!revision.equals(starrocks.selectionRevision()) || !cfg.equals(settings.embedding()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "模型或表字段配置已变更，请重新构建索引");
            jdbc.update("INSERT INTO da_app_settings(setting_key,setting_value) VALUES('semantic.revision',?) " +
                "ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)", revision);
            jdbc.update("DELETE FROM da_semantic_vectors");''')
edit(emb, '        if (!cfg.configured()) return "";\n        Integer count', '''        if (!cfg.configured()) return "";
        String revision = starrocks.selectionRevision();
        List<String> indexed = jdbc.query("SELECT setting_value FROM da_app_settings WHERE setting_key='semantic.revision'", (rs,n) -> rs.getString(1));
        if (indexed.isEmpty() || !revision.equals(indexed.getFirst())) return "";
        Integer count''')
edit(emb, '            return kept==0?"":sb.toString();', '            if (!revision.equals(starrocks.selectionRevision()) || !cfg.equals(settings.embedding())) return "";\n            return kept==0?"":sb.toString();')
write('src/main/java/com/dataask/semantic/MetricScope.java', '''package com.dataask.semantic;

import com.dataask.data.ColumnAccessGuard;
import com.dataask.data.SqlGuard;
import com.dataask.data.StarRocksGateway;
import java.util.*;

/** Structured metric bindings must resolve against the currently selected physical fields. */
public final class MetricScope {
    private MetricScope() {}
    public static boolean permits(String table, String field, String time, String dimensions,
                                  List<StarRocksGateway.TableMeta> metadata) {
        table = Objects.toString(table, "").trim();
        field = Objects.toString(field, "").trim();
        time = Objects.toString(time, "").trim();
        dimensions = Objects.toString(dimensions, "").trim();
        if (table.isEmpty()) return field.isEmpty() && time.isEmpty() && dimensions.isEmpty();
        final String name = table;
        var matches = metadata.stream().filter(t -> t.name().equalsIgnoreCase(name) ||
            (!name.contains(".") && t.name().toLowerCase(Locale.ROOT).endsWith("." + name.toLowerCase(Locale.ROOT)))).toList();
        if (matches.size() != 1) return false;
        var target = matches.getFirst();
        Map<String,List<String>> grants = Map.of(target.name(), target.columns().stream().map(StarRocksGateway.ColumnMeta::name).toList());
        List<String> expressions = new ArrayList<>();
        if (!field.isEmpty()) expressions.add(field);
        if (!time.isEmpty()) expressions.add(time);
        if (!dimensions.isEmpty()) expressions.addAll(Arrays.asList(dimensions.split("[,，、]")));
        try {
            for (String expression : expressions) {
                expression = expression.trim();
                final String label = expression;
                var labels = target.columns().stream().filter(c -> c.comment().equals(label)).toList();
                if (labels.size() == 1) expression = labels.getFirst().name();
                String sql = "SELECT " + expression + " FROM " + target.name();
                new SqlGuard().validate(sql, grants.keySet(), target.name().split("\\\\.")[0]);
                new ColumnAccessGuard().validate(sql, grants, target.name().split("\\\\.")[0]);
            }
            return true;
        } catch (RuntimeException ignored) { return false; }
    }
}
''')
write('src/main/java/com/dataask/common/BuildInfoController.java', '''package com.dataask.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class BuildInfoController {
    private final Map<String,Object> build;
    public BuildInfoController(ObjectMapper mapper) {
        Map<String,Object> value = new LinkedHashMap<>();
        try (var input = new ClassPathResource("static/build-info.json").getInputStream()) {
            var json = mapper.readTree(input);
            for (String key : new String[]{"buildSha", "builtAt", "uiLayout", "configStorage", "catalogVersion"})
                value.put(key, json.path(key).asText("unknown"));
        } catch (Exception ex) {
            value.put("buildSha", "unpackaged");
            value.put("uiLayout", "unknown");
        }
        value.put("pid", ProcessHandle.current().pid());
        value.put("startedAt", ProcessHandle.current().info().startInstant().map(Object::toString).orElse("unknown"));
        build = Map.copyOf(value);
    }
    @GetMapping("/api/build-info")
    public ResponseEntity<Map<String,Object>> info() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(build);
    }
}
''')
write('scripts/build-web.mjs', '''import { execFileSync, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
if (Number(process.versions.node.split('.')[0]) < 22) throw new Error('构建需要 Node.js 22 或更高版本')
let sha = 'unknown'
try { sha = execFileSync('git', ['rev-parse', '--short=8', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim() } catch {}
const dirty = spawnSync('git', ['diff', '--quiet', 'HEAD'], { cwd: root }).status === 1
if (dirty) sha += '-dirty'
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
function run(args, env = process.env) {
  const result = spawnSync(npm, args, { cwd: resolve(root, 'web'), env, stdio: 'inherit', shell: process.platform === 'win32' })
  if (result.error || result.status !== 0) throw new Error(`npm ${args.join(' ')} 执行失败：${result.error?.message || result.status}`)
}
run([existsSync(resolve(root, 'web/package-lock.json')) ? 'ci' : 'install', '--no-audit', '--no-fund'])
run(['run', 'build'], { ...process.env, DATAASK_BUILD_SHA: sha })
const build = { buildSha: sha, builtAt: new Date().toISOString(), uiLayout: 'desktop-three-pane',
  modelManagement: 'web', configStorage: 'mysql', catalogVersion: 'table-column-selector-v1' }
mkdirSync(resolve(root, 'web/dist'), { recursive: true })
writeFileSync(resolve(root, 'web/dist/build-info.json'), JSON.stringify(build) + '\\n')
rmSync(resolve(root, 'target/classes/static'), { recursive: true, force: true })
console.log(`DataAsk 前端构建版本: ${sha}`)
''')
pom='pom.xml'
edit(pom, '<java.version>21</java.version>', '<java.version>21</java.version><dataask.skipWeb>false</dataask.skipWeb>')
edit(pom, '  <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>', '''  <build>
    <resources>
      <resource><directory>src/main/resources</directory><excludes><exclude>static/**</exclude></excludes></resource>
      <resource><directory>web/dist</directory><targetPath>static</targetPath></resource>
    </resources>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
      <plugin>
        <groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId><version>3.5.0</version>
        <executions><execution><id>build-web</id><phase>generate-resources</phase><goals><goal>exec</goal></goals>
          <configuration><skip>${dataask.skipWeb}</skip><executable>node</executable>
            <workingDirectory>${project.basedir}</workingDirectory>
            <arguments><argument>scripts/build-web.mjs</argument></arguments>
          </configuration>
        </execution></executions>
      </plugin>
    </plugins>
  </build>''')
write('scripts/package.sh', '''#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
for TOOL in java mvn node npm tar unzip; do command -v "$TOOL" >/dev/null || { echo "缺少构建工具: $TOOL"; exit 1; }; done
mvn -B clean package
JAR="$ROOT/target/dataask-0.1.0-SNAPSHOT.jar"
INFO="$(unzip -p "$JAR" BOOT-INF/classes/static/build-info.json)"
echo "$INFO" | grep -F 'table-column-selector-v1' >/dev/null || { echo "JAR 中缺少新版前端，拒绝发布"; exit 1; }
BUILD_SHA="$(printf '%s' "$INFO" | sed -n 's/.*"buildSha":"\\([^" ]*\\)".*/\\1/p')"
[ -n "$BUILD_SHA" ] || { echo "缺少构建版本"; exit 1; }
OUT="$ROOT/release/dataask-0.1.0"
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/conf" "$OUT/lib" "$OUT/logs"
cp "$JAR" "$OUT/lib/dataask.jar"
cp scripts/runtime/*.sh "$OUT/bin/"
cp scripts/runtime/application.yml "$OUT/conf/application.yml"
printf '%s\\n' "$INFO" > "$OUT/build-info.json"
chmod +x "$OUT/bin/"*.sh
ARCHIVE="$ROOT/release/dataask-0.1.0-$BUILD_SHA.tar.gz"
tar -czf "$ARCHIVE" -C "$ROOT/release" dataask-0.1.0
cp "$ARCHIVE" "$ROOT/release/dataask-0.1.0.tar.gz"
(cd "$ROOT/release" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256")
echo "完成: $ARCHIVE"
echo "构建信息: $INFO"
echo "请升级实际运行目录，并访问 /api/build-info 核对 buildSha 和启动 PID。"
''')
write('package.sh', '''#!/usr/bin/env bash
set -euo pipefail
exec bash "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/package.sh" "$@"
''')
write('scripts/runtime/application.yml', '''# 仅配置启动必需的元数据库连接和服务端口。
# 模型、StarRocks 分析连接、数据库/表/字段均在管理员页面设置并存入 MySQL。
server:
  port: ${DATAASK_PORT:8087}
spring:
  datasource:
    url: ${DATAASK_MYSQL_URL:jdbc:mysql://127.0.0.1:3306/dataask?useUnicode=true&characterEncoding=utf8}
    username: ${DATAASK_MYSQL_USER:root}
    password: ${DATAASK_MYSQL_PASSWORD:123456}
# 旧安装升级时保留原配置文件：旧 JWT/业务配置只在首次迁移时读取，之后以 MySQL 为准。
# 已有账号密码不会改变。全新安装可用 DATAASK_ADMIN_PASSWORD 指定初始密码，
# 未指定时启动日志只输出一次自动生成的 admin 初始密码。
''')
p=ROOT/'src/main/resources/application.yml'; text=p.read_text(); text=text[:text.index('dataask:\n')]; p.write_text(text)
edit(install, 'env.getProperty("dataask.jwt-secret", "").trim()', 'env.getProperty("dataask.jwt-secret", env.getProperty("DATAASK_JWT_SECRET", "")).trim()')
edit('src/main/java/com/dataask/security/SecurityConfig.java', '@Value("${dataask.admin-password:}")', '@Value("${dataask.admin-password:${DATAASK_ADMIN_PASSWORD:}}")')
p=ROOT/'web/package.json'; package=json.loads(p.read_text()); package['devDependencies']['playwright']='1.56.1'; package['scripts']['test:e2e']='node e2e/admin-workspace.mjs'; p.write_text(json.dumps(package,ensure_ascii=False,indent=2)+'\n')
print('Applied targeted configuration/catalog integration edits.')

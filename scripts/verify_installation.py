#!/usr/bin/env python3
"""Isolated CI test only: MySQL supplies fixtures; model HTTP responses are deterministic.
This does not connect to a user's StarRocks or call paid model APIs.
"""
from __future__ import annotations
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / 'target' / 'installation-test'
REPORT.mkdir(parents=True, exist_ok=True)
if os.environ.get('DATAASK_CI_INTEGRATION') != '1':
    raise SystemExit('This test is restricted to an isolated CI database; set DATAASK_CI_INTEGRATION=1 there.')
MYSQL_PORT = int(os.environ.get('DATAASK_TEST_MYSQL_PORT', '13306'))
MYSQL_PASS = os.environ['DATAASK_TEST_MYSQL_PASSWORD']
APP_PORT = int(os.environ.get('DATAASK_TEST_PORT', '18087'))
BASE = f'http://127.0.0.1:{APP_PORT}'
ADMIN_PASS = 'ci-admin-password-123'
YESTERDAY = (dt.datetime.now(ZoneInfo('Asia/Shanghai')).date() - dt.timedelta(days=1)).isoformat()
checks: list[str] = []
def passed(name: str) -> None:
    checks.append(name)
    print('PASS:', name, flush=True)
def mysql(sql: str) -> str:
    env = dict(os.environ, MYSQL_PWD=MYSQL_PASS)
    return subprocess.check_output(['mysql', '--protocol=TCP', '-h127.0.0.1', f'-P{MYSQL_PORT}', '-uroot',
        '--default-character-set=utf8mb4', '-N', '-B', '-e', sql], env=env, text=True)
def request(path: str, payload=None, token='', expected=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=None if payload is None else json.dumps(payload).encode(), headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            status, data = response.status, response.read()
    except urllib.error.HTTPError as exc:
        status, data = exc.code, exc.read()
    assert status == expected, f'{path}: expected HTTP {expected}, got {status}: {data[:800]!r}'
    return json.loads(data) if data and data.lstrip().startswith((b'{', b'[')) else data

class ModelHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path == '/embeddings':
            size = body.get('dimensions', 64)
            output = {'data': [{'index': i, 'embedding': [1.0] * size} for i, _ in enumerate(body['input'])]}
        else:
            question = body['messages'][-1]['content']
            if '用户问题：' in question:
                question = question.rsplit('用户问题：', 1)[1]
            sql = f"SELECT SUM(amount) AS revenue FROM ads_ci.revenue_daily WHERE dt='{YESTERDAY}'"
            chart = 'metric'
            if '内部金额' in question:
                sql = f"SELECT SUM(secret_amount) AS revenue FROM ads_ci.revenue_daily WHERE dt='{YESTERDAY}'"
            elif '按城市' in question:
                sql = f"SELECT city_name,SUM(amount) AS revenue FROM ads_ci.revenue_daily WHERE dt='{YESTERDAY}' GROUP BY city_name ORDER BY revenue DESC"
                chart = 'bar'
            elif '西安' in question:
                sql += " AND city_name='西安'"
            plan = {'title': '查询结果', 'sql': sql, 'chartType': chart, 'metricLabel': '总金额',
                'measureColumn': 'revenue', 'columnLabels': {'city_name': '城市', 'revenue': '总金额'}}
            content = 'OK' if question == '仅回答 OK' else json.dumps(plan, ensure_ascii=False)
            output = {'choices': [{'message': {'content': content}}]}
        data = json.dumps(output, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def log_message(self, *args):
        pass

fixture = f"""
CREATE DATABASE IF NOT EXISTS dataask_ci CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS ads_ci CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS ads_other CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'analysis_ci'@'%' IDENTIFIED WITH mysql_native_password BY 'analysis-ci-password';
GRANT SELECT ON ads_ci.* TO 'analysis_ci'@'%';
GRANT SELECT ON ads_other.* TO 'analysis_ci'@'%';
CREATE TABLE IF NOT EXISTS ads_ci.revenue_daily (
 dt DATE COMMENT '业务日期',city_name VARCHAR(50) COMMENT '城市名称',
 amount DECIMAL(18,2) COMMENT '当日金额',secret_amount DECIMAL(18,2) COMMENT '内部金额'
) COMMENT='城市经营收入汇总';
DELETE FROM ads_ci.revenue_daily;
INSERT INTO ads_ci.revenue_daily VALUES ('{YESTERDAY}','西安市',120,900),('{YESTERDAY}','杭州市',180,800);
CREATE TABLE IF NOT EXISTS ads_other.city_dim(city_name VARCHAR(50) COMMENT '城市名称', region VARCHAR(50) COMMENT '区域');
CREATE TABLE IF NOT EXISTS dataask_ci.da_app_settings(setting_key VARCHAR(100) PRIMARY KEY, setting_value TEXT NOT NULL,
 updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB;
"""
for i in range(12):
    fixture += f"CREATE TABLE IF NOT EXISTS ads_ci.extra_{i:02d}(id INT COMMENT '编号',amount DECIMAL(18,2) COMMENT '金额');\n"
mysql(fixture)
model = ThreadingHTTPServer(('127.0.0.1', 0), ModelHandler)
threading.Thread(target=model.serve_forever, daemon=True).start()
model_url = f'http://127.0.0.1:{model.server_port}'
archive = ROOT / 'release/dataask-0.1.0.tar.gz'
with tempfile.TemporaryDirectory(prefix='dataask-ci-') as tmp:
    subprocess.run(['tar', '-xzf', str(archive), '-C', tmp], check=True)
    home = Path(tmp) / 'dataask-0.1.0'
    conf = home / 'conf/application.yml'
    conf.write_text(f"""server:
  port: {APP_PORT}
spring:
  datasource:
    url: 'jdbc:mysql://127.0.0.1:{MYSQL_PORT}/dataask_ci?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=utf8'
    username: root
    password: '{MYSQL_PASS}'
""")
    env = {k: v for k, v in os.environ.items() if not k.startswith('DATAASK_MYSQL_') and k != 'DATAASK_JWT_SECRET'}
    env.update(DATAASK_HOME=str(home), DATAASK_PORT=str(APP_PORT), DATAASK_ADMIN_PASSWORD=ADMIN_PASS,
        JAVA_OPTS='-Xms128m -Xmx512m', DATAASK_TEST_BASE=BASE, DATAASK_TEST_ADMIN_PASSWORD=ADMIN_PASS)
    def runtime(script, *args):
        subprocess.run(['bash', str(home / 'bin' / script), *map(str, args)], env=env, check=True, timeout=150)
    try:
        runtime('start.sh')
        first_build = request('/api/build-info')
        assert first_build['catalogVersion'] == 'table-column-selector-v1'
        assert first_build['configStorage'] == 'mysql'
        assert first_build['buildSha'] == json.loads((home / 'build-info.json').read_text())['buildSha']
        passed('完整打包与运行版本、PID校验')
        token = request('/api/auth/login', {'username': 'admin', 'password': ADMIN_PASS})['token']
        status = request('/api/admin/status', token=token)
        assert not status['starrocksConfigured'] and not status['aiConfigured']
        assert 'longtext' in mysql("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='dataask_ci' AND TABLE_NAME='da_app_settings' AND COLUMN_NAME='setting_value'")
        passed('无模型/分析连接也能启动登录，旧TEXT配置列无损迁移')
        request('/api/admin/users', {'username': 'viewer', 'displayName': '测试领导', 'password': 'viewer-ci-password', 'role': 'USER'}, token)
        viewer = request('/api/auth/login', {'username': 'viewer', 'password': 'viewer-ci-password'})['token']
        request('/api/admin/settings', token=viewer, expected=403)
        passed('管理员配置入口权限')
        ai = {'baseUrl': model_url, 'model': 'mock-chat', 'apiKey': 'test-model-key', 'clearKey': False}
        request('/api/admin/settings/ai', ai, token)
        request('/api/admin/settings/ai/test', ai, token)
        sr = {'jdbcUrl': f'jdbc:mysql://127.0.0.1:{MYSQL_PORT}/', 'username': 'analysis_ci', 'password': 'analysis-ci-password', 'schema': 'ads_ci'}
        request('/api/admin/settings/starrocks/test', sr, token)
        request('/api/admin/settings/starrocks', sr, token)
        db = request('/api/admin/catalog/databases', token=token)
        assert {'ads_ci', 'ads_other'}.issubset(set(db['databases']))
        tables = request('/api/admin/catalog/tables?database=ads_ci', token=token)
        revenue = next(t for t in tables['tables'] if t['name'] == 'ads_ci.revenue_daily')
        assert revenue['comment'] == '城市经营收入汇总'
        assert any(c['comment'] == '当日金额' for c in revenue['columns'])
        selection = {'database': 'ads_ci', 'sourceKey': tables['sourceKey'], 'revision': tables['revision'],
            'tables': {'ads_ci.revenue_daily': ['dt', 'city_name', 'amount']}}
        result = request('/api/admin/catalog/selection', selection, token)
        request('/api/admin/catalog/selection', selection, token, expected=409)
        selection['revision'] = result['revision']
        invalid = dict(selection, tables={'ads_ci.revenue_daily': ['not_a_column']})
        request('/api/admin/catalog/selection', invalid, token, expected=400)
        passed('数据库/表注释发现、逐字段保存、非法字段与过期版本校验')
        other = request('/api/admin/catalog/tables?database=ads_other', token=token)
        request('/api/admin/catalog/selection', {'database': 'ads_other', 'sourceKey': other['sourceKey'], 'revision': other['revision'],
            'tables': {'ads_other.city_dim': ['city_name', 'region']}}, token)
        all_selected = request('/api/admin/catalog/databases', token=token)['selection']
        assert len(all_selected) == 2 and 'secret_amount' not in all_selected['ads_ci.revenue_daily']
        passed('多数据库选择互不覆盖，未选字段不开放')
        answer = request('/api/chat/ask', {'question': '昨日总金额'}, viewer)
        assert not answer['unsupported'] and float(answer['rows'][0]['revenue']) == 300
        follow = request('/api/chat/ask', {'question': '按城市看', 'sessionId': answer['sessionId'], 'sourceMessageId': answer['id']}, viewer)
        assert not follow['unsupported'] and len(follow['rows']) == 2
        city = request('/api/chat/ask', {'question': '昨日西安的总金额'}, viewer)
        assert not city['unsupported'] and float(city['rows'][0]['revenue']) == 120
        hidden = request('/api/chat/ask', {'question': '昨日内部金额'}, viewer)
        assert hidden['unsupported'] and '许愿' in hidden['summary']
        passed('问数真实SQL结果、按城市连续追问、西安别名、未选字段拒绝')
        embedding = {'baseUrl': model_url, 'model': 'mock-embedding', 'apiKey': 'embedding-test-key', 'clearKey': False,
            'enabled': True, 'dimensions': 64}
        request('/api/admin/settings/embedding', embedding, token)
        request('/api/admin/settings/embedding/test', embedding, token)
        request('/api/admin/semantic/rebuild', {}, token)
        assert request('/api/admin/semantic/status', token=token)['count'] > 0
        changed = request('/api/admin/catalog/tables?database=ads_other', token=token)
        request('/api/admin/catalog/selection', {'database': 'ads_other', 'sourceKey': changed['sourceKey'], 'revision': changed['revision'],
            'tables': {}}, token)
        assert request('/api/admin/semantic/status', token=token)['count'] == 0
        assert len(request('/api/admin/catalog/databases', token=token)['selection']) == 1
        passed('撤销表字段即清理语义索引，空选择不等于全开放')
        runtime('stop.sh')
        conf.write_text(conf.read_text() + "\ndataask:\n  jwt-secret: stale-file-secret-with-more-than-32-characters\n  ai:\n    base-url: 'http://127.0.0.1:1'\n    model: stale-yaml-model\n")
        runtime('start.sh')
        # The original token remains valid: the signing key is owned by MySQL, not the new stale YAML.
        saved = request('/api/admin/settings', token=token)
        assert saved['ai']['model'] == 'mock-chat' and saved['ai']['apiKeySet']
        assert saved['starrocks']['passwordSet'] and saved['embedding']['model'] == 'mock-embedding'
        assert request('/api/admin/catalog/databases', token=token)['selection']['ads_ci.revenue_daily'] == ['dt', 'city_name', 'amount']
        passed('重启后配置、密钥和字段选择保留，旧YAML不覆盖MySQL')
        before = request('/api/build-info')
        conf_hash = hashlib.sha256(conf.read_bytes()).hexdigest()
        # Simulate an obsolete installed stop script; the new upgrade must use its own verified stop implementation.
        (home / 'bin/stop.sh').write_text('#!/usr/bin/env bash\nexit 23\n')
        runtime('upgrade.sh', archive, home)
        after = request('/api/build-info')
        assert before['pid'] != after['pid'] and before['buildSha'] == after['buildSha']
        assert conf_hash == hashlib.sha256(conf.read_bytes()).hexdigest()
        assert request('/api/admin/settings', token=token)['ai']['model'] == 'mock-chat'
        passed('升级使用新版停止脚本、新PID启动验证、原配置文件不覆盖')
        subprocess.run(['node', 'web/e2e/admin-workspace.mjs'], cwd=ROOT, env=env, check=True, timeout=180)
        passed('PC与手机管理页面浏览器交互')
    finally:
        subprocess.run(['bash', str(home / 'bin/stop.sh')], env=env, check=False, timeout=60)
        if (home / 'logs/dataask.log').exists():
            shutil.copy2(home / 'logs/dataask.log', REPORT / 'dataask.log')
        (REPORT / 'checks.json').write_text(json.dumps({'passed': checks, 'count': len(checks)}, ensure_ascii=False, indent=2))
        model.shutdown()
print(f'Installation integration: {len(checks)} checks passed.', flush=True)

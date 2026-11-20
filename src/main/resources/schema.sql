CREATE TABLE IF NOT EXISTS da_datasources (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 name VARCHAR(100) NOT NULL UNIQUE,
 jdbc_url VARCHAR(500) NOT NULL,
 username VARCHAR(120) NOT NULL,
 password_enc TEXT NOT NULL,
 schema_name VARCHAR(64) NOT NULL,
 description VARCHAR(500) NOT NULL DEFAULT '',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 INDEX idx_da_datasource_updated (updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS da_users (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(64) NOT NULL UNIQUE,
 display_name VARCHAR(100) NOT NULL,
 password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(16) NOT NULL DEFAULT 'USER',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Independent audit history: no cascading FK to users, messages or charts.
-- DATETIME(3) values are written/read explicitly as UTC; UI filters use Asia/Shanghai.
CREATE TABLE IF NOT EXISTS da_operation_audit (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 request_id VARCHAR(36) NOT NULL,
 username VARCHAR(64) NOT NULL,
 actor_role VARCHAR(16) NOT NULL,
 http_method VARCHAR(8) NOT NULL,
 path VARCHAR(512) NOT NULL,
 module VARCHAR(24) NOT NULL,
 action VARCHAR(80) NOT NULL,
 target VARCHAR(500) NOT NULL,
 detail TEXT NULL,
 outcome VARCHAR(16) NOT NULL,
 http_status INT NULL,
 duration_ms BIGINT NULL,
 occurred_at DATETIME(3) NOT NULL,
 finished_at DATETIME(3) NULL,
 INDEX idx_da_op_time (occurred_at,id),
 INDEX idx_da_op_user_time (username,occurred_at,id),
 INDEX idx_da_op_module_time (module,occurred_at,id),
 INDEX idx_da_op_action_time (action,occurred_at,id),
 INDEX idx_da_op_outcome_time (outcome,occurred_at,id),
 INDEX idx_da_op_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS da_query_audit (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 operation_id VARCHAR(36) NULL,
 request_id VARCHAR(36) NULL,
 username VARCHAR(64) NOT NULL,
 source VARCHAR(24) NOT NULL,
 session_id VARCHAR(36) NULL,
 question VARCHAR(800) NOT NULL,
 sql_text TEXT NOT NULL,
 status VARCHAR(16) NOT NULL,
 row_count INT NULL,
 duration_ms BIGINT NULL,
 error_code VARCHAR(64) NULL,
 started_at DATETIME(3) NOT NULL,
 finished_at DATETIME(3) NULL,
 INDEX idx_da_query_time (started_at,id),
 INDEX idx_da_query_user_time (username,started_at,id),
 INDEX idx_da_query_status_time (status,started_at,id),
 INDEX idx_da_query_source_time (source,started_at,id),
 INDEX idx_da_query_operation (operation_id),
 INDEX idx_da_query_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS da_allowed_tables (
 table_name VARCHAR(255) NOT NULL PRIMARY KEY,
 enabled TINYINT(1) NOT NULL DEFAULT 0,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS da_sessions (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 username VARCHAR(64) NOT NULL,
 title VARCHAR(180) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 INDEX idx_da_sessions_user (username, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS da_messages (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 session_id VARCHAR(36) NOT NULL,
 question TEXT NOT NULL,
 generated_sql TEXT NOT NULL,
 title VARCHAR(200) NOT NULL,
 summary TEXT NOT NULL,
 chart_type VARCHAR(20) NOT NULL,
 payload_json LONGTEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_da_messages_session (session_id, created_at),
 CONSTRAINT fk_da_msg_session FOREIGN KEY (session_id) REFERENCES da_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE IF NOT EXISTS da_app_settings (
  setting_key VARCHAR(100) NOT NULL PRIMARY KEY,
  setting_value LONGTEXT NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Each upload is a versioned import, allowing admins to remove a bad workbook later.
CREATE TABLE IF NOT EXISTS da_metric_import_batches (
  id VARCHAR(36) PRIMARY KEY,
  source_file VARCHAR(255) NOT NULL,
  uploaded_by VARCHAR(64) NOT NULL,
  row_count INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_da_metric_batch_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS da_metric_definitions (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL,
  metric_name VARCHAR(150) NOT NULL,
  aliases VARCHAR(500) NOT NULL DEFAULT '',
  definition TEXT NOT NULL,
  ads_table VARCHAR(255) NOT NULL DEFAULT '',
  ads_field VARCHAR(255) NOT NULL DEFAULT '',
  time_field VARCHAR(128) NOT NULL DEFAULT '',
  dimensions VARCHAR(500) NOT NULL DEFAULT '',
  notes TEXT NOT NULL,
  sheet_name VARCHAR(128) NOT NULL,
  excel_row INT NOT NULL,
  INDEX idx_da_metric_name (metric_name),
  INDEX idx_da_metric_batch (batch_id),
  CONSTRAINT fk_da_metric_batch FOREIGN KEY(batch_id)
    REFERENCES da_metric_import_batches(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- Legacy da_ads_join_rules tables from prior releases are left untouched,
-- but new installations no longer create or use manual association rules.

-- Optional semantic vector index, built on-demand from authorized ADS metadata and metric definitions.
-- No business data rows are copied into this table.
CREATE TABLE IF NOT EXISTS da_semantic_vectors (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_type VARCHAR(32) NOT NULL,
  source_key VARCHAR(255) NOT NULL,
  content TEXT NOT NULL,
  vector_json LONGTEXT NOT NULL,
  model_name VARCHAR(180) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_da_semantic_type (source_type),
  INDEX idx_da_semantic_model (model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Miniapp chart collection. Saved charts persist the original question, not a frozen
-- synthetic result: reopening performs a fresh authorized NL2SQL query.
CREATE TABLE IF NOT EXISTS da_saved_charts (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 owner_username VARCHAR(64) NOT NULL,
 source_message_id VARCHAR(36) NOT NULL,
 question TEXT NOT NULL,
 title VARCHAR(180) NOT NULL,
 chart_type VARCHAR(20) NOT NULL,
 share_scope VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uq_da_chart_source(owner_username,source_message_id),
 INDEX idx_da_chart_owner(owner_username,updated_at),
 INDEX idx_da_chart_scope(share_scope,updated_at),
 CONSTRAINT fk_da_chart_user FOREIGN KEY(owner_username) REFERENCES da_users(username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS da_chart_acl (
 chart_id VARCHAR(36) NOT NULL,
 username VARCHAR(64) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(chart_id,username),
 INDEX idx_da_chart_acl_user(username),
 CONSTRAINT fk_da_acl_chart FOREIGN KEY(chart_id) REFERENCES da_saved_charts(id) ON DELETE CASCADE,
 CONSTRAINT fk_da_acl_user FOREIGN KEY(username) REFERENCES da_users(username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

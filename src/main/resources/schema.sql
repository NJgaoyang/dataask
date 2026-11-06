CREATE TABLE IF NOT EXISTS da_users (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(64) NOT NULL UNIQUE,
 display_name VARCHAR(100) NOT NULL,
 password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(16) NOT NULL DEFAULT 'USER',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
  setting_value TEXT NOT NULL,
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


-- Administrator-reviewed ADS join relations, with explicit grain and key mapping.
-- A multi-table NL2SQL query must use these relations, never an invented join.
CREATE TABLE IF NOT EXISTS da_ads_join_rules (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  left_table VARCHAR(255) NOT NULL,
  right_table VARCHAR(255) NOT NULL,
  left_keys VARCHAR(500) NOT NULL,
  right_keys VARCHAR(500) NOT NULL,
  cardinality VARCHAR(20) NOT NULL,
  grain_note VARCHAR(800) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_da_join_left (left_table),
  INDEX idx_da_join_right (right_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

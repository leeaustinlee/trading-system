CREATE TABLE IF NOT EXISTS external_probe_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  checked_at DATETIME NOT NULL,
  taifex_date DATE,
  live_line BOOLEAN NOT NULL DEFAULT FALSE,
  live_claude BOOLEAN NOT NULL DEFAULT FALSE,

  taifex_status VARCHAR(20),
  taifex_success BOOLEAN,
  taifex_detail VARCHAR(1000),

  line_status VARCHAR(20),
  line_success BOOLEAN,
  line_detail VARCHAR(1000),

  claude_status VARCHAR(20),
  claude_success BOOLEAN,
  claude_detail VARCHAR(1000),

  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_external_probe_log_checked_at ON external_probe_log(checked_at);

CREATE TABLE IF NOT EXISTS market_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trading_date DATE NOT NULL,
  market_grade VARCHAR(20),
  market_phase VARCHAR(50),
  decision VARCHAR(20),
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sector_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trading_date DATE NOT NULL,
  sector_name VARCHAR(100) NOT NULL,
  strength_score DECIMAL(8,4),
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS candidate_stock (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trading_date DATE NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  stock_name VARCHAR(120),
  score DECIMAL(8,4),
  reason VARCHAR(500),
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS stock_evaluation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trading_date DATE NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  stock_profile VARCHAR(40),
  valuation_mode VARCHAR(40),
  entry_price_zone VARCHAR(100),
  stop_loss_price DECIMAL(12,4),
  take_profit_1 DECIMAL(12,4),
  take_profit_2 DECIMAL(12,4),
  risk_reward_ratio DECIMAL(8,4),
  include_in_final_plan BOOLEAN,
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS final_decision (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trading_date DATE NOT NULL,
  decision VARCHAR(20) NOT NULL,
  summary VARCHAR(1000),
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS trading_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  decision_id BIGINT,
  symbol VARCHAR(20),
  position_size DECIMAL(12,4),
  stop_loss_price DECIMAL(12,4),
  take_profit_1 DECIMAL(12,4),
  take_profit_2 DECIMAL(12,4),
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS hourly_gate_decision (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trading_date DATE NOT NULL,
  gate_time TIME NOT NULL,
  hourly_gate VARCHAR(20) NOT NULL,
  should_run_5m_monitor BOOLEAN,
  trigger_event VARCHAR(50),
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS monitor_decision (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trading_date DATE NOT NULL,
  decision_time DATETIME NOT NULL,
  monitor_mode VARCHAR(20) NOT NULL,
  should_notify BOOLEAN,
  trigger_event VARCHAR(50),
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS position (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  symbol VARCHAR(20) NOT NULL,
  side VARCHAR(10) DEFAULT 'LONG',
  qty DECIMAL(12,4),
  avg_cost DECIMAL(12,4),
  status VARCHAR(20),
  opened_at DATETIME,
  closed_at DATETIME,
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS daily_pnl (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trading_date DATE NOT NULL,
  realized_pnl DECIMAL(14,4),
  unrealized_pnl DECIMAL(14,4),
  win_rate DECIMAL(8,4),
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS notification_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_time DATETIME NOT NULL,
  notification_type VARCHAR(40),
  source VARCHAR(40),
  title VARCHAR(200),
  content TEXT,
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS trading_state (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trading_date DATE NOT NULL,
  market_grade VARCHAR(20),
  decision_lock VARCHAR(20),
  time_decay_stage VARCHAR(20),
  hourly_gate VARCHAR(20),
  monitor_mode VARCHAR(20),
  payload_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS scheduler_execution_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_name VARCHAR(120) NOT NULL,
  trigger_time DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL,
  duration_ms BIGINT,
  message VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

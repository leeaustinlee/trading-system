CREATE INDEX idx_market_snapshot_date ON market_snapshot (trading_date);
CREATE INDEX idx_candidate_stock_date_symbol ON candidate_stock (trading_date, symbol);
CREATE INDEX idx_stock_eval_date_symbol ON stock_evaluation (trading_date, symbol);
CREATE INDEX idx_final_decision_date ON final_decision (trading_date);
CREATE INDEX idx_monitor_decision_date_time ON monitor_decision (trading_date, decision_time);
CREATE INDEX idx_notification_log_event_time ON notification_log (event_time);
CREATE INDEX idx_scheduler_log_job_time ON scheduler_execution_log (job_name, trigger_time);

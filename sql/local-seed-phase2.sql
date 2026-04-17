-- Local seed data for Phase 2/3 verification
-- Run manually on local DB: trading_system

SET NAMES utf8mb4;

DELETE FROM stock_evaluation WHERE trading_date = CURDATE();
DELETE FROM candidate_stock WHERE trading_date = CURDATE();
DELETE FROM market_snapshot WHERE trading_date = CURDATE();
DELETE FROM trading_state WHERE trading_date = CURDATE();

INSERT INTO market_snapshot (trading_date, market_grade, market_phase, decision, payload_json)
VALUES
  (CURDATE(), 'B', '高檔震盪期', 'WATCH', JSON_OBJECT('source','local-seed-phase2','note','market snapshot seed'));

INSERT INTO trading_state (trading_date, market_grade, decision_lock, time_decay_stage, hourly_gate, monitor_mode, payload_json)
VALUES
  (CURDATE(), 'B', 'NONE', 'EARLY', 'ON', 'WATCH', JSON_OBJECT('source','local-seed-phase2','note','state seed'));

INSERT INTO candidate_stock (trading_date, symbol, stock_name, score, reason, payload_json)
VALUES
  (CURDATE(), '2330', '台積電', 9.2000, '主流半導體，突破前高結構', JSON_OBJECT('entry_hint','breakout')),
  (CURDATE(), '2303', '聯電',   8.3000, '回測不破均價，等待第二段量增', JSON_OBJECT('entry_hint','pullback')),
  (CURDATE(), '2454', '聯發科', 7.9000, '洗盤後轉強，族群同步', JSON_OBJECT('entry_hint','reversal'));

INSERT INTO stock_evaluation (
  trading_date, symbol, stock_profile, valuation_mode, entry_price_zone,
  stop_loss_price, take_profit_1, take_profit_2, risk_reward_ratio,
  include_in_final_plan, payload_json
)
VALUES
  (CURDATE(), '2330', 'TREND_FUNDAMENTAL', 'VALUE_FAIR', '1010-1020', 995.0000, 1065.0000, 1100.0000, 2.3000, TRUE, JSON_OBJECT('source','local-seed-phase2')),
  (CURDATE(), '2303', 'MOMENTUM_LEADER',   'VALUE_LOW',  '52.0-52.5', 51.0000,   55.0000,   57.0000,   2.1000, TRUE, JSON_OBJECT('source','local-seed-phase2')),
  (CURDATE(), '2454', 'MOMENTUM_LEADER',   'VALUE_HIGH', '1200-1210', 1180.0000, 1260.0000, 1300.0000, 1.9000, TRUE, JSON_OBJECT('source','local-seed-phase2'));

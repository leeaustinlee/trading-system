-- Phase 3: Position 關閉欄位
ALTER TABLE position
  ADD COLUMN close_price   DECIMAL(12,4) NULL AFTER closed_at,
  ADD COLUMN realized_pnl  DECIMAL(14,4) NULL AFTER close_price;

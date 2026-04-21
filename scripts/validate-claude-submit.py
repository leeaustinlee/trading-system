#!/usr/bin/env python3
"""
Claude submit 本地驗證（v2.5）。

給 Claude Code Agent 在寫 `claude-submit/*.json` 前 run 一次，
檢查 scores.keys / thesis.keys ⊆ request.allowed_symbols。

用法：
  python3 validate-claude-submit.py \
      --request D:/ai/stock/claude-research-request.json \
      --submit  D:/ai/stock/claude-submit/claude-OPENING-2026-04-21-0920-task-8.json

退出碼：
  0  通過（可以 rename .tmp → .json）
  2  CLAUDE_LOCAL_SYMBOL_MISMATCH（禁止寫入 submit）
  3  格式錯（request 或 submit JSON 無法解析）
  4  參數錯
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def load_json(path: Path) -> dict:
    try:
        with path.open("r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        print(f"[validate] ERROR: 無法解析 {path}: {e}", file=sys.stderr)
        sys.exit(3)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--request", required=True, help="Java 寫的 claude-research-request.json")
    ap.add_argument("--submit",  required=True, help="Claude 即將提交的 claude-submit JSON")
    args = ap.parse_args()

    req_path = Path(args.request)
    sub_path = Path(args.submit)
    if not req_path.exists():
        print(f"[validate] ERROR: request 檔不存在：{req_path}", file=sys.stderr)
        return 4
    if not sub_path.exists():
        print(f"[validate] ERROR: submit 檔不存在：{sub_path}", file=sys.stderr)
        return 4

    req = load_json(req_path)
    sub = load_json(sub_path)

    # allowed_symbols 優先用 v2.5 明確欄位，fallback 到舊 candidates
    allowed = req.get("allowed_symbols") or req.get("candidates") or []
    allowed_set = {str(s).strip() for s in allowed if s}

    if not allowed_set:
        print("[validate] ERROR: request 內無 allowed_symbols/candidates，無法驗證", file=sys.stderr)
        return 4

    scores = sub.get("scores") or {}
    thesis = sub.get("thesis") or {}

    invalid_score = [k for k in scores.keys() if str(k).strip() not in allowed_set]
    invalid_thesis = [k for k in thesis.keys() if str(k).strip() not in allowed_set]

    if invalid_score or invalid_thesis:
        print("[validate] ❌ CLAUDE_LOCAL_SYMBOL_MISMATCH")
        print(f"  taskType       = {sub.get('taskType') or req.get('taskType')}")
        print(f"  taskId         = {sub.get('taskId')}")
        print(f"  allowed_symbols= {sorted(allowed_set)}")
        if invalid_score:
            print(f"  invalid scores = {invalid_score}")
        if invalid_thesis:
            print(f"  invalid thesis = {invalid_thesis}")
        print("  ⇒ 禁止寫入 submit！請修正 scores/thesis，只保留 allowed_symbols 子集。")
        return 2

    # 額外檢查：taskId / taskType / tradingDate 一致性（警告而非擋）
    warnings = []
    if req.get("taskId") is not None and sub.get("taskId") not in (None, req.get("taskId")):
        warnings.append(f"taskId mismatch: request={req.get('taskId')} submit={sub.get('taskId')}")
    req_type = req.get("taskType") or req.get("type")
    sub_type = sub.get("taskType")
    if req_type and sub_type and str(req_type).upper() != str(sub_type).upper():
        warnings.append(f"taskType mismatch: request={req_type} submit={sub_type}")
    if req.get("trading_date") and sub.get("tradingDate") and req["trading_date"] != sub["tradingDate"]:
        warnings.append(f"tradingDate mismatch: request={req['trading_date']} submit={sub['tradingDate']}")
    for w in warnings:
        print(f"[validate] WARN: {w}")

    total_scored = len(scores)
    coverage_pct = (total_scored / len(allowed_set) * 100) if allowed_set else 0
    print(f"[validate] ✅ OK — scores {total_scored}/{len(allowed_set)} ({coverage_pct:.0f}%)  thesis={len(thesis)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

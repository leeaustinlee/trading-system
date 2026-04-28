#!/usr/bin/env python3
"""
trace_one.py — Forensic tracer for the FinalDecisionEngine pipeline.

Given a (symbol, trading_date) pair OR a final_decision.id, dumps every
intermediate row that participated in producing the final_decision and
prints a verdict explaining where an ENTER signal might have been lost.

Usage:
    python3 scripts/trace_one.py --symbol 6770 --date 2026-04-22
    python3 scripts/trace_one.py --decision-id 36
    python3 scripts/trace_one.py --symbol 6770 --date 2026-04-22 --json

The script shells out to `docker exec hktv_mms_db mysql ...` so no Python
SQL driver is required. Requires WSL access to the host docker container.
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
from typing import Any

# ---------------------------------------------------------------------------
# DB helper — prefer WSL+docker. Detects environment and switches form.
# ---------------------------------------------------------------------------

def _is_wsl() -> bool:
    try:
        return "microsoft" in (os.uname().release.lower() if hasattr(os, "uname") else "")
    except Exception:
        return False


def _is_windows() -> bool:
    return os.name == "nt"


def _run_sql(sql: str) -> list[list[str]]:
    """Run a SELECT and return list of rows (each row = list of columns).

    Three execution modes:
      1. Already inside container (CI) — run mysql directly.
      2. Inside WSL but on host shell — try `docker exec` directly; if the
         current user lacks docker socket permissions (typical for non-root
         WSL users), fall back to `sudo -n` then to `wsl --user root` proxy.
      3. Windows (PowerShell / cmd) — invoke `wsl --user root -- bash -c`.
    """
    docker_inner = ("docker exec hktv_mms_db mysql -uroot -pHKtv2014 trading_system "
                    f"-N -B --default-character-set=utf8mb4 -e {shlex.quote(sql)}")
    candidates: list[list[str]] = []
    if _is_windows():
        candidates.append(["wsl", "--user", "root", "--", "bash", "-c", docker_inner])
    else:
        candidates.append(["bash", "-c", docker_inner])
        candidates.append(["sudo", "-n", "bash", "-c", docker_inner])
        # When inside WSL as a non-root user, hop to root WSL.
        if _is_wsl():
            candidates.append(["wsl.exe", "--user", "root", "--", "bash", "-c", docker_inner])
    last_err = ""
    for cmd in candidates:
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                               errors="replace")
        except FileNotFoundError as e:
            last_err = str(e)
            continue
        if p.returncode == 0:
            rows: list[list[str]] = []
            for line in p.stdout.splitlines():
                if not line or line.startswith("Warning"):
                    continue
                rows.append(line.split("\t"))
            return rows
        last_err = (p.stderr or "").strip().splitlines()[-1] if p.stderr else "(no stderr)"
    sys.stderr.write(f"SQL FAILED: {sql}\n  {last_err}\n")
    return []


def _q(value: str | int | None) -> str:
    """SQL quote (very minimal — values already validated)."""
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def _parse_json(s: str | None) -> Any:
    if s is None or s == "" or s == "NULL":
        return None
    try:
        return json.loads(s)
    except (TypeError, ValueError):
        return s


# ---------------------------------------------------------------------------
# Trace data collectors
# ---------------------------------------------------------------------------

def _resolve_target(decision_id: int | None, symbol: str | None,
                     date: str | None) -> tuple[str, str, list[int]]:
    """Return (symbol, trading_date, [final_decision.id ...])."""
    if decision_id is not None:
        rows = _run_sql(
            f"SELECT trading_date, JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.selected_stocks[0].stockCode')) "
            f"FROM final_decision WHERE id={decision_id}")
        if not rows:
            sys.exit(f"ERROR: final_decision.id={decision_id} not found")
        date = rows[0][0]
        sym = rows[0][1] if len(rows[0]) > 1 else None
        if sym is None or sym == "NULL":
            print(f"WARN: final_decision id={decision_id} has no selected_stocks; symbol unknown.")
            sym = "(unknown)"
        return sym, date, [decision_id]
    if symbol is None or date is None:
        sys.exit("ERROR: must pass --decision-id OR (--symbol AND --date)")
    rows = _run_sql(f"SELECT id FROM final_decision WHERE trading_date={_q(date)} ORDER BY id")
    fd_ids = [int(r[0]) for r in rows]
    return symbol, date, fd_ids


def collect_candidate_stock(symbol: str, date: str) -> dict[str, Any]:
    rows = _run_sql(
        "SELECT id, score, theme_tag, sector, is_momentum_candidate, "
        "JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.tradabilityTag')) AS tradability_tag, "
        "JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.includeInFinalPlan')) AS include_in_plan, "
        "JSON_KEYS(payload_json) AS payload_keys "
        f"FROM candidate_stock WHERE symbol={_q(symbol)} AND trading_date={_q(date)}")
    if not rows:
        return {"found": False}
    r = rows[0]
    return {
        "found": True,
        "id": int(r[0]),
        "score": r[1],
        "theme_tag": r[2],
        "sector": r[3],
        "is_momentum_candidate": r[4] == "1",
        "tradabilityTag": r[5] if r[5] != "NULL" else None,
        "includeInFinalPlan": r[6] if r[6] != "NULL" else None,
        "payload_keys": _parse_json(r[7]),
    }


def collect_stock_evaluation(symbol: str, date: str) -> dict[str, Any]:
    rows = _run_sql(
        "SELECT id, java_structure_score, claude_score, codex_score, ai_weighted_score, "
        "final_rank_score, score_dispersion, consensus_score, disagreement_penalty, "
        "is_vetoed, java_veto_flags, theme_tag, momentum_score, "
        "JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.veto_trace.bucket')) AS bucket, "
        "JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.veto_trace.raw_rank')) AS raw_rank, "
        "JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.veto_trace.penalty')) AS penalty, "
        "JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.scoring_trace.aiConfidenceMode')) AS ai_mode, "
        "JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.scoring_trace.aiWeightOverrideReason')) AS reweight_reason "
        f"FROM stock_evaluation WHERE symbol={_q(symbol)} AND trading_date={_q(date)}")
    if not rows:
        return {"found": False}
    r = rows[0]
    return {
        "found": True,
        "id": int(r[0]),
        "java_structure_score": r[1],
        "claude_score": r[2],
        "codex_score": r[3],
        "ai_weighted_score": r[4],
        "final_rank_score": r[5],
        "score_dispersion": r[6],
        "consensus_score": r[7],
        "disagreement_penalty": r[8],
        "is_vetoed": r[9] == "1",
        "java_veto_flags": _parse_json(r[10]),
        "theme_tag": r[11] if r[11] != "NULL" else None,
        "momentum_score": r[12],
        "bucket": r[13] if r[13] != "NULL" else None,
        "raw_rank": r[14] if r[14] != "NULL" else None,
        "penalty": r[15] if r[15] != "NULL" else None,
        "aiConfidenceMode": r[16] if r[16] != "NULL" else None,
        "aiWeightOverrideReason": r[17] if r[17] != "NULL" else None,
    }


def collect_theme_strength(symbol: str, date: str, theme_tag: str | None) -> dict[str, Any]:
    if not theme_tag:
        return {"found": False, "reason": "candidate has no theme_tag"}
    rows = _run_sql(
        "SELECT id, theme_tag, strength_score, theme_stage, decay_risk, tradable, "
        "catalyst_type "
        f"FROM theme_strength_decision WHERE theme_tag={_q(theme_tag)} AND trading_date={_q(date)}")
    if not rows:
        return {"found": False, "theme_tag": theme_tag}
    r = rows[0]
    return {
        "found": True,
        "id": int(r[0]),
        "theme_tag": r[1],
        "strength_score": r[2],
        "theme_stage": r[3],
        "decay_risk": r[4],
        "tradable": r[5] == "1",
        "catalyst_type": r[6],
    }


def collect_execution_decisions(symbol: str, date: str) -> list[dict[str, Any]]:
    rows = _run_sql(
        "SELECT id, action, reason_code, codex_vetoed, ranking_snapshot_id, "
        "regime_decision_id, setup_decision_id, timing_decision_id, risk_decision_id, "
        "JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.baseAction')) AS base, "
        "JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.timingMode')) AS timing_mode, "
        "JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.regimeType')) AS regime, "
        "JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.riskApproved')) AS risk_ok "
        f"FROM execution_decision_log WHERE symbol={_q(symbol)} AND trading_date={_q(date)} ORDER BY id")
    return [
        {
            "id": int(r[0]), "action": r[1], "reason_code": r[2],
            "codex_vetoed": r[3] == "1", "ranking_snapshot_id": r[4],
            "regime_decision_id": r[5], "setup_decision_id": r[6],
            "timing_decision_id": r[7], "risk_decision_id": r[8],
            "baseAction": r[9] if r[9] != "NULL" else None,
            "timingMode": r[10] if r[10] != "NULL" else None,
            "regimeType": r[11] if r[11] != "NULL" else None,
            "riskApproved": r[12] if r[12] != "NULL" else None,
        }
        for r in rows
    ]


def collect_setup_decision(symbol: str, date: str) -> dict[str, Any]:
    rows = _run_sql(
        "SELECT id, setup_type, valid+0 AS valid_int, rejection_reason, "
        "ideal_entry_price, initial_stop_price, take_profit_1_price, take_profit_2_price, "
        "entry_zone_low, entry_zone_high, holding_window_days, trailing_mode "
        f"FROM setup_decision_log WHERE symbol={_q(symbol)} AND trading_date={_q(date)} "
        "ORDER BY id DESC LIMIT 1")
    if not rows:
        return {"found": False}
    r = rows[0]
    return {
        "found": True, "id": int(r[0]), "setup_type": r[1],
        "valid": r[2] == "1", "rejection_reason": r[3] if r[3] != "NULL" else None,
        "ideal_entry_price": r[4], "initial_stop_price": r[5],
        "take_profit_1_price": r[6], "take_profit_2_price": r[7],
        "entry_zone_low": r[8], "entry_zone_high": r[9],
        "holding_window_days": r[10], "trailing_mode": r[11],
    }


def collect_market_regime(date: str) -> list[dict[str, Any]]:
    rows = _run_sql(
        "SELECT id, market_grade, regime_type, trade_allowed+0, risk_multiplier, "
        "summary, evaluated_at "
        f"FROM market_regime_decision WHERE trading_date={_q(date)} ORDER BY id")
    return [
        {
            "id": int(r[0]), "market_grade": r[1], "regime_type": r[2],
            "trade_allowed": r[3] == "1", "risk_multiplier": r[4],
            "summary": r[5], "evaluated_at": r[6],
        } for r in rows
    ]


def collect_monitor_decision(date: str) -> list[dict[str, Any]]:
    rows = _run_sql(
        "SELECT id, decision_time, monitor_mode, should_notify+0, trigger_event "
        f"FROM monitor_decision WHERE trading_date={_q(date)} ORDER BY id")
    return [
        {"id": int(r[0]), "decision_time": r[1], "monitor_mode": r[2],
         "should_notify": r[3] == "1", "trigger_event": r[4]} for r in rows
    ]


def collect_final_decisions(date: str, fd_ids: list[int],
                             symbol: str) -> list[dict[str, Any]]:
    if not fd_ids:
        return []
    id_csv = ",".join(str(i) for i in fd_ids)
    rows = _run_sql(
        f"SELECT id, decision, summary, fallback_reason, source_task_type, "
        f"strategy_type, ai_status, ai_task_id, "
        f"JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.summary')) AS payload_summary, "
        f"JSON_EXTRACT(payload_json,'$.rejected_reasons') AS rejected, "
        f"JSON_EXTRACT(payload_json,'$.selected_stocks') AS selected, "
        f"JSON_KEYS(payload_json) AS payload_keys, created_at "
        f"FROM final_decision WHERE id IN ({id_csv}) ORDER BY id")
    out: list[dict[str, Any]] = []
    for r in rows:
        rejected = _parse_json(r[9])
        selected = _parse_json(r[10])
        # Filter rejected/selected to entries that mention our symbol
        sel_for_sym = []
        if isinstance(selected, list):
            sel_for_sym = [s for s in selected if isinstance(s, dict)
                           and s.get("stockCode") == symbol]
        rej_for_sym = []
        if isinstance(rejected, list):
            rej_for_sym = [s for s in rejected if isinstance(s, str) and symbol in s]
        out.append({
            "id": int(r[0]), "decision": r[1], "summary": r[2],
            "fallback_reason": r[3] if r[3] != "NULL" else None,
            "source_task_type": r[4], "strategy_type": r[5],
            "ai_status": r[6], "ai_task_id": r[7],
            "payload_summary": r[8] if r[8] != "NULL" else None,
            "selected_for_symbol": sel_for_sym,
            "rejected_for_symbol": rej_for_sym,
            "payload_keys": _parse_json(r[11]),
            "created_at": r[12],
        })
    return out


SCORE_CONFIG_KEYS = [
    "scoring.grade_ap_min", "scoring.grade_a_min", "scoring.grade_b_min",
    "scoring.rr_min_ap", "scoring.rr_min_grade_a", "scoring.rr_min_grade_b",
    "scoring.java_weight", "scoring.claude_weight", "scoring.codex_weight",
    "scoring.late_stop_market_grade",
    "veto.theme_rank_max", "veto.final_theme_score_min",
    "veto.codex_score_min", "veto.score_divergence_max", "veto.require_theme",
    "consensus.penalty_jc", "consensus.penalty_jx", "consensus.penalty_cx",
    "penalty.rr_below_min", "penalty.not_in_final_plan",
    "penalty.high_val_weak_market", "penalty.no_theme",
    "penalty.codex_low", "penalty.theme_not_top", "penalty.theme_score_too_low",
    "penalty.score_divergence_high", "penalty.entry_too_extended",
    "decision.max_pick_aplus", "decision.max_pick_a", "decision.max_pick_b",
    "decision.position_factor_aplus", "decision.position_factor_a", "decision.position_factor_b",
    "theme.engine.v2.enabled", "theme.shadow_mode.enabled",
    "final_decision.ai_default_reweight.enabled",
    "final_decision.respect_tradability_tag.enabled",
    "final_decision.tradability_tag.soft_penalty",
    "final_decision.select_buy_now_bypass_soft_penalty.enabled",
    "final_decision.require_claude", "final_decision.require_codex",
    "trading.status.allow_trade",
    "ranking.main_stream_boost",
]


def collect_score_config() -> dict[str, str]:
    in_clause = ",".join(_q(k) for k in SCORE_CONFIG_KEYS)
    rows = _run_sql(
        f"SELECT config_key, config_value FROM score_config WHERE config_key IN ({in_clause}) "
        "ORDER BY config_key")
    return {r[0]: r[1] for r in rows}


# ---------------------------------------------------------------------------
# Verdict reasoner
# ---------------------------------------------------------------------------

def derive_verdict(trace: dict[str, Any]) -> list[str]:
    lines: list[str] = []
    sev = trace.get("stock_evaluation", {})
    final_rank = sev.get("final_rank_score")
    bucket = sev.get("bucket")
    is_vetoed = sev.get("is_vetoed", False)
    raw_rank = sev.get("raw_rank")
    penalty = sev.get("penalty")
    flags = sev.get("java_veto_flags") or []

    # Did execution layer say ENTER?
    exec_actions = [e["action"] for e in trace.get("execution_decisions", [])]
    exec_enter = "ENTER" in exec_actions

    # Did final_decision say ENTER?
    fd_decisions = [f["decision"] for f in trace.get("final_decisions", [])]
    fd_enter = any(d == "ENTER" for d in fd_decisions)

    if not exec_enter and not fd_enter:
        lines.append("Neither execution nor final_decision recommended ENTER. "
                     "Likely a setup-layer or ranking-layer reject; not the FinalDecisionEngine bottleneck.")
        return lines
    if exec_enter and fd_enter:
        lines.append("Both execution and final_decision agree on ENTER. No silent downgrade detected.")
        return lines
    if exec_enter and not fd_enter:
        lines.append("ENTER signal at execution layer; final_decision shows REST/PLAN/WAIT. "
                     "Investigating downgrade reasons:")

    cfg = trace.get("score_config", {})
    grade_b_min = float(cfg.get("scoring.grade_b_min", "6.5"))
    grade_a_min = float(cfg.get("scoring.grade_a_min", "7.5"))

    # Rule 1: bucket=C
    if bucket == "C" or (final_rank is not None and float(final_rank) < grade_b_min):
        lines.append(f"  GATE: stock_evaluation.bucket={bucket}, final_rank={final_rank} "
                     f"< grade_b_min={grade_b_min} → FinalDecisionEngine REJECTS as 等級 C.")
        if penalty and float(penalty) > 0:
            lines.append(f"  rawRank={raw_rank}, penalty={penalty}, finalRank={final_rank}")
            lines.append(f"  penalty reasons: {flags}")
            lines.append("  Penalty cascade pushes finalRank below B threshold — this is the hidden gate.")

    # Rule 2: hard veto
    if is_vetoed:
        lines.append(f"  GATE: stock_evaluation.is_vetoed=true, flags={flags}")
        lines.append("  VetoEngine HARD VETO blocks this candidate.")

    # Rule 3: postclose plan vs intraday
    fd_postclose = [f for f in trace.get("final_decisions", []) if (f.get("source_task_type") or "").endswith("MARKET")
                    or f.get("source_task_type") in ("POSTMARKET", "T86_TOMORROW")]
    fd_intraday = [f for f in trace.get("final_decisions", []) if f.get("source_task_type") in ("OPENING", "MIDDAY")]
    if fd_postclose and not any(f["decision"] == "ENTER" for f in fd_intraday):
        plan_listed = False
        for f in fd_postclose:
            if f.get("selected_for_symbol"):
                plan_listed = True
                break
        if plan_listed:
            lines.append("  POSTCLOSE PLAN listed this symbol (PLAN row), but INTRADAY OPENING/MIDDAY did NOT promote to ENTER. "
                         "This means the postclose pre-rank was strong enough to be a primary/backup candidate, "
                         "but on the actual trading day the same scoring pipeline produced a lower rank.")

    # Rule 4: market_grade=C kills
    regimes = trace.get("market_regimes", [])
    if any(r.get("market_grade") == "C" for r in regimes):
        lines.append("  GATE: market_regime.market_grade=C present — HARD REST regardless of candidate scores.")

    # Rule 5: ai_default_reweight not engaged when both AI present
    reweight = sev.get("aiWeightOverrideReason")
    if reweight in (None, "NO_OVERRIDE", "FLAG_DISABLED"):
        lines.append(f"  NOTE: ai_default_reweight={reweight} (not engaged for this candidate). "
                     "If Java=low / Claude=high, weighted dilution may be acceptable, but min(weighted, consensus) "
                     "still anchors to the lower of the two. Consensus path can drop scores significantly.")

    # Rule 6: penalty.rr_below_min cascade
    if "PENALTY:RR_BELOW_MIN" in (flags or []):
        lines.append("  COMMON PATTERN: PENALTY:RR_BELOW_MIN — the candidate's RR (e.g. 1.33) is below "
                     "scoring.rr_min_grade_b (1.8). Setup-generated TP1/TP2 from default 8%/12% targets "
                     "rarely produce RR>=1.8 with a 5% stop. Every PULLBACK setup is hit by this 0.5 penalty.")

    if not lines or (len(lines) == 1 and lines[0].endswith(":")):
        lines.append("  No single offending gate identified; check the raw trace above.")
    return lines


# ---------------------------------------------------------------------------
# Pretty printer
# ---------------------------------------------------------------------------

def render_text(trace: dict[str, Any]) -> str:
    out: list[str] = []

    def hdr(s: str):
        out.append("\n" + "=" * 78)
        out.append(s)
        out.append("=" * 78)

    out.append(f"trace_one.py — symbol={trace['symbol']} date={trace['trading_date']}")
    if trace.get("decision_id_filter"):
        out.append(f"filter: final_decision.id IN {trace['decision_id_filter']}")

    hdr("1. candidate_stock")
    cs = trace["candidate_stock"]
    if cs.get("found"):
        for k, v in cs.items():
            if k == "found": continue
            out.append(f"  {k:<26} {v}")
    else:
        out.append("  (no row)")

    hdr("2. stock_evaluation (final scoring after veto/penalty)")
    sev = trace["stock_evaluation"]
    if sev.get("found"):
        for k, v in sev.items():
            if k == "found": continue
            out.append(f"  {k:<26} {v}")
    else:
        out.append("  (no row — never scored)")

    hdr("3. theme_strength_decision")
    th = trace["theme_strength"]
    for k, v in th.items():
        out.append(f"  {k:<26} {v}")

    hdr("4. execution_decision_log (lower-layer baseAction)")
    edl = trace["execution_decisions"]
    if not edl:
        out.append("  (no rows)")
    for e in edl:
        out.append(f"  id={e['id']} action={e['action']} reason={e['reason_code']} "
                   f"codex_vetoed={e['codex_vetoed']} regime={e['regimeType']} timing={e['timingMode']} "
                   f"risk_ok={e['riskApproved']}")

    hdr("5. setup_decision_log")
    sd = trace["setup_decision"]
    if sd.get("found"):
        for k, v in sd.items():
            if k == "found": continue
            out.append(f"  {k:<26} {v}")
    else:
        out.append("  (no row)")

    hdr("6. market_regime_decision (market_grade for the day)")
    for r in trace["market_regimes"]:
        out.append(f"  id={r['id']} grade={r['market_grade']} regime={r['regime_type']} "
                   f"allowed={r['trade_allowed']} mult={r['risk_multiplier']} at={r['evaluated_at']}")
    if not trace["market_regimes"]:
        out.append("  (no rows)")

    hdr("7. monitor_decision")
    for m in trace["monitor_decision"]:
        out.append(f"  id={m['id']} time={m['decision_time']} mode={m['monitor_mode']} "
                   f"notify={m['should_notify']} trigger={m['trigger_event']}")
    if not trace["monitor_decision"]:
        out.append("  (no rows)")

    hdr("8. score_config snapshot (relevant keys)")
    cfg = trace["score_config"]
    for k in SCORE_CONFIG_KEYS:
        out.append(f"  {k:<58} {cfg.get(k, '<missing>')}")

    hdr("9. final_decision rows for the day")
    fds = trace["final_decisions"]
    if not fds:
        out.append("  (no rows)")
    for f in fds:
        out.append(f"  id={f['id']} decision={f['decision']} task={f['source_task_type']} "
                   f"strategy={f['strategy_type']} ai={f['ai_status']} fallback={f['fallback_reason']} "
                   f"created={f['created_at']}")
        out.append(f"      summary={f['summary']!r}")
        if f["selected_for_symbol"]:
            for s in f["selected_for_symbol"]:
                out.append(f"      SELECTED: zone={s.get('entryPriceZone')} sl={s.get('stopLossPrice')} "
                           f"tp1={s.get('takeProfit1')} tp2={s.get('takeProfit2')} rr={s.get('riskRewardRatio')}")
        if f["rejected_for_symbol"]:
            for r in f["rejected_for_symbol"]:
                out.append(f"      REJECTED: {r}")

    hdr("10. VERDICT — where was the ENTER lost?")
    for v in derive_verdict(trace):
        out.append(v)

    return "\n".join(out)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    p = argparse.ArgumentParser(description="Trace a final_decision back through the pipeline.")
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--decision-id", type=int, help="final_decision.id to trace")
    g.add_argument("--symbol", type=str, help="symbol (use with --date)")
    p.add_argument("--date", type=str, help="trading_date YYYY-MM-DD (use with --symbol)")
    p.add_argument("--json", action="store_true", help="emit JSON instead of text")
    args = p.parse_args()

    symbol, date, fd_ids = _resolve_target(args.decision_id, args.symbol, args.date)

    cs = collect_candidate_stock(symbol, date)
    sev = collect_stock_evaluation(symbol, date)
    theme_tag = (sev.get("theme_tag") or cs.get("theme_tag")
                 if (sev.get("found") or cs.get("found")) else None)
    trace = {
        "symbol": symbol,
        "trading_date": date,
        "decision_id_filter": [args.decision_id] if args.decision_id else None,
        "candidate_stock": cs,
        "stock_evaluation": sev,
        "theme_strength": collect_theme_strength(symbol, date, theme_tag),
        "execution_decisions": collect_execution_decisions(symbol, date),
        "setup_decision": collect_setup_decision(symbol, date),
        "market_regimes": collect_market_regime(date),
        "monitor_decision": collect_monitor_decision(date),
        "score_config": collect_score_config(),
        "final_decisions": collect_final_decisions(
            date,
            fd_ids if not args.decision_id else [args.decision_id],
            symbol),
    }

    if args.json:
        print(json.dumps(trace, indent=2, ensure_ascii=False, default=str))
    else:
        print(render_text(trace))


if __name__ == "__main__":
    main()

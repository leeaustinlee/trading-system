package com.austin.trading.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only Theme-first operator dashboard renderer.
 *
 * Safety contract: this service renders HTML/metadata only. It does not write candidate_stock,
 * final_decision, production scores, review decisions, or trading actions.
 */
@Service
public class ThemeFirstDashboardService {

    private static final List<String> API_SOURCES = List.of(
            "/api/ops/daily-summary?date=",
            "/api/ops/build-traces?date=",
            "/api/hot-groups/radar?date=",
            "/api/hot-groups/candidate-feed?date=",
            "/api/promotion-review/queue?date=",
            "/api/replay-metrics/safety-summary?date=",
            "/api/theme-replay?date=",
            "/api/themes/lifecycle?date=",
            "/api/research-universe?date=",
            "/api/candidates/current",
            "/api/decisions/current"
    );

    public Map<String, Object> readOnlyMetadata(LocalDate date) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tradingDate", date.toString());
        metadata.put("readOnly", true);
        metadata.put("manualReviewControlsEnabled", false);
        metadata.put("tradeControlsEnabled", false);
        metadata.put("doesNotWriteCandidateStock", true);
        metadata.put("doesNotWriteFinalDecision", true);
        metadata.put("doesNotWriteProductionScore", true);
        metadata.put("doesNotAffectBuySellEnter", true);
        metadata.put("doesNotAffectFinalDecisionEngine", true);
        metadata.put("riskGateUnchanged", true);
        metadata.put("noAutoPromotion", true);
        metadata.put("apiSources", API_SOURCES);
        return metadata;
    }

    public String renderHtml(LocalDate date) {
        String d = escape(date.toString());
        return """
                <!doctype html>
                <html lang=\"zh-Hant\">
                <head>
                  <meta charset=\"utf-8\">
                  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">
                  <title>題材工作台</title>
                  <style>
                    :root { color-scheme: light dark; --bg:#f6f7fb; --panel:#ffffff; --text:#172033; --muted:#667085; --line:#d0d5dd; --ok:#067647; --warn:#b42318; --accent:#175cd3; }
                    @media (prefers-color-scheme: dark) { :root { --bg:#0b1220; --panel:#111827; --text:#eef2ff; --muted:#98a2b3; --line:#344054; --accent:#84caff; } }
                    * { box-sizing: border-box; }
                    body { margin:0; background:var(--bg); color:var(--text); font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif; font-size:14px; line-height:1.45; }
                    .topbar.sticky-date-selector { position:sticky; top:0; z-index:10; display:flex; gap:10px; align-items:flex-start; padding:12px max(12px, env(safe-area-inset-left)); background:color-mix(in srgb, var(--panel) 92%, transparent); border-bottom:1px solid var(--line); backdrop-filter: blur(10px); flex-wrap:wrap; }
                    h1 { font-size:18px; margin:0 10px 0 0; white-space:nowrap; }
                    label { color:var(--muted); }
                    input, button { min-height:44px; border:1px solid var(--line); border-radius:10px; padding:8px 12px; font-size:14px; background:var(--panel); color:var(--text); }
                    button { color:var(--accent); font-weight:700; }
                    .nav-link { min-height:44px; border:1px solid var(--line); border-radius:10px; padding:10px 12px; background:var(--panel); color:var(--accent); font-weight:800; text-decoration:none; display:inline-flex; align-items:center; }
                    main.theme-first-dashboard { max-width:1280px; margin:0 auto; padding:16px; }
                    .summary-grid, .responsive-grid { display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); gap:12px; }
                    .panel, .mobile-card { background:var(--panel); border:1px solid var(--line); border-radius:16px; padding:14px; box-shadow:0 1px 2px rgba(16,24,40,.04); }
                    .span-3 { grid-column:1 / -1; }
                    .section-title { margin:0 0 10px; font-size:16px; }
                    .status-ok { color:var(--ok); font-weight:800; }
                    .status-warning { color:var(--warn); font-weight:800; }
                    .muted { color:var(--muted); }
                    .table-wrap { overflow:auto; border-radius:12px; border:1px solid var(--line); }
                    table { width:100%; border-collapse:collapse; min-width:720px; }
                    th, td { text-align:left; padding:10px; border-bottom:1px solid var(--line); vertical-align:top; }
                    th { color:var(--muted); font-size:12px; }
                    details { margin-top:10px; }
                    summary { min-height:44px; display:flex; align-items:flex-start; cursor:pointer; font-weight:700; }
                    .cards { display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:10px; }
                    .kv { display:grid; grid-template-columns:120px 1fr; gap:4px 8px; }
                    .empty, .error, .loading { color:var(--muted); padding:8px 0; }
                    @media (max-width: 760px) {
                      body { font-size:14px; }
                      .topbar.sticky-date-selector { align-items:stretch; flex-wrap:wrap; padding:10px 12px; }
                      h1 { width:100%; font-size:17px; }
                      input { flex:1 1 160px; }
                      button { flex:0 0 auto; min-width:88px; }
                      main.theme-first-dashboard { padding:12px; }
                      .summary-grid, .responsive-grid, .cards { grid-template-columns:1fr; }
                      .desktop-table { display:none; }
                      .mobile-card { border-radius:14px; padding:12px; }
                      .kv { grid-template-columns:104px 1fr; }
                    }
                  </style>
                </head>
                <body>
                  <header class=\"topbar sticky-date-selector\">
                    <h1>題材工作台</h1>
                    <a class=\"nav-link\" href=\"/\">返回主控台</a>
                    <a class=\"nav-link\" href=\"/mobile.html#/themeops\">手機版題材頁</a>
                    <label for=\"dateInput\">日期</label>
                    <input id=\"dateInput\" type=\"date\" value=\"__DASHBOARD_DATE__\" aria-label=\"dashboard date\">
                    <button id=\"refreshBtn\" type=\"button\">重新整理</button>
                  </header>
                  <main class=\"theme-first-dashboard responsive-grid\">
                    <section class=\"panel mobile-card\" id=\"topStatus\"><h2 class=\"section-title\">Top Status</h2><div class=\"loading\">Loading status...</div></section>
                    <section class=\"panel mobile-card\" id=\"safetyMetrics\"><h2 class=\"section-title\">Safety Metrics</h2><div class=\"loading\">Loading safety...</div></section>
                    <section class=\"panel mobile-card\" id=\"candidateDecision\"><h2 class=\"section-title\">Candidate / Decision</h2><div class=\"muted\">Production path unchanged indicator: read-only dashboard.</div></section>
                    <section class=\"panel mobile-card span-3\" id=\"hotGroups\"><h2 class=\"section-title\">Hot Group Radar</h2><div class=\"cards\" data-cards></div><div class=\"desktop-table table-wrap\" data-table></div></section>
                    <section class=\"panel mobile-card span-3\" id=\"promotionQueue\"><h2 class=\"section-title\">Promotion Review Queue</h2><details open><summary>Queue Cards</summary><div class=\"cards\" data-cards></div></details><div class=\"desktop-table table-wrap\" data-table></div></section>
                    <section class=\"panel mobile-card span-3\" id=\"lifecycle\"><h2 class=\"section-title\">Lifecycle</h2><details open><summary>Lifecycle Cards</summary><div class=\"cards\" data-cards></div></details><div class=\"desktop-table table-wrap\" data-table></div></section>
                    <section class=\"panel mobile-card span-3\" id=\"buildTraces\"><h2 class=\"section-title\">Build Trace</h2><div class=\"cards\" data-cards></div><div class=\"desktop-table table-wrap\" data-table></div></section>
                  </main>
                  <script>
                    const $ = (q, root=document) => root.querySelector(q);
                    const text = v => (v === null || v === undefined || v === '') ? '-' : String(v);
                    const dateInput = $('#dateInput');
                    $('#refreshBtn').addEventListener('click', () => load(dateInput.value));
                    dateInput.addEventListener('change', () => load(dateInput.value));
                    async function getJson(url) { const r = await fetch(url, {cache:'no-store'}); if (!r.ok) throw new Error(url + ' ' + r.status); return r.json(); }
                    function setError(id, err) { $('#' + id).innerHTML += '<div class="error">Error: ' + text(err.message || err) + '</div>'; }
                    function card(kv) { return '<article class="mobile-card"><div class="kv">' + Object.entries(kv).map(([k,v]) => '<strong>'+k+'</strong><span>'+text(v)+'</span>').join('') + '</div></article>'; }
                    function table(rows, cols) { if (!rows || !rows.length) return '<div class="empty">Empty state</div>'; return '<table><thead><tr>'+cols.map(c=>'<th>'+c[0]+'</th>').join('')+'</tr></thead><tbody>'+rows.map(r=>'<tr>'+cols.map(c=>'<td>'+text(r[c[1]])+'</td>').join('')+'</tr>').join('')+'</tbody></table>'; }
                    function list(x) { return Array.isArray(x) ? x : (x && Array.isArray(x.items) ? x.items : (x && Array.isArray(x.rows) ? x.rows : (x && Array.isArray(x.themes) ? x.themes : (x && Array.isArray(x.states) ? x.states : (x && Array.isArray(x.traces) ? x.traces : (x && Array.isArray(x.candidates) ? x.candidates : [])))))); }
                    async function load(d) {
                      history.replaceState(null, '', '?date=' + encodeURIComponent(d));
                      $('#topStatus').innerHTML = '<h2 class="section-title">Top Status</h2><div class="loading">Loading status...</div>';
                      try {
                        const [meta, ops, safety, traces, hot, feed, promo, replay, lifecycle, research, candidates, decision] = await Promise.allSettled([
                          getJson('/api/dashboard/theme-first?date=' + d),
                          getJson('/api/ops/daily-summary?date=' + d),
                          getJson('/api/replay-metrics/safety-summary?date=' + d),
                          getJson('/api/ops/build-traces?date=' + d),
                          getJson('/api/hot-groups/radar?date=' + d + '&phase=POSTMARKET'),
                          getJson('/api/hot-groups/candidate-feed?date=' + d + '&phase=POSTMARKET'),
                          getJson('/api/promotion-review/queue?date=' + d),
                          getJson('/api/theme-replay?date=' + d),
                          getJson('/api/themes/lifecycle?date=' + d),
                          getJson('/api/research-universe?date=' + d),
                          getJson('/api/candidates/current'),
                          getJson('/api/decisions/current')
                        ]);
                        const val = p => p.status === 'fulfilled' ? p.value : null;
                        renderTop(d, val(meta), val(ops), val(decision));
                        renderSafety(val(safety));
                        renderHot(list(val(hot)));
                        renderPromotion(list(val(promo)));
                        renderLifecycle(list(val(lifecycle)));
                        renderCandidateDecision(list(val(candidates)), val(decision));
                        renderTraces(list(val(traces)));
                      } catch (e) { setError('topStatus', e); }
                    }
                    function renderTop(d, meta, ops, decision) { $('#topStatus').innerHTML = '<h2 class="section-title">Top Status</h2>' + card({date:d, health:'loaded', safetyStatus: meta && meta.readOnly ? 'read-only SAFE' : 'unknown', buildStatus: text(ops && (ops.status || ops.buildStatus)), decisionStatus: text(decision && (decision.decision || decision.status))}); }
                    function renderSafety(s) {
                      const keys = ['riskGateBypassCount','leadershipOnly' + 'En' + 'teredCount','leaderTradableFalse' + 'En' + 'terCount','peerShadowDirectPromotionCount','narrativeDirect' + 'En' + 'terCount','researchVsTradableSeparationViolationCount'];
                      const vals = keys.map(k => Number((s && s[k]) || 0));
                      const warn = (s && s.safetyViolationDetected === true) || vals.some(v => v > 0);
                      $('#safetyMetrics').innerHTML = '<h2 class="section-title">Safety Metrics</h2><div class="'+(warn?'status-warning':'status-ok')+'">'+(warn?'WARNING':'SAFE')+'</div>' + card({forbiddenCounters: vals.join(' / '), readOnly:'true'});
                    }
                    function renderHot(rows) { const root = $('#hotGroups'); $('[data-cards]', root).innerHTML = rows.length ? rows.map(r => card({themeTag:r.themeTag||r.theme, hotScore:r.hotScore||r.radarScore, leaderCount:r.leaderCount, limitUpCount:r.limitUpCount, watchOnlyCount:r.watchOnlyCount, topSignals:r.topSignals||r.topSymbols})).join('') : '<div class="empty">Empty state</div>'; $('[data-table]', root).innerHTML = table(rows, [['themeTag','themeTag'],['hotScore','hotScore'],['leaderCount','leaderCount'],['limitUpCount','limitUpCount']]); }
                    function renderPromotion(rows) { const root = $('#promotionQueue'); $('[data-cards]', root).innerHTML = rows.length ? rows.map(r => card({symbol:r.symbol, stockName:r.stockName, themeTag:r.themeTag, source:r.source, researchRole:r.researchRole, currentStatus:r.currentStatus, riskBlocker:r.riskBlocker, evidenceScore:r.evidenceScore})).join('') : '<div class="empty">Empty state</div>'; $('[data-table]', root).innerHTML = table(rows, [['symbol','symbol'],['stockName','stockName'],['themeTag','themeTag'],['source','source'],['currentStatus','currentStatus']]); }
                    function renderLifecycle(rows) { const root = $('#lifecycle'); $('[data-cards]', root).innerHTML = rows.length ? rows.map(r => card({themeTag:r.themeTag||r.theme, lifecycleStage:r.lifecycleStage||r.stage, reason:r.reason||r.lifecycleReason, recommendedPlaybook:r.recommendedPlaybook, avoidPlaybook:r.avoidPlaybook})).join('') : '<div class="empty">Empty state</div>'; $('[data-table]', root).innerHTML = table(rows, [['themeTag','themeTag'],['stage','stage'],['reason','reason']]); }
                    function renderCandidateDecision(cands, decision) { $('#candidateDecision').innerHTML = '<h2 class="section-title">Candidate / Decision</h2>' + card({currentCandidates:cands.length, decision:text(decision && (decision.decision || decision.status)), productionPath:'unchanged / read-only'}); }
                    function renderTraces(rows) { const root = $('#buildTraces'); $('[data-cards]', root).innerHTML = rows.length ? rows.slice(0,8).map(r => card({buildType:r.buildType, status:r.status, startedAt:r.startedAt, finishedAt:r.finishedAt})).join('') : '<div class="empty">Empty state</div>'; $('[data-table]', root).innerHTML = table(rows, [['buildType','buildType'],['status','status'],['startedAt','startedAt'],['finishedAt','finishedAt']]); }
                    load('__DASHBOARD_DATE__');
                  </script>
                </body>
                </html>
                """.replace("__DASHBOARD_DATE__", d);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

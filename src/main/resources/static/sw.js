/* Trading Terminal mobile — service worker
 *
 * 策略：
 *   - 靜態檔（mobile.html / manifest / icons）：cache-first，第一次載入後 offline 也可開
 *   - /api/* 與 /actuator/*：一律 network-only（**永遠抓最新，避免顯示舊行情/決策誤判**）
 *   - 其他資源：network-first，網路掛了 fallback cache
 *   - activate：清掉舊版 cache
 *
 * 升版：改 CACHE_VERSION，舊 cache 自動清空。
 */
const CACHE_VERSION = 'tt-v5-2026-04-28-paper-trade';
const STATIC_CACHE  = `static-${CACHE_VERSION}`;
const PRECACHE_URLS = [
  '/mobile.html',
  '/manifest.webmanifest',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/apple-touch-icon.png',
  '/icons/icon-maskable-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(STATIC_CACHE).then((cache) =>
      cache.addAll(PRECACHE_URLS).catch((err) => {
        // 個別資源 fail 不阻擋整個 install（icon 缺仍然要可用）
        console.warn('[sw] precache partial fail', err);
      })
    ).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys.filter((k) => k !== STATIC_CACHE).map((k) => caches.delete(k))
      )
    ).then(() => self.clients.claim())
  );
});

function isApiOrActuator(url) {
  return url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator/');
}

function isPrecached(url) {
  // 比對 pathname；query string 忽略
  return PRECACHE_URLS.some((p) => p === url.pathname);
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;                       // 只攔 GET
  let url;
  try { url = new URL(req.url); } catch (e) { return; }
  if (url.origin !== self.location.origin) return;        // 跨域不管

  // ── API / actuator：絕不 cache，永遠 network ─────────────
  if (isApiOrActuator(url)) {
    event.respondWith(fetch(req));
    return;
  }

  // ── precache 檔：cache-first，背景更新 ──────────────────
  if (isPrecached(url)) {
    event.respondWith(
      caches.match(req).then((hit) => {
        const fetchPromise = fetch(req).then((res) => {
          if (res && res.ok) {
            const clone = res.clone();
            caches.open(STATIC_CACHE).then((c) => c.put(req, clone));
          }
          return res;
        }).catch(() => hit);
        return hit || fetchPromise;
      })
    );
    return;
  }

  // ── 其他靜態：network-first，fallback cache ────────────
  event.respondWith(
    fetch(req).then((res) => {
      if (res && res.ok && res.type === 'basic') {
        const clone = res.clone();
        caches.open(STATIC_CACHE).then((c) => c.put(req, clone));
      }
      return res;
    }).catch(() => caches.match(req))
  );
});

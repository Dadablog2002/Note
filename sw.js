const CACHE = 'notatnyk-v28';
const SHELL = ['./', './index.html', './manifest.json', './icons/icon-192.png', './icons/icon-512.png', './icons/apple-touch-icon.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE && k !== 'rem-q').map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  const url = new URL(req.url);
  // Тільки свої GET-запити; GitHub API (синхронізація) не чіпаємо
  if (req.method !== 'GET' || url.origin !== location.origin) return;

  e.respondWith(
    fetch(req)
      .then(res => {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(req, copy));
        return res;
      })
      .catch(() => caches.match(req).then(r => r || caches.match('./index.html')))
  );
});

self.addEventListener('notificationclick', e => {
  e.notification.close();
  const d = e.notification.data || {};
  const act = e.action === 'done' || e.action === 'snooze' ? e.action : 'open';
  e.waitUntil((async () => {
    // Дію кладемо в чергу — додаток забере її одразу або при пробудженні
    if (act !== 'open' && d.id) {
      const c = await caches.open('rem-q');
      await c.put('/__rem/' + Date.now() + '-' + Math.random().toString(36).slice(2), new Response(JSON.stringify({ action: act, id: d.id })));
    }
    const list = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (list.length) {
      list.forEach(c => c.postMessage({ type: 'rem-q' }));
      if (act === 'open') {
        const c = list.find(x => x.focused) || list[0];
        if (c.focus) return c.focus();
      }
      return;
    }
    return self.clients.openWindow('./');
  })());
});

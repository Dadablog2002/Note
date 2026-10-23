const CACHE = 'notatnyk-v19';
const SHELL = ['./', './index.html', './manifest.json', './icons/icon-192.png', './icons/icon-512.png', './icons/apple-touch-icon.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
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
  e.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
      if (list.length) {
        const c = list.find(x => x.focused) || list[0];
        c.postMessage({ type: 'rem', action: act, id: d.id });
        return c.focus ? c.focus() : undefined;
      }
      const q = act === 'open' ? './' : './?rem=' + act + ':' + encodeURIComponent(d.id || '');
      return self.clients.openWindow(q);
    })
  );
});

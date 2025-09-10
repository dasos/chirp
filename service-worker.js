/* Basic PWA service worker for Chirp */
const CACHE_NAME = 'chirp-pwa-v1';
const ASSETS = [
  '/',
  '/manifest.json',
  '/icons/icon-192x192.png',
  '/icons/icon-512x512.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

function isSameOrigin(url) {
  try {
    const u = new URL(url);
    return u.origin === self.location.origin;
  } catch {
    return false;
  }
}

self.addEventListener('fetch', (event) => {
  const { request } = event;

  // Only handle same-origin GET requests
  if (request.method !== 'GET' || !isSameOrigin(request.url)) return;

  // For navigations: network-first with fallback to cached shell
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request).then((resp) => {
        const copy = resp.clone();
        caches.open(CACHE_NAME).then((c) => c.put('/', copy));
        return resp;
      }).catch(() => caches.match('/') )
    );
    return;
  }

  // For static assets: stale-while-revalidate
  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME);
    const cached = await cache.match(request);
    const fetchPromise = fetch(request)
      .then((resp) => {
        // Only cache successful basic responses
        if (resp && resp.status === 200 && resp.type === 'basic') {
          cache.put(request, resp.clone());
        }
        return resp;
      })
      .catch(() => cached);
    return cached || fetchPromise;
  })());
});


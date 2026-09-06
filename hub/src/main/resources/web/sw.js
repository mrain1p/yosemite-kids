// The hub's service worker: it exists so the admin page opens as an app,
// and so a parent standing in front of the NAS with the container down gets
// the page and an honest "can't reach the hub" instead of the browser's
// dinosaur.
//
// SHELL IS AN ALLOW-LIST, AND THAT IS THE WHOLE SECURITY MODEL HERE. This
// origin serves the family's entire configuration — kids, rules, device
// tokens — behind a session cookie. Anything this worker caches is written
// to Cache Storage, which outlives the session, the sign-out and the tab. So
// a request whose path is not one of these four is never touched: not
// cached, not read from cache, not even wrapped. /api, /login and /logout
// therefore go to the network exactly as if this file did not exist.
//
// Guard 19 in scripts/check.* fails the build if this list grows anything
// that is not a static asset.
var CACHE = "yk-hub-shell-v1";
var SHELL = [
  "/",
  "/manifest.webmanifest",
  "/icon-192.png",
  "/icon-512.png"
];

self.addEventListener("install", function (e) {
  // skipWaiting so a rebuilt container's worker takes over on the next load
  // rather than after every tab is closed: this page is opened for a minute
  // at a time and would otherwise stay a version behind for weeks.
  e.waitUntil(
    caches.open(CACHE).then(function (c) { return c.addAll(SHELL); }).then(function () {
      return self.skipWaiting();
    })
  );
});

self.addEventListener("activate", function (e) {
  e.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.map(function (k) {
        return k === CACHE ? null : caches.delete(k);
      }));
    }).then(function () { return self.clients.claim(); })
  );
});

self.addEventListener("fetch", function (e) {
  if (e.request.method !== "GET") return;
  var url;
  try { url = new URL(e.request.url); } catch (err) { return; }
  if (url.origin !== self.location.origin) return;
  if (SHELL.indexOf(url.pathname) === -1) return;

  // Network first, cache second. The hub is on the same LAN as the phone, so
  // the network is nearly always there and nearly always fastest; the cache
  // is for the case the container is down, where a stale shell that can say
  // so beats a browser error page.
  e.respondWith(
    fetch(e.request).then(function (res) {
      if (res && res.ok) {
        var copy = res.clone();
        caches.open(CACHE).then(function (c) { c.put(e.request, copy); });
      }
      return res;
    }).catch(function () {
      return caches.match(e.request).then(function (hit) {
        if (hit) return hit;
        throw new Error("offline and not cached");
      });
    })
  );
});

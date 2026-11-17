/* Firebase Cloud Messaging service worker.
   Shows notifications sent from Firebase Console while the site is closed.
   Must stay in the site root, next to index.html. */
importScripts("https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js");
importScripts("https://www.gstatic.com/firebasejs/10.12.2/firebase-messaging-compat.js");

firebase.initializeApp({
  apiKey: "AIzaSyDGd3KAo45UuqmeGFALziz_oKm3htEASHY",
  authDomain: "mywebtools-f8d53.firebaseapp.com",
  projectId: "mywebtools-f8d53",
  messagingSenderId: "979594414301",
  appId: "1:979594414301:web:7048c995e56e331a85f334"   // same Web app ID as PUSH_WEB_APP_ID in index.html
});

const messaging = firebase.messaging();

// Admin panel messages carry a `notification` block (webpush config) so
// FCM/browser shows them automatically on PC Chrome/Edge/Firefox.
// We only handle the rare case where `notification` is missing (data-only fallback).
messaging.onBackgroundMessage((p) => {
  // If FCM already has a `notification` block it shows the notification itself —
  // calling showNotification() again would duplicate it, so we return early.
  if (p.notification) return;
  // Fallback: data-only message (kept for safety / older sends)
  const d = p.data || {};
  return self.registration.showNotification(d.title || "Rajib Tutorial", {
    body: d.body || "",
    tag: d.id || undefined,
    data: { url: "./" }
  });
});

self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const url = (e.notification.data && e.notification.data.url) || "./";
  e.waitUntil(
    clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
      for (const c of list) {
        if (c.url && c.url.includes(self.location.origin) && "focus" in c) return c.focus();
      }
      return clients.openWindow(url);
    })
  );
});

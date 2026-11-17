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
  appId: ""   // same Web app ID as PUSH_WEB_APP_ID in index.html
});

firebase.messaging();

# Rajib Tutorial — Web

EEE Related Course Website with Firebase Firestore.

## Live Site
GitHub Pages থেকে serve হবে: `https://raseledutools.github.io/rajib-tutorial-web/`

## Setup (Firebase config)

`index.html` এ এই section টি update করো:

```js
const firebaseConfig = {
  apiKey:            "your-api-key",
  authDomain:        "your-project.firebaseapp.com",
  projectId:         "your-project-id",
  storageBucket:     "your-project.appspot.com",
  messagingSenderId: "000000000000",
  appId:             "1:000:web:000"
};
```

Firebase Console → Project Settings → Your apps → Web app → Config copy করো।

Same Firebase project use করছো যেটা Android app use করে,
তাহলে `tutorial_courses` collection automatically shared হবে।

## Admin Access
- Website এ ⋮ (3-dot) → Admin Login
- Password: **1111**
- Course add/edit/delete, Video URL/title/duration manage করা যাবে

## Features
- Course list (Firestore থেকে live)
- Video list per course
- YouTube embed + direct video URL support
- Admin panel: course CRUD + video CRUD
- Password protected admin (1111)
- Fully responsive mobile/desktop

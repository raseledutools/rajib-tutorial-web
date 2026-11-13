# Backend (Twilio Voice calls + admin push notifications)

1. Twilio Console: create an **API Key** (SID + secret), a **TwiML App** and buy/verify a **caller ID** number.
2. Deploy this folder (Render / Railway / Fly) with env vars:
   `TWILIO_ACCOUNT_SID`, `TWILIO_API_KEY`, `TWILIO_API_SECRET`, `TWILIO_TWIML_APP_SID`,
   `TWILIO_CALLER_ID` (e.g. +1555…), `TWILIO_AUTH_TOKEN` (for webhook signature check), `APP_KEY` (any long random string).
3. Set the TwiML App **Voice Request URL** to `https://<your-host>/voice` (HTTP POST).
4. Add GitHub repo secret `TWILIO_TOKEN_URL` = `https://<your-host>/token?key=<APP_KEY>` and re-run the APK build.

## Push notifications (admin → users, even when the app is closed)

Uses Firebase Cloud Messaging (FCM). One-time setup:

1. **Firebase Console** (project `mywebtools-f8d53`) → Project settings → *Your apps* → **Add app → Android**,
   package name `com.rajibtutorial` → download **`google-services.json`** and upload it to the repo as
   `android/app/google-services.json` (it only holds public identifiers). Then re-run the APK build.
2. Firebase Console → Project settings → **Service accounts → Generate new private key**. Put the whole JSON
   file content in the server env var `FIREBASE_SERVICE_ACCOUNT` (never commit it).
3. Set env var `ADMIN_KEY` on the server (any long random string) — the admin panel asks for it once.
4. Redeploy this folder. Optional: `ALLOWED_ORIGIN` (default `https://raseledutools.github.io`).
5. Web admin panel → **Notification Center** → ⚙ Server settings → enter this server's URL and the `ADMIN_KEY`.

The app registers its token at `<same host as TWILIO_TOKEN_URL>/register` (override with repo secret `PUSH_BACKEND_URL`).
Endpoints: `POST /register` (app), `POST /notify` and `GET /history` (admin, header `x-admin-key`).

# Call backend (Twilio Voice)

1. Twilio Console: create an **API Key** (SID + secret), a **TwiML App** and buy/verify a **caller ID** number.
2. Deploy this folder (Render / Railway / Fly) with env vars:
   `TWILIO_ACCOUNT_SID`, `TWILIO_API_KEY`, `TWILIO_API_SECRET`, `TWILIO_TWIML_APP_SID`,
   `TWILIO_CALLER_ID` (e.g. +1555…), `TWILIO_AUTH_TOKEN` (for webhook signature check), `APP_KEY` (any long random string).
3. Set the TwiML App **Voice Request URL** to `https://<your-host>/voice` (HTTP POST).
4. Add GitHub repo secret `TWILIO_TOKEN_URL` = `https://<your-host>/token?key=<APP_KEY>` and re-run the APK build.

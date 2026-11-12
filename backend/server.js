// Minimal Twilio Voice backend: /token (for the Android app) and /voice (TwiML App webhook).
const express = require('express');
const twilio = require('twilio');

const { AccessToken } = twilio.jwt;
const { VoiceGrant } = AccessToken;
const VoiceResponse = twilio.twiml.VoiceResponse;

const {
  TWILIO_ACCOUNT_SID, TWILIO_API_KEY, TWILIO_API_SECRET,
  TWILIO_TWIML_APP_SID, TWILIO_CALLER_ID, APP_KEY, PORT = 3000,
} = process.env;

const app = express();
app.set('trust proxy', true);
app.use(express.urlencoded({ extended: false }));

// App -> backend: returns a short-lived access token with an outgoing-call grant.
app.get('/token', (req, res) => {
  if (APP_KEY && req.query.key !== APP_KEY) return res.status(403).json({ error: 'forbidden' });
  const identity = String(req.query.identity || '').replace(/[^\w-]/g, '').slice(0, 64);
  if (!identity) return res.status(400).json({ error: 'identity required' });

  const token = new AccessToken(TWILIO_ACCOUNT_SID, TWILIO_API_KEY, TWILIO_API_SECRET, { identity, ttl: 3600 });
  token.addGrant(new VoiceGrant({ outgoingApplicationSid: TWILIO_TWIML_APP_SID, incomingAllow: false }));
  res.json({ token: token.toJwt() });
});

// Twilio -> backend: TwiML App "Voice Request URL" (POST). Dials the number sent by the app.
app.post('/voice', twilio.webhook(), (req, res) => {
  const to = String(req.body.To || '');
  const twiml = new VoiceResponse();
  if (/^\+[1-9]\d{6,14}$/.test(to)) {
    twiml.dial({ callerId: TWILIO_CALLER_ID }, to);
  } else {
    twiml.say('Invalid number.');
  }
  res.type('text/xml').send(twiml.toString());
});

app.get('/', (_req, res) => res.send('ok'));
app.listen(PORT, () => console.log('call backend on ' + PORT));

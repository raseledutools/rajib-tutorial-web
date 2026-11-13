// Backend: Twilio Voice (/token, /voice) + admin push notifications (/register, /notify, /history).
const crypto = require('crypto');
const express = require('express');
const twilio = require('twilio');

const { AccessToken } = twilio.jwt;
const { VoiceGrant } = AccessToken;
const VoiceResponse = twilio.twiml.VoiceResponse;

const {
  TWILIO_ACCOUNT_SID, TWILIO_API_KEY, TWILIO_API_SECRET,
  TWILIO_TWIML_APP_SID, TWILIO_CALLER_ID, APP_KEY, PORT = 3000,
  FIREBASE_SERVICE_ACCOUNT, ADMIN_KEY,
  ALLOWED_ORIGIN = 'https://raseledutools.github.io',
} = process.env;

const app = express();
app.set('trust proxy', true);
app.use(express.urlencoded({ extended: false }));
app.use(express.json({ limit: '20kb' }));

// CORS: the admin panel (GitHub Pages / the app's WebView) calls the push endpoints from the browser.
app.use((req, res, next) => {
  const origin = req.headers.origin;
  if (origin && origin === ALLOWED_ORIGIN) {
    res.set('Access-Control-Allow-Origin', origin);
    res.set('Vary', 'Origin');
    res.set('Access-Control-Allow-Headers', 'Content-Type, x-admin-key');
    res.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  }
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

// ── Firebase (push) — optional: the Twilio endpoints keep working without it ──
let fbAdmin = null, fsdb = null, fcm = null, fbError = '';
try {
  if (FIREBASE_SERVICE_ACCOUNT) {
    fbAdmin = require('firebase-admin');
    fbAdmin.initializeApp({ credential: fbAdmin.credential.cert(JSON.parse(FIREBASE_SERVICE_ACCOUNT)) });
    fsdb = fbAdmin.firestore();
    fcm = fbAdmin.messaging();
  } else {
    fbError = 'FIREBASE_SERVICE_ACCOUNT is not set';
  }
} catch (e) {
  fbError = 'Firebase init failed: ' + e.message;
  console.error(fbError);
}

const safeEq = (a, b) => {
  const x = Buffer.from(String(a || '')), y = Buffer.from(String(b || ''));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};
const digits = (v) => String(v || '').replace(/\D/g, '').slice(0, 15);
const sha = (v) => crypto.createHash('sha256').update(v).digest('hex').slice(0, 40);

function needFirebase(_req, res, next) {
  if (!fcm) return res.status(503).json({ error: 'Push server is not configured (' + fbError + ')' });
  next();
}
function needAdmin(req, res, next) {
  if (!ADMIN_KEY) return res.status(503).json({ error: 'ADMIN_KEY is not set on the server' });
  if (!safeEq(req.get('x-admin-key'), ADMIN_KEY)) return res.status(403).json({ error: 'Admin key ভুল' });
  next();
}

// App -> backend: store this device's FCM token (and which member mobile is logged in, if any).
app.post('/register', needFirebase, async (req, res) => {
  try {
    const { token, device, key } = req.body || {};
    if (APP_KEY && key !== APP_KEY) return res.status(403).json({ error: 'forbidden' });
    if (typeof token !== 'string' || token.length < 40 || token.length > 4096)
      return res.status(400).json({ error: 'bad token' });
    await fsdb.collection('push_tokens').doc(sha(token)).set({
      token,
      mobile: digits(req.body.mobile),
      device: String(device || '').slice(0, 80),
      updatedAt: Date.now(),
    }, { merge: true });
    res.json({ ok: true });
  } catch (e) {
    console.error('register', e);
    res.status(500).json({ error: 'server error' });
  }
});

// Admin panel -> backend: send to ALL users (topic) or one member (by mobile).
app.post('/notify', needFirebase, needAdmin, async (req, res) => {
  try {
    const title = String(req.body.title || '').trim().slice(0, 100);
    const body = String(req.body.body || '').trim().slice(0, 500);
    const target = req.body.target === 'user' ? 'user' : 'all';
    const mobile = digits(req.body.mobile);
    if (!title || !body) return res.status(400).json({ error: 'Title ও Message দিন' });
    if (target === 'user' && mobile.length < 6) return res.status(400).json({ error: 'সঠিক মোবাইল নম্বর দিন' });

    const message = {
      notification: { title, body },
      data: { title, body, kind: 'admin' },
      android: { priority: 'high', notification: { channelId: 'rt_push_v1' } },
    };

    let result;
    if (target === 'all') {
      await fcm.send({ ...message, topic: 'all' });
      result = { sent: 'all' };
    } else {
      const snap = await fsdb.collection('push_tokens').where('mobile', '==', mobile).get();
      if (snap.empty) {
        return res.status(404).json({ error: 'এই user এর কোনো device registered নেই (app এ login করে একবার app খুলতে হবে)' });
      }
      const docs = snap.docs.slice(0, 500);
      const r = await fcm.sendEachForMulticast({ ...message, tokens: docs.map((d) => d.data().token) });
      const dead = [];
      r.responses.forEach((x, i) => {
        const c = x.error && x.error.code;
        if (c === 'messaging/registration-token-not-registered' || c === 'messaging/invalid-registration-token') dead.push(docs[i].ref);
      });
      await Promise.all(dead.map((ref) => ref.delete()));
      result = { sent: r.successCount, failed: r.failureCount };
      if (!r.successCount) return res.status(502).json({ error: 'কোনো device এ পাঠানো যায়নি (token মেয়াদোত্তীর্ণ হতে পারে)' });
    }

    await fsdb.collection('push_messages').add({ title, body, target, mobile: target === 'user' ? mobile : '', at: Date.now(), result });
    res.json({ ok: true, ...result });
  } catch (e) {
    console.error('notify', e);
    res.status(500).json({ error: 'Send failed: ' + e.message });
  }
});

// Admin panel -> backend: last messages + number of registered devices.
app.get('/history', needFirebase, needAdmin, async (_req, res) => {
  try {
    const [msgs, cnt] = await Promise.all([
      fsdb.collection('push_messages').orderBy('at', 'desc').limit(50).get(),
      fsdb.collection('push_tokens').count().get(),
    ]);
    res.json({ devices: cnt.data().count, messages: msgs.docs.map((d) => ({ id: d.id, ...d.data() })) });
  } catch (e) {
    console.error('history', e);
    res.status(500).json({ error: 'server error' });
  }
});

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

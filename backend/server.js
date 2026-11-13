// Push-only backend: /register, /notify, /history
// Firebase credential & ADMIN_KEY are embedded — no env vars needed for these.
const crypto = require('crypto');
const express = require('express');

// ── Embedded secrets ───────────────────────────────────────────────────────────
const ADMIN_KEY = '27a1a6aa90ada9b4d5136f7dd34e2079ccf2d150b91b775dd096308c227fcc7c';

const FIREBASE_SA = {
  type: 'service_account',
  project_id: 'mywebtools-f8d53',
  private_key_id: 'c420a8253fe6fb794a4d869d4d1949c9b27d5bff',
  private_key: '-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDWqseNhhm6ZpLz\nuGEJMdv61j1wKE6+mv2Jt6b4mM6jszlTk2IsbliKo9ZoUYxC2VMhShJFcb6ZjWif\nul5VVw+0/u5s2pxxdKR0odb0ZxEqsofF5EnZMdqX8zYcqK/7Ns+iDN/GXKuSJOgo\n+cQV4BODKJ1NTJb8932JYUJEa+DuyHUZgggXl63D5EtXkpweVk5mYMF1B3vaPv1c\nS9KX7FoXAFY3qILBrODG+FuaebUDR0t+1Q/ZFfjKbxmVzlgGGCx0iA4JP2D41Rk8\njv1bQJGHb8w78V/La4n1asHKB+D2xkFDlHALFgA4LX0xlp4627fR/kHpKs5NvfCM\ngtxH9oVLAgMBAAECggEAQb4lxUMHiB237apPFYjOjSClyj6d9yPyeAXlvgbWai4O\nZXkivObCeRs6I9KtuR9jpTNRKzFtpQs4uopSL6KtLcpRbMb62IhXgVy7Pzh9em0Y\nxdNnhnwNLTIQJXDIDJC5wDBfLBnfda+V5k6v7d8lOqrqmezV5P1K1hMSP3AmgBoW\nerj4W8YfbgRkSIRswzYGFc/kVDHeJkH29oDTzDD1Cx6IAB/9QXi4G3Wx+TbV9976\nqRdASrVnvapa0wknyBnps1mRIvHip44jwuHU5G5lwGEukYWY6YSbClBjV3pmNwm9\ngsZdRmbG+gJ00i+/y63LjaTxuFqPQSkQl28V1dBaUQKBgQD1y2ndFn4GtJkRoLQd\nkATc26EUslJCksYjDnINayUBbuwLqo65LqtjIkaR2yr+KDETjaoTqDF9yVQFB6Me\nR8MfzJfpneAxE0oEsND4VGY4XRoEi7gnLMcLTaUzOwAPjJGpfNvmTGir1a/KQlPE\nWZrWXC7qYGtH9mfYVREKxzgFgwKBgQDflIHqlV4hTJhmgpQXrlXQio7FfaoCCXHF\nZKVh+zEEJI7ZjHnTWep/2P3Jtc7BsDTCYYM661HB19fRgvUsYtIs/6Ybj6VeYcol\n9/Ic3+9APthz600gMWRLCbw5jNpQJyeJrPvBOxlEnB/DOhk7kBZuFpBnzi5oE+ko\n4XGSlcy+mQKBgQCMX2NSNGo9Ah75BkJXL8zXJUdVPpHkG1a05qcUKsCzlNmRwBJK\nh/CxaP3MhtKC3AlLFXiwSToakEQ/qyDpRiqVmo6XiOZkoJOBIkn1yJ2oSuN0/SZq\nmRQDzJAwNd3PA4ScuwYK/FA68oq2hDK6zEG1cmgIyo/6RSsBTXOyKBxCaQKBgGuW\nukHZaPebNUc3FO6kXPVCh9uvUg0y4EW93fvddcPpl7hGaiVzgm3NTKsxSvJXybu0\nUwMaVDh0lo1ieHrIfx1aVGO05z4Yzr8gpqiQdkAzPdPiajXQrtORNrt29WNWxCbz\nUB63QryKaJp4rcXE25ZUU18wj5JT6oJaol2IqoMZAoGBAKHeuI6HsSzBgUxfbBij\nG8ouPie8AgCXpPeB3gFRvZk8A/i73xYxzOf9zvv1XZWrrwEJC7qwS8Ww5kyMbjVO\n8jsuJQ3ESy2BBlyzi+B5iE6+85KUQTus/SLT0Ge/jytHzVoosL3zgJdAveDuNsAy\nTs2QZJE9zynpvH7Xr8YBgOWu\n-----END PRIVATE KEY-----\n',
  client_email: 'firebase-adminsdk-fbsvc@mywebtools-f8d53.iam.gserviceaccount.com',
  client_id: '103376990897205692904',
  auth_uri: 'https://accounts.google.com/o/oauth2/auth',
  token_uri: 'https://oauth2.googleapis.com/token',
  auth_provider_x509_cert_url: 'https://www.googleapis.com/oauth2/v1/certs',
  client_x509_cert_url: 'https://www.googleapis.com/robot/v1/metadata/x509/firebase-adminsdk-fbsvc%40mywebtools-f8d53.iam.gserviceaccount.com',
  universe_domain: 'googleapis.com',
};

const ALLOWED_ORIGIN = 'https://raseledutools.github.io';
const PORT = process.env.PORT || 3000;
// ──────────────────────────────────────────────────────────────────────────────

const app = express();
app.set('trust proxy', true);
app.use(express.json({ limit: '20kb' }));

// CORS
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

// ── Firebase Admin init ───────────────────────────────────────────────────────
const fbAdmin = require('firebase-admin');
fbAdmin.initializeApp({ credential: fbAdmin.credential.cert(FIREBASE_SA) });
const fsdb = fbAdmin.firestore();
const fcm  = fbAdmin.messaging();

// ── Helpers ───────────────────────────────────────────────────────────────────
const safeEq = (a, b) => {
  const x = Buffer.from(String(a || '')), y = Buffer.from(String(b || ''));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};
const digits = (v) => String(v || '').replace(/\D/g, '').slice(0, 15);
const sha    = (v) => crypto.createHash('sha256').update(v).digest('hex').slice(0, 40);

function needAdmin(req, res, next) {
  if (!safeEq(req.get('x-admin-key'), ADMIN_KEY))
    return res.status(403).json({ error: 'Admin key ভুল' });
  next();
}

// ── POST /register  (Android app → save FCM token) ───────────────────────────
app.post('/register', async (req, res) => {
  try {
    const { token, device, mobile } = req.body || {};
    if (typeof token !== 'string' || token.length < 40 || token.length > 4096)
      return res.status(400).json({ error: 'bad token' });
    await fsdb.collection('push_tokens').doc(sha(token)).set({
      token,
      mobile: digits(mobile),
      device: String(device || '').slice(0, 80),
      updatedAt: Date.now(),
    }, { merge: true });
    res.json({ ok: true });
  } catch (e) {
    console.error('register', e);
    res.status(500).json({ error: 'server error' });
  }
});

// ── POST /notify  (admin panel → send push) ──────────────────────────────────
app.post('/notify', needAdmin, async (req, res) => {
  try {
    const title  = String(req.body.title  || '').trim().slice(0, 100);
    const body   = String(req.body.body   || '').trim().slice(0, 500);
    const target = req.body.target === 'user' ? 'user' : 'all';
    const mobile = digits(req.body.mobile);

    if (!title || !body)
      return res.status(400).json({ error: 'Title ও Message দিন' });
    if (target === 'user' && mobile.length < 6)
      return res.status(400).json({ error: 'সঠিক মোবাইল নম্বর দিন' });

    // Data-only: the app builds the notification itself (sound/vibration + tap -> overlay page)
    const mid = 'm' + Date.now();
    const message = {
      data: { title, body, kind: 'admin', id: mid, time: String(Date.now()) },
      android: { priority: 'high', ttl: '86400s' },
    };

    let result;
    if (target === 'all') {
      // topic + every registered token (same id => shown once per phone)
      let topicOk = false;
      try { await fcm.send({ ...message, topic: 'all' }); topicOk = true; } catch (e) { console.error('topic', e.message); }
      const snap = await fsdb.collection('push_tokens').get();
      const tokens = [...new Set(snap.docs.map((d) => d.data().token).filter(Boolean))];
      let ok = 0, failed = 0;
      for (let i = 0; i < tokens.length; i += 500) {
        const r = await fcm.sendEachForMulticast({ ...message, tokens: tokens.slice(i, i + 500) });
        ok += r.successCount; failed += r.failureCount;
      }
      if (!topicOk && !ok) return res.status(502).json({ error: 'কোনো device এ পাঠানো যায়নি' });
      result = { sent: 'all', ok, failed };
    } else {
      const snap = await fsdb.collection('push_tokens').where('mobile', '==', mobile).get();
      if (snap.empty)
        return res.status(404).json({ error: 'এই user এর কোনো device registered নেই' });

      const docs = snap.docs.slice(0, 500);
      const r = await fcm.sendEachForMulticast({
        ...message,
        tokens: docs.map((d) => d.data().token),
      });

      const dead = [];
      r.responses.forEach((x, i) => {
        const c = x.error && x.error.code;
        if (
          c === 'messaging/registration-token-not-registered' ||
          c === 'messaging/invalid-registration-token'
        ) dead.push(docs[i].ref);
      });
      await Promise.all(dead.map((ref) => ref.delete()));

      result = { sent: r.successCount, failed: r.failureCount };
      if (!r.successCount)
        return res.status(502).json({ error: 'কোনো device এ পাঠানো যায়নি' });
    }

    await fsdb.collection('push_messages').add({
      title, body, target,
      mobile: target === 'user' ? mobile : '',
      at: Date.now(),
      result,
    });
    res.json({ ok: true, ...result });
  } catch (e) {
    console.error('notify', e);
    res.status(500).json({ error: 'Send failed: ' + e.message });
  }
});

// ── GET /history  (admin panel → last messages + device count) ───────────────
app.get('/history', needAdmin, async (_req, res) => {
  try {
    const [msgs, cnt] = await Promise.all([
      fsdb.collection('push_messages').orderBy('at', 'desc').limit(50).get(),
      fsdb.collection('push_tokens').count().get(),
    ]);
    res.json({
      devices: cnt.data().count,
      messages: msgs.docs.map((d) => ({ id: d.id, ...d.data() })),
    });
  } catch (e) {
    console.error('history', e);
    res.status(500).json({ error: 'server error' });
  }
});

app.get('/', (_req, res) => res.send('ok'));

app.listen(PORT, () => console.log('push backend on ' + PORT));

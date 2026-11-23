#!/usr/bin/env python3
"""Send an FCM 'app_update' push (topic) right after a GitHub Release is published,
so installed phones show the update notification even when the app is closed.

Env:
  FIREBASE_SERVICE_ACCOUNT  - service-account JSON of the Firebase project (GitHub secret).
                              If missing, the step is skipped (build does not fail).
  RELEASE_TAG               - e.g. v7
"""
import json, os, sys, datetime

LOG = "fcm_push_log.txt"
def log(msg):
    with open(LOG, "w") as f:
        f.write(f"{datetime.datetime.utcnow():%Y-%m-%d %H:%M:%S} UTC | {os.environ.get('RELEASE_TAG','')}\n{msg}\n")

raw = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "").strip()
tag = os.environ.get("RELEASE_TAG", "").strip()
if not raw:
    print("::warning title=FCM update push skipped::FIREBASE_SERVICE_ACCOUNT secret not set")
    log("SKIPPED: FIREBASE_SERVICE_ACCOUNT secret is not set - add it in repo Settings > Secrets and variables > Actions.")
    sys.exit(0)

from google.oauth2 import service_account
from google.auth.transport.requests import Request, AuthorizedSession

info = json.loads(raw)
creds = service_account.Credentials.from_service_account_info(
    info, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
creds.refresh(Request())
session = AuthorizedSession(creds)

body = {"message": {
    "topic": "app_update",
    "data": {"type": "app_update", "tag": tag},
    "android": {"priority": "HIGH", "ttl": "86400s"},
}}
r = session.post(
    f"https://fcm.googleapis.com/v1/projects/{info['project_id']}/messages:send",
    json=body, timeout=30)
print(r.status_code, r.text)
log(f"{'OK' if r.ok else 'FAILED'}: HTTP {r.status_code} {r.text[:500]}")
if r.ok:
    print(f"::notice title=FCM update push::sent for {tag} (HTTP {r.status_code})")
else:
    print(f"::error title=FCM update push failed::HTTP {r.status_code} {r.text[:300]}")
sys.exit(0 if r.ok else 1)

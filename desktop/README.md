# Rajib Tutorial — Windows Desktop

Electron app (Windows 10 2004+ / 11). The whole window is excluded from screenshots,
Snipping Tool, OBS and screen sharing (`setContentProtection` → `WDA_EXCLUDEFROMCAPTURE`).

- **Web Section** – the live site, inside the protected window. External `https` links open in another protected window.
- **Study Tools** – PDF Reader, Merge, Split, Photo→PDF, Photo Resizer / Job Photo / Signature, QR Generator, Age Calculator. All offline.
- **Focus Mode** – timed session, PIN to stop early, optional full lock, Windows app blocking (process names).

## Build
GitHub Actions → *Build Windows Desktop App* → download the installer from the run's artifacts.
Local: `cd desktop && npm ci && npm start` (dev) or `npm run dist` (installer in `dist/`).

## Not ported yet (Android native screens)
Native Home/Course/Discussion/Inbox screens, Members/Manage/Notify admin screens, Compress PDF, PDF→Photo, QR Reader, Apps Lock, Push.

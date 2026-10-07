# Synsuck — design (v1)

Private Android app for reading Librus Synergia messages across multiple accounts.
Distributed by sideloading a signed APK; not published to any store.

## Scope

**In v1**
- Read inbox messages for multiple Librus accounts
- Merged "All" view + per-account tabs with unread counts
- Message detail with attachments (download / open)
- Forward message as email (via Android share intent)
- Encrypted password storage
- Background checks for new messages + notifications
- UI in English and Polish, configurable

**Out of v1**: sent messages, replying/composing, grades, announcements, timetable.

## Screens

### 1. Inbox

```
┌──────────────────────────────────────┐
│ Messages                     ⟳   ⚙   │
├──────────────────────────────────────┤
│ All (4) │ Ania (3) │ Kuba (1)        │  ← scrollable tabs, bold if unread
│ ═══════                              │
├──────────────────────────────────────┤
│ ● ▌Ania  Kowalska Maria      10:42   │  ● = unread
│   ▌Wycieczka do Krakowa              │  ▌ = account color stripe
│   ▌Szanowni Państwo, w dniu 15...  📎│      (account chip only in "All")
├──────────────────────────────────────┤
│ ● ▌Kuba  Nowak Piotr      Yesterday  │
│   ▌Sprawdzian z matematyki           │
├──────────────────────────────────────┤
│ ⚠ Kuba: login failed – tap to fix    │  ← per-account error banner
└──────────────────────────────────────┘
```

- First tab "All": merged list of all accounts, sorted by date desc.
- Per-account tabs: same rows, without the account chip.
- Tab title = account label + unread count; "All" shows the total.
- Tabs hidden when only one account is configured.
- Pull-to-refresh syncs all accounts in parallel; failure of one account shows a
  banner and does not block the others.

### 2. Message detail

```
┌──────────────────────────────────────┐
│ ←                        ✉ Forward   │
├──────────────────────────────────────┤
│ Wycieczka do Krakowa                 │
│ ▌Ania · Kowalska Maria               │
│ Oct 6, 2026, 10:42                   │
├──────────────────────────────────────┤
│ Szanowni Państwo, ...                │
├──────────────────────────────────────┤
│ Attachments                          │
│ 📄 zgoda_wycieczka.pdf   ⬇  ↗        │  ← download / open in external viewer
│ 📄 program.docx          ⬇  ↗        │
├──────────────────────────────────────┤
│        [ ✉ Forward as email ]        │
└──────────────────────────────────────┘
```

### 3. Accounts & settings

```
┌──────────────────────────────────────┐   ┌──────────────────────────────────────┐
│ ← Accounts                           │   │ ← Add account                        │
├──────────────────────────────────────┤   ├──────────────────────────────────────┤
│ ▌Ania   login: 1234567u    ✓   ⋮     │   │ Label:    [ Ania            ]        │
│ ▌Kuba   login: 7654321u    ⚠   ⋮     │   │ Login:    [ 1234567u        ]        │
│                                      │   │ Password: [ ••••••••     👁 ]        │
│          [ + Add account ]           │   │ Color:    🔴 🟠 🟢 🔵 🟣             │
├──────────────────────────────────────┤   │                                      │
│ Settings                             │   │        [ Verify & save ]             │
│ Language:        [ System ▾ ]        │   │  (performs a test login first)       │
│ Forward to:      [ me@example.com ]  │   └──────────────────────────────────────┘
│ Check every:     [ 1 h ▾ ]           │
│ Notifications:   [✓]                 │
└──────────────────────────────────────┘
```

Account menu (⋮): edit, re-enter password, remove (also deletes cached messages and cookies).

## Key rules

### Read-marking
Fetching a message body from Librus marks it as read server-side (same as the web UI).
- Background sync and refresh fetch **only the message list** (id, sender, subject,
  date, read flag, attachment flag) — never bodies.
- Body is fetched only when the user opens a message, then cached locally.

### Forward as email
Uses `ACTION_SEND_MULTIPLE` share intent — no SMTP, no email credentials stored.
1. Download attachments into app cache dir.
2. Expose them through a `FileProvider` (`content://` URIs, read grant only).
3. Open the user's mail app prefilled with:
   - To: default address from settings (optional)
   - Subject: `Fwd: <subject>`
   - Body: header (`From / Date / Account`, localized) + message text
   - Attachments
4. User taps Send in the mail app.
Cached forward files are cleaned up on next app start / after 24 h.

### Internationalization
- All UI strings in `values/strings.xml` (English, default) and `values-pl/strings.xml`.
- Language setting: System / English / Polski, applied via
  `AppCompatDelegate.setApplicationLocales()`; `locales_config.xml` enables the
  per-app language option in system settings on Android 13+.
- Dates/relative times formatted with locale-aware formatters.
- Librus content (message text, names, file names) is shown as-is.

## Background checks & notifications

### Scheduling
- `WorkManager` periodic work; interval options: 15 min / 30 min / 1 h / 3 h / off
  (15 min is the Android minimum). Constraint: network connected.
- Exact timing is not guaranteed (Doze / battery optimizations may defer runs) — acceptable.
- Each run: for every enabled account, log in (or reuse valid session cookies),
  fetch the message list, upsert into local DB.

### Detecting "new"
- A message is new if its Librus id is not in the local DB **and** it is unread.
- First sync after adding an account establishes a baseline — no notifications for
  messages that already existed.

### Notifications
- Channel `new_messages` (default importance) and channel `account_problems` (low).
- One notification per account, grouped under a summary when several accounts have news:
  - single new message → title = sender, text = subject; tap opens message detail
  - multiple → "Ania: 3 new messages", InboxStyle with subjects; tap opens that
    account's tab
- Opening a notification does not fetch the body until the message is actually shown.
- `POST_NOTIFICATIONS` runtime permission (Android 13+) requested when the user enables
  notifications (and on first launch with the toggle on by default).

### Failure handling
- Network errors: silent, WorkManager retries with backoff.
- Authentication failure (bad password / changed password): mark account as
  `AUTH_FAILED`, show one notification on `account_problems`, and **stop background
  logins for that account** until the user re-enters the password — avoids repeated
  failed logins that could lock the Librus account.
- Unexpected response format (Librus changed something): mark account error, show
  banner in the inbox; no notification spam (max one per account per day).

## Security

- Passwords encrypted with AES-256-GCM using a key held in the Android Keystore
  (non-exportable, hardware-backed where available); ciphertext + IV stored in the DB.
- The Keystore key does **not** require user authentication — required so background
  work can log in while the phone is locked. Trade-off accepted; an optional biometric
  lock of the app UI can be added later.
- Session cookies kept in memory only (per account), never written to disk.
- `android:allowBackup="false"` and excluded from device-to-device transfer, so
  encrypted data and cookies don't leave the device.
- HTTPS only (`cleartextTrafficPermitted="false"`); no logging of credentials,
  cookies, or message content in release builds.
- Attachments shared only via `FileProvider` with temporary read grants.

## Tech stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Navigation | Navigation Compose |
| HTTP | OkHttp with per-account cookie jar |
| Parsing | kotlinx.serialization (JSON APIs), Jsoup (HTML where needed) |
| Storage | Room (accounts, messages, attachments metadata), DataStore (settings) |
| Background | WorkManager |
| DI | Manual / lightweight (no Hilt for v1) |
| Min SDK | 26 (Android 8.0) |

## Distribution

- Signed release APK built locally (keep the signing keystore safe — required for updates).
- Install via `adb install` or by opening the APK on the device.
- Optional: GitHub Releases + Obtainium for updates on family devices.

## Data model

Librus API details: see [librus-api.md](librus-api.md).

### Room

**`accounts`**
| Column | Type | Notes |
|---|---|---|
| `id` | Long PK autoincrement | local id |
| `label` | String | e.g. "Ania"; pre-filled from `/api/me` first name |
| `login` | String | Librus login |
| `passwordCipher` | ByteArray | AES-GCM ciphertext |
| `passwordIv` | ByteArray | GCM IV |
| `color` | Int | index into fixed palette |
| `sortOrder` | Int | tab order |
| `status` | enum `OK / AUTH_FAILED / CAPTCHA_REQUIRED / ERROR` | drives banners + background skip |
| `lastError` | String? | technical detail for the banner |
| `lastSyncAt` | Instant? | |
| `baselineDone` | Boolean | false until first successful sync → no notifications for pre-existing mail |

**`messages`** — PK (`accountId`, `messageId`), FK `accountId` → `accounts` ON DELETE CASCADE
| Column | Type | Notes |
|---|---|---|
| `accountId` | Long | |
| `messageId` | String | Librus id (string in API) |
| `folder` | enum `INBOX / ARCHIVE` | |
| `senderName` | String | |
| `topic` | String | |
| `preview` | String | list `content` |
| `sentAt` | Instant | API local time interpreted as `Europe/Warsaw` |
| `readAt` | Instant? | null = unread; updated from server on every sync (read on web → read here) |
| `hasAttachments` | Boolean | |
| `body` | String? | decoded `Message` content; null until opened |
| `attachmentInfo` | String? | |
| `firstSeenAt` | Instant | |
| `notified` | Boolean | prevents duplicate notifications |

Indexes: (`accountId`, `sentAt`), (`readAt`).

**`attachments`** — PK (`accountId`, `messageId`, `attachmentId`), FK → `messages` CASCADE
| Column | Type |
|---|---|
| `attachmentId` | String |
| `filename` | String |

Downloaded files are not tracked in the DB — they live in the cache dir and are
re-downloaded when needed (download links are one-time).

Derived values (queries, not stored): unread count per account / total, merged
"All" list (`ORDER BY sentAt DESC`).

### DataStore (settings)
`language` (system/en/pl), `forwardTo` (String?), `syncInterval` (off/15m/30m/1h/3h),
`notificationsEnabled`.

### Session cookies
Kept **in memory only**, one cookie jar per account. Each background run starts
with a fresh login (~8 small requests). Avoids storing session tokens at rest and
removes session-expiry edge cases; the UI process reuses its jar while alive and
re-logs in on 401/redirect.

### Sync algorithm (per account)
1. Skip if `status` is `AUTH_FAILED` / `CAPTCHA_REQUIRED`.
2. Log in + messages handoff; on bad credentials → `AUTH_FAILED` + notification; stop.
3. For each folder (inbox, archive): fetch page 1, 2, … until a page contains only
   known ids or the list ends (first sync: fetch all, cap 500).
4. Upsert list fields (never overwrite `body`); refresh `readAt` for known messages.
5. New = inserted in this run AND `readAt == null` AND `baselineDone`; notify those
   with `notified = false`, then set `notified = true`.
6. Set `baselineDone = true`, `lastSyncAt = now`, `status = OK`.

Messages deleted on the server are kept locally in v1.

## Open items

- Remaining unknowns listed in [librus-api.md](librus-api.md#todo-capture-later)
  — resolve with the first prototype.

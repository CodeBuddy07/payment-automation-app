# SMS Gateway Agent

A standalone, **backend-free** Android app that observes incoming SMS, runs them through
configurable **rules + processors**, and forwards structured data to your **webhooks**.

It ships with **no server of its own**: you point it at *your* endpoints. The same binary serves
payment verification, OTP forwarding, bank-transaction monitoring, business automation, alert
forwarding and CRM integration **without changing the app's code**.

```
Incoming SMS → Rule Match → Processor → Payload Builder → Offline Queue → Webhook Sender
```

---

## Why it is reliable

The entire delivery pipeline lives in **native Kotlin**, not in JavaScript. SMS capture, rule
matching, processing, payload building, queueing and webhook delivery all run in a
`BroadcastReceiver` + `WorkManager` worker. The React Native / TypeScript layer is purely a
**configuration & monitoring UI** over the same SQLite database.

That means the agent keeps working when:

- the app UI is closed or swiped away (process killed),
- the phone reboots (`BootReceiver` reschedules the queue),
- the network is down (messages persist in SQLite and drain when connectivity returns),
- a webhook is temporarily failing (exponential backoff + dead-letter queue),
- the same SMS is delivered twice (content-hash de-duplication + idempotency keys).

---

## Tech stack

| Layer        | Choice |
|--------------|--------|
| App          | React Native CLI 0.86 (New Architecture), TypeScript |
| Navigation   | React Navigation (native-stack + bottom-tabs) |
| State        | Zustand |
| Native       | Kotlin · BroadcastReceiver · WorkManager · Foreground Service |
| Storage      | Native SQLite (single source of truth) |
| Networking   | OkHttp (background sender) + Axios (available in UI) |
| JS processor | Mozilla Rhino (sandboxed, runs without the RN runtime) |
| Security     | Android Keystore · EncryptedSharedPreferences · HMAC-SHA256 |

---

## Install & run

```bash
# 1. Install JS dependencies
npm install

# 2. Start Metro
npm start

# 3. Build & run on a connected device / emulator (USB debugging on)
npm run android

# Type-check / lint / test
npx tsc --noEmit
npm run lint
npm test                                   # JS unit tests
cd android && ./gradlew testDebugUnitTest  # Kotlin unit tests (TemplateEngine, …)

# Release APK
cd android && ./gradlew assembleRelease

# Self-contained sideloadable APK (no Metro needed; works around the Windows path limit)
cd android && ./gradlew assembleStandalone
#   → android/app/build/outputs/apk/standalone/app-standalone.apk
# Smaller, single-ABI build for a specific phone:
cd android && ./gradlew assembleStandalone -PreactNativeArchitectures=arm64-v8a
```

> Requires Node ≥ 22, JDK 17, Android SDK (compileSdk 36), and the `ANDROID_HOME` env var.

### Windows note & the `standalone` build

On Windows the New-Architecture Fabric C++ codegen for `react-native-safe-area-context` produces
paths that exceed the **260-char limit** in the `release` (`RelWithDebInfo`) variant. If your
project path is short (e.g. `C:\src\sms-agent`) `assembleRelease` works directly. Otherwise use the
included **`standalone`** build type: it is `debuggable` (so native compiles in the short `Debug`
CMake folder and links against the matching debug prefab) yet not the `debug` build type (so
`BuildConfig.DEBUG=false` → it bundles the production JS and runs **without a Metro dev server**).
The result is a fully self-contained, sideloadable APK.

### First launch: guided setup

On first open the app shows a **guided Onboarding checklist** that walks through everything the
agent needs and deep-links straight to the right system screen for each item:

- SMS access (`RECEIVE_SMS` / `READ_SMS`) and Phone/SIM (`READ_PHONE_STATE`)
- Notifications (`POST_NOTIFICATIONS` + channel)
- **Ignore battery optimization** (one-tap allow dialog)
- **Remove background restriction** (shown only when restricted)
- **Allow auto-start** on OEMs that gate it (Xiaomi, Oppo, Vivo, Huawei, Samsung, …)
- Optional **Persistent mode** (foreground service)

Each row re-checks itself when you return from the settings page (via `AppState`), flipping to
**DONE** automatically. You can revisit it all anytime in **Settings → Diagnostics**.

---

## Configure it for your use case (no code)

1. **Webhooks** → add your endpoint(s). Attach a bearer token and/or an HMAC secret (stored in the
   Keystore, never in the database). Mark one as **default**.
2. **Rules** → *New*. Choose how the **sender** matches (any / contains / exact / regex) and a
   **processor** (Raw / Template / Regex / JSON / JavaScript).
3. For a Template rule, tap **Train from existing SMS**, select real messages, and the app
   generates a reusable `{placeholder}` template: *no AI involved*, fully deterministic.
4. Optionally provide a **payload template** to shape the outgoing JSON.
5. **Test** the rule against a sample or your whole message history before enabling it.

Matching SMS are now parsed and delivered to your webhook.

---

## Processors (plugin architecture)

Processors implement a single Kotlin interface and self-register in `ProcessorRegistry` ,
**new processors require no changes to rules, the pipeline, or the UI**.

| Processor   | What it does |
|-------------|--------------|
| `raw`       | Forwards the message unchanged (always matches). |
| `template`  | Matches a trained `{placeholder}` template, extracts named fields. |
| `regex`     | Runs a custom regex; named groups become fields. |
| `json`      | Parses JSON embedded in the body, maps via JSON paths. |
| `javascript`| Runs sandboxed JS (Rhino): works even when the app is killed. |

```kotlin
interface Processor {
    val type: String
    fun process(message: SmsMessage, rule: Rule, context: ProcessorContext): ProcessorResult
}
// ProcessorResult = { matched, data, errors, metadata }
```

Future processors (Payment, AI, HTTP, custom user processors) drop in the same way: see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#adding-a-processor).

---

## Training (deterministic, not AI)

Example input:

```
Cash In Tk 500 from 01711111111. TrxID ABC12345. Balance Tk 2000.
```

Generated template:

```
Cash In Tk {amount} from {phone}. TrxID {trxId}. Balance Tk {balance}.
```

The engine detects `amount`, `phone`, `trxId`, `balance`, `otp`, `date`, `time`, `account`,
`reference` and generic numbers, then compiles the template to a named-group regex used for
extraction. Stored on the rule as `{ sender, template, regex, sampleMessages }`.

---

## Webhook payload

Default envelope (used when a rule has no custom payload template):

```json
{
  "deviceId": "…",
  "sender": "bKash",
  "body": "Cash In Tk 500 …",
  "rawSms": "Cash In Tk 500 …",
  "timestamp": 1719500000000,
  "parsedData": { "amount": "500", "trxId": "ABC12345" },
  "simInfo": { "slot": 0, "carrier": "…", "phoneNumber": "…" }
}
```

Custom payload templates use `{{variable}}` substitution where variables come from the processor
output plus `sender`, `body`, `rawSms`, `timestamp`, `deviceId`, `simSlot`, `phoneNumber`, …

### Security headers added by the sender

- `Authorization: Bearer <token>` (if configured)
- `X-Signature: sha256=<hmac>`, `X-Timestamp`, `X-Nonce` (replay protection, if HMAC configured)
- `X-Idempotency-Key: <sms-hash>` (safe retries / dedupe on your server)

HTTPS is enforced unless explicitly overridden in Settings.

---

## Offline queue & retries

Persistent FIFO queue in SQLite. Each item: `payload, createdAt, retryCount, nextRetryAt,
lastError, webhookUrl, status`. Delivery uses `30s · 2ⁿ` exponential backoff (6h cap, jitter),
bounded by **max retries**; exhausted items move to a **dead-letter** state with **manual retry**.
A 15-minute periodic worker and a `BootReceiver` guarantee the backlog always drains.

---

## SIM management

Reads both SIM slots (slot, carrier, subscription id, ICCID, phone number) via `SubscriptionManager`,
detects **inserted / removed / changed / swapped** events against a stored snapshot, records SIM
history, and can optionally push a `sim_changed` event to your default webhook. Manual phone-number
override is supported per subscription.

---

## Screens

Splash · Dashboard · SMS History · Rules · Create Rule · Rule Testing · Training · Processor
Management · Webhook Settings · Queue Management · SIM Information · Diagnostics · Settings · About.

---

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): module map, data flow, extension points.
- [`docs/DATABASE.md`](docs/DATABASE.md): full SQLite schema.

## License

Provided as a reusable commercial-grade product scaffold. Add your own license before distribution.

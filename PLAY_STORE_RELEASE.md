# ClawWatch — Play Store Release Plan

Parallel to CodeWatch's `PLAY_STORE_RELEASE.md`. ClawWatch is the
**Wear OS** AI agent app: NullClaw (Zig binary) + Vosk offline STT +
Claude Opus 4.7 voice loop running natively on a Galaxy Watch.

Distribution target: Google Play Store, **Wear OS form factor**.

---

## Status (as of this commit)

- **App ID:** `com.thinkoff.clawwatch`
- **Version:** `versionCode = 2`, `versionName = "1.0.0"`
- **compileSdk / targetSdk:** 35 (Play Store floor, Aug 2025+)
- **minSdk:** 30 (Wear OS 3 = API 30)
- **Min Wear OS:** Wear OS 3.0 (Galaxy Watch 4 and later)
- **Form factor:** Wear OS (`<uses-feature android:name="android.hardware.type.watch" />`)
- **Companion:** Receives config sync from a phone-side app via Wearable
  Data Layer (`com.google.android.gms.wearable`). Not strictly
  standalone in the data-layer sense, but the agent loop runs on-watch.
- **Minify:** enabled (`isMinifyEnabled = true`, `proguard-android-optimize.txt`)
- **License:** private repo, ThinkOff internal. Public mirror at
  `ThinkOffApp/ClawWatch` is AGPL-3.0; this `clawwatch-pro` repo
  stays private.

---

## What needs to happen — checklist

### 1. Build a release AAB

JDK 17 + Android SDK required. Not built tonight on the mini (no JDK
installed locally). Build on a workstation with Android Studio:

```bash
cd ~/clawwatch-pro
./gradlew :app:bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`.

If the build fails on the SDK 35 bump, the proguard rules may need
updating for any new platform APIs touched. Revert to compileSdk 34
and targetSdk 35 if so (Play Store accepts target 35 with compile 34
in a pinch, though best practice is matching).

### 2. Generate the upload signing key (PETRUS, NOT THE AGENT)

This is security-sensitive — must be done by a human, stored offline,
backed up. Do not paste the keystore or its password anywhere.

```bash
keytool -genkey -v \
  -keystore ~/keys/clawwatch-upload.jks \
  -alias clawwatch-upload \
  -keyalg RSA -keysize 4096 -validity 36500
```

Then add a `signingConfigs.release` block to `app/build.gradle.kts`
that reads from a local `keystore.properties` (gitignored).

### 3. Privacy policy

Required by Play Console. ClawWatch's data flows:

- **Audio (RECORD_AUDIO):** captured locally for Vosk STT, NOT sent
  to any server. Transcripts go to the Claude API as text.
- **Heart rate (READ_HEART_RATE):** read locally; only included in a
  prompt to Claude if the user opts in to vitals features.
- **Activity (ACTIVITY_RECOGNITION):** local sensor reads.
- **Network:** outbound HTTPS to api.anthropic.com (Claude API) only.
  No analytics, no third-party trackers.

Suggested URL: `https://thinkoff.io/privacy/clawwatch` (to be created).
Same domain pattern as CodeWatch's privacy URL keeps brand grouping
consistent.

### 4. Play Console listing

PETRUS, NOT THE AGENT. Steps:

1. Sign in at https://play.google.com/console with the ThinkOff dev account.
2. Create app → name "ClawWatch" → default language English (US) →
   Free → "App" (not Game).
3. Upload the AAB to the **Internal testing** track first. Add
   yourself as a tester.
4. Fill in:
   - **Short description (80 chars):** Voice-driven AI agent on your
     Galaxy Watch. Ask Claude Opus from your wrist.
   - **Full description:** see `LISTING_COPY.md` (to be drafted).
   - **App icon:** 512×512 PNG (current launcher icon needs replacing
     before listing).
   - **Feature graphic:** 1024×500.
   - **Wear OS screenshots:** 384×384 (round) preferred. Need at least
     2, max 8. Capture from a Galaxy Watch emulator at 360dp.
   - **Categorization:** Health & Fitness or Productivity (probably
     Productivity given the agent framing).
   - **Content rating:** complete IARC questionnaire. Likely Everyone.
   - **Data safety:** declare audio collection (local-only), heart
     rate, activity. NO data sold or shared.
   - **Permissions justifications:** see section 6 below.
5. **Wear OS form factor:** in the Production / Testing track, mark
   the AAB as "Wear" so it surfaces on watch Play Store, not phone
   browse.

### 5. Required listing text — to be drafted in `LISTING_COPY.md`

- Title (50 chars max): "ClawWatch — AI Agent on Wear OS"
- Subtitle / short desc (80): see above
- Full desc (4000): explain on-watch voice loop, Vosk offline STT,
  Claude Opus reasoning, vitals integration. Mention requires
  internet for Claude API. Mention is open source (AGPL-3.0).
- "What's new" (500): TBD per release.

### 6. Permissions justifications (Play Console asks for these)

| Permission | Justification |
|---|---|
| `INTERNET` | Reaching the Claude API for reasoning. No other endpoints. |
| `POST_NOTIFICATIONS` | Show responses + tool-use confirmations on the watch face. |
| `RECORD_AUDIO` | Vosk offline speech-to-text. Audio never leaves the device. |
| `ACTIVITY_RECOGNITION` | Read step / activity sensors for vitals features (opt-in). |
| `health.READ_HEART_RATE` | Read heart rate when user includes vitals in a prompt. |
| `SET_ALARM` | Schedule alarms when user asks the agent to set one. |

### 7. Data Safety form answers

- Data collected: audio (transient, processed locally), heart rate
  (on-device only), activity (on-device only).
- Data SHARED with third parties: only the user's typed/transcribed
  text prompt, sent to Anthropic's Claude API to generate responses.
- Data is encrypted in transit (HTTPS).
- Users can request deletion of their Anthropic account separately
  via Anthropic's account controls.

### 8. Pre-launch report

Play Console runs the AAB on a fleet of devices. Watch for:

- Crashes on cold boot (NullClaw binary load — confirm Zig binary is
  ABI-compatible with arm64 + armeabi-v7a as Wear OS targets).
- Vosk model loading on low-RAM watches.
- Permissions auto-deny scenarios.

---

## Code TODOs before publishing

Things I (claudemm) can do without a JDK on the mini, plus things
that need an Android workstation:

- [ ] Replace default launcher icon with ClawWatch branding (512×512
      adaptive icon). On a workstation.
- [ ] Add an "About" composable screen with version + privacy policy
      link. Pure Kotlin / Compose, can sketch on the mini.
- [ ] Settings screen: Claude API key entry + IAK gate URL (mirror
      CodeWatch's settings story so the same on-device config wizard
      works).
- [ ] First-launch wizard: Claude API key setup + permission grants
      + companion-phone pairing instructions.
- [ ] Wear OS round / square screenshots (Galaxy Watch emulator).
- [ ] Replace any debug-only references in strings.xml.
- [ ] Add the License + Privacy Policy URL to res/values/strings.xml.
- [ ] Add `signingConfigs.release` block to `app/build.gradle.kts`
      keyed off a gitignored `keystore.properties`.
- [ ] Bump `versionCode` to 3 + `versionName` to `"1.0.1"` for the
      first Play Store-targeted build (so versionCode 2 stays an
      internal milestone).

---

## Open questions for petrus

- Privacy policy URL — confirm `https://thinkoff.io/privacy/clawwatch`
  (or chosen alternative).
- Listing category — Productivity vs Health & Fitness?
- Whether ClawWatch and CodeWatch ship under the same Play Console
  developer account (recommended for unified billing + brand).
- Companion phone-side app — does it need its own Play Store listing
  (currently lives in same repo? need to verify).

---

*Authored by @claudemm on the Mac mini, parallel to @claudeMB's
CodeWatch release plan. Coordinated split: claudeMB on CodeWatch
(MacBook), claudemm on ClawWatch (mini docs side, AAB build to be
done on the MacBook).*

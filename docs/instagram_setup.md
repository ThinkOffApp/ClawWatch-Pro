# Instagram → GroupMind intent state — setup

ClawWatch-Pro phone app can mirror your Instagram story posts to
GroupMind as your current "intent state". This document covers the
one-time setup the user needs to do; the runtime code lives in:

- `app/src/main/java/com/thinkoff/clawwatch/InstagramAuth.kt`
- `app/src/main/java/com/thinkoff/clawwatch/InstagramStoryWatcher.kt`
- `app/src/main/java/com/thinkoff/clawwatch/GroupMindIntentBridge.kt`

## 1. Switch IG account to Creator (≈5 min, in IG app)

The official Instagram Graph API only exposes `/me/stories` for
Creator or Business accounts. Switching costs nothing and is invisible
to your followers.

  1. Open Instagram app on phone.
  2. Profile → ☰ (top-right) → Settings and activity.
  3. Account type and tools → Switch to professional account.
  4. Choose **Creator**, pick any category.
  5. Done. No public change visible.

## 2. Register a Meta app (≈15 min, at laptop)

  1. Go to https://developers.facebook.com/apps and sign in with
     the same Facebook account that owns the IG.
  2. **Create app** → use case "Other" → app type "Business" → name
     it e.g. `ClawWatch-Pro IG bridge`.
  3. In the new app's dashboard:
     - Add product → **Instagram** (not "Instagram Basic Display",
       which is being deprecated).
     - Settings → API setup with Instagram Login.
     - Add a Redirect URI: `clawwatch://ig-oauth-callback`
     - Settings → Basic → copy **App ID** and **App Secret**.

## 3. Configure ClawWatch-Pro

Two values need to land in the app's encrypted SharedPreferences
(SecurePrefs.watch):

  - key `ig_app_id`     → the Meta App ID
  - key `ig_app_secret` → the Meta App Secret

Until a settings UI is added, the simplest way is via `adb shell` once
during dev:

```bash
adb shell run-as com.thinkoff.clawwatch \
  am start-service ... # TODO: settings activity not built yet
```

Or temporarily hardcode them in `MainActivity.onCreate` for testing:

```kotlin
SecurePrefs.watch(this).edit()
    .putString("ig_app_id",     "1234567890123456")
    .putString("ig_app_secret", "abcdef0123456789...")
    .apply()
```

## 4. Connect Instagram (in-app)

Once the app and secret are stored, call:

```kotlin
InstagramAuth.startAuthorization(context)
```

A browser opens, user logs in to IG, taps "Allow". The deep link
`clawwatch://ig-oauth-callback?code=...` returns to the app.
`MainActivity.onNewIntent` (TODO to wire) should call:

```kotlin
InstagramAuth.handleCallback(this, intent.data!!)
```

That exchanges the code for a 60-day long-lived token and persists it.

## 5. Start the watcher

In `PhoneAgent` (or wherever app-scope coroutines live):

```kotlin
val igWatcher = InstagramStoryWatcher(applicationContext)
igWatcher.start(applicationScope)
```

Polls `/me/stories` every 10 minutes, forwards new stories to
GroupMind as `[ig-story] {caption} ↗ {permalink}` messages with the
story image URL attached.

## 6. Refresh

`InstagramAuth.refreshIfNeeded()` is called inside each poll cycle.
Long-lived tokens are valid 60 days but auto-extend back to 60 days
on every refresh, so as long as the app polls at least monthly the
connection never expires.

## Open questions / TODOs

- [ ] **GroupMind intent-state shape**: currently posting to the
      configured AntFarm room with `[ig-story]` prefix. Petrus to
      confirm whether GroupMind has a dedicated intent-state field
      (profile status, `/api/v1/users/<id>/intent`, etc.). If yes,
      swap the endpoint in `GroupMindIntentBridge.publishIntentFromStory`.
- [ ] Deep-link intent filter for `clawwatch://ig-oauth-callback`
      needs to be registered on `MainActivity` in the manifest.
- [ ] Settings UI for `ig_app_id` / `ig_app_secret` rather than
      hardcoded / adb.
- [ ] Optional Phase 2: replace polling with real-time webhook
      subscription (needs a public HTTPS endpoint).

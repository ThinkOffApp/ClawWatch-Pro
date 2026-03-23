# Apple Watch ClawWatch

This directory contains the first native watchOS app target for `ClawWatch-Pro`.

## What is implemented

- native SwiftUI Apple Watch app
- orange lobster avatar with state-driven motion
- thought bubble for listening/thinking/searching
- speech bubble for replies
- Anthropic API call path
- Ant Farm room posting path
- on-watch settings for:
  - Anthropic API key
  - model
  - system prompt
  - Ant Farm base URL
  - Ant Farm room
  - Ant Farm X-API-Key

## Generate the Xcode project

```bash
cd apple-watch
xcodegen generate
```

## Build for watchOS Simulator

```bash
cd apple-watch
xcodegen generate
xcodebuild -project ClawWatchApple.xcodeproj \
  -scheme ClawWatchApple \
  -destination 'generic/platform=watchOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

## Current scope

This is the first watchOS-native shell, not a full parity port yet.

Not yet implemented:

- live speech recognition loop
- HealthKit vitals integration
- push alert handling / complications
- watch-to-phone sync
- room history fetch / family summary fetch

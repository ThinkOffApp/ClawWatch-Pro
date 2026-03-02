<p align="center">
  <img src="assets/logo/clawwatch-logo.jpeg" alt="ClawWatch logo" width="280">
</p>

# ClawWatch Pro (Play Store Edition)

**Private repository for the Play Store version of ClawWatch.**

This repo shares core code with the [open source ClawWatch](https://github.com/ThinkOffApp/ClawWatch) (AGPL-3.0) but is licensed proprietary for store distribution.

## Repo Structure

- `origin` = this private repo (ThinkOffApp/ClawWatch-Pro)
- `upstream` = open source repo (ThinkOffApp/ClawWatch)

## Syncing from upstream

```bash
git fetch upstream
git merge upstream/main
# Resolve any conflicts in Pro-specific files (LICENSE, README, signing config)
```

## Key Differences from OSS Version

- Proprietary license for Play Store distribution
- Release signing configuration
- Play Store metadata and assets
- Pro-specific features (TBD)

## Build for Release

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew assembleRelease
```

## License

Proprietary. Copyright (C) 2026 ThinkOff / Petrus Pennanen. All rights reserved.

For the open source community edition, see [ClawWatch](https://github.com/ThinkOffApp/ClawWatch) (AGPL-3.0).

Logo by [herrpunk](https://github.com/herrpunk)

# Cookbook for Android

A native Android client (Kotlin, Jetpack Compose) for the
[Nextcloud Cookbook](https://github.com/nextcloud/cookbook) app.

It is a rewrite of the Flutter client
[Teifun2/nextcloud-cookbook-flutter](https://github.com/Teifun2/nextcloud-cookbook-flutter)
(CC0), keeping its user experience but using Android's own tooling.

## Requirements

- Android 8.0 or newer
- A Nextcloud server with the Cookbook app installed

## Installation

Download the APK from the latest [release](https://github.com/Quiss42/cookbook-android/releases/latest)
and open it on the phone (installing from unknown sources must be allowed for the browser).
Updates install over the previous version. The app can also look for new releases itself
(Settings → Updates).

## Features

**Login**
- Through the browser (Nextcloud Login Flow v2) or with an app password
- Credentials encrypted with an Android Keystore key; logging out revokes the app password
- Self-signed certificates (optional, off by default): the certificate is trusted on first
  use and pinned, a different one is refused later; user-installed CAs are trusted
- If the app password is revoked, the app offers to log in again; offline changes are kept

**Browsing**
- Categories with cover photos, 1–3 per row (configurable), "All recipes" and "Uncategorized"
- Recipe lists with thumbnails, pull to refresh
- Server-side search (names, keywords, categories)

**Recipe view**
- Photo, description, servings, times, category, keywords, link to the original
- Ingredients and steps can be ticked off while cooking; the marks are kept per recipe
  for 12 hours, also when leaving the recipe or restarting the app
- Nutrition per serving: calories, protein, fat, carbohydrates, sugar
- Decimal numbers shown with the phone language's separator ("0.5 l" → "0,5 l" in German)
- Keeps the screen on while a recipe is open (configurable)

**Editing**
- Create, edit and delete recipes
- Photo from Nextcloud files, a web address (downloaded by the phone; for a web page its
  preview image is used, prefilled with the recipe's source), the camera or the gallery
- Category with suggestions; keywords as chips (existing ones can be checked, new ones added)
- Reorderable tools, ingredients and steps; ingredients with a foreign decimal separator
  are marked and can be fixed with a tap
- Nutrition per serving (serving size is set to "1 serving" automatically); values are
  stored with "." as decimal separator and shown with the phone's separator
- Lists values stored in the recipe that the app does not show, with the option to
  delete single ones or all; server-maintained fields are kept
- Unsaved edits survive Android closing the app in the background

**More**
- Import recipes from a web page, also via Android's share menu
- Cooking timers with exact alarms, countdown notification and timers screen; survive reboots.
  A finished timer rings like an alarm clock (alarm volume, repeating until stopped); the app
  warns if notifications are switched off
- Light/dark theme, wallpaper colours, per-app language (Android 13+)
- Offline reading: all recipes and thumbnails are kept on the phone and synced in the
  background (on start at most every 10 minutes, on pull to refresh, or from Settings);
  without a connection the stored state is shown with a notice
- Offline editing: creating, editing and deleting recipes works without a connection;
  changes (including camera/gallery photos) are queued and uploaded in order once the
  server is reachable, also when the app is closed (WorkManager). If a recipe was changed
  on the server meanwhile (detected via `dateModified`), the app asks: my version, server
  version or keep both. Saves that timed out are checked before retrying, so no
  duplicates are created
- Update check against GitHub releases (on start, optional, or manually)
- English and German

## Technical notes

- API calls go through `/index.php/apps/cookbook/api/…`, so they also work on web servers
  that only rewrite GET requests to Nextcloud.
- Photos from the phone are downscaled to 2048 px JPEG, uploaded to a temporary file in the
  user's Nextcloud, attached by path (Cookbook copies it into the recipe folder) and the
  temporary file is deleted again.
- Web photos are limited to 20 MB.
- Fields the app does not know are preserved when a recipe is saved.
- Offline copies are only stored after the answer parsed as JSON (no captive-portal pages);
  queue and index files are written atomically.

## Building

Every push runs the unit tests and lint and builds a release APK with GitHub Actions
(Actions tab → run → artifacts `cookbook-apk` and `reports`). A new version
(`APP_VERSION_NAME` in `gradle.properties`) on the default branch also creates a GitHub
release with the APK; without the release keystore the default branch build fails instead
of publishing a wrongly signed APK. The `versionCode` is derived from the version name
(1.2.3 → 1002003). Dependabot proposes dependency and action updates monthly; actions
are pinned to commit SHAs.

Locally: Android SDK and JDK 17 or newer, then `./gradlew :app:assembleDebug` or
`./gradlew :app:testDebugUnitTest` (the wrapper downloads the right Gradle version).

Release signing uses the repository secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`
(or `key.properties` locally).

## License

Copyright (C) 2026 Quiss42

This program is free software: you can redistribute it and/or modify it under the terms
of the GNU General Public License as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE).

The app and notification icon (an open book with fork and knife) is an own design and
part of this project.

### Third-party material

- **Libraries** used by the app (AndroidX, Jetpack Compose, OkHttp, Coil, kotlinx) are not
  part of this repository; they are licensed under the Apache License 2.0, which is
  compatible with GPL-3.0.

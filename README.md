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
- Optional acceptance of self-signed certificates (off by default); user-installed CAs are trusted

**Browsing**
- Categories with cover photos, 1–3 per row (configurable), "All recipes" and "Uncategorized"
- Recipe lists with thumbnails, pull to refresh
- Server-side search (names, keywords, categories)

**Recipe view**
- Photo, description, servings, times, category, keywords, link to the original
- Ingredients and steps can be ticked off while cooking
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
- Nutrition per serving (serving size is set to "1 serving" automatically)
- Lists values stored in the recipe that the app does not show, with the option to
  delete single ones or all; server-maintained fields are kept

**More**
- Import recipes from a web page, also via Android's share menu
- Cooking timers with exact alarms, countdown notification and timers screen; survive reboots
- Light/dark theme, wallpaper colours, per-app language (Android 13+)
- Offline reading: all recipes and thumbnails are kept on the phone and synced in the
  background (on start at most every 10 minutes, on pull to refresh, or from Settings);
  without a connection the stored state is shown with a notice
- Update check against GitHub releases (on start, optional, or manually)
- English and German; partial translations for 23 more languages taken from the Flutter
  app (`tools/port_translations.py`)

Planned: editing while offline.

## Technical notes

- API calls go through `/index.php/apps/cookbook/api/…`, so they also work on web servers
  that only rewrite GET requests to Nextcloud.
- Photos from the phone are downscaled to 2048 px JPEG, uploaded to a temporary file in the
  user's Nextcloud, attached by path (Cookbook copies it into the recipe folder) and the
  temporary file is deleted again.
- Fields the app does not know are preserved when a recipe is saved.

## Building

Every push builds a release APK with GitHub Actions (Actions tab → run → artifact).
A new version (`APP_VERSION_NAME` in `gradle.properties`) on the default branch
also creates a GitHub release with the APK.

Locally: Android SDK + Gradle 8.14, then `gradle :app:assembleDebug`.

Release signing uses the repository secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`
(or `key.properties` locally).
